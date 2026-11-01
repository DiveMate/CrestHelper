/* CrestHelper · 界面预览（手机尺寸 HTML 原型）
 *
 * 定位：这是「界面长什么样」的可点原型，不是第二套实现。
 * 减压算法、合并规则、同步协议都在安卓端（Kotlin）里，这里只做展示。
 * 数据由 tools/make_web_demo.py 生成：摘要全量，逐点剖面只放少量示例。
 */
(function () {
  'use strict';

  var D = window.APP_DATA || { meta: {}, dives: [] };
  var META = D.meta;
  var DIVES = D.dives || [];
  var state = { year: null };

  /* ---------------------------------------------------------------- 工具 */
  function pad(n) { return n < 10 ? '0' + n : '' + n; }

  function fmtDate(dt) {           // "2026-10-05 18:23:41" -> "10月05日"
    var p = dt.split(' ');
    var md = p[0].split('-');
    return (+md[1]) + '月' + md[2] + '日';
  }

  function fmtClock(dt) { return dt.slice(11, 16); }

  function fmtDur(sec) {
    if (!sec) return '—';
    var m = Math.round(sec / 60);
    return m >= 60 ? Math.floor(m / 60) + ' 时 ' + pad(m % 60) + ' 分' : m + ' 分';
  }

  function one(v) { return (Math.round(v * 10) / 10).toFixed(1); }

  function esc(s) {
    return String(s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  /* ------------------------------------------------------------ 导航切换 */
  var tabbar = document.getElementById('tabbar');
  var pages = document.querySelectorAll('.page');

  function show(pageId) {
    Array.prototype.forEach.call(pages, function (p) {
      p.classList.toggle('active', p.id === pageId);
    });
    Array.prototype.forEach.call(tabbar.querySelectorAll('.tab'), function (t) {
      t.classList.toggle('on', t.dataset.page === pageId);
    });
  }

  tabbar.addEventListener('click', function (e) {
    var btn = e.target.closest('.tab');
    if (btn) show(btn.dataset.page);
  });

  document.getElementById('btnBack').addEventListener('click', function () {
    show('page-dives');
  });

  /* ---------------------------------------------------------------- 日志 */
  function years() {
    var set = {};
    DIVES.forEach(function (d) { set[d.dt.slice(0, 4)] = 1; });
    return Object.keys(set).sort().reverse();
  }

  function shownDives() {
    return state.year ? DIVES.filter(function (d) { return d.dt.slice(0, 4) === state.year; }) : DIVES;
  }

  function renderFilters() {
    var bar = document.getElementById('filterBar');
    var html = '<button class="chip' + (state.year ? '' : ' on') + '" data-y="">全部</button>';
    years().forEach(function (y) {
      html += '<button class="chip' + (state.year === y ? ' on' : '') +
        '" data-y="' + y + '">' + y + '</button>';
    });
    bar.innerHTML = html;
  }

  function renderSummary(list) {
    var hours = list.reduce(function (s, d) { return s + d.dur; }, 0) / 3600;
    var deepest = list.reduce(function (m, d) { return Math.max(m, d.maxd); }, 0);
    document.getElementById('summaryBar').innerHTML =
      '<div class="grid" style="padding:0 14px;margin-bottom:6px">' +
        '<div class="stat"><div class="v">' + list.length + '</div><div class="k">潜次</div></div>' +
        '<div class="stat"><div class="v">' + one(hours) + '<small>h</small></div><div class="k">累计时长</div></div>' +
        '<div class="stat"><div class="v">' + one(deepest) + '<small>m</small></div><div class="k">最大深度</div></div>' +
      '</div>' +
      '<div style="padding:0 15px 8px;font-size:11px;color:var(--ink2)">当前范围：' +
        (state.year ? state.year + ' 年' : '全部记录') + '</div>';
  }

  function renderList() {
    var list = shownDives();
    renderSummary(list);
    var box = document.getElementById('diveList');
    if (!list.length) {
      box.innerHTML = '<div class="empty">当前筛选下没有潜水记录</div>';
      return;
    }
    box.innerHTML = list.map(function (d) {
      var temp = d.mint != null ? one(d.mint) + '°' : '—';
      var tags = '';
      if (d.profile) tags += '<span class="tag t-merged">含剖面</span>';
      return '<div class="dcard" data-id="' + esc(d.id) + '">' +
        '<div class="dmain">' +
          '<div class="d1"><span class="ddate">' + fmtDate(d.dt) + '</span>' +
            '<span class="dur">' + fmtDur(d.dur) + '</span></div>' +
          '<div class="d2">最深 <b>' + one(d.maxd) + ' m</b>' +
            (d.avgd != null ? ' · 平均 <b>' + one(d.avgd) + ' m</b>' : '') +
            ' · 水温 <b>' + temp + '</b></div>' +
          (tags ? '<div class="tags">' + tags + '</div>' : '') +
        '</div>' +
        '<span class="arrow">›</span>' +
      '</div>';
    }).join('');
  }

  document.getElementById('filterBar').addEventListener('click', function (e) {
    var chip = e.target.closest('.chip');
    if (!chip) return;
    state.year = chip.dataset.y || null;
    renderFilters();
    renderList();
  });

  document.getElementById('diveList').addEventListener('click', function (e) {
    var card = e.target.closest('.dcard');
    if (card) openDetail(card.dataset.id);
  });

  /* ------------------------------------------------------------ 折线图 */
  function lineChart(points, opt) {
    // points: [[x, y], ...]；opt: {w,h,color,fill,yMin,yMax,xUnit,yUnit,invert}
    var w = opt.w || 340, h = opt.h || 130;
    var pl = 30, pr = 8, pt = 8, pb = 18;
    var xs = points.map(function (p) { return p[0]; });
    var ys = points.map(function (p) { return p[1]; });
    var x0 = Math.min.apply(null, xs), x1 = Math.max.apply(null, xs);
    var y0 = opt.yMin != null ? opt.yMin : Math.min.apply(null, ys);
    var y1 = opt.yMax != null ? opt.yMax : Math.max.apply(null, ys);
    if (y1 - y0 < 1e-6) y1 = y0 + 1;
    var sx = function (x) { return pl + (x - x0) / (x1 - x0 || 1) * (w - pl - pr); };
    var sy = function (y) {
      var t = (y - y0) / (y1 - y0);
      return opt.invert ? pt + t * (h - pt - pb) : h - pb - t * (h - pt - pb);
    };
    var path = points.map(function (p, i) {
      return (i ? 'L' : 'M') + sx(p[0]).toFixed(1) + ' ' + sy(p[1]).toFixed(1);
    }).join(' ');
    var area = path + ' L' + sx(x1).toFixed(1) + ' ' + (h - pb) + ' L' + sx(x0).toFixed(1) + ' ' + (h - pb) + ' Z';
    var grid = '';
    for (var i = 0; i <= 3; i++) {
      var gy = pt + i * (h - pt - pb) / 3;
      var val = opt.invert ? y0 + i * (y1 - y0) / 3 : y1 - i * (y1 - y0) / 3;
      grid += '<line x1="' + pl + '" y1="' + gy + '" x2="' + (w - pr) + '" y2="' + gy +
        '" stroke="#eef2f6" stroke-width="1"/>' +
        '<text x="' + (pl - 4) + '" y="' + (gy + 3) + '" font-size="8" fill="#9aadb9" text-anchor="end">' +
        (y1 - y0 >= 6 ? Math.round(val) : one(val)) + '</text>';
    }
    var id = 'g' + Math.random().toString(36).slice(2, 7);
    return '<svg class="chart" viewBox="0 0 ' + w + ' ' + h + '" preserveAspectRatio="none">' +
      '<defs><linearGradient id="' + id + '" x1="0" y1="0" x2="0" y2="1">' +
        '<stop offset="0" stop-color="' + opt.color + '" stop-opacity=".28"/>' +
        '<stop offset="1" stop-color="' + opt.color + '" stop-opacity="0"/>' +
      '</linearGradient></defs>' + grid +
      '<path d="' + area + '" fill="url(#' + id + ')"/>' +
      '<path d="' + path + '" fill="none" stroke="' + opt.color + '" stroke-width="1.6" ' +
        'stroke-linejoin="round" stroke-linecap="round"/>' +
      '<text x="' + (w - pr) + '" y="' + (h - 5) + '" font-size="8" fill="#9aadb9" text-anchor="end">' +
        opt.xLabel + '</text></svg>';
  }

  /* ---------------------------------------------------------------- 详情 */
  function openDetail(id) {
    var d = DIVES.filter(function (x) { return x.id === id; })[0];
    if (!d) return;

    document.getElementById('dtTitle').textContent = fmtDate(d.dt);
    document.getElementById('dtSub').textContent =
      fmtClock(d.dt) + ' · ' + fmtDur(d.dur) + (d.profile ? ' · 记录 #' + d.no : ' · 摘要（无剖面）');

    var html = '';

    // 潜水信息（用户手填；这里给的是示例标注）
    html += '<div class="card"><div class="card-title">潜水信息<span class="more">示例标注</span></div>' +
      '<div class="kv"><span class="k">潜点</span><span class="v">蜈支洲岛 · 后海</span></div>' +
      '<div class="kv"><span class="k">能见度</span><span class="v">18 m</span></div>' +
      '<div class="kv"><span class="k">穿着</span><span class="v">湿衣 3 mm</span></div>' +
      '<div class="kv"><span class="k">类型</span><span class="v">珊瑚礁 · 海水</span></div>' +
      '</div>';

    // 关键指标
    html += '<div class="grid">' +
      '<div class="stat"><div class="v">' + one(d.maxd) + '<small>m</small></div><div class="k">最大深度</div></div>' +
      '<div class="stat"><div class="v">' + (d.avgd != null ? one(d.avgd) : '—') +
        (d.avgd != null ? '<small>m</small>' : '') + '</div><div class="k">平均深度</div></div>' +
      '<div class="stat"><div class="v">' + Math.round(d.dur / 60) + '<small>min</small></div><div class="k">水下时间</div></div>' +
      '</div>';

    html += '<div class="grid g2">' +
      '<div class="stat"><div class="v">' + (d.mint != null ? one(d.mint) : '—') +
        (d.mint != null ? '<small>°C</small>' : '') + '</div><div class="k">最低水温</div></div>' +
      '<div class="stat"><div class="v">' + (d.maxt != null ? one(d.maxt) : '—') +
        (d.maxt != null ? '<small>°C</small>' : '') + '</div><div class="k">最高水温</div></div>' +
      '</div>';

    if (d.profile && d.profile.length > 2) {
      var depthPts = d.profile.map(function (p) { return [p[0], p[1]]; });
      var tempPts = d.profile.filter(function (p) { return p[2] != null; })
        .map(function (p) { return [p[0], p[2]]; });
      var maxMin = Math.ceil(d.dur / 60);

      html += '<div class="card"><div class="card-title">深度剖面' +
        '<span class="more">' + d.profile.length + ' 点</span></div>' +
        '<div class="chart-wrap">' +
        lineChart(depthPts, {
          color: '#0d6e8c', invert: true, yMin: 0,
          yMax: Math.ceil(d.maxd / 5) * 5, xLabel: maxMin + ' min'
        }) +
        '</div><div class="legend"><span><i style="background:#0d6e8c"></i>深度 (m)</span></div></div>';

      if (tempPts.length > 2) {
        var ts = tempPts.map(function (p) { return p[1]; });
        html += '<div class="card"><div class="card-title">水温</div><div class="chart-wrap">' +
          lineChart(tempPts, {
            color: '#f08a24', h: 90,
            yMin: Math.floor(Math.min.apply(null, ts)) - 0.5,
            yMax: Math.ceil(Math.max.apply(null, ts)) + 0.5,
            xLabel: maxMin + ' min'
          }) + '</div></div>';
      }

      html += '<div class="card"><div class="card-title">记录信息</div>' +
        '<div class="kv"><span class="k">采样点</span><span class="v">' + d.n + '</span></div>' +
        '<div class="kv"><span class="k">记录编号</span><span class="v">#' + d.no + '</span></div>' +
        '<div class="kv"><span class="k">原始文件</span><span class="v" style="font-size:10.5px">' +
          esc(d.id) + '</span></div></div>';
    } else {
      html += '<div class="note">这条只有摘要行（时间、时长、深度、水温）。' +
        '逐点剖面属于个人潜水轨迹，公开仓库里只挑了少量几条做示例。</div>';
      html += '<div class="card"><div class="card-title">记录信息</div>' +
        '<div class="kv"><span class="k">采样点</span><span class="v">' + d.n + '</span></div>' +
        '<div class="kv"><span class="k">记录编号</span><span class="v">#' + d.no + '</span></div></div>';
    }

    document.getElementById('detailScroll').innerHTML = html;
    show('page-detail');
  }

  /* ---------------------------------------------------------------- 统计 */
  function bucket(values, step, max) {
    var out = [];
    for (var i = 0; i <= Math.floor(max / step); i++) out.push(0);
    values.forEach(function (v) {
      var i = Math.min(out.length - 1, Math.floor(v / step));
      if (i >= 0) out[i]++;
    });
    // 砍掉尾部连续为 0 的桶，别让表格拖着几行空柱
    while (out.length > 1 && out[out.length - 1] === 0) out.pop();
    return out;
  }

  function barRow(label, count, maxCount, unit) {
    var pct = maxCount ? Math.max(2, count / maxCount * 100) : 0;
    return '<div class="algrow"><span class="an">' + label + '</span>' +
      '<span class="ab"><span class="bar"><i style="width:' + pct + '%"></i></span></span>' +
      '<span class="av">' + count + '</span></div>';
  }

  function renderStats() {
    var byYear = {};
    DIVES.forEach(function (d) {
      var y = d.dt.slice(0, 4);
      byYear[y] = byYear[y] || { n: 0, sec: 0 };
      byYear[y].n++;
      byYear[y].sec += d.dur;
    });
    var ys = Object.keys(byYear).sort();
    var maxN = Math.max.apply(null, ys.map(function (y) { return byYear[y].n; }));

    var hours = DIVES.reduce(function (s, d) { return s + d.dur; }, 0) / 3600;
    var deepest = DIVES.reduce(function (m, d) { return Math.max(m, d.maxd); }, 0);

    document.getElementById('statsSub').textContent =
      DIVES.length + ' 次潜水 · 累计 ' + one(hours) + ' 小时 · ' +
      (META.date_from || '').slice(0, 7) + ' 起';

    var html = '';
    html += '<div class="grid">' +
      '<div class="stat"><div class="v">' + DIVES.length + '</div><div class="k">总潜次</div></div>' +
      '<div class="stat"><div class="v">' + one(hours) + '<small>h</small></div><div class="k">总时长</div></div>' +
      '<div class="stat"><div class="v">' + one(deepest) + '<small>m</small></div><div class="k">最深</div></div>' +
      '</div>';

    html += '<div class="card"><div class="card-title">按年份</div>';
    ys.slice().reverse().forEach(function (y) {
      html += barRow(y, byYear[y].n, maxN);
    });
    html += '</div>';

    var dep = bucket(DIVES.map(function (d) { return d.maxd; }), 5, 40);
    var maxDep = Math.max.apply(null, dep);
    html += '<div class="card"><div class="card-title">最大深度分布</div>';
    dep.forEach(function (n, i) {
      html += barRow((i * 5) + '–' + (i * 5 + 5) + ' m', n, maxDep);
    });
    html += '</div>';

    var dur = bucket(DIVES.map(function (d) { return d.dur / 60; }), 15, 120);
    var maxDur = Math.max.apply(null, dur);
    html += '<div class="card"><div class="card-title">水下时间分布</div>';
    dur.forEach(function (n, i) {
      html += barRow((i * 15) + '–' + (i * 15 + 15) + ' 分', n, maxDur);
    });
    html += '</div>';

    document.getElementById('statsScroll').innerHTML = html;
  }

  /* ---------------------------------------------------------------- 设备 */
  function renderDevice() {
    var html = '';

    html += '<div class="devhero"><div class="dn">Crest CR-4</div>' +
      '<div class="ds">未连接 · 点下方扫描后自动配对</div>' +
      '<div class="dr">' +
        '<div><div class="v">' + DIVES.length + '</div><div class="k">本地记录</div></div>' +
        '<div><div class="v">' + (META.date_to || '').slice(0, 10) + '</div><div class="k">最近一次</div></div>' +
      '</div></div>';

    html += '<button class="btn">扫描附近的手表</button>' +
      '<div style="height:9px"></div>' +
      '<button class="btn ghost">直接连接已配对设备</button>' +
      '<div style="height:11px"></div>';

    html += '<div class="card"><div class="card-title">同步</div>' +
      '<div class="kv"><span class="k">同步范围</span><span class="v">增量</span></div>' +
      '<div class="kv"><span class="k">后台运行</span><span class="v">前台服务 + 唤醒锁</span></div>' +
      '<div class="note" style="margin:10px 0 0">同步跑在进程级引擎里，退出页面、切 App、锁屏都不会中断；' +
        '进度和取消在通知栏。</div></div>';

    html += '<div class="card"><div class="card-title">数据维护</div>' +
      '<div class="kv"><span class="k">导入 .bin 文件</span><span class="v">选择文件</span></div>' +
      '<div class="kv"><span class="k">清空导入数据</span><span class="v" style="color:var(--danger)">删除</span></div>' +
      '<div class="kv"><span class="k">回收站中的记录</span><span class="v">1 条</span></div></div>';

    // 折叠卡：默认只给一行摘要
    html += '<div class="card" id="envCard"><div class="card-title">运行环境与构建' +
      '<span class="more" id="envToggle">展开明细</span></div>' +
      '<div style="font-size:12px;color:var(--ink2)">Android 16 (API 36) · SM-S9110 · libdc 已加载</div>' +
      '<div id="envBody" style="display:none;margin-top:10px">' +
        '<div class="kv"><span class="k">设备</span><span class="v">samsung SM-S9110</span></div>' +
        '<div class="kv"><span class="k">ABI</span><span class="v">arm64-v8a</span></div>' +
        '<div class="kv"><span class="k">蓝牙</span><span class="v">已开启</span></div>' +
        '<div class="kv"><span class="k">权限</span><span class="v">已授予</span></div>' +
        '<div class="kv"><span class="k">libdc (.so)</span><span class="v">已加载</span></div>' +
        '<div class="kv"><span class="k">libdc 版本</span><span class="v">v0.9.0</span></div>' +
      '</div></div>';

    document.getElementById('deviceScroll').innerHTML = html;

    document.getElementById('envToggle').addEventListener('click', function () {
      var body = document.getElementById('envBody');
      var open = body.style.display !== 'none';
      body.style.display = open ? 'none' : 'block';
      this.textContent = open ? '展开明细' : '收起明细';
    });
  }

  /* ---------------------------------------------------------------- 设置 */
  function renderSettings() {
    var html = '';

    html += '<div class="card"><div class="card-title">减压算法</div>' +
      '<div class="kv"><span class="k">算法</span><span class="v">ZHL-16C</span></div>' +
      '<div class="kv"><span class="k">梯度余量</span><span class="v">30 / 85</span></div>' +
      '<div class="note" style="margin:10px 0 0">参数默认 30/85，要别的值直接在详情页改；' +
        '改完当场按新参数重算 NDL 与最大安全深度。</div></div>';

    html += '<div class="card"><div class="card-title">合并规则</div>' +
      '<div class="kv"><span class="k">水面间隔阈值</span><span class="v">600 秒</span></div>' +
      '<div class="kv"><span class="k">自动合并</span><span class="v">开</span></div></div>';

    html += '<div class="card"><div class="card-title">脏数据过滤</div>' +
      '<div class="kv"><span class="k">最大深度小于</span><span class="v">3 m</span></div>' +
      '<div class="kv"><span class="k">或水下时间小于</span><span class="v">120 秒</span></div></div>';

    html += '<div class="card"><div class="card-title">回收站' +
      '<span class="more">打开 ›</span></div>' +
      '<div style="font-size:12px;color:var(--ink2)">删除只打标记，原始记录和采样点都还在，可随时恢复。</div>' +
      '<div class="kv" style="margin-top:8px"><span class="k">当前条数</span><span class="v">1 条</span></div></div>';

    html += '<div class="card"><div class="card-title">关于</div>' +
      '<div class="kv"><span class="k">版本</span><span class="v">0.2.0</span></div>' +
      '<div class="kv"><span class="k">适配对象</span><span class="v">Crest CR-4 系</span></div></div>';

    document.getElementById('settingsScroll').innerHTML = html;
  }

  /* ---------------------------------------------------------------- 启动 */
  // 截图/演示用：允许用 #stats / #device / #settings / #detail=<文件名> 直接落到某一页
  function routeFromHash() {
    var h = (location.hash || '').replace(/^#/, '');
    if (!h) return;
    if (h.indexOf('detail=') === 0) {
      openDetail(decodeURIComponent(h.slice(7)));
      return;
    }
    if (document.getElementById('page-' + h)) show('page-' + h);
  }

  function boot() {
    document.getElementById('divesSub').textContent =
      DIVES.length + ' 次 · 累计 ' + one((META.total_time_h || 0)) + ' 小时 · ' +
      (META.date_from || '') + ' ~ ' + (META.date_to || '');
    renderFilters();
    renderList();
    renderStats();
    renderDevice();
    renderSettings();
    routeFromHash();
  }

  boot();
})();
