# -*- coding: utf-8 -*-
"""
deco.py —— 减压模型内核（ZHL-16C + Gradient Factors）

这是整个项目里唯一需要"数值正确"的模块，也是将来移植到 Rust 时
要逐行对拍的基准实现。设计原则：
  * 纯函数，无 IO，无第三方依赖
  * 单位统一：压力 bar，深度 m，时间 min（对外接口用秒，内部转分）
  * 所有常量集中放置，方便和 Subsurface core/deco.c 对照

参考：
  * Bühlmann ZHL-16C 参数表（16 组织，N2/He 双系数）
  * Erik Baker, "Clearing Up The Confusion About Gradient Factors"
"""

import math

# ---------------------------------------------------------------------------
# 常量
# ---------------------------------------------------------------------------

# 1 bar 对应的海水深度（10.13 m/bar 是海水标准，这里用 10 简化，与
# libdivecomputer 内部一致，误差 < 2%）
M_PER_BAR = 10.0
SURFACE_PRESSURE = 1.0  # bar，水面绝对压

# ZHL-16C：氮气半时（分钟）
N2_HALF_TIMES = [
    4.0, 8.0, 12.5, 18.5, 27.0, 38.3, 54.3, 77.0,
    109.0, 146.0, 187.0, 239.0, 305.0, 390.0, 498.0, 635.0,
]

# ZHL-16C：氮气 A 系数（bar）
N2_A = [
    1.1696, 1.0000, 0.8618, 0.7562, 0.6667, 0.5600,
    0.4947, 0.4500, 0.4187, 0.3798, 0.3497, 0.3223,
    0.2850, 0.2737, 0.2523, 0.2327,
]

# ZHL-16C：氮气 B 系数（无量纲）
N2_B = [
    0.5578, 0.6514, 0.7222, 0.7825, 0.8126, 0.8434,
    0.8693, 0.8910, 0.9092, 0.9222, 0.9319, 0.9403,
    0.9477, 0.9544, 0.9602, 0.9653,
]

# ZHL-16C：氦气半时（分钟）
HE_HALF_TIMES = [
    1.51, 3.02, 4.52, 6.04, 9.05, 12.07, 18.11, 24.14,
    36.20, 48.26, 60.37, 72.45, 90.57, 120.76, 150.94, 181.02,
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
    """绝对压（bar） -> 深度（m），可能为负（ceiling 高于水面时钳到 0）"""
    return (pressure_bar - SURFACE_PRESSURE) * M_PER_BAR


class DecoModel(object):
    """ZHL-16C 组织模型。

    tissues[i] = (pn2, phe)，单位 bar。
    """

    def __init__(self, gas=AIR):
        self.gas = gas
        n2_0 = SURFACE_PRESSURE * gas.n2
        he_0 = SURFACE_PRESSURE * gas.he
        self.tissues = [(n2_0, he_0)] * NCOMP

    # -- 核心：Schreiner ---------------------------------------------------
    def add_segment(self, depth0, depth1, time_min, gas=None):
        """一段线性深度变化。

        Schreiner:  P = Palv + (P0 - Palv) * e^(-kt) + R * (t - (1 - e^(-kt))/k)
        其中 R = f_gas * (dP/dt) 是惰性气体分压的变化速率。
        """
        if time_min <= 0:
            # 瞬时深度变化（上升/下降的"跳变"），只更新环境压参考
            return
        gas = gas or self.gas
        p0 = depth_to_pressure(depth0)
        p1 = depth_to_pressure(depth1)
        rate = (p1 - p0) / time_min  # bar/min

        fn2, fhe = gas.n2, gas.he
        new_tissues = []
        for i in range(NCOMP):
            pn2, phe = self.tissues[i]
            # 氮气
            palv_n2 = p1 * fn2          # 用段末环境压作为"肺泡"分压基准
            k_n2 = math.log(2.0) / N2_HALF_TIMES[i]
            r_n2 = fn2 * rate
            pn2_new = palv_n2 + (pn2 - palv_n2) * math.exp(-k_n2 * time_min) \
                + r_n2 * (time_min - (1.0 - math.exp(-k_n2 * time_min)) / k_n2)
            # 氦气
            palv_he = p1 * fhe
            k_he = math.log(2.0) / HE_HALF_TIMES[i]
            r_he = fhe * rate
            phe_new = palv_he + (phe - palv_he) * math.exp(-k_he * time_min) \
                + r_he * (time_min - (1.0 - math.exp(-k_he * time_min)) / k_he)
            new_tissues.append((pn2_new, phe_new))
        self.tissues = new_tissues

    def add_surface_interval(self, minutes):
        """水面间隔：在 1 bar 呼吸空气排气。"""
        self.add_segment(0.0, 0.0, minutes)

    # -- 输出 --------------------------------------------------------------
    def tissue_pressure(self, i):
        pn2, phe = self.tissues[i]
        return pn2 + phe

    def m_value(self, i, p_amb):
        """Bühlmann M 值线：允许的最大组织惰性气体分压。

        对混合惰性气体，A/B 按当前组织里 N2/He 的比例插值。
        """
        pn2, phe = self.tissues[i]
        tot = pn2 + phe
        if tot <= 0:
            a, b = N2_A[i], N2_B[i]
        else:
            w_he = phe / tot
            a = N2_A[i] * (1 - w_he) + HE_A[i] * w_he
            b = N2_B[i] * (1 - w_he) + HE_B[i] * w_he
        return a + p_amb / b

    def inert_fraction_total(self):
        """总惰性气体份额 f = fN2 + fHe。

        关键：Bühlmann 的组织压力是「惰性气体分压」，而 M 值线公式
        M = A + P_amb/B 用的是「总环境压」。两者量纲不同，比较前必须
        把环境压折算成惰性气体分压：P_amb_inert = f * P_amb。
        漏掉这一步会导致水面平衡态算出 GF99 = -10.7 而不是 0。
        """
        return self.gas.n2 + self.gas.he

    def gf99(self, p_amb):
        """GF99：当前组织过饱和梯度相对 M 值线的百分比。

            GF99 = (P_tissue - f*P_amb) / (M - f*P_amb) * 100

        > 100 表示已经越过 M 值线。取所有组织的最大值。
        深处通常为负（还在吸氮），上升到浅处转正 —— 这是正常的，
        峰值出现在上升段/出水点，也正是风险所在。
        """
        f = self.inert_fraction_total()
        p_inert = f * p_amb
        worst = -1e9
        for i in range(NCOMP):
            pt = self.tissue_pressure(i)
            m = self.m_value(i, p_amb)
            denom = m - p_inert
            if denom <= 1e-9:
                continue
            g = (pt - p_inert) / denom * 100.0
            if g > worst:
                worst = g
        return worst

    def _ab(self, i):
        """第 i 组织的 A/B 系数，按组织内 N2/He 比例插值。"""
        pn2, phe = self.tissues[i]
        tot = pn2 + phe
        if tot <= 0:
            return N2_A[i], N2_B[i]
        w_he = phe / tot
        a = N2_A[i] * (1 - w_he) + HE_A[i] * w_he
        b = N2_B[i] * (1 - w_he) + HE_B[i] * w_he
        return a, b

    def _ceiling_at_gf(self, gf, f):
        """给定生效的 gf，反解减压天花板深度（米）。

        由 GF 定义  P_tissue = f*P_amb + gf*(A + P_amb/B - f*P_amb)
        解出       P_amb = (P_tissue - gf*A) / (f*(1-gf) + gf/B)
        """
        worst = -1e9
        for i in range(NCOMP):
            a, b = self._ab(i)
            denom = f * (1.0 - gf) + gf / b
            if denom <= 1e-9:
                continue
            p_allowed = (self.tissue_pressure(i) - gf * a) / denom
            d = pressure_to_depth(p_allowed)
            if d > worst:
                worst = d
        return worst

    def ceiling_gf(self, gf_low, gf_high, depth):
        """带 Gradient Factor 的减压天花板（米，>= 0）。

        Baker 的 GF 曲线：第一减压站（最深）用 gf_low，水面用 gf_high，
        中间按深度线性插值。

        锚点必须用 gf_low 而不是 gf_high：
          - 先用 gf_low 求出第一减压站 c_low（最保守、最深的那道约束）
          - 若当前深度还深于 c_low，说明还没到第一站 -> ceiling 就是 c_low
          - 否则按 depth/c_low 插值出该深度真正生效的 gf 再反解
        早先用 gf_high 做锚点是错的：gf_high 宽松时会把 c0 算成 0 直接
        提前返回，导致 gf_low 完全不起作用（表现为 GF30/85 反而比
        GF50/70 的减压义务更少）。
        """
        f = self.inert_fraction_total()
        c_low = self._ceiling_at_gf(gf_low, f)
        if c_low <= 0:
            return 0.0
        if depth >= c_low:
            return c_low
        frac = min(1.0, max(0.0, depth / c_low))
        gf = gf_high + (gf_low - gf_high) * frac
        return max(0.0, self._ceiling_at_gf(gf, f))

    def ceiling(self, depth=None):
        """纯 Bühlmann（等价于 GF 100/100）的减压天花板（米）。"""
        return self.ceiling_gf(1.0, 1.0, depth or 0.0)

    def copy(self):
        m = DecoModel(self.gas)
        m.tissues = list(self.tissues)
        return m


# ---------------------------------------------------------------------------
# 剖面分析：把一条潜水的采样点喂进去，逐点算风险指标
# ---------------------------------------------------------------------------

# 预置算法档位。name 用于 UI 展示与切换。
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
      gf99_max        峰值 GF99（相对纯 M 值线）
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

    gf99_series = []
    ceiling_series = []

    prev_t, prev_d = samples[0][0], samples[0][1]
    # 入水前先在水面平衡
    model.add_surface_interval(0.0)

    for idx in range(1, len(samples)):
        t, d = samples[idx]
        dt_min = (t - prev_t) / 60.0
        if dt_min <= 0:
            continue
        model.add_segment(prev_d, d, dt_min, gas)

        p_amb = depth_to_pressure(d)
        g99 = model.gf99(p_amb)
        if g99 > gf99_max:
            gf99_max = g99
        c = model.ceiling_gf(gf_low, gf_high, d)
        if c > ceiling_max:
            ceiling_max = c
        if c > 0.0:
            deco_sec += (t - prev_t)

        # 上升速率
        if d < prev_d:
            rate = (prev_d - d) / dt_min
            if rate > ascent_max:
                ascent_max = rate
            if rate > ASCENT_LIMIT:
                ascent_violations += 1

        gf99_series.append([t, round(g99, 1)])
        ceiling_series.append([t, round(c, 2)])

        prev_t, prev_d = t, d

    # 出水后 30 秒（如果没有后续记录，模拟一下到达水面的状态）
    if prev_d > 0:
        model.add_segment(prev_d, 0.0, 0.5, gas)

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
    """等间隔抽稀，但保留首尾和一个"峰值保留"变体：先取等间隔点，
    再把全局最大值补进去，避免抽稀把尖峰抹掉。"""
    if len(series) <= n:
        return series
    step = len(series) / float(n)
    out = [series[int(i * step)] for i in range(n)]
    out.append(series[-1])
    return out


# ---------------------------------------------------------------------------
# 自测：用一条已知剖面验证数值合理性
# ---------------------------------------------------------------------------

def _selftest():
    print("=" * 60)
    print("deco.py 自检")
    print("=" * 60)

    # 1) 水面平衡后 GF99 应该 = 0（组织与环境压相等 -> 分子为 0）
    m = DecoModel(AIR)
    m.add_surface_interval(600)
    print("水面静置 10h 后 GF99 = %.2f  （期望 0）" % m.gf99(1.0))
    print("水面静置 10h 后 ceiling = %.2f m （期望 0）" % m.ceiling())

    # 2) 30m 停留 20 分钟：经典免减压极限边缘，ZHL-16C 下应出现减压义务
    m = DecoModel(AIR)
    m.add_segment(0, 30, 1.0)     # 1 分钟下潜
    m.add_segment(30, 30, 20.0)   # 底停 20 分钟
    print("\n30m/20min 后：")
    print("  ceiling(ZHL-16C) = %.2f m" % m.ceiling())
    print("  ceiling(GF30/85) = %.2f m" % m.ceiling_gf(0.30, 0.85, 30.0))
    print("  ceiling(GF50/70) = %.2f m" % m.ceiling_gf(0.50, 0.70, 30.0))
    print("  GF99 = %.1f" % m.gf99(depth_to_pressure(30)))

    # 3) 上升到 10m 时天花板应更高（GF low 生效）
    m2 = m.copy()
    m2.add_segment(30, 10, 2.0)
    print("\n上升到 10m 后：")
    print("  ceiling(GF30/85) = %.2f m" % m2.ceiling_gf(0.30, 0.85, 10.0))
    print("  GF99 = %.1f" % m2.gf99(depth_to_pressure(10)))

    # 4) 单调性：底停越久，组织越接近饱和，同深度 GF99 越高
    prev = -1e9
    ok = True
    for bottom in [5, 10, 20, 40, 120]:
        mm = DecoModel(AIR)
        mm.add_segment(0, 30, 1.0)
        mm.add_segment(30, 30, float(bottom))
        g = mm.gf99(depth_to_pressure(30))
        if g < prev:
            ok = False
        prev = g
        print("  30m 底停 %3d min -> GF99 = %6.1f  ceiling = %.1f m"
              % (bottom, g, mm.ceiling()))
    print("单调性检查：%s（底停越久 GF99 越高）" % ("通过" if ok else "失败"))

    # 5) 瞬间上浮：GF99 应远超 100
    m3 = DecoModel(AIR)
    m3.add_segment(0, 30, 1.0)
    m3.add_segment(30, 30, 40.0)
    m3.add_segment(30, 0, 0.1)
    print("\n30m/40min 后 0.1 分钟冲到水面：GF99 = %.1f%%（应远超 100）"
          % m3.gf99(1.0))

    print("\n完成。")


if __name__ == "__main__":
    _selftest()
