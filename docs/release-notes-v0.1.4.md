# v0.1.4 发布说明

> 对应 `versionCode 5 / versionName 0.1.4`，tag `v0.1.4`，APK 仅 `arm64-v8a`。
> 本文即 GitHub Release 正文，可直接整段复制到 Release 页面。

这一版**主要动减压内核和文档**：不改同步、不改数据库结构。界面只有一处收尾
——把「潜水日志」标题栏那个重复的「+」导入按钮删掉，导入统一走「设备 → 导入日志」。
起因是拿自己写的另一套潜前计划器 `deco-planner` 与 Subsurface `core/deco.cpp`
逐项对拍，发现我们标着「ZHL-16C」的内核其实有三处口径没对齐。

## 改了什么

**1. 参数表换成真正的 ZHL-16C**

旧表混了三套东西：N₂ 首半时 `4.0`（16C 是 `5.0`）、第 5~16 格的 N₂ a 系数是
**ZHL-16B** 的 `0.6667 / 0.5600 / …`、He 半时是「N₂ 半时 ÷ 2.65」推出来的。
He 的 a/b 反而是对的。现在 16 组 N₂/He 半时与 a/b 全部换成公开的标准 16C 表
（Wikipedia / Subsurface / deco-planner 三方一致）。

常规休闲剖面差异很小，但短深潜和混合气会放大：
40 m / 5 min 快速潜的 GF99 差约 11 个百分点、天花板差约 2.4 m。

**2. 补上肺泡水蒸气压与物理口径**

- 吸入惰性气体分压与组织初值改为 `f * (p_amb − 0.0627 bar)`（Bühlmann 的 37°C 水蒸气值）。
  旧版不扣，等于把全程吸入分压高估约 6%。
- 水面压 `1.0 → 1.01325 bar`，深度换算 `10 m/bar → 9.9001 m/bar`（海水 1.03 kg/L）。
- 水蒸气**只**用于吸入分压与初始饱和，不进 M 值线公式 —— 与 Subsurface 一致。
- GF99 的基线同步改为 `f*(p_surf − p_wv)`，水面平衡仍精确为 0%，
  100% 仍精确对应 M 值线，所以图表和 100% 阈值线的含义没变。

**3. GF 天花板改为冻结锚点 + 解析解**

旧实现每个采样点都重算 gf_low 的第一减压站，再按 `当前深度 / 第一站深度`
插值出 gf —— 锚点会随排气漂移，而且让保守的 gf_low 在整条剖面上误生效。
现在按 Subsurface `tissue_tolerance_calc()` 的做法：第一减压站锚点取整条潜水的
历史最大值并冻结，在**压力空间**连出 GF 直线，再解析求组织张力与直线的交点。
常数 GF（如 80/80、100/100）时解析解与闭式解精确一致，有单元测试守着。

**4. NDL 改成二分搜索**

恒深度段的 Schreiner 是闭式的，任意分钟一步就能算到，所以 40 次二分即可取代
99 次逐分钟仿真。结果与逐分钟仿真一致，曲线开销从「277 点 × 99 次」降到毫秒级。

**5. 去掉日志页重复的导入入口**

「潜水日志」标题栏右侧原本有个 `+`，功能与「设备 → 导入日志 → 选择文件导入」
完全一样（共用同一个 `importLauncher`）。现在只保留设备页那一个入口；导入进度条、
解析入库链路都没变，只是少了一个重复按钮。

## 升级后数字会变 —— 这是预期的

口径三处同时变了，**v0.1.4 与 v0.1.3 的 GF99 / 最小安全深度 / 减压时长不能直接比较**。
在同一台机器的 253 条真实记录上（GF30/85）实测：

| 指标 | v0.1.3 | v0.1.4 |
| --- | --- | --- |
| 被判定「有减压义务」的潜次 | 100 条 | **16 条** |
| GF99 峰值 > 100% 的潜次 | 2 条 | 0 条 |
| `#183`（21.4 m / 51 min）GF99 | 101.3% | 97.9% |
| `#185`（22.6 m / 46 min）GF99 | 100.8% | 97.4% |
| 典型剖面 GF99 峰值 | — | 整体下移约 3~8 个百分点 |

**减压义务从 100 条降到 16 条不是"放宽了安全标准"**，是删掉了旧解法的虚假报警：
旧实现用 gf_low=0.30 的保守天花板去判「能不能直接出水」，而 gf_low 本来只该
作用于**第一减压站**；能不能出水要看水面的 gf_high=0.85。典型例子是 40 m / 5 min
的短深潜，旧版报 12.7 m 天花板，新版为 0 —— 组织张力根本没超过水面允许值。

GF99 峰值下移主要来自水蒸气扣除与参数表修正；这两条都是把"高估的负荷"改回真实值。

> 再强调一次：这些数字是**按假设参数事后重放**的结果，用途是同一版本内换 GF
> 做横向对比，不能用来判断任何一次潜水是否安全。

## 测试

- `python tools/deco.py` 自检：水面平衡 GF99=0、常数 GF 解析解与闭式解差 <1e-14、
  Schreiner 升降段与 20 万步数值积分差 <1e-10
- `bash android/at.sh unit`：**172 个测试全绿**（新增 6 条护栏：标准大气压/海水密度、
  水蒸气扣除、常数 GF 解析解自洽、GF ceiling 与当前深度无关、短深潜无虚假减压义务、
  NDL 二分与逐分钟仿真一致）
- `tools/deco.py`（Python 基准）与 `Deco.kt`（Kotlin）逐行对拍，黄金值全部重算
- 真机（Galaxy S23 / Android 16）覆盖安装验证：日志页 `+` 已消失、设备页
  「选择文件导入」可用、297 条 / 247 组数据完整保留，无崩溃

## 安装

| 项 | 说明 |
| --- | --- |
| 文件 | `CrestHelper-0.1.4-arm64-v8a.apk` |
| 大小 | 7,204,946 字节（约 6.9 MB） |
| MD5 | `235d158eb923cf45ab9bc48318d24bbc` |
| 签名 | 与 0.1.1~0.1.3 同一证书（SHA-256 `f97af9ba…e1`） |
| 系统 | Android 8.0+（minSdk 26，targetSdk 35） |
| 架构 | 仅 `arm64-v8a`，32 位设备装不上 |
| 实测 | 三星 Galaxy S23（SM-S9110，Android 16） |

- 已装 0.1.3 的可以直接覆盖升级，记录、标注和设置都保留
- 首次启动是**空库**：不内置示例数据，日志来自手表同步或 `.bin` 导入
- 若之前装过 `debug` 测试包，两者签名不同，需先卸载再装

## 已知限制

- 减压分析仍按**单一气体**计算：CR-4 的 v0 记录里只有气体索引、没有成分表，
  中途换气暂未计入
- 深度→压力用 1.03 kg/L 海水密度；手表内部换算可能用 10 m/bar 或 EN13319 的
  1.02 kg/L，两者约有 1% 差异
- GF 天花板用的是 Subsurface 的压力空间割线解析解（标准做法），不是不动点迭代解
- 水蒸气扣除已按 Subsurface 公式实现，但还没跑过逐点数值对拍
- 只支持 CR-4 系（同硬件贴牌：Deep Six Excursion / Genesis Centauri / Scorpena Alpha）的
  `deepsix_excursion` 记录格式；只在 Galaxy S23 上验证过
- 只做水肺模式，无账号、无云同步，数据全在本机 SQLite

## 致谢

- 记录格式与字段解析参考 [libdivecomputer](https://github.com/libdivecomputer/libdivecomputer)
- 减压口径对照参考 [Subsurface](https://github.com/subsurface/subsurface) `core/deco.cpp`，
  以及本人另一套潜前计划器的 Bühlmann 实现
- BLE 传输层是本项目自己实现的

代码 MIT，libdivecomputer LGPL-2.1。

---

**这是复盘工具，不是潜水计划工具。** 重算出来的 NDL、减压时长、最小安全深度都是
"按假设参数重放一遍记录"，不能替代电脑表的实时计算，也不能用来判断任何一次潜水是否安全。

## English

### What changed

Mainly the decompression kernel and docs — no sync or database changes. The one UI cleanup
removes a duplicate import button from the log screen (import now lives only under Device).
The trigger was a line-by-line comparison against my own dive planner (`deco-planner`) and Subsurface
`core/deco.cpp`, which showed three places where our "ZHL-16C" kernel did not match the label.

1. **Real ZHL-16C tissue table.** The old one mixed a `4.0 min` N₂ half-time (16C uses `5.0`),
   ZHL-16B N₂ a-coefficients (`0.6667 / 0.5600 / …`) and He half-times derived as
   "N₂ half-time ÷ 2.65". All 16 compartments now use the public 16C table. Small effect on
   recreational profiles, ~11 GF99 points / 2.4 m ceiling on a fast 40 m / 5 min bounce.
2. **Alveolar water vapour and physical constants.** Inspired inert pressure and initial
   tissue saturation now subtract `0.0627 bar`; surface pressure is `1.01325 bar` and
   depth conversion uses sea water `1.03 kg/L` (9.9001 m/bar). Water vapour is applied
   *only* to inspired pressure, not to the M-value line — same as Subsurface. The GF99
   baseline moved with it, so surface equilibrium is still exactly 0% and 100% still
   means the M-value line.
3. **Anchored analytic GF ceiling.** The old code recomputed the first-deco-stop anchor at
   every sample and interpolated GF by `current depth / first stop depth`, which let the
   conservative `gf_low` apply across the whole dive. Now the anchor is the historical
   maximum for the dive (frozen), and GF is a line in pressure space solved analytically,
   following Subsurface `tissue_tolerance_calc()`. With constant GF the analytic and
   closed-form solutions are identical (covered by a test).
4. **NDL via binary search** instead of 99 one-minute simulations; identical results.
5. **Removed the duplicate import entry.** The `+` in the log screen's top bar shared the
   same `importLauncher` as "Device → Import logs → Choose files"; only the Device entry
   remains. The progress indicator and import pipeline are unchanged.

### Numbers will change — that is expected

All three calibration items changed at once, so **GF99 / min safe depth / deco time are not
comparable across v0.1.3 and v0.1.4**. On 253 real dives (GF30/85): dives flagged with a
deco obligation dropped from **100 to 16**, and GF99 peaks over 100% from 2 to 0.

That is **not a looser safety threshold** — it removes false positives. The old code used
the conservative `gf_low = 0.30` ceiling to decide whether surfacing was allowed, but
`gf_low` only applies to the *first deco stop*; surfacing is governed by `gf_high = 0.85`
at the surface. A 40 m / 5 min bounce used to report a 12.7 m ceiling and now reports 0.
The GF99 drop comes from the water-vapour fix and the corrected tissue table.

These are replayed estimates with assumed parameters, useful only for comparing GF settings
within the same version — never for judging whether a dive was safe.

### Verification

- `python tools/deco.py` self-check: surface equilibrium GF99 = 0; analytic vs closed-form
  constant-GF ceiling differs by <1e-14; Schreiner segments vs 200,000-step numerical
  integration differ by <1e-10
- `bash android/at.sh unit`: **172 tests, 0 failures** (6 new guard tests)
- `tools/deco.py` and `Deco.kt` kept in lockstep; all golden values recomputed
- On-device check (Galaxy S23 / Android 16): log screen `+` gone, Device import button
  still works, all 297 records / 247 groups intact, no crashes

### Install

| | |
| --- | --- |
| File | `CrestHelper-0.1.4-arm64-v8a.apk` (7,204,946 bytes, MD5 `235d158e…4bbc`) |
| Requires | Android 8.0+ (minSdk 26, targetSdk 35) |
| ABI | `arm64-v8a` only |
| Tested on | Galaxy S23 (SM-S9110, Android 16) |

- Upgrades over 0.1.3 in place; records, annotations and settings are kept
- Starts with an **empty logbook** — dives come from watch sync or `.bin` import
- If you previously installed the `debug` build, uninstall it first (different signature)
- Deco analysis still assumes a **single gas**; CR-4 v0 logs carry gas indices but no mix table
- Depth→pressure uses 1.03 kg/L, which may differ ~1% from the watch's own conversion

---

**This is a log review tool, not a dive planning tool.** Recomputed NDL, deco time and
min safe depth are replays with assumed parameters; they cannot replace your dive
computer's real-time calculation or be used to judge whether any dive was safe.
