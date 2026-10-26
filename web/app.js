/* DiveLog · 手机端原型 交互逻辑 */
(function () {
  'use strict';

  var D = window.Deco;
  var DATA = window.APP_DATA;
  var ALGS = D.ALGORITHMS;
  var state = {
    page: 'page-dives',
    filter: 'all',
    curDive: null,
    curAlg: 'gf50_70'
  };

  if (!DATA) {
    document.getElementById('screen').innerHTML =
      '<div class="empty">app-data.js 未生成，请先运行 tools/analyze.py</div>';
    return;
  }

  // ------------------------------------------------------------------ 工具
  function $(s) { return document.querySelector(s); }
  function fmtDur(sec) {
    if (!sec) return '0分';
    var m = Math.round(sec / 60);
    if (m < 60) return m + '分';
    return Math.floor(m / 60) + '时' + (m % 60) + '分';
  }
  function fmtDate(s) {
    // "2026-10-05T18:23:43" -> "10月05日 18:23"
    var d = s.slice(0, 10).split('-'), t = s.slice(11, 16);
    return (+d[1]) + '月' + d[2] + '日 ' + t;
  }
  function fmtDateFull(s) {
    var d = s.slice(0, 10).split('-'), t = s.slice(11, 16);
    return d[0] + '-' + d[1] + '-' + d[2] + ' ' + t;
  }
  function algById(id) {
    for (var i = 0; i < ALGS.length; i++) if (ALGS[i].id === id) return ALGS[i];
    return ALGS[2];
  }
  function isInvalid(d) {
    return d.flags.indexOf('shallow') >= 0 || d.flags.indexOf('short') >= 0;
  }
  function hasHiddenRisk(d) {
    return !d.algo.zhl16c.deco_duty &&
      (d.algo.gf50_70.deco_duty || d.algo.gf30_85.deco_duty);
  }

  // ------------------------------------------------------------------ 图表
  function setupCanvas(cv, w, h) {
    var dpr = window.devicePixelRatio || 1;
    cv.width = w * dpr; cv.height = h * dpr;
    cv.style.width = w + 'px'; cv.style.height = h + 'px';
    var c = cv.getContext('2d');
    c.setTransform(dpr, 0, 0, dpr, 0, 0);
    c.clearRect(0, 0, w, h);
    return c;
  }

  var PAD = { l: 34, r: 12, t: 10, b: 18 };

  function axes(c, w, h, xmax, ymax, ymin, opts) {
    opts = opts || {};
    c.strokeStyle = '#e3ebf1'; c.lineWidth = 1;
    c.fillStyle = '#9aadb9';
    c.font = '9px -apple-system,sans-serif';
    var y0 = PAD.t, y1 = h - PAD.b;
    var rows = opts.rows || 4;
    for (var i = 0; i <= rows; i++) {
      var y = y0 + (y1 - y0) * i / rows;
      c.beginPath(); c.moveTo(PAD.l, y); c.lineTo(w - PAD.r, y); c.stroke();
      var v = ymax - (ymax - ymin) * i / rows;
      c.textAlign = 'right'; c.textBaseline = 'middle';
      c.fillText(opts.fy ? opts.fy(v) : Math.round(v), PAD.l - 4, y);
    }
    // x 轴时间刻度
    if (xmax > 0) {
      c.textAlign = 'center'; c.textBaseline = 'top';
      var ticks = 3;
      for (var j = 0; j <= ticks; j++) {
        var x = PAD.l + (w - PAD.l - PAD.r) * j / ticks;
        var mn = Math.round(xmax * j / ticks / 60);
        c.fillText(mn + "'", x, y1 + 4);
      }
    }
  }

  function plotX(v, xmax, w) {
    return PAD.l + (w - PAD.l - PAD.r) * (xmax ? v / xmax : 0);
  }

  /* 深度剖面：深度向下，蓝色面积；温度叠加橙线（右轴独立缩放） */
  function drawProfile(cv, profile) {
    var w = cv.parentNode.clientWidth - 28, h = 150;
    var c = setupCanvas(cv, w, h);
    if (!profile.length) return;
    var xmax = profile[profile.length - 1][0];
    var dmax = 0, tmin = 999, tmax = -999, hasT = false;
    profile.forEach(function (p) {
      if (p[1] > dmax) dmax = p[1];
      if (p[2] != null) { hasT = true; if (p[2] < tmin) tmin = p[2]; if (p[2] > tmax) tmax = p[2]; }
    });
    dmax = Math.max(1, Math.ceil(dmax * 1.12));
    var y0 = PAD.t, y1 = h - PAD.b;

    axes(c, w, h, xmax, dmax, 0, {
      rows: 4, fy: function (v) { return Math.round(v) + 'm'; }
    });

    // 深度面积
    var grad = c.createLinearGradient(0, y0, 0, y1);
    grad.addColorStop(0, 'rgba(0,168,204,.34)');
    grad.addColorStop(1, 'rgba(13,110,140,.10)');
    c.beginPath();
    c.moveTo(plotX(0, xmax, w), y0);
    profile.forEach(function (p) {
      c.lineTo(plotX(p[0], xmax, w), y0 + (y1 - y0) * (p[1] / dmax));
    });
    c.lineTo(plotX(profile[profile.length - 1][0], xmax, w), y0);
    c.closePath(); c.fillStyle = grad; c.fill();

    c.beginPath();
    profile.forEach(function (p, i) {
      var x = plotX(p[0], xmax, w), y = y0 + (y1 - y0) * (p[1] / dmax);
      if (i === 0) c.moveTo(x, y); else c.lineTo(x, y);
    });
    c.strokeStyle = '#0d6e8c'; c.lineWidth = 1.7; c.stroke();

    // 温度
    if (hasT && tmax - tmin > 0.3) {
      c.beginPath();
      var first = true;
      profile.forEach(function (p) {
        if (p[2] == null) return;
        var x = plotX(p[0], xmax, w);
        var y = y0 + (y1 - y0) * (1 - (p[2] - tmin) / (tmax - tmin));
        if (first) { c.moveTo(x, y); first = false; } else c.lineTo(x, y);
      });
      c.strokeStyle = '#f08a24'; c.lineWidth = 1.3; c.setLineDash([3, 2]);
      c.stroke(); c.setLineDash([]);
    }

    // 最深点标注
    var mi = 0;
    profile.forEach(function (p, i) { if (p[1] > profile[mi][1]) mi = i; });
    var mx = plotX(profile[mi][0], xmax, w), my = y0 + (y1 - y0) * (profile[mi][1] / dmax);
    c.beginPath(); c.arc(mx, my, 3, 0, 6.284);
    c.fillStyle = '#0d6e8c'; c.fill();
    c.fillStyle = '#0d6e8c'; c.font = 'bold 9.5px -apple-system,sans-serif';
    c.textAlign = mx > w - 60 ? 'right' : 'left'; c.textBaseline = 'bottom';
    c.fillText(profile[mi][1].toFixed(1) + 'm', mx + (mx > w - 60 ? -5 : 5), my - 3);
  }

  /* GF99 风险曲线：100% 为 M 值线，越界区域红色 */
  function drawGF(cv, series) {
    var w = cv.parentNode.clientWidth - 28, h = 128;
    var c = setupCanvas(cv, w, h);
    if (!series || !series.length) return;
    var xmax = series[series.length - 1][0];
    var vmax = 0, vmin = 0;
    series.forEach(function (p) { if (p[1] > vmax) vmax = p[1]; if (p[1] < vmin) vmin = p[1]; });
    vmax = Math.max(100, Math.ceil(vmax / 20) * 20 + 10);
    vmin = Math.min(0, Math.floor(vmin / 20) * 20 - 10);
    var y0 = PAD.t, y1 = h - PAD.b;
    var yv = function (v) { return y0 + (y1 - y0) * (1 - (v - vmin) / (vmax - vmin)); };

    axes(c, w, h, xmax, vmax, vmin, {
      rows: 4, fy: function (v) { return Math.round(v) + '%'; }
    });

    // 100% M 值线
    c.strokeStyle = '#e63946'; c.lineWidth = 1.2; c.setLineDash([4, 3]);
    c.beginPath(); c.moveTo(PAD.l, yv(100)); c.lineTo(w - PAD.r, yv(100)); c.stroke();
    c.setLineDash([]);

    // 越界填充
    var over = series.filter(function (p) { return p[1] > 100; });
    if (over.length) {
      c.fillStyle = 'rgba(230,57,70,.13)';
      c.fillRect(PAD.l, y0, w - PAD.l - PAD.r, yv(100) - y0);
    }

    c.beginPath();
    series.forEach(function (p, i) {
      var x = plotX(p[0], xmax, w), y = yv(p[1]);
      if (i === 0) c.moveTo(x, y); else c.lineTo(x, y);
    });
    c.strokeStyle = '#00a8cc'; c.lineWidth = 1.7; c.stroke();

    // 峰值
    var pi = 0;
    series.forEach(function (p, i) { if (p[1] > series[pi][1]) pi = i; });
    var px = plotX(series[pi][0], xmax, w), py = yv(series[pi][1]);
    c.beginPath(); c.arc(px, py, 3.2, 0, 6.284);
    c.fillStyle = series[pi][1] > 100 ? '#e63946' : '#2a9d8f'; c.fill();
    c.font = 'bold 9.5px -apple-system,sans-serif';
    c.fillStyle = series[pi][1] > 100 ? '#e63946' : '#2a9d8f';
    c.textAlign = px > w - 70 ? 'right' : 'left'; c.textBaseline = 'bottom';
    c.fillText('峰值 ' + series[pi][1].toFixed(0) + '%', px + (px > w - 70 ? -5 : 5), py - 4);
  }

  /* 减压天花板：随算法切换重算 */
  function drawCeiling(cv, series) {
    var w = cv.parentNode.clientWidth - 28, h = 112;
    var c = setupCanvas(cv, w, h);
    if (!series || !series.length) {
      c.fillStyle = '#9aadb9'; c.font = '12px -apple-system,sans-serif';
      c.textAlign = 'center'; c.textBaseline = 'middle';
      c.fillText('无数据', w / 2, h / 2);
      return;
    }
    var xmax = series[series.length - 1][0];
    var vmax = 0;
    series.forEach(function (p) { if (p[1] > vmax) vmax = p[1]; });
    vmax = Math.max(3, Math.ceil(vmax * 1.2));
    var y0 = PAD.t, y1 = h - PAD.b;

    axes(c, w, h, xmax, vmax, 0, {
      rows: 3, fy: function (v) { return Math.round(v) + 'm'; }
    });

    // 有义务区域填充
    c.beginPath();
    c.moveTo(plotX(series[0][0], xmax, w), y0);
    series.forEach(function (p) {
      c.lineTo(plotX(p[0], xmax, w), y0 + (y1 - y0) * (p[1] / vmax));
    });
    c.lineTo(plotX(series[series.length - 1][0], xmax, w), y0);
    c.closePath();
    c.fillStyle = 'rgba(240,138,36,.20)'; c.fill();

    c.beginPath();
    series.forEach(function (p, i) {
      var x = plotX(p[0], xmax, w), y = y0 + (y1 - y0) * (p[1] / vmax);
      if (i === 0) c.moveTo(x, y); else c.lineTo(x, y);
    });
    c.strokeStyle = '#f08a24'; c.lineWidth = 1.7; c.stroke();
  }

  /* 柱状图（月度 / 深度分布） */
  function drawBars(cv, items, color, h) {
    h = h || 110;
    var w = cv.parentNode.clientWidth - 28;
    var c = setupCanvas(cv, w, h);
    if (!items.length) return;
    var pl = 6, pr = 6, pt = 12, pb = 20;
    var bw = (w - pl - pr) / items.length;
    var vmax = 0;
    items.forEach(function (it) { if (it.v > vmax) vmax = it.v; });
    vmax = Math.max(1, vmax);
    var y1 = h - pb;

    c.fillStyle = '#9aadb9'; c.font = '9px -apple-system,sans-serif';
    c.textAlign = 'center'; c.textBaseline = 'top';
    var gap = Math.max(1, Math.ceil(items.length / 12));
    items.forEach(function (it, i) {
      var x = pl + bw * i;
      var bh = (y1 - pt) * (it.v / vmax);
      c.fillStyle = color;
      var r = Math.min(3, bw / 3);
      c.beginPath();
      c.moveTo(x + bw * 0.16, y1);
      c.lineTo(x + bw * 0.16, y1 - bh + r);
      c.quadraticCurveTo(x + bw * 0.16, y1 - bh, x + bw * 0.16 + r, y1 - bh);
      c.lineTo(x + bw * 0.84 - r, y1 - bh);
      c.quadraticCurveTo(x + bw * 0.84, y1 - bh, x + bw * 0.84, y1 - bh + r);
      c.lineTo(x + bw * 0.84, y1);
      c.closePath(); c.fill();
      if (i % gap === 0) {
        c.fillStyle = '#9aadb9';
        c.fillText(it.k, x + bw / 2, y1 + 4);
      }
    });
  }

  // ------------------------------------------------------------------ 日志页
  function renderDives() {
    var list = DATA.dives;
    var f = state.filter;
    if (f === 'risk') list = list.filter(hasHiddenRisk);
    else if (f === 'merged') list = list.filter(function (d) { return d.merged; });
    else if (f === 'invalid') list = list.filter(isInvalid);
    else list = list.filter(function (d) { return !isInvalid(d); });

    $('#divesSub').textContent =
      '共 ' + DATA.meta.n_valid + ' 次有效潜水 · 累计 ' + DATA.meta.total_time_h +
      ' 小时 · ' + DATA.meta.date_from + ' ~ ' + DATA.meta.date_to;

    if (!list.length) {
      $('#diveList').innerHTML = '<div class="empty">没有符合条件的记录</div>';
      return;
    }

    var html = '';
    list.forEach(function (d) {
      var gf = d.algo.gf50_70;
      var cls = gf.gf99_max >= 100 ? 'gf-bad' : (gf.gf99_max >= 80 ? 'gf-warn' : 'gf-safe');
      var tags = '';
      if (d.merged) tags += '<span class="tag t-merged">' + d.raw_ids.length + '条合并</span>';
      if (hasHiddenRisk(d)) tags += '<span class="tag t-risk">隐藏风险</span>';
      if (gf.deco_duty) tags += '<span class="tag t-deco">减压义务</span>';
      if (isInvalid(d)) tags += '<span class="tag t-invalid">疑似无效</span>';
      html += '<div class="dcard" data-id="' + d.id + '">' +
        '<div class="gfbox ' + cls + '"><div class="n">' + Math.round(gf.gf99_max) +
        '</div><div class="l">GF99</div></div>' +
        '<div class="dmain">' +
        '<div class="d1"><span class="ddate">' + fmtDate(d.dt) + '</span>' +
        '<span class="dur">' + fmtDur(d.duration) + '</span></div>' +
        '<div class="d2"><span>最深 <b>' + d.maxdepth.toFixed(1) + 'm</b></span>' +
        (d.min_temp ? '<span>水温 <b>' + d.min_temp.toFixed(1) + '°C</b></span>' : '') +
        '</div>' +
        (tags ? '<div class="tags">' + tags + '</div>' : '') +
        '</div>' +
        '<svg class="arrow" width="7" height="12" viewBox="0 0 7 12"><path d="M1 1l5 5-5 5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>' +
        '</div>';
    });
    $('#diveList').innerHTML = html;

    Array.prototype.forEach.call($('#diveList').querySelectorAll('.dcard'), function (el) {
      el.addEventListener('click', function () {
        openDetail(+el.getAttribute('data-id'));
      });
    });
  }

  // ------------------------------------------------------------------ 详情页
  function openDetail(id) {
    var d = null;
    DATA.dives.forEach(function (x) { if (x.id === id) d = x; });
    if (!d) return;
    state.curDive = d;
    state.curAlg = 'gf50_70';
    $('#dtTitle').textContent = fmtDateFull(d.dt);
    $('#dtSub').textContent = '#' + d.no + ' · 最大深度 ' + d.maxdepth.toFixed(1) +
      'm · ' + fmtDur(d.duration);
    renderDetail();
    go('page-detail');
  }

  function renderDetail() {
    var d = state.curDive;
    if (!d) return;
    var alg = algById(state.curAlg);
    var pts = d.profile.map(function (p) { return [p[0], p[1]]; });
    var r = D.analyzeProfile(pts, alg, 400);
    var a = d.algo[state.curAlg];

    var risk = r.gf99_max >= 100 ? 'gf-bad' : (r.gf99_max >= 80 ? 'gf-warn' : 'gf-safe');

    var chips = ALGS.map(function (x) {
      return '<button class="chip alg' + (x.id === state.curAlg ? ' on' : '') +
        '" data-alg="' + x.id + '">' + x.name + '</button>';
    }).join('');

    var gfMaxAll = 0;
    ALGS.forEach(function (x) { if (d.algo[x.id].gf99_max > gfMaxAll) gfMaxAll = d.algo[x.id].gf99_max; });

    var html =
      '<div class="grid">' +
      stat(fmtDur(d.duration), '', '时长') +
      stat(d.maxdepth.toFixed(1), 'm', '最大深度') +
      stat(d.min_temp != null ? d.min_temp.toFixed(1) : '—', d.min_temp != null ? '°C' : '', '最低水温') +
      '</div>' +
      '<div class="grid">' +
      stat(r.gf99_max.toFixed(0), '%', 'GF99 峰值', risk) +
      stat(r.deco_duty ? r.ceiling_max.toFixed(1) : '无', r.deco_duty ? 'm' : '', '减压天花板') +
      stat(r.ascent_max.toFixed(0), 'm/min', '最大上升速率') +
      '</div>';

    // 标签
    var tags = '';
    if (d.merged) tags += '<span class="tag t-merged">' + d.raw_ids.length + ' 条原始记录已合并</span> ';
    if (hasHiddenRisk(d)) tags += '<span class="tag t-risk">隐藏风险</span> ';
    if (a.deco_duty) tags += '<span class="tag t-deco">本算法下有减压义务</span> ';
    if (isInvalid(d)) tags += '<span class="tag t-invalid">疑似无效</span> ';
    if (r.ascent_violations > 0) tags += '<span class="tag t-deco">上升过快 ×' + r.ascent_violations + '</span> ';
    if (tags) html += '<div class="card tight"><div class="tags">' + tags + '</div></div>';

    // 剖面
    html += '<div class="card"><div class="card-title">深度剖面<span class="more">' +
      d.nsamples + ' 采样点</span></div>' +
      '<div class="chart-wrap"><canvas class="chart" id="cvProfile"></canvas></div>' +
      '<div class="legend"><span><i style="background:#0d6e8c"></i>深度</span>' +
      '<span><i style="background:#f08a24"></i>水温</span>' +
      '<span><i style="background:#9aadb9"></i>横轴：分钟</span></div></div>';

    // GF99
    html += '<div class="card"><div class="card-title">GF99 过饱和梯度' +
      '<span class="more">100% = M 值线</span></div>' +
      '<div class="chart-wrap"><canvas class="chart" id="cvGF"></canvas></div>' +
      '<div class="legend"><span><i style="background:#00a8cc"></i>GF99</span>' +
      '<span><i style="background:#e63946"></i>M 值线 100%</span></div></div>';

    // 天花板 + 算法切换
    html += '<div class="card"><div class="card-title">减压天花板' +
      '<span class="more">' + alg.name + '</span></div>' +
      '<div class="chart-wrap"><canvas class="chart" id="cvCeil"></canvas></div>' +
      '<div class="legend"><span><i style="background:#f08a24"></i>需停留的最浅深度</span></div>' +
      '<div class="filterbar" style="padding:10px 0 0;margin:0">' + chips + '</div>' +
      '<div style="font-size:11px;color:var(--ink2);margin-top:8px;line-height:1.5">' +
      alg.desc + '</div></div>';

    // 多算法对拍
    html += '<div class="card"><div class="card-title">五种算法对拍</div>';
    ALGS.forEach(function (x) {
      var aa = d.algo[x.id];
      var pct = Math.min(100, aa.gf99_max);
      html += '<div class="algrow"><div class="an">' + x.name + '</div>' +
        '<div class="ab"><div class="bar"><i class="' +
        (aa.deco_duty ? 'danger' : 'safe') + '" style="width:' + pct + '%"></i></div></div>' +
        '<div class="av">' + (aa.deco_duty ? aa.ceiling_max.toFixed(1) + 'm' : '免减压') + '</div></div>';
    });
    html += '<div style="font-size:11px;color:var(--ink2);margin-top:9px;line-height:1.5">' +
      'GF99 峰值 ' + gfMaxAll.toFixed(0) + '% 与算法无关（相对 M 值线），' +
      '差异体现在减压义务判定上。</div></div>';

    // 原始数据
    html += '<div class="card tight">' +
      '<div class="kv"><span class="k">原始记录编号</span><span class="v">#' +
      d.raw_ids.join(', #') + '</span></div>' +
      '<div class="kv"><span class="k">采样点数</span><span class="v">' + d.nsamples + '</span></div>' +
      '<div class="kv"><span class="k">水面间隔阈值</span><span class="v">' +
      DATA.meta.si_threshold + ' 秒</span></div></div>';

    $('#detailScroll').innerHTML = html;

    drawProfile($('#cvProfile'), d.profile);
    drawGF($('#cvGF'), r.gf99_series);
    drawCeiling($('#cvCeil'), r.ceiling_series);

    Array.prototype.forEach.call(
      $('#detailScroll').querySelectorAll('.chip.alg'), function (el) {
        el.addEventListener('click', function () {
          state.curAlg = el.getAttribute('data-alg');
          renderDetail();
        });
      });
  }

  function stat(v, unit, key, cls) {
    return '<div class="stat"><div class="v"' +
      (cls ? ' style="color:var(--' + (cls === 'gf-bad' ? 'danger' : cls === 'gf-warn' ? 'warn' : 'safe') + ')"' : '') +
      '>' + v + (unit ? '<small>' + unit + '</small>' : '') +
      '</div><div class="k">' + key + '</div></div>';
  }

  // ------------------------------------------------------------------ 分析页
  function renderAnalysis() {
    var st = DATA.algo_stats;
    var valid = DATA.dives.filter(function (d) { return !isInvalid(d); });
    var maxDeco = 0;
    Object.keys(st).forEach(function (k) { if (st[k].deco_dives > maxDeco) maxDeco = st[k].deco_dives; });

    var html = '<div class="card"><div class="card-title">算法保守度对比' +
      '<span class="more">' + valid.length + ' 条有效</span></div>';
    ALGS.forEach(function (a) {
      var s = st[a.id];
      html += '<div class="algrow"><div class="an">' + s.name + '</div>' +
        '<div class="ab"><div class="bar"><i class="' +
        (s.deco_ratio > 40 ? 'danger' : s.deco_ratio > 15 ? 'warn' : 'safe') +
        '" style="width:' + (maxDeco ? s.deco_dives / maxDeco * 100 : 0) + '%"></i></div></div>' +
        '<div class="av">' + s.deco_dives + ' 条</div></div>';
    });
    html += '<div style="font-size:11px;color:var(--ink2);margin-top:10px;line-height:1.55">' +
      '同一批潜水，换一套减压算法，有减压义务的潜次相差 ' +
      (st.gf20_60.deco_dives / Math.max(1, st.zhl16c.deco_dives)).toFixed(0) +
      ' 倍。保守算法不是"更安全"，而是揭示被宽松设置掩盖的减压义务。</div></div>';

    // 隐藏风险
    var hr = DATA.hidden_risks;
    html += '<div class="card"><div class="card-title">隐藏风险' +
      '<span class="more">' + hr.length + ' 条</span></div>' +
      '<div style="font-size:11px;color:var(--ink2);margin:-4px 0 9px;line-height:1.5">' +
      '原始 M 值线判定"免减压"，但保守算法下有减压义务</div>';
    if (!hr.length) {
      html += '<div class="empty" style="padding:20px">无</div>';
    } else {
      hr.slice(0, 30).forEach(function (h) {
        html += '<div class="riskrow" data-id="' + h.id + '">' +
          '<div class="rd">' + fmtDate(h.dt) + '</div>' +
          '<div class="rm"><b>' + h.maxdepth.toFixed(1) + 'm</b> · ' +
          Math.round(h.duration / 60) + '分钟</div>' +
          '<div class="rc">' + Math.max(h.gf50_70, h.gf30_85).toFixed(1) + 'm</div>' +
          '</div>';
      });
      if (hr.length > 30) {
        html += '<div style="font-size:11px;color:var(--ink3);text-align:center;padding-top:9px">' +
          '仅显示最近 30 条，共 ' + hr.length + ' 条</div>';
      }
    }
    html += '</div>';

    // 合并
    var mg = DATA.dives.filter(function (d) { return d.merged; });
    html += '<div class="card"><div class="card-title">日志合并' +
      '<span class="more">' + mg.length + ' 组</span></div>' +
      '<div style="font-size:11px;color:var(--ink2);margin:-4px 0 9px;line-height:1.55">' +
      '水面间隔 ≤ ' + DATA.meta.si_threshold +
      ' 秒的相邻记录已合并为一次潜水。合并时把水面段当作真实间隔参与排气积分，' +
      '否则会低估余氮。</div>';
    mg.slice(0, 12).forEach(function (d) {
      html += '<div class="riskrow" data-id="' + d.id + '">' +
        '<div class="rd">' + fmtDate(d.dt) + '</div>' +
        '<div class="rm"><b>' + d.raw_ids.length + '</b> 条 → 1 条 · ' +
        fmtDur(d.duration) + '</div>' +
        '<div class="rc" style="color:var(--brand2)">' + d.maxdepth.toFixed(1) + 'm</div>' +
        '</div>';
    });
    if (mg.length > 12) {
      html += '<div style="font-size:11px;color:var(--ink3);text-align:center;padding-top:9px">' +
        '共 ' + mg.length + ' 组</div>';
    }
    html += '</div>';

    $('#analysisScroll').innerHTML = html;
    Array.prototype.forEach.call(
      $('#analysisScroll').querySelectorAll('.riskrow'), function (el) {
        el.addEventListener('click', function () { openDetail(+el.getAttribute('data-id')); });
      });
  }

  // ------------------------------------------------------------------ 统计页
  function renderStats() {
    var valid = DATA.dives.filter(function (d) { return !isInvalid(d); });
    var depths = valid.map(function (d) { return d.maxdepth; });
    var avg = depths.reduce(function (a, b) { return a + b; }, 0) / Math.max(1, depths.length);
    var deepest = valid.reduce(function (a, b) { return b.maxdepth > a.maxdepth ? b : a; }, valid[0]);
    var totalSec = valid.reduce(function (a, b) { return a + b.duration; }, 0);

    $('#statsSub').textContent = DATA.meta.date_from + ' ~ ' + DATA.meta.date_to;

    var html = '<div class="grid g2">' +
      stat(String(valid.length), '次', '有效潜水') +
      stat((totalSec / 3600).toFixed(0), '小时', '累计水下') +
      stat(deepest.maxdepth.toFixed(1), 'm', '最深纪录') +
      stat(avg.toFixed(1), 'm', '平均深度') +
      '</div>';

    // 月度
    var bm = DATA.by_month;
    var wm = bm.map(function (x) {
      return { k: x.month.slice(2).replace('-', '/'), v: x.count };
    });
    html += '<div class="card"><div class="card-title">月度分布' +
      '<span class="more">' + bm.length + ' 个月</span></div>' +
      '<div class="chart-wrap"><canvas class="chart" id="cvMonth"></canvas></div></div>';

    // 深度分布
    var bins = [
      { k: '0-5', lo: 0, hi: 5 }, { k: '5-10', lo: 5, hi: 10 },
      { k: '10-15', lo: 10, hi: 15 }, { k: '15-20', lo: 15, hi: 20 },
      { k: '20-25', lo: 20, hi: 25 }, { k: '25-30', lo: 25, hi: 30 },
      { k: '30+', lo: 30, hi: 999 }
    ];
    bins.forEach(function (b) {
      b.v = depths.filter(function (x) { return x >= b.lo && x < b.hi; }).length;
    });
    html += '<div class="card"><div class="card-title">最大深度分布' +
      '<span class="more">单位：次</span></div>' +
      '<div class="chart-wrap"><canvas class="chart" id="cvDepth"></canvas></div></div>';

    // 年度
    var byYear = {};
    valid.forEach(function (d) {
      var y = d.dt.slice(0, 4);
      if (!byYear[y]) byYear[y] = { n: 0, t: 0, max: 0 };
      byYear[y].n++;
      byYear[y].t += d.duration;
      if (d.maxdepth > byYear[y].max) byYear[y].max = d.maxdepth;
    });
    html += '<div class="card"><div class="card-title">年度汇总</div>';
    Object.keys(byYear).sort().forEach(function (y) {
      var b = byYear[y];
      html += '<div class="kv"><span class="k">' + y + ' 年</span><span class="v">' +
        b.n + ' 次 · ' + (b.t / 3600).toFixed(0) + ' 小时 · 最深 ' + b.max.toFixed(1) + 'm</span></div>';
    });
    html += '</div>';

    // 数据质量
    html += '<div class="card"><div class="card-title">数据质量</div>' +
      '<div class="kv"><span class="k">原始记录</span><span class="v">' + DATA.meta.n_raw + ' 条</span></div>' +
      '<div class="kv"><span class="k">合并后</span><span class="v">' + DATA.meta.n_groups + ' 条</span></div>' +
      '<div class="kv"><span class="k">被合并</span><span class="v">' + DATA.meta.n_merged + ' 组</span></div>' +
      '<div class="kv"><span class="k">疑似无效</span><span class="v">' + DATA.meta.n_invalid + ' 条</span></div>' +
      '<div style="font-size:11px;color:var(--ink2);margin-top:9px;line-height:1.55">' +
      '无效记录只做标记不删除，原始二进制永不丢弃；调整阈值可全库重算。</div></div>';

    $('#statsScroll').innerHTML = html;
    drawBars($('#cvMonth'), wm, '#00a8cc', 118);
    drawBars($('#cvDepth'), bins, '#0d6e8c', 118);
  }

  // ------------------------------------------------------------------ 设备页
  var ble = { device: null, server: null, ch: null, scanning: false, log: [] };

  function renderDevice() {
    var html =
      '<div class="devhero">' +
      '<div class="dn">Crest CR-4</div>' +
      '<div class="ds">Deep Six Excursion 贴牌 · libdivecomputer 已支持</div>' +
      '<div class="dr">' +
      '<div><div class="v">' + DATA.meta.n_raw + '</div><div class="k">已下载</div></div>' +
      '<div><div class="v">' + DATA.meta.n_groups + '</div><div class="k">合并后</div></div>' +
      '<div><div class="v">' + DATA.meta.total_time_h + '</div><div class="k">累计小时</div></div>' +
      '</div></div>';

    html += '<div class="card"><div class="card-title">蓝牙连接</div>' +
      '<button class="btn" id="btnScan">' +
      (ble.scanning ? '扫描中…' : '扫描潜水电脑表') + '</button>' +
      '<button class="btn ghost" id="btnDownload" style="margin-top:8px"' +
      (ble.ch ? '' : ' disabled') + '>' +
      (ble.ch ? '下载新记录' : '未连接') + '</button>' +
      '</div>';

    html += '<div class="card"><div class="card-title">连接状态</div>' +
      '<div id="bleState">' + bleLogHtml() + '</div></div>';

    html += '<div class="card"><div class="card-title">实测 GATT 参数' +
      '<span class="more">已验证</span></div>' +
      '<div class="kv"><span class="k">设备名</span><span class="v">Crest-CR4</span></div>' +
      '<div class="kv"><span class="k">地址类型</span><span class="v">random（关键）</span></div>' +
      '<div class="kv"><span class="k">数据服务</span><span class="v" style="font-size:10px">f000ffe0-…</span></div>' +
      '<div class="kv"><span class="k">数据特征</span><span class="v" style="font-size:10px">f000ffe1-…</span></div>' +
      '<div class="kv"><span class="k">广播 UUID</span><span class="v">0xFFC0 / 0xFFF0</span></div>' +
      '<div style="font-size:11px;color:var(--ink2);margin-top:9px;line-height:1.55">' +
      '地址类型必须是 random —— Windows 默认按 public 连接会在 GATT 发现阶段报 Unreachable。' +
      '广播里声明的 UUID 与连接后枚举出的服务不一致，选通道必须靠 GATT 枚举。</div></div>';

    html += '<div class="note">' +
      '<b>Web 端限制：</b>Web Bluetooth 需要 Chrome / Edge，且页面必须来自 HTTPS 或 localhost。' +
      '用 file:// 直接打开时扫描不可用，但上面 4 个页面的分析功能完整可用。' +
      '正式版的蓝牙栈会落在原生侧（桌面 Rust + btleplug，安卓 Android BLE）。</div>';

    $('#deviceScroll').innerHTML = html;

    $('#btnScan').addEventListener('click', doScan);
    $('#btnDownload').addEventListener('click', doDownload);
  }

  function bleLogHtml() {
    if (!ble.log.length) {
      return '<div style="font-size:12px;color:var(--ink3);padding:6px 0">尚未扫描</div>';
    }
    return ble.log.map(function (l) {
      return '<div class="kv"><span class="k">' + l[0] + '</span><span class="v">' + l[1] + '</span></div>';
    }).join('');
  }
  function bleLog(k, v) {
    ble.log.push([k, v]);
    var el = document.getElementById('bleState');
    if (el) el.innerHTML = bleLogHtml();
  }

  var DATA_SERVICE = 'f000ffe0-ab12-45ec-84c8-46483f4626e9';
  var DATA_CHAR = 'f000ffe1-ab12-45ec-84c8-46483f4626e9';

  function doScan() {
    if (!navigator.bluetooth) {
      bleLog('错误', '浏览器不支持 Web Bluetooth');
      alert('Web Bluetooth 不可用。请用 Chrome / Edge，并通过 http://localhost 打开页面。');
      return;
    }
    ble.scanning = true;
    $('#btnScan').textContent = '扫描中…';
    bleLog('状态', '正在扫描…');
    navigator.bluetooth.requestDevice({
      filters: [{ namePrefix: 'Crest' }, { namePrefix: 'EXCURSION' },
                { namePrefix: 'CENTAURI' }, { namePrefix: 'ALPHA' }],
      optionalServices: [DATA_SERVICE]
    }).then(function (dev) {
      ble.device = dev;
      bleLog('发现', dev.name + ' ' + dev.id);
      bleLog('状态', '连接中…');
      return dev.gatt.connect();
    }).then(function (srv) {
      ble.server = srv;
      bleLog('已连接', 'GATT 就绪');
      return srv.getPrimaryService(DATA_SERVICE);
    }).then(function (s) {
      return s.getCharacteristic(DATA_CHAR);
    }).then(function (ch) {
      ble.ch = ch;
      bleLog('通道', 'ffe1 已就绪');
      var b = document.getElementById('btnDownload');
      if (b) { b.disabled = false; b.textContent = '下载新记录'; }
      $('#btnScan').textContent = '重新扫描';
      ble.scanning = false;
    }).catch(function (e) {
      ble.scanning = false;
      $('#btnScan').textContent = '扫描潜水电脑表';
      bleLog('失败', String(e && e.message ? e.message : e).slice(0, 40));
    });
  }

  function doDownload() {
    if (!ble.ch) return;
    bleLog('下载', '协议层待接入');
    // 协议层：deepsix 包结构 [grp][cmd][dir][len][payload][csum]
    // 已在 tools/dctool.py 中用 Python 验证通过，此处是迁移到 JS 的位置。
    alert('协议层尚未接入 Web 版。\n\nPython 侧已完整实现并可下载：\n  python dctool.py download --all-devices\n\n' +
      'JS 侧迁移位置：web/app.js 的 doDownload()。');
  }

  // ------------------------------------------------------------------ 导航
  function go(page) {
    state.page = page;
    Array.prototype.forEach.call(document.querySelectorAll('.page'), function (p) {
      p.classList.toggle('active', p.id === page);
    });
    Array.prototype.forEach.call(document.querySelectorAll('.tab'), function (t) {
      t.classList.toggle('on', t.getAttribute('data-page') === page);
    });
    if (page === 'page-dives') renderDives();
    if (page === 'page-analysis') renderAnalysis();
    if (page === 'page-stats') renderStats();
    if (page === 'page-device') renderDevice();
  }

  Array.prototype.forEach.call(document.querySelectorAll('.tab'), function (t) {
    t.addEventListener('click', function () {
      go(t.getAttribute('data-page'));
    });
  });
  $('#btnBack').addEventListener('click', function () { go('page-dives'); });
  Array.prototype.forEach.call(document.querySelectorAll('#filterBar .chip'), function (c) {
    c.addEventListener('click', function () {
      Array.prototype.forEach.call(document.querySelectorAll('#filterBar .chip'), function (x) {
        x.classList.remove('on');
      });
      c.classList.add('on');
      state.filter = c.getAttribute('data-filter');
      renderDives();
    });
  });

  // 状态栏时钟
  function tick() {
    var d = new Date();
    $('#sbTime').textContent = d.getHours() + ':' +
      ('0' + d.getMinutes()).slice(-2);
  }
  tick(); setInterval(tick, 30000);

  // 启动。支持 URL 参数直开某页，方便调试与分享：
  //   index.html?page=page-detail&dive=198
  //   index.html?page=page-analysis
  function boot() {
    var q = {};
    (location.search || '').replace(/^\?/, '').split('&').forEach(function (kv) {
      if (!kv) return;
      var p = kv.split('=');
      q[decodeURIComponent(p[0])] = decodeURIComponent(p[1] || '');
    });
    if (q.page === 'page-detail' && q.dive) {
      openDetail(+q.dive);
      return;
    }
    go(q.page && document.getElementById(q.page) ? q.page : 'page-dives');
  }
  boot();
  window.addEventListener('resize', function () {
    if (state.page === 'page-stats') renderStats();
    if (state.page === 'page-detail') renderDetail();
  });
})();
