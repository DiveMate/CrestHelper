/* deco.js —— ZHL-16C + Gradient Factors 减压模型（JS 版）
 *
 * 这是 tools/deco.py 的逐行移植，三份实现（Python / JS / 未来的 Rust）
 * 必须给出一致结果。改任何一份都要同步另外两份，并用 deco.py 的
 * _selftest() 做数值对拍。
 *
 * 单位：压力 bar，深度 m，时间 min（对外接口用秒）
 */
(function (global) {
  'use strict';

  var M_PER_BAR = 10.0;
  var SURFACE_PRESSURE = 1.0;
  var NCOMP = 16;

  var N2_HALF_TIMES = [4.0, 8.0, 12.5, 18.5, 27.0, 38.3, 54.3, 77.0,
                       109.0, 146.0, 187.0, 239.0, 305.0, 390.0, 498.0, 635.0];
  var N2_A = [1.1696, 1.0000, 0.8618, 0.7562, 0.6667, 0.5600,
              0.4947, 0.4500, 0.4187, 0.3798, 0.3497, 0.3223,
              0.2850, 0.2737, 0.2523, 0.2327];
  var N2_B = [0.5578, 0.6514, 0.7222, 0.7825, 0.8126, 0.8434,
              0.8693, 0.8910, 0.9092, 0.9222, 0.9319, 0.9403,
              0.9477, 0.9544, 0.9602, 0.9653];
  var HE_HALF_TIMES = [1.51, 3.02, 4.52, 6.04, 9.05, 12.07, 18.11, 24.14,
                       36.20, 48.26, 60.37, 72.45, 90.57, 120.76, 150.94, 181.02];
  var HE_A = [1.6189, 1.3830, 1.1919, 1.0458, 0.9220, 0.8205,
              0.7305, 0.6502, 0.5950, 0.5545, 0.5333, 0.5189,
              0.5181, 0.5176, 0.5172, 0.5119];
  var HE_B = [0.4770, 0.5747, 0.6527, 0.7223, 0.7582, 0.7957,
              0.8279, 0.8553, 0.8757, 0.8903, 0.8997, 0.9073,
              0.9122, 0.9171, 0.9217, 0.9267];

  // 预计算 k = ln2 / halftime
  var K_N2 = N2_HALF_TIMES.map(function (t) { return Math.log(2) / t; });
  var K_HE = HE_HALF_TIMES.map(function (t) { return Math.log(2) / t; });

  var ASCENT_LIMIT = 18.0; // m/min，休闲潜水上升速率告警阈值

  var ALGORITHMS = [
    { id: 'zhl16c', name: 'ZHL-16C 原始', gf_low: 1.00, gf_high: 1.00,
      desc: 'Bühlmann 原始 M 值线，不施加任何梯度余量。最激进。' },
    { id: 'gf80', name: 'GF 80/80', gf_low: 0.80, gf_high: 0.80,
      desc: '轻度保守，接近多数电脑表的默认策略。' },
    { id: 'gf50_70', name: 'GF 50/70', gf_low: 0.50, gf_high: 0.70,
      desc: '常见休闲潜水保守设置。' },
    { id: 'gf30_85', name: 'GF 30/85', gf_low: 0.30, gf_high: 0.85,
      desc: '技术潜水常用，深处留大余量、浅处放宽。' },
    { id: 'gf20_60', name: 'GF 20/60', gf_low: 0.20, gf_high: 0.60,
      desc: '极保守，用于压力测试。' }
  ];

  function depthToPressure(d) { return SURFACE_PRESSURE + d / M_PER_BAR; }
  function pressureToDepth(p) { return (p - SURFACE_PRESSURE) * M_PER_BAR; }

  function DecoModel(gas) {
    this.gas = gas || { o2: 0.21, he: 0.0, n2: 0.79 };
    this.pn2 = new Float64Array(NCOMP);
    this.phe = new Float64Array(NCOMP);
    var n2_0 = SURFACE_PRESSURE * this.gas.n2;
    var he_0 = SURFACE_PRESSURE * this.gas.he;
    for (var i = 0; i < NCOMP; i++) { this.pn2[i] = n2_0; this.phe[i] = he_0; }
  }

  DecoModel.prototype.inertFraction = function () {
    return this.gas.n2 + this.gas.he;
  };

  // Schreiner 方程
  DecoModel.prototype.addSegment = function (depth0, depth1, timeMin, gas) {
    if (timeMin <= 0) return;
    gas = gas || this.gas;
    var p0 = depthToPressure(depth0);
    var p1 = depthToPressure(depth1);
    var rate = (p1 - p0) / timeMin;
    var fn2 = gas.n2, fhe = gas.he;
    for (var i = 0; i < NCOMP; i++) {
      var palvN2 = p1 * fn2;
      var eN2 = Math.exp(-K_N2[i] * timeMin);
      var rN2 = fn2 * rate;
      this.pn2[i] = palvN2 + (this.pn2[i] - palvN2) * eN2 +
        rN2 * (timeMin - (1 - eN2) / K_N2[i]);
      var palvHe = p1 * fhe;
      var eHe = Math.exp(-K_HE[i] * timeMin);
      var rHe = fhe * rate;
      this.phe[i] = palvHe + (this.phe[i] - palvHe) * eHe +
        rHe * (timeMin - (1 - eHe) / K_HE[i]);
    }
  };

  DecoModel.prototype.addSurfaceInterval = function (min) {
    this.addSegment(0, 0, min);
  };

  DecoModel.prototype.tissuePressure = function (i) {
    return this.pn2[i] + this.phe[i];
  };

  DecoModel.prototype.ab = function (i) {
    var tot = this.pn2[i] + this.phe[i];
    if (tot <= 0) return [N2_A[i], N2_B[i]];
    var w = this.phe[i] / tot;
    return [N2_A[i] * (1 - w) + HE_A[i] * w, N2_B[i] * (1 - w) + HE_B[i] * w];
  };

  DecoModel.prototype.mValue = function (i, pAmb) {
    var ab = this.ab(i);
    return ab[0] + pAmb / ab[1];
  };

  // GF99 = (P_tissue - f*P_amb) / (M - f*P_amb) * 100
  DecoModel.prototype.gf99 = function (pAmb) {
    var f = this.inertFraction();
    var pInert = f * pAmb;
    var worst = -1e9;
    for (var i = 0; i < NCOMP; i++) {
      var m = this.mValue(i, pAmb);
      var denom = m - pInert;
      if (denom <= 1e-9) continue;
      var g = (this.tissuePressure(i) - pInert) / denom * 100;
      if (g > worst) worst = g;
    }
    return worst;
  };

  // P_amb = (P_tissue - gf*A) / (f*(1-gf) + gf/B)
  DecoModel.prototype.ceilingAtGf = function (gf, f) {
    var worst = -1e9;
    for (var i = 0; i < NCOMP; i++) {
      var ab = this.ab(i);
      var denom = f * (1 - gf) + gf / ab[1];
      if (denom <= 1e-9) continue;
      var d = pressureToDepth((this.tissuePressure(i) - gf * ab[0]) / denom);
      if (d > worst) worst = d;
    }
    return worst;
  };

  // 锚点用 gf_low：先用最保守的 gf 求第一减压站，再按深度插值。
  // 用 gf_high 做锚点是错的（宽松时会提前返回 0，吞掉深处约束）。
  DecoModel.prototype.ceilingGf = function (gfLow, gfHigh, depth) {
    var f = this.inertFraction();
    var cLow = this.ceilingAtGf(gfLow, f);
    if (cLow <= 0) return 0;
    if (depth >= cLow) return cLow;
    var frac = Math.min(1, Math.max(0, depth / cLow));
    var gf = gfHigh + (gfLow - gfHigh) * frac;
    return Math.max(0, this.ceilingAtGf(gf, f));
  };

  DecoModel.prototype.ceiling = function (depth) {
    return this.ceilingGf(1.0, 1.0, depth || 0);
  };

  DecoModel.prototype.clone = function () {
    var m = new DecoModel(this.gas);
    m.pn2.set(this.pn2);
    m.phe.set(this.phe);
    return m;
  };

  /* 对一条剖面跑分析
   * samples: [[t_sec, depth_m], ...]
   * 返回 { gf99_max, ceiling_max, deco_sec, deco_duty, ascent_max,
   *        ascent_violations, gf99_series, ceiling_series }
   */
  function analyzeProfile(samples, alg, maxPoints) {
    maxPoints = maxPoints || 400;
    var empty = {
      gf99_max: 0, ceiling_max: 0, deco_sec: 0, deco_duty: false,
      ascent_max: 0, ascent_violations: 0,
      gf99_series: [], ceiling_series: [], depth_series: []
    };
    if (!samples || samples.length < 2) return empty;

    var gfLow = alg.gf_low, gfHigh = alg.gf_high;
    var model = new DecoModel();
    var gf99Max = -1e9, ceilingMax = 0, decoSec = 0;
    var ascentMax = 0, ascentViolations = 0;
    var gfSeries = [], cSeries = [], dSeries = [];

    var prevT = samples[0][0], prevD = samples[0][1];
    for (var i = 1; i < samples.length; i++) {
      var t = samples[i][0], d = samples[i][1];
      var dtMin = (t - prevT) / 60;
      if (dtMin <= 0) continue;
      model.addSegment(prevD, d, dtMin);

      var pAmb = depthToPressure(d);
      var g = model.gf99(pAmb);
      if (g > gf99Max) gf99Max = g;
      var c = model.ceilingGf(gfLow, gfHigh, d);
      if (c > ceilingMax) ceilingMax = c;
      if (c > 0) decoSec += (t - prevT);

      if (d < prevD) {
        var rate = (prevD - d) / dtMin;
        if (rate > ascentMax) ascentMax = rate;
        if (rate > ASCENT_LIMIT) ascentViolations++;
      }

      gfSeries.push([t, Math.round(g * 10) / 10]);
      cSeries.push([t, Math.round(c * 100) / 100]);
      dSeries.push([t, Math.round(d * 100) / 100]);

      prevT = t; prevD = d;
    }

    return {
      gf99_max: Math.round(Math.max(0, gf99Max) * 10) / 10,
      ceiling_max: Math.round(ceilingMax * 100) / 100,
      deco_sec: Math.round(decoSec),
      deco_duty: ceilingMax > 0,
      ascent_max: Math.round(ascentMax * 10) / 10,
      ascent_violations: ascentViolations,
      gf99_series: decimate(gfSeries, maxPoints),
      ceiling_series: decimate(cSeries, maxPoints),
      depth_series: decimate(dSeries, maxPoints)
    };
  }

  function decimate(series, n) {
    if (series.length <= n) return series;
    var step = series.length / n, out = [];
    for (var i = 0; i < n; i++) out.push(series[Math.floor(i * step)]);
    out.push(series[series.length - 1]);
    return out;
  }

  /* 免减压极限：给定深度，二分搜索能停留多久（用指定算法判定 ceiling<=0） */
  function ndl(depth, alg, maxMin) {
    maxMin = maxMin || 200;
    var lo = 0, hi = maxMin;
    function duty(bottomMin) {
      var m = new DecoModel();
      m.addSegment(0, depth, depth / 20); // 按 20 m/min 下潜
      m.addSegment(depth, depth, bottomMin);
      return m.ceilingGf(alg.gf_low, alg.gf_high, depth) > 0;
    }
    if (!duty(hi)) return null; // 该深度在此算法下无减压义务
    var mid;
    for (var i = 0; i < 24; i++) {
      mid = (lo + hi) / 2;
      if (duty(mid)) hi = mid; else lo = mid;
    }
    return Math.round(hi * 10) / 10;
  }

  global.Deco = {
    DecoModel: DecoModel,
    ALGORITHMS: ALGORITHMS,
    ASCENT_LIMIT: ASCENT_LIMIT,
    analyzeProfile: analyzeProfile,
    depthToPressure: depthToPressure,
    pressureToDepth: pressureToDepth,
    ndl: ndl,
    N2_HALF_TIMES: N2_HALF_TIMES
  };
})(window);
