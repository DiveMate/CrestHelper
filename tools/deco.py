# -*- coding: utf-8 -*-
"""
deco.py —— 减压模型内核（ZHL-16C + Gradient Factors）

这是整个项目里唯一需要"数值正确"的模块，也是 Kotlin 侧 Deco.kt 的逐行基准。
设计原则：
  * 纯函数，无 IO，无第三方依赖
  * 单位统一：压力 bar，深度 m，时间 min（对外接口用秒，内部转分）
  * 所有常量集中放置，方便和 Subsurface core/deco.cpp 对照

v0.1.4 算法口径校准（2026-11-10）：
  * 参数表换成**标准 ZHL-16C**。旧版混了 16B 的 N2 a 系数、4.0 min 的
    N2 首半时，以及「N2 半时 ÷ 2.65」推出来的 He 半时，名实不符。
  * 吸入惰性气体分压扣肺泡水蒸气 0.0627 bar（Bühlmann 值），组织初值
    同样按 (p_surface - p_wv) * f 平衡。旧版高估吸入分压约 6%。
  * 水面压 1.01325 bar、海水密度 1.03 kg/L（旧版 1.0 bar / 10 m/bar）。
  * GF 天花板改用 Subsurface 的「冻结第一减压站锚点 + 压力空间割线解析解」，
    不再逐点重算锚点、也不再按当前深度线性插值 gf。

参考：
  * Subsurface core/deco.cpp —— GF 插值直线、a/b 线性插值、水蒸气扣除
  * deco-planner server/app/deco_engine/buhlmann.py —— 同为本人所写，参数表同源
  * Erik Baker, "Clearing Up The Confusion About Gradient Factors"

⚠️ 本版数值与 v0.1.3 不可直接横向比较：参数表、压力口径、GF 求解都变了。
"""

import math

# ---------------------------------------------------------------------------
# 常量
# ---------------------------------------------------------------------------

SURFACE_PRESSURE = 1.01325       # bar，平均海平面大气压
WATER_VAPOR_PRESSURE = 0.0627    # bar，37°C 肺泡水蒸气压（Bühlmann Rq=1.0）
WATER_DENSITY = 1.03             # kg/L，海水
GRAVITY = 9.80665                # m/s^2
BAR_PER_METER = WATER_DENSITY * GRAVITY / 100.0   # bar/m ≈ 0.1010085
M_PER_BAR = 1.0 / BAR_PER_METER                  # m/bar ≈ 9.8997

# ZHL-16C：氮气半时（分钟）
N2_HALF_TIMES = [
    5.0, 8.0, 12.5, 18.5, 27.0, 38.3, 54.3, 77.0,
    109.0, 146.0, 187.0, 239.0, 305.0, 390.0, 498.0, 635.0,
]

# ZHL-16C：氮气 A 系数（bar）
N2_A = [
    1.1696, 1.0000, 0.8618, 0.7562, 0.6200, 0.5043,
    0.4410, 0.4000, 0.3750, 0.3500, 0.3295, 0.3065,
    0.2835, 0.2610, 0.2480, 0.2327,
]

# ZHL-16C：氮气 B 系数（无量纲，M = a + p_amb / b）
N2_B = [
    0.5578, 0.6514, 0.7222, 0.7825, 0.8126, 0.8434,
    0.8693, 0.8910, 0.9092, 0.9222, 0.9319, 0.9403,
    0.9477, 0.9544, 0.9602, 0.9653,
]

# ZHL-16C：氦气半时（分钟）
HE_HALF_TIMES = [
    1.88, 3.02, 4.72, 6.99, 10.21, 14.48, 20.53, 29.11,
    41.20, 55.19, 70.69, 90.34, 115.29, 147.42, 188.24, 240.03,
]

HE_A = [
    1.6189, 1.3830, 1.1919, 1.0458, 0.9220, 0.8205,
    0.7305, 0.6502, 0.5950, 0.5545, 0.5333, 0.5189,
    0.5181, 0.5176, 0.5172, 0.5119,
]

HE_B = [
    0.4770, 0.5747, 0.6527, 0.7223, 0.7582, 0.7957,
    0.8279, 0.8553, 0.8757, 0.8903, 0.8997, 0.9073,
    0.9122, 0.9171, 0.9217, 0.9267,
]

NCOMP = 16


class Gas(object):
    """呼吸气体。默认空气。"""

    def __init__(self, o2=0.21, he=0.0):
        self.o2 = o2
        self.he = he
        self.n2 = 1.0 - o2 - he

    def inert_fraction(self, i):
        """返回第 i 组织对应的惰性气体份额（这里把 N2/He 分开算，
        所以返回的是 (fn2, fhe)）。"""
        return self.n2, self.he

    def __repr__(self):
        if self.he > 0:
            return "Trimix %.0f/%.0f" % (self.o2 * 100, self.he * 100)
        return "Nitrox %.0f" % (self.o2 * 100)


AIR = Gas(0.21, 0.0)


def depth_to_pressure(depth_m):
    """深度（m） -> 绝对压（bar）"""
    return SURFACE_PRESSURE + depth_m / M_PER_BAR


def pressure_to_depth(pressure_bar):
    """绝对压（bar） -> 深度（m），ceiling 高于水面时钳到 0"""
    return max(0.0, (pressure_bar - SURFACE_PRESSURE) * M_PER_BAR)


class DecoModel(object):
    """ZHL-16C 组织模型。

    tissues[i] = (pn2, phe)，单位 bar；均为**惰性气体分压**。
    """

    def __init__(self, gas=AIR):
        self.gas = gas
        p_inspired = SURFACE_PRESSURE - WATER_VAPOR_PRESSURE
        n2_0 = p_inspired * gas.n2
        he_0 = p_inspired * gas.he
        self.tissues = [(n2_0, he_0)] * NCOMP

    # -- 核心：Schreiner ---------------------------------------------------
    def add_segment(self, depth0, depth1, time_min, gas=None):
        """一段线性深度变化。

        Schreiner:  P = Palv + (P0 - Palv) * e^(-kt) + R * (t - (1 - e^(-kt))/k)
        其中 P_alv = f_gas * (p_amb0 - p_wv) 是**段起始**的吸入惰性气体分压，
        R = f_gas * (dp_amb/dt) 是惰性气体分压变化率（水蒸气是常数，不参与微分）。

        坑（v0.1.3 审查）：Palv 必须用 p0，不能用 p1；用 p1 再叠加 R 项等于
        把深度变化算了两遍。恒深度段 p0==p1 看不出来。
        """
        if time_min <= 0:
            return
        gas = gas or self.gas
        p0 = depth_to_pressure(depth0)
        p1 = depth_to_pressure(depth1)
        rate = (p1 - p0) / time_min  # bar/min
        p_alv_base = p0 - WATER_VAPOR_PRESSURE

        fn2, fhe = gas.n2, gas.he
        new_tissues = []
        for i in range(NCOMP):
            pn2, phe = self.tissues[i]
            palv_n2 = p_alv_base * fn2
            k_n2 = math.log(2.0) / N2_HALF_TIMES[i]
            e_n2 = math.exp(-k_n2 * time_min)
            pn2_new = palv_n2 + (pn2 - palv_n2) * e_n2 \
                + fn2 * rate * (time_min - (1.0 - e_n2) / k_n2)

            palv_he = p_alv_base * fhe
            k_he = math.log(2.0) / HE_HALF_TIMES[i]
            e_he = math.exp(-k_he * time_min)
            phe_new = palv_he + (phe - palv_he) * e_he \
                + fhe * rate * (time_min - (1.0 - e_he) / k_he)
            new_tissues.append((pn2_new, phe_new))
        self.tissues = new_tissues

    def add_surface_interval(self, minutes):
        """水面间隔：在水面呼吸当前气体排气。"""
        self.add_segment(0.0, 0.0, minutes)

    # -- 输出 --------------------------------------------------------------
    def tissue_pressure(self, i):
        pn2, phe = self.tissues[i]
        return pn2 + phe

    def _ab(self, i):
        """第 i 组织的 A/B 系数，按组织内 N2/He 负荷比例**线性插值**。

        Subsurface core/deco.cpp 的 tissue_tolerance_calc 同样是对 a 和 b
        本身加权（不是对 1/b 加权），别改成调和形式。
        """
        pn2, phe = self.tissues[i]
        tot = pn2 + phe
        if tot <= 0:
            return N2_A[i], N2_B[i]
        w_he = phe / tot
        a = N2_A[i] * (1 - w_he) + HE_A[i] * w_he
        b = N2_B[i] * (1 - w_he) + HE_B[i] * w_he
        return a, b

    def m_value(self, i, p_amb):
        """Bühlmann M 值线：允许的最大组织惰性气体分压。

        用**总环境压**（不扣水蒸气）——Subsurface 的水蒸气修正只作用于
        吸入分压与初始组织饱和，不进 M 值线公式。
        """
        a, b = self._ab(i)
        return a + p_amb / b

    def inert_fraction_total(self):
        """总惰性气体份额 f = fN2 + fHe。"""
        return self.gas.n2 + self.gas.he

    def inspired_inert_pressure(self, p_amb):
        """环境压对应的吸入惰性气体分压：f * (p_amb - p_wv)。

        组织压力是惰性气体分压，M 值线却用总环境压，两者量纲不同；
        GF/GF99 的基线必须用这个折算值，否则水面平衡态不落在 0%。
        """
        return self.inert_fraction_total() * (p_amb - WATER_VAPOR_PRESSURE)

    def gf99(self, p_amb):
        """GF99 通用式：给定环境压 p_amb 下的过饱和百分比。

            GF99 = (P_tissue - f*(p_amb - p_wv)) / (M - f*(p_amb - p_wv)) * 100

        > 100 表示已经越过 M 值线。取所有组织的最大值。

        语义完全取决于传入的 p_amb：
          - ``gf99(SURFACE_PRESSURE)`` = 标准定义的「直冲水面 GF99」，界面用它；
          - 传当前深度压 = 「当前深度下的过饱和度」，深处会恒为 0/负值。
        分析/图表一律用 :meth:`gf99_surface`。
        """
        p_inert = self.inspired_inert_pressure(p_amb)
        worst = -1e9
        for i in range(NCOMP):
            pt = self.tissue_pressure(i)
            denom = self.m_value(i, p_amb) - p_inert
            if denom <= 1e-9:
                continue
            g = (pt - p_inert) / denom * 100.0
            if g > worst:
                worst = g
        return worst

    def gf99_surface(self):
        """直冲水面 GF99（%）：此刻直接升到水面时的过饱和百分比。"""
        return self.gf99(SURFACE_PRESSURE)

    # -- 天花板 -----------------------------------------------------------
    def _ceiling_pressure_at_gf(self, gf):
        """常数 gf 下允许的最小环境压（bar），取所有组织最严格的那个。

        由 P_tissue = f*(p - p_wv) + gf*(a + p/b - f*(p - p_wv)) 解出：

            p = (P_tissue - gf*a + f*p_wv*(1-gf)) / (f*(1-gf) + gf/b)
        """
        f = self.inert_fraction_total()
        worst = -1e9
        for i in range(NCOMP):
            a, b = self._ab(i)
            denom = f * (1.0 - gf) + gf / b
            if denom <= 1e-9:
                continue
            num = self.tissue_pressure(i) - gf * a \
                + f * WATER_VAPOR_PRESSURE * (1.0 - gf)
            p_allowed = num / denom
            if p_allowed > worst:
                worst = p_allowed
        return worst

    def gf_low_anchor_pressure(self, gf_low):
        """当前时刻的 GF-low 第一减压站锚点压力（bar），不低于水面压。

        Subsurface 的 ``gf_low_pressure_this_dive`` 取的是整次潜水里这个值的
        **历史最大值**：锚点一旦定下就冻结，不随后续排气漂移。
        :meth:`analyze_profile` / Kotlin 的 series() 负责按剖面推进这个状态。
        """
        return max(SURFACE_PRESSURE, self._ceiling_pressure_at_gf(gf_low))

    def ceiling_gf(self, gf_low, gf_high, depth=None, anchor_pressure=None):
        """带 Gradient Factor 的减压天花板（米，>= 0）。

        Subsurface / deco-planner 的标准解法：在 (环境压 p, 允许组织张力 Y)
        平面上，以水面 ``(S, gf_high)`` 与冻结的第一减压站 ``(L, gf_low)``
        两点连成直线，再解析求 Y = P_tissue 与直线的交点：

            Y(p)   = f*(p-p_wv) + gf*(a + p/b - f*(p-p_wv))
            Y_S    = Y(S) with gf_high
            Y_L    = Y(L) with gf_low
            p_ceil = S + (P_tissue - Y_S) * (L - S) / (Y_L - Y_S)

        取所有组织中最大的 p_ceil 换算成深度。常数 gf（gf_low == gf_high）时
        Y 本身就是线性的，这个解与 :meth:`_ceiling_pressure_at_gf` 完全等价。

        ``depth`` 仅为兼容旧签名保留：GF ceiling 由组织状态决定，与当前深度无关。
        ``anchor_pressure`` 传整条潜水冻结下来的锚点；不传则用当前时刻值。

        Y_L <= Y_S（斜率 <= 0）时说明水面 gf_high 已经允许直接出水、不存在
        天花板，按 Subsurface/decoplanner 的做法过滤掉该组织。
        """
        S = SURFACE_PRESSURE
        anchor = self.gf_low_anchor_pressure(gf_low)
        if anchor_pressure is not None:
            anchor = max(anchor, anchor_pressure)
        if anchor <= S + 1e-9:
            return 0.0
        L = anchor

        f = self.inert_fraction_total()
        pwv = WATER_VAPOR_PRESSURE
        worst = S
        for i in range(NCOMP):
            a, b = self._ab(i)

            def tolerated(p, gf):
                inert = f * (p - pwv)
                return inert + gf * (a + p / b - inert)

            y_s = tolerated(S, gf_high)
            y_l = tolerated(L, gf_low)
            slope = (y_l - y_s) / (L - S)
            if slope <= 1e-12:
                continue
            p_allowed = S + (self.tissue_pressure(i) - y_s) / slope
            if p_allowed > worst:
                worst = p_allowed
        return pressure_to_depth(worst)

    def ceiling(self, depth=None):
        """纯 Bühlmann（等价于 GF 100/100）的减压天花板（米）。"""
        return self.ceiling_gf(1.0, 1.0, depth)

    def surface_ceiling(self, gf_high):
        """直接升到水面的天花板（m）：>0 表示已不能直接出水。

        NDL 用这个判据（对应水面 gf_high 约束），不用 ceiling_gf 的
        第一减压站锚点 —— 后者问的是「该在哪停」，不是「能不能出水」。
        """
        return pressure_to_depth(self._ceiling_pressure_at_gf(gf_high))

    def ndl_minutes(self, gf_high, depth, cap_min=99):
        """剩余免减压时间（分钟）：保持当前深度还能待多久仍可直接出水。

        二分搜索代替逐分钟仿真（deco-planner planner.py 的做法）。
        恒深度段的 Schreiner 是闭式的，任意时刻一步就能算到，因此二分
        是单调判据上的精确搜索，40 次迭代精度远高于 1 分钟步进。
        """
        if self.surface_ceiling(gf_high) > 0.01:
            return 0.0

        def violates(minutes):
            m = self.copy()
            m.add_segment(depth, depth, minutes)
            return m.surface_ceiling(gf_high) > 0.01

        if not violates(cap_min):
            return float(cap_min)
        lo, hi = 0.0, float(cap_min)
        for _ in range(40):
            mid = (lo + hi) / 2.0
            if violates(mid):
                hi = mid
            else:
                lo = mid
        return lo

    def copy(self):
        m = DecoModel(self.gas)
        m.tissues = list(self.tissues)
        return m


# ---------------------------------------------------------------------------
# 剖面分析：把一条潜水的采样点喂进去，逐点算风险指标
# ---------------------------------------------------------------------------

ALGORITHMS = [
    {"id": "zhl16c", "name": "ZHL-16C 原始", "gf_low": 1.00, "gf_high": 1.00,
     "desc": "Bühlmann 原始 M 值线，不施加任何梯度余量。最激进。"},
    {"id": "gf80", "name": "GF 80/80", "gf_low": 0.80, "gf_high": 0.80,
     "desc": "轻度保守，接近多数电脑表的默认策略。"},
    {"id": "gf50_70", "name": "GF 50/70", "gf_low": 0.50, "gf_high": 0.70,
     "desc": "常见休闲潜水保守设置。"},
    {"id": "gf30_85", "name": "GF 30/85", "gf_low": 0.30, "gf_high": 0.85,
     "desc": "技术潜水常用，深处留大余量、浅处放宽。"},
    {"id": "gf20_60", "name": "GF 20/60", "gf_low": 0.20, "gf_high": 0.60,
     "desc": "极保守，用于压力测试：看看最坏情况下有多少潜次会冒出减压义务。"},
]


def analyze_profile(samples, algorithm, gas=AIR, max_points=400):
    """对一条潜水剖面跑一遍完整分析。

    samples: [(t_sec, depth_m), ...]，时间必须递增
    algorithm: ALGORITHMS 里的一项
    返回 dict：
      gf99_max        峰值 GF99（相对纯 M 值线，直冲水面口径）
      ceiling_max     该算法下的最大减压天花板深度（m）
      deco_sec        存在减压义务（ceiling > 0）的累计秒数
      deco_duty       是否有减压义务
      ascent_max      最大上升速率（m/min）
      ascent_violations 上升速率超标次数
      gf99_series     [[t, gf99], ...]  降采样后
      ceiling_series  [[t, ceiling_m], ...]
    """
    gf_low = algorithm["gf_low"]
    gf_high = algorithm["gf_high"]

    if not samples:
        return {
            "gf99_max": 0.0, "ceiling_max": 0.0, "deco_sec": 0,
            "deco_duty": False, "ascent_max": 0.0, "ascent_violations": 0,
            "gf99_series": [], "ceiling_series": [],
        }

    model = DecoModel(gas)
    gf99_max = -1e9
    ceiling_max = 0.0
    deco_sec = 0
    ascent_max = 0.0
    ascent_violations = 0
    anchor = SURFACE_PRESSURE   # 冻结的第一减压站锚点，只增不减

    gf99_series = []
    ceiling_series = []

    prev_t, prev_d = samples[0][0], samples[0][1]
    model.add_surface_interval(0.0)

    for idx in range(1, len(samples)):
        t, d = samples[idx]
        dt_min = (t - prev_t) / 60.0
        if dt_min <= 0:
            continue
        model.add_segment(prev_d, d, dt_min, gas)

        g99 = model.gf99_surface()
        if g99 > gf99_max:
            gf99_max = g99
        anchor = max(anchor, model.gf_low_anchor_pressure(gf_low))
        c = model.ceiling_gf(gf_low, gf_high, d, anchor)
        if c > ceiling_max:
            ceiling_max = c
        if c > 0.0:
            deco_sec += (t - prev_t)

        if d < prev_d:
            rate = (prev_d - d) / dt_min
            if rate > ascent_max:
                ascent_max = rate
            if rate > ASCENT_LIMIT:
                ascent_violations += 1

        gf99_series.append([t, round(g99, 1)])
        ceiling_series.append([t, round(c, 2)])

        prev_t, prev_d = t, d

    # 出水后补 30 秒到水面：日志经常在水面截断，最后一段上升没记全。
    # 这一步的 GF99 要计入峰值（v0.1.3 Kotlin 已修，这里同步）。
    if prev_d > 0:
        model.add_segment(prev_d, 0.0, 0.5, gas)
        gf99_max = max(gf99_max, model.gf99_surface())

    return {
        "gf99_max": round(max(0.0, gf99_max), 1),
        "ceiling_max": round(ceiling_max, 2),
        "deco_sec": int(deco_sec),
        "deco_duty": ceiling_max > 0.0,
        "ascent_max": round(ascent_max, 1),
        "ascent_violations": ascent_violations,
        "gf99_series": _decimate(gf99_series, max_points),
        "ceiling_series": _decimate(ceiling_series, max_points),
    }


# 上升速率告警阈值（m/min）。休闲潜水通用 18 m/min，保守 10 m/min。
ASCENT_LIMIT = 18.0


def _decimate(series, n):
    """等间隔抽稀，但保留首尾（避免抽稀把尖峰抹掉）。"""
    if len(series) <= n:
        return series
    step = len(series) / float(n)
    out = [series[int(i * step)] for i in range(n)]
    out.append(series[-1])
    return out


# ---------------------------------------------------------------------------
# 自测：黄金值输出（Kotlin DecoTest.kt 的数字必须与此一致）
# ---------------------------------------------------------------------------

def _selftest():
    print("=" * 64)
    print("deco.py 自检  —  ZHL-16C / 水蒸气扣除 / GF 解析解（v0.1.4 口径）")
    print("=" * 64)

    # 1) 水面平衡：GF99、天花板、水面约束都必须是 0
    m = DecoModel(AIR)
    m.add_surface_interval(600)
    print("水面静置 10h：GF99 = %.4f  ceiling = %.4f m  surfaceCeiling(0.7) = %.4f m"
          % (m.gf99_surface(), m.ceiling(), m.surface_ceiling(0.7)))

    # 2) 30m/20min 黄金值
    m = DecoModel(AIR)
    m.add_segment(0, 30, 1.0)
    m.add_segment(30, 30, 20.0)
    print("\n30m/20min 后：")
    print("  ceiling(ZHL-16C) = %.4f m" % m.ceiling())
    print("  ceiling(GF30/85) = %.4f m" % m.ceiling_gf(0.30, 0.85))
    print("  ceiling(GF50/70) = %.4f m" % m.ceiling_gf(0.50, 0.70))
    print("  GF99(水面口径)   = %.4f %%" % m.gf99_surface())

    m2 = m.copy()
    m2.add_segment(30, 10, 2.0)
    print("\n上升到 10m 后：")
    print("  ceiling(GF30/85) = %.4f m" % m2.ceiling_gf(0.30, 0.85))
    print("  GF99(水面口径)   = %.4f %%" % m2.gf99_surface())

    # 3) 瞬浮
    m3 = DecoModel(AIR)
    m3.add_segment(0, 30, 1.0)
    m3.add_segment(30, 30, 40.0)
    m3.add_segment(30, 0, 0.1)
    print("\n30m/40min 后 0.1 分钟冲到水面：GF99 = %.4f %%" % m3.gf99_surface())

    # 4) Schreiner 第一组织（用于 Kotlin 与数值积分对拍）
    print("\nSchreiner 第一组织 N2 分压（bar）：")
    for d0, d1, t in [(0.0, 30.0, 1.0), (0.0, 30.0, 100.0), (30.0, 10.0, 2.0)]:
        mm = DecoModel(AIR)
        mm.add_segment(d0, d1, t)
        print("  %5.1f -> %5.1f m / %6.1f min : %.6f" % (d0, d1, t, mm.tissue_pressure(0)))

    # 5) GF 档位单调性 + NDL
    print("\n30m/20min 各档 ceiling 与 18m NDL：")
    for gfl, gfh in [(1.0, 1.0), (0.8, 0.8), (0.5, 0.7), (0.3, 0.85), (0.2, 0.6)]:
        print("  GF %3d/%3d : ceiling = %6.2f m   NDL(18m) = %5.1f min"
              % (gfl * 100, gfh * 100, m.ceiling_gf(gfl, gfh),
                 DecoModel(AIR).ndl_minutes(gfh, 18.0)))

    # 6) 常数 GF 下解析解与旧闭式解必须一致（割线在 Y 线性时精确）
    print("\n常数 GF 解析解自洽检查：")
    for gf in [0.3, 0.5, 0.7, 0.85, 1.0]:
        a = m.ceiling_gf(gf, gf)
        b = m.surface_ceiling(gf)
        print("  gf=%.2f : ceiling_gf=%.4f m  surface_ceiling=%.4f m  差=%.2e"
              % (gf, a, b, abs(a - b)))

    # 7) 底停越久 GF99 越高
    prev, ok = -1e9, True
    for bottom in [5, 10, 20, 40, 120]:
        mm = DecoModel(AIR)
        mm.add_segment(0, 30, 1.0)
        mm.add_segment(30, 30, float(bottom))
        g = mm.gf99_surface()
        if g < prev:
            ok = False
        prev = g
        print("  30m 底停 %3d min -> GF99 = %6.1f  ceiling = %5.2f m"
              % (bottom, g, mm.ceiling()))
    print("单调性检查：%s" % ("通过" if ok else "失败"))
    print("\n完成。")


if __name__ == "__main__":
    _selftest()
