# v0.1.2 发布说明

> 对应 `versionCode 3 / versionName 0.1.2`，tag `v0.1.2`，APK 仅 `arm64-v8a`。
> 本文即 GitHub Release 正文，可直接整段复制到 Release 页面。

为 **Crest CR-4** 潜水电脑写的第三方 Android 客户端。这一版只重做了一件事：**潜水详情页**。

## 本次改动

**信息重排，一屏看完一次潜水**

- 第一栏：编号、开始时间、结束时间、时长（结束时间按开始时刻加潜水时长现算，精确到秒）
- 第二栏：最大深度、平均深度、最低水温、最高水温
- 原来散在几张卡里的「其他信息」和采样点数量被删掉，页面短了一截

**气体与算法合并成一张卡**

- 左边呼吸气体（`空气` / `EAN32`，高氧时数值单独标色），右边减压算法 `ZHL-16C 30/85`
- 各自带一个小按钮：气体点「标注」、算法点「配置」，改完就地生效，不用翻设置页

**合并记录**

- 被**手动合并**的几次潜水会显示一条提示，可以直接在这条记录上「拆开」；自动合并的组不提供拆除入口

> 订正（2026-11）：这里原写「被合并的几次潜水…可以直接拆开」，不准确 —— 只有手动合并的组可拆。

## 安装

| 项 | 说明 |
| --- | --- |
| 文件 | `CrestHelper-0.1.2-arm64-v8a.apk`（6.9 MB） |
| 系统 | Android 8.0+（minSdk 26，targetSdk 35） |
| 架构 | 仅 `arm64-v8a`，32 位设备装不上 |
| 实测 | 三星 Galaxy S23（SM-S9110，Android 16） |

- 已装 0.1.1 的可以直接覆盖升级，记录和设置都保留
- 首次启动是**空库**：不内置示例数据，日志来自手表同步或 `.bin` 导入
- 若之前装过 `debug` 测试包，两者签名不同，需先卸载再装

## 已知限制

- 只支持 CR-4 系（同硬件贴牌：Deep Six Excursion / Genesis Centauri / Scorpena Alpha）的 `deepsix_excursion` 记录格式
- 只在 Galaxy S23 上验证过，其他机型未测，问题请附机型 + Android 版本
- 只做水肺模式，自由潜懒得搞了
- 无账号、无云同步，数据全在本机 SQLite
- 老记录不带气体信息，气体种类依赖手动标注

## 已知问题（0.1.3 已修复，建议升级）

- **GF99 用错了口径**：曲线与峰值按「当前深度」而不是「直冲水面」计算，深水段恒显示 0/负值。
  实测有 2 条真实潜水（#183、#185）已经越过 M 值线，却被显示成 85% 左右。
- 减压内核的 Schreiner 段积分把段末环境压当成肺泡压基准，升降段组织负荷有偏差
  （对真实剖面影响很小：减压时长差 ≤30 秒、天花板差 <0.01 m，但公式本身是错的）。
- 未关闭系统备份：潜水库与手表 MAC 可能被 adb / 云备份 / 换机直传导出。
- 同步取消不生效、手表断线后连接资源不释放、前台服务启动失败会崩。

详见 [v0.1.3 发布说明](release-notes-v0.1.3.md)。

## 致谢

记录格式与字段解析参考 [libdivecomputer](https://github.com/libdivecomputer/libdivecomputer)；BLE 传输层是本项目自己实现的。

本项目代码 MIT，libdivecomputer 为 LGPL-2.1。

---

**这是日志复盘工具，不是潜水计划工具。** 重算出的 NDL、减压时间、安全深度基于事后回放与假设参数，不能替代潜水电脑的实时计算，也不能作为下水前的计划依据。

---

## English

A third-party Android client for the **Crest CR-4** dive computer. This release reworks one screen only: the **dive detail page**.

### What changed

**Everything on one screen**

- Row one: number, start time, end time, duration (end time is computed from start + dive duration, to the second)
- Row two: max depth, average depth, lowest water temp, highest water temp
- The old "other info" card and the sample count are gone, so the page is noticeably shorter

**Gas and deco algorithm share one card**

- Left: breathing gas (`Air` / `EAN32`, the value alone is tinted for nitrox). Right: `ZHL-16C 30/85`
- Each has its own small action: "annotate" for gas, "configure" for the algorithm — no need to dig through Settings

**Merged dives**

- Dives merged **manually** show a hint and can be split apart right there; auto-merged groups have no split action

> Correction (Nov 2026): the original wording implied any merged dive could be split. Only manual groups can.

### Install

| | |
| --- | --- |
| File | `CrestHelper-0.1.2-arm64-v8a.apk` (6.9 MB) |
| Requires | Android 8.0+ (minSdk 26, targetSdk 35) |
| ABI | `arm64-v8a` only |
| Verified on | Samsung Galaxy S23 (SM-S9110, Android 16) |

- Upgrading over 0.1.1 works in place — dives and settings are kept
- Starts with an **empty logbook** — no sample data; dives come from watch sync or `.bin` import
- If you previously installed the `debug` build, uninstall it first (different signature)

### Known limitations

- CR-4 family only (`deepsix_excursion` format; rebadges: Deep Six Excursion / Genesis Centauri / Scorpena Alpha)
- Verified on the Galaxy S23 only — please include model + Android version in reports
- SCUBA mode only; freediving isn't worth the bother
- No accounts, no cloud sync — everything stays in a local SQLite database
- Older records carry no gas information, so gas type depends on manual annotation

### Known issues (fixed in 0.1.3 — please upgrade)

- **GF99 used the wrong reference**: curves and peaks were computed against *current depth* instead of
  *surfacing now*, so the deep section always read 0 / negative. Two real dives (#183, #185) had crossed
  the M-value line yet were shown around 85%.
- The Schreiner segment integration used the segment's end pressure as the alveolar baseline, skewing
  on-gassing/off-gassing during descents and ascents (small in practice: ≤30 s deco time, <0.01 m ceiling,
  but the formula was simply wrong).
- Android backup was not disabled: the dive database and watch MAC could be exported via adb / cloud backup / device transfer.
- Cancelling a sync didn't stop it, GATT resources leaked after a disconnect, and a failed foreground-service start crashed the app.

See the [v0.1.3 release notes](release-notes-v0.1.3.md).

### Credits

Record format and field parsing follow [libdivecomputer](https://github.com/libdivecomputer/libdivecomputer); the BLE transport layer is implemented here.

Code MIT, libdivecomputer LGPL-2.1.

---

**A log review tool, not a dive planner.** Recomputed NDL, deco times and safe depths replay a recorded profile with assumed parameters — they cannot replace your computer's real-time computation or serve as a dive plan.
