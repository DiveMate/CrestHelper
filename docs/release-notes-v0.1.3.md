# v0.1.3 发布说明

> 对应 `versionCode 4 / versionName 0.1.3`，tag `v0.1.3`，APK 仅 `arm64-v8a`。
> 本文即 GitHub Release 正文，可直接整段复制到 Release 页面。

这一版**不改界面**，全部是"看不见但必须修"的问题：一次全盘代码审查（49 个 Kotlin 文件 +
native 层 + 构建脚本）之后逐条修出来的。重点是**减压分析的结论口径**和**同步的失败路径**。

## 最重要的一条：GF99 口径修正

GF99 的定义是「此刻**直冲水面**的过饱和度」，但之前算的是「当前深度下的过饱和度」。
后果不是显示不好看，而是**结论反了**：

- 深水段组织已经高度饱和，曲线却恒显示 0 / 负值
- 实测 253 条真实记录里，`#183`（21.4 m / 51 min）与 `#185`（22.6 m / 46 min）
  按定义口径分别是 **101.3% / 100.8%（已越过 M 值线）**，App 当时显示的是 **84.9% / 85.9%**
- 峰值最大被低估 26 个百分点

现在曲线、峰值、100% 阈值线、详情页说明全部统一到水面口径。
**升级后这两条记录的 GF99 会明显变大，那不是新出现的风险，是原来就该显示的数值。**

同时修掉了另一个同类问题：减压内核的 Schreiner 段积分把「段末环境压」当成肺泡压基准，
等于把深度变化算了两遍。这个 bug 对真实剖面的影响很小（减压时长最大差 30 秒、
天花板差 <0.01 m、是否进入减压 0 条反转），但公式本身是错的，已连同 Python 参考实现
和黄金值一起改正，并补了「升降段 vs 数值积分」的对拍用例（以前的黄金值全是恒底停，测不出来）。

## 隐私

- **关闭系统备份**（`allowBackup=false` + 备份/换机直传规则排除数据库与偏好）。
  潜水库、逐点剖面、手填潜点、手表 MAC 都不会再被 adb 备份 / 云备份 / 换机直传带走。

## 同步可靠性

- **取消现在真的能停**：以前取消异常被当成"这一条失败"吞掉，会继续跑完剩下的记录，
  最后还写库并记一条「同步完成」。现在取消立即中止，不写库、不记完成。
- **手表断线后不再假装已连接**：断线会释放 GATT 并把界面切到"未连接"，
  不再出现"显示已连接、一点同步就失败"。连接失败路径也不再泄漏 GATT 客户端。
- **增量同步必须验锚点**：编号没变但手表最后一条被改写时，以前会永久判「已是最新」、
  永远不自愈；现在锚点指纹对不上就降级全量。
- 通知订阅（CCCD）失败不再当成功；短响应不再越界崩溃；写手表时间失败会把未落盘的写入丢弃，
  不让设备停在"日期改了、时间没改"的半写入状态。
- 前台服务启动失败不再崩溃，改成提示「后台保障未生效，请保持屏幕常亮」；结束后通知可点掉。
- 整轮同步加了 30 分钟上限，卡死时主动收尾而不是无限转圈。

## 其它

- **设置里的 GF 小数**：以前 `30.5` 能存进去但显示成 `31`，接着输入就变成非法值；现在按一位小数显示
- **深色模式**：跟随系统，不再恒为白底
- 旋转屏幕不再丢当前页签、筛选、多选，也不会重放一遍「同步完成」提示
- 同步时手动填的过滤标记不会被覆盖；统计/列表口径不变
- 修掉构建脚本在 Windows 下 16 KB 页对齐检查的误报（之前每次 build 都会报 `[FAIL]`）
- 文档订正：组织半时表此前误抄 ZHL-16B、统计页功能描述与实际不符、0.1.2 说明里「合并记录可直接拆开」不准确

## 安装

| 项 | 说明 |
| --- | --- |
| 文件 | `CrestHelper-0.1.3-arm64-v8a.apk`（6.9 MB） |
| 系统 | Android 8.0+（minSdk 26，targetSdk 35） |
| 架构 | 仅 `arm64-v8a`，32 位设备装不上 |
| 实测 | 三星 Galaxy S23（SM-S9110，Android 16） |

- 已装 0.1.2 的可以直接覆盖升级，记录和设置都保留
- 首次启动是**空库**：不内置示例数据，日志来自手表同步或 `.bin` 导入
- 若之前装过 `debug` 测试包，两者签名不同，需先卸载再装

## 已知限制

- 只支持 CR-4 系（同硬件贴牌：Deep Six Excursion / Genesis Centauri / Scorpena Alpha）的 `deepsix_excursion` 记录格式
- 只在 Galaxy S23 上验证过，其他机型未测，问题请附机型 + Android 版本
- 只做水肺模式，自由潜懒得搞了
- 无账号、无云同步，数据全在本机 SQLite
- 老记录不带气体信息，气体种类依赖手动标注
- 减压分析按**单一气体**计算：中途换气暂未计入（CR-4 的 v0 记录本身也不带气体表）

## 致谢

记录格式与字段解析参考 [libdivecomputer](https://github.com/libdivecomputer/libdivecomputer)；BLE 传输层是本项目自己实现的。

代码 MIT，libdivecomputer LGPL-2.1。

---

**这是复盘工具，不是潜水计划工具。** 重算出来的 NDL、减压时长、最小安全深度都是"按假设参数重放一遍记录"，
不能替代电脑表的实时计算，也不能用来判断任何一次潜水是否安全。

## English

### What changed

No UI changes — this release is entirely fixes from a full code review (49 Kotlin files, the native layer
and the build scripts). Two themes: **what the deco numbers actually mean**, and **what happens on the failure paths**.

**GF99 now uses the correct reference**

GF99 means "saturation if you surfaced *right now*", but it was computed against *current depth*.
That didn't just look wrong, it inverted the conclusion: the deep section always read 0 / negative, and two
real dives (`#183`, `#185`) that had crossed the M-value line (101.3% / 100.8%) were displayed as 84.9% / 85.9%.
Curves, peaks, the 100% threshold and the on-screen explanation are now all on the surfacing reference.
**Those records will show visibly higher GF99 after upgrading — that is the number that should have been there.**

Also fixed: the Schreiner segment integration used the segment's *end* pressure as the alveolar baseline
(the depth change was effectively counted twice). Impact on real profiles is small (≤30 s deco time,
<0.01 m ceiling, zero flips in deco obligation), but the formula was wrong; the Python reference
implementation and golden values were corrected together, and a "descending/ascending segment vs numerical
integration" test was added (the old golden values were all constant-depth, which cannot catch this).

**Privacy**

- Android backup is disabled (`allowBackup=false` plus extraction rules excluding the database and prefs).
  The dive database, per-sample profiles, hand-entered sites and the watch MAC can no longer be pulled out
  via adb backup, cloud backup or device-to-device transfer.

**Sync reliability**

- Cancelling actually stops: previously the cancellation was swallowed as "this one failed", the loop kept
  going, and the run still wrote to the database and logged a "completed" entry.
- A watch disconnect no longer leaves the UI showing "connected"; GATT resources are released on every
  failure path instead of leaking client interfaces.
- Incremental sync now verifies the anchor fingerprint; a rewritten last record can no longer be
  permanently reported as "up to date".
- CCCD subscription failures are no longer treated as success, short responses no longer crash, and a
  failed clock write discards the uncommitted values instead of leaving the watch half-written.
- A failed foreground-service start no longer crashes the app; the sync continues and the UI says so.
- The whole run is capped at 30 minutes.

**Other**

- GF decimals: `30.5` used to be stored but displayed as `31` (and the next keystroke made it invalid).
- Dark mode follows the system.
- Rotation no longer loses the tab/filters/multi-select or replays the "sync finished" snackbar.
- Filter marks survive a re-sync; statistics keep the same definitions.
- Fixed a false `[FAIL]` in the Windows 16 KB page-alignment check.
- Docs: the tissue half-time table was copied from ZHL-16B, the statistics description didn't match the app,
  and the 0.1.2 notes wrongly said any merged dive could be split.

### Install

| | |
| --- | --- |
| File | `CrestHelper-0.1.3-arm64-v8a.apk` (6.9 MB) |
| Requires | Android 8.0+ (minSdk 26, targetSdk 35) |
| ABI | `arm64-v8a` only |
| Tested on | Galaxy S23 (SM-S9110, Android 16) |

- Upgrades over 0.1.2 in place; records and settings are kept
- Starts with an **empty logbook** — dives come from watch sync or `.bin` import
- If you previously installed the `debug` build, uninstall it first (different signature)

### Known limitations

- CR-4 family only (`deepsix_excursion` format; rebadges: Deep Six Excursion / Genesis Centauri / Scorpena Alpha)
- Verified on the Galaxy S23 only — please include model + Android version in reports
- SCUBA mode only; freediving isn't worth the bother
- No accounts, no cloud sync — everything stays in a local SQLite database
- Older records carry no gas information, so gas type depends on manual annotation
- Deco analysis assumes a **single gas**; gas switches mid-dive are not modelled (CR-4 v0 records carry no gas table)

### Credits

Record format and field parsing follow [libdivecomputer](https://github.com/libdivecomputer/libdivecomputer); the BLE transport layer is implemented here.

Code MIT, libdivecomputer LGPL-2.1.

---

**A log review tool, not a dive planner.** Recomputed NDL, deco times and safe depths replay a recorded
profile with assumed parameters — they cannot replace your computer's real-time computation or serve as a dive plan.
