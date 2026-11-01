# v0.1.1 发布说明

> 对应 `versionCode 2 / versionName 0.1.1`，tag `v0.1.1`，APK 仅 `arm64-v8a`。
> 本文即 GitHub Release 正文，可直接整段复制到 Release 页面。

为 **Crest CR-4** 潜水电脑写的第三方 Android 客户端。原厂 App 每次同步都要全盘扫一遍、只能停在前台等；这个版本把「同步、复盘、记录整理」三件事重做了一遍。

## 这个版本能做什么

**同步**

- 轻量增量同步：只比对日志头部，取表里多出来的那几条，不做全表扫描
- 后台同步：进程级引擎 + 前台服务，切页面、切 App、锁屏都不中断，通知栏看进度
- 文件导入：SAF 多选 `.bin`，指纹去重后与已有数据合并

**复盘**

- 算法页：ZHL-16C + 可调梯度因子（默认 30/85），随时改
- 按真实剖面重算 NDL 剩余、最小安全深度、GF99 曲线
- 详情页给出剖面与水温曲线、最大/平均深度、温度极值，支持标注气体、潜点、能见度、装备与地形

**记录整理**

- 碎片合并：中途临时升水又下水，按水面间隔把中断的几次记录贯穿成一次潜水
- 垃圾过滤：按深度/时长阈值筛掉误触发的记录
- 回收站：删除只打标记，原始记录与采样点都还在，可单条 / 多选 / 全部恢复

## 安装

| 项 | 说明 |
| --- | --- |
| 文件 | `CrestHelper-0.1.1-arm64-v8a.apk`（6.9 MB） |
| 系统 | Android 8.0+（minSdk 26，targetSdk 35） |
| 架构 | 仅 `arm64-v8a`，32 位设备装不上 |
| 实测 | 三星 Galaxy S23（SM-S9110，Android 16） |

- 首次启动是**空库**：不内置示例数据，日志来自手表同步或 `.bin` 导入
- 若之前装过 `debug` 测试包，两者签名不同，需先卸载再装

## 已知限制

- 只支持 CR-4 系（同硬件贴牌：Deep Six Excursion / Genesis Centauri / Scorpena Alpha）的 `deepsix_excursion` 记录格式
- 只在 Galaxy S23 上验证过，其他机型未测，问题请附机型 + Android 版本
- 只做水肺模式，自由潜懒得搞了
- 无账号、无云同步，数据全在本机 SQLite
- 老记录不带气体信息，气体种类依赖手动标注

## 致谢

记录格式与字段解析参考 [libdivecomputer](https://github.com/libdivecomputer/libdivecomputer)；BLE 传输层是本项目自己实现的。

本项目代码 MIT，libdivecomputer 为 LGPL-2.1。

---

**这是日志复盘工具，不是潜水计划工具。** 重算出的 NDL、减压时间、安全深度基于事后回放与假设参数，不能替代潜水电脑的实时计算，也不能作为下水前的计划依据。

---

## English

A third-party Android client for the **Crest CR-4** dive computer. The stock app scans the whole logbook before every sync and blocks you on that screen; this release rebuilds sync, review and logbook cleanup.

### What's in this release

**Sync**

- Lightweight incremental sync — compares log headers and pulls only the new entries, no full scan
- Background sync — process-level engine + foreground service; switch pages, switch apps or lock the screen mid-sync
- File import — multi-select `.bin` via SAF, fingerprint dedupe, merged with existing data

**Review**

- Algorithm page — ZHL-16C with adjustable gradient factors (default 30/85)
- Recomputes NDL remaining, minimum safe depth and the GF99 curve from the real profile
- Dive detail with depth/temperature curves, max & average depth, temperature extremes, gas / site / visibility / suit / terrain annotations

**Cleanup**

- Fragment merge — breaking the surface mid-dive and going back down is stitched into one dive by surface interval
- Junk filter — depth/duration thresholds drop accidental triggers
- Recycle bin — deletion only sets a flag; restore one, many or all

### Install

| | |
| --- | --- |
| File | `CrestHelper-0.1.1-arm64-v8a.apk` (6.9 MB) |
| Requires | Android 8.0+ (minSdk 26, targetSdk 35) |
| ABI | `arm64-v8a` only |
| Verified on | Samsung Galaxy S23 (SM-S9110, Android 16) |

- Starts with an **empty logbook** — no sample data; dives come from watch sync or `.bin` import
- If you previously installed the `debug` build, uninstall it first (different signature)

### Known limitations

- CR-4 family only (`deepsix_excursion` format; rebadges: Deep Six Excursion / Genesis Centauri / Scorpena Alpha)
- Verified on the Galaxy S23 only — please include model + Android version in reports
- SCUBA mode only; freediving isn't worth the bother
- No accounts, no cloud sync — everything stays in a local SQLite database
- Older records carry no gas information, so gas type depends on manual annotation

### Credits

Record format and field parsing follow [libdivecomputer](https://github.com/libdivecomputer/libdivecomputer); the BLE transport layer is implemented here.

Code MIT, libdivecomputer LGPL-2.1.

---

**A log review tool, not a dive planner.** Recomputed NDL, deco times and safe depths replay a recorded profile with assumed parameters — they cannot replace your computer's real-time computation or serve as a dive plan.
