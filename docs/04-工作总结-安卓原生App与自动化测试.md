# 04 · 工作总结：安卓原生 App 与自动化测试基建

**一句话**：项目已从「人工装 APK → 手点 → 看到崩了 → 抓 logcat」的人工测试模式，
升级为「一条命令跑完全部测试」的自动化模式；Compase 骨架 + libdc.so 解析链 +
三层测试 + 可用 App（253 条真实数据可视化）均已就位，全部测试全绿。

> 本文是工作总收官文档。设计类文档看 01~03，测试日常使用看 `android/TESTING.md`，
> 本文回答三个问题：**做了什么、现在到哪了、接下来做什么**。
> 状态截至 **2026-10-26**（真机复测：单元 70/70 + 真机 19/19 全绿）。

---

## 1. 全景时间线

| 日期 | 阶段 | 关键产出 |
|---|---|---|
| 10-09 ~ 10-10 | 调研 | CR-4 全链路跑通：扫描→连接→下载→解析（见 01 文档） |
| 10-10 | 数据管道 | 292 条原始日志落盘 `raw/`，253 条解析成功 |
| 10-10 | 算法 | 减压内核 ZHL-16C+GF，Python/JS 双实现对拍逐位一致 |
| 10-10 | Web 原型 | 手机尺寸 5 页 UI 原型（UI 试错场，非正式客户端） |
| 10-11 | 路线收窄 | 用户拍板「只在安卓开发」，Flutter/桌面端暂缓 |
| 10-11 | 环境安装 | Android SDK + JDK17 + NDK 离线落地，可重跑 |
| 10-11 | Phase 1 | Compose 骨架 APK（9.7MB），环境自检页 |
| 10-11 ~ 10-12 | Phase 2 | libdc.so + JNI 解析链，283KB `.so` 打进 APK |
| 10-11 ~ 10-12 | **测试基建** | `at.sh` 一条命令 + 三层测试（37 单元 + 17 真机） |
| 10-12 | 真机联调 | 三星 S23 装机验证：安装成功、冷启动零 FATAL、`.so` 加载 ok |
| 10-17 | 状态复核 | 重跑单元测试确认现状；整理本文档 |
| 10-25 | **复测全绿** | 修 Robolectric 注解后单元 37/37；真机 17/17 一次跑通 |
| 10-25 | **可视化 App** | 内置 253 条真实数据，五页可视化（列表/详情/分析/统计/设备） |
| 10-26 | **四项新功能** | 年月筛选 / 合并（含自动间隔）/ 垃圾清理 / 平均深度+温度+安全深度+NDL |
| 10-26 | **测试编排重构** | `at.sh device` 改 `am instrument` 直跑，避开 Gradle 编排挂死；70 单元 + 19 真机全绿 |

---

## 2. 各阶段详情

### 2.1 调研与数据管道 ✅ 完成

- **协议事实**（01 文档）：Crest CR-4 走 libdivecomputer 的 `deepsix_excursion.c` 后端；
  BLE 是**随机地址**（`address_type=random`，Windows/WinRT 与 Android 两侧都要处理）；
  GATT service `f000ffe0-ab12-45ec-84c8-46483f4626e9`，数据 char `f000ffe1-...`；
  设备 hardware `GB-004` / software `C01-5C` / serial `CBK20400042`。
- **数据**：292 条原始 `.bin` 落盘 `raw/`，官方解析器解出 253 条（39 条待查）。
- **工具**：`tools/dctool.py`（多表通用，设备缓存 + 指纹断点续传）、
  `tools/batch_parse.py`（批量解析 → `data/dives.csv` + `samples.csv`）。

### 2.2 算法与分析引擎 ✅ 完成

- `tools/deco.py`（基准，含 `_selftest`）→ `web/deco.js`（逐行移植），
  黄金值对拍：30m/20min 五档天花板 1.68 / 4.38 / 9.73 / 15.24 / 18.97 **逐位一致**。
- `tools/analyze.py`：合并（SI≤600s，253→198）+ 无效过滤（13 条）+
  多算法对拍（185 条有效：ZHL16C 1 条义务 → GF20/60 143 条）。
- **两个数值坑（已修，别再踩）**：① Bühlmann 组织压力是惰性气体**分压**，
  M 值线用**总压** → GF 公式里必须 `f*P_amb` 折算；② GF ceiling 锚点必须用 **gf_low**。
- Kotlin 侧已平移：`Deco.kt`（ZHL-16C+GF 内核）+ `DiveMerger.kt`（合并/过滤）。

### 2.3 Web 原型 ✅ 完成（定位：UI 试错）

- `web/` 五页：日志（筛选/列表）/ 详情（剖面+GF99+算法切换实时重算）/
  分析（对拍+隐藏风险+合并）/ 统计 / 设备（Web Bluetooth）。
- Canvas 自绘零依赖，`file://` 双击可用；Web Bluetooth 需 localhost/HTTPS。
- **定位声明**：这是 UI 试错场，正式客户端是安卓 Compose，结构照搬这 5 页。

### 2.4 路线收窄：纯安卓原生（决策，10-11）

- 用户拍板「只在安卓开发」。M4 = 安卓原生核心（Kotlin/NDK），M5 = Compose App。
- **核心架构决策**：**BLE 扫描/连接/下载全部放 Kotlin（btleplug），
  libdc.so 只做「原始字节 → 结构化 dive」解析**。绕开 libdc 传输层移植，
  只编 parser 全家桶（context/array/buffer/checksum/common/datetime/parser +
  全部 `*_parser.c` + 空 `config.h` + 4 个 stubs），ABI 只编 arm64-v8a。

### 2.5 安卓环境 ✅ 完成（可重跑）

- 脚本 `$ANDROID_HOME-setup.sh`：JDK17（Temurin 17.0.20.1）+
  cmdline-tools + platforms;android-34/35 + build-tools;34/35.0.0 +
  platform-tools + NDK 27.0.12077973，整跑可重装。
- 工具链：Gradle 8.7（`$GRADLE`）+ AGP 8.5.2 +
  Kotlin 2.0.21 + Compose BOM 2024.09 + compileSdk/targetSdk 35 + minSdk 26 + Java 17。

### 2.6 Phase 1：Compose 骨架 ✅ 完成

- `MainActivity.kt` 环境自检页：三张卡片（运行环境 / 蓝牙 BLE / 构建状态）+
  一个按钮。**已移除「首次进入自动弹权限」**——已授权时按钮只刷新状态，
  避免系统对话框盖住 Activity 拖挂自动化测试。
- 权限状态用 `Lifecycle ON_RESUME` + `DisposableEffect` 刷新。

### 2.7 Phase 2：libdc.so + JNI 解析链 ✅ 完成

- `cpp/CMakeLists.txt` 指向工作区 libdivecomputer 源码；`cpp/dc_bridge.cpp`
  导出 `nativeVersion` / `nativeParseDive`（手工拼 JSON，含过短/空数据防御）；
  `cpp/stubs.c` + `cpp/config.h`。
- `DiveParser.kt`：`fromJson()` 抽成**纯 Kotlin**（可 JVM 测），
  native 调用包 `runCatching`，`.so` 加载失败不闪退、界面显示"未加载"。
- 产物：`libdcbridge.so`（283KB）打进 APK，JNI 符号
  `Java_com_divelog_app_DiveParser_*` 与 `deepsix_excursion_parser_create` 均正确导出。
- **16 KB 页对齐（10-25 补）**：`CMakeLists.txt` 加
  `-Wl,-z,max-page-size=16384`（仅 arm64-v8a），`at.sh build` 自动校验
  LOAD 段 Align=0x4000，防止 Android 15+ 16 KB 页设备装不上。

### 2.8 自动化测试基建 ✅ 完成（本次核心交付）

`dive/android/at.sh` —— 一条命令入口，七个子命令：

| 命令 | 干什么 | 要手机 |
|---|---|---|
| `bash at.sh` | 全量：L1 + L2 + L3 | L3 要 |
| `bash at.sh unit` | L1 单元测试（~60s） | ❌ |
| `bash at.sh build` | L2 构建 APK（含 NDK） | ❌ |
| `bash at.sh install` | 只装 APK | ✅ |
| `bash at.sh device` | L3 真机测试（自动装 + 抓崩溃） | ✅ |
| `bash at.sh crash` | 抓设备崩溃堆栈 | ✅ |
| `bash at.sh logs` | 实时 App 日志 | ✅ |

**三层测试**：

| 层 | 位置 | 数量 | 内容 |
|---|---|---|---|
| L1 | `app/src/test/` | 49 项 | `DecoTest`16（黄金值锁死）+ `DiveMergerTest`14（真实数据回归）+ `DiveParserJsonTest`7（脏数据容错）+ `DiveCsvTest`4（CSV 解析）+ `AnalyticsTest`8（合并/过滤/统计） |
| L2 | `assembleDebug` | — | Kotlin/NDK 编译、`.so` 产出、APK 打包 |
| L3 | `app/src/androidTest/` | 15 项 | `EnvScreenTest`4（底部导航/真实数据加载/进详情/算法切换）+ `NativeParseTest`7（真实 `.bin` 对拍 dives.csv：3673s/7.5m/32.0C/377样）+ `SmokeLaunchTest`4（冷启动 + logcat FATAL 检查，**专治「装上打不开」**） |

`at.sh` 内置的关键保护：无条件覆盖 JAVA_HOME 到 JDK17、设备检测去 `\r`、
跑测试前唤醒+常亮+解锁+预授 BLE 权限、报告路径动态查找、失败自动打堆栈。

### 2.9 真机联调 ✅ 完成

- 设备：三星 S23（SM-S9110，Android 16 / API 36，arm64-v8a）——ABI 与 APK 匹配。
- **已验证 ok**：`adb install -r -t` 成功；`monkey` 冷启动后进程存活、**零 FATAL**；
  `libdcbridge.so` 加载 ok、`DCBridge: nativeVersion called`；截图三卡片正常渲染。
- **10-25 复测全绿**：手机重新授权连接后，`bash at.sh device` 17/17 一次通过
  （1 分 50 秒），此前那次中断确认是屏幕/连接状态导致，非代码问题。

### 2.10 可用的可视化 App ✅ 交付（10-25）

用户要求「手头暂时没有手表，先用现有数据做好可视化展示」——于是把 253 条真实日志
做成一个**不依赖蓝牙、开箱即用**的完整 App。

- **数据接入**：`dives.csv`（253 条）+ `samples.csv`（60,638 个采样点）打包进
  APK `assets/`，启动一次性解析进内存（约 2~3 MB，无需数据库）。
- **五个页面**（底部导航）：
  1. **日志**：总潜次 / 累计时长 / 最大深度概览 + 年份筛选 + 卡片列表
     （时长、深度、水温、采样数；浅水 / 短时 / 合并组标记）
  2. **详情**：深度剖面图（叠加红色天花板虚线）+ GF99 曲线（100% 阈值线）+
     指标卡（GF99 峰值 / 天花板 / 减压时长 / 最大上升率）+
     **5 档算法实时切换重算**（同一潜次：ZHL-16C 原始 无义务 ↔ GF50/70 4.2m / 37:00）
  3. **分析**：数据治理（253 → 198 组 → 13 无效 → 185 有效）+ 多算法对拍
     （ZHL-16C 1 条 → GF80/80 33 → GF50/70 74 → GF30/85 100 → GF20/60 143）
     + 隐藏风险 Top 10（点击直达详情）
  4. **统计**：年度潜次 / 深度分布 / 时长分布 / 最近一年月度
  5. **设备**：运行环境自检（Android / ABI / BLE / `.so` 版本）+ 数据概况
- **图表**：全部 Compose Canvas 自绘（剖面 / GF99 / 柱状图），**零第三方图表依赖**。
- **测试**：新增 `DiveCsvTest`(4) + `AnalyticsTest`(8)，真机 `EnvScreenTest` 重写为
  真实 UI 测试（导航 / 数据加载 / 进详情 / 算法切换）。当时 **49 单元 + 15 真机全绿**。

### 2.11 四项新功能 ✅ 交付（10-26 凌晨）

用户提出四条需求，全部落地并真机验证：

| # | 需求 | 实现 |
|---|---|---|
| 1 | 记录页按年/月筛选，顶栏统计跟着筛选变 | 年份 chips + 选中年份后出现月份 chips；顶部三联（潜次 / 累计时长 / 最大深度）**永远是当前筛选结果**的统计，下方一行「当前范围：2024 年 10 月」明示口径 |
| 2 | 日志合并 + 可设自动合并间隔 | 新增 `DiveEntries`/`DiveSettings`：水面间隔 ≤ 阈值即视为同一次（默认 600s；**阈值自由输入**，秒/分钟单位可切、支持小数，范围 1 秒 ~ 24 小时，可关）；另有「多选合并」手动成组；合并后拼剖面时**中间插入水面段**，排气积分才正确 |
| 3 | 垃圾日志筛选/删除 | 「清理垃圾」面板：两个滑块（时长 ≤ X 秒、最深 < Y 米），下方**实时列出将被删除的记录**（含合计分钟数）；确认后软删除（墓碑 + 可撤销 + 可全部恢复），列表/统计/分析同步消失 |
| 4 | 单次潜水看更多指标与曲线 | 新增 **平均深度**（时间加权梯形积分，非采样点平均）、**最低/最高水温**、**最小安全深度**（当前算法下的天花板）、**NDL 剩余**；剖面图叠加**水温曲线**与**最小安全深度曲线**，NDL 单独一张图（已减压时段打红条） |

- **NDL 判据**（关键）：用「还能否直接升到水面」（gf_high 在水面处的约束）判定，
  而不是「产生天花板」。后者在 GF50/70 的 18m 会算出 9 分钟，明显偏离实际。
  实测：10m ≈ 99+ 分、18m 约 50 分、30m 约 18 分、40m 约 9 分（GF50/70）。
- **性能**：NDL 只按 60 个锚点仿真（每点最多 99 次 1 分钟积分）再线性插值，
  剖面 1000+ 采样点仍是毫秒级，不会卡主线程。
- **测试**：新增 `EntriesTest`（合并/清理/平均深度）+ `DecoNdlTest`（NDL 量级与天花板），
  `EnvScreenTest` 扩到 8 项（筛选、两个对话框、详情新指标）。
  当前 **70 单元 + 19 真机全绿**。

---

## 3. 当前真实状态（2026-10-26 真机复测）

### 3.1 测试通过情况（全绿）

| 层 | 结果 | 说明 |
|---|---|---|
| L1 单元测试 | **70/70 通过** | `DecoTest` 16 + `DiveMergerTest` 14 + `DiveParserJsonTest` 7 + `DiveCsvTest` 4 + `AnalyticsTest` 8 + `EntriesTest` 12 + `DecoNdlTest` 9 |
| L2 构建 | ✅ | APK 正常产出（10.4MB，含 2.9MB 真实数据 + `.so`） |
| L3 真机测试 | **19/19 通过** | 三星 S23 实机，`am instrument` 直跑 **22 秒**（含构建安装共 69 秒） |

`bash at.sh unit` → 70 项 0 失败；`bash at.sh device` → 19 项 0 失败。
真机 19 项 = `EnvScreenTest` 8 + `NativeParseTest` 7 + `SmokeLaunchTest` 4。

### 3.2 两个历史问题（均已解决，留档备查）

**问题一：Robolectric 4.13 不支持 targetSdk 35** ✅ 已修（10-25）

- 现象：`DiveParserJsonTest > initializationError`：
  `Package targetSdkVersion=35 > maxSdkVersion=34`，整类 7 项一条都跑不了。
- 根因：为修 Android 16 上的 ANR 把 targetSdk 提到 35，
  而 Robolectric 4.13 最高支持 SDK 34。
- 修法：`DiveParserJsonTest` 加 `@Config(sdk = [34])`
  —— 只影响 JVM 测试运行环境，不改 targetSdk、不影响真机行为。
  修复后 37/37 通过。

**问题二：`EnvScreenTest` 曾挂死 551.7 秒** ✅ 已排除（10-25）

- 现象（10-12 那轮）：`test_should_show_android_version_and_abi` 跑了 551.7 秒后
  超时失败（空 failure 消息），紧接着手机掉线（`device not found`），17 项只跑了 5 项。
- 根因：屏幕变暗 / 连接不稳 → Activity 离开 RESUMED → Compose 的 `assertIsDisplayed`
  永久等待；JUnit 的 `@Test(timeout)` 对 Compose 等待循环不生效（中断被吞）。
- 结论：**不是代码 bug，是测试环境问题**。10-25 手机重新授权连接后，
  同一套代码 17/17 一次通过。at.sh 的唤醒 + `svc power stayon usb` + 解锁 +
  预授权限已能兜住。
- 使用建议：跑 L3 前确保手机亮屏、USB 线不松动；若再出现挂死，
  优先怀疑屏幕/连接，而不是被测代码。

**问题三：Android 16 弹「应用兼容性」——16 KB 页对齐** ✅ 已修（10-25）

- 现象：装机启动后系统弹窗「此应用不符合 16 KB 对齐要求」，
  点名 `libdcbridge.so: LOAD 区段未对齐`。
- 根因：NDK r27 默认按 **4 KB（0x1000）** 链接；Android 15+ 要求 `.so` 的
  LOAD 段按 **16 KB（0x4000）** 对齐——16 KB 页设备会装不上/起不来，
  4 KB 页设备（如 S23）虽能跑但会弹警告，观感上就像"装了个坏 App"。
- 修法：`CMakeLists.txt` 加 `-Wl,-z,max-page-size=16384`（仅 arm64-v8a），
  并在 `at.sh build` 里加自动校验（`llvm-readelf` 读 LOAD 段 Align，
  非 `0x4000` 直接报错）。修复后重装，警告消失。
- 备注：APK 的 zip 条目对齐 AGP 8.5.2 已自动处理（`zipalign -P 16`），
  只需管 ELF 段对齐。

### 3.3 「装上打不开」复核结论

- **adb 安装路径下不复现**：安装成功、冷启动进程存活、零 FATAL、`.so` 加载 ok。
- **16 KB 对齐已在 10-25 修复**（见 §3.2 问题三），重装后系统不再弹兼容性警告。
- 若用户侧（非 adb）安装仍打不开，按优先级查：
  1. **ABI**：APK 只含 `arm64-v8a`，x86 模拟器/老设备会直接闪退
     （`adb shell getprop ro.product.cpu.abilist` 核对）；
  2. **minSdk 26**：低于 Android 8.0 装不上；
  3. 安装来源：非官方来源需允许安装。

---

## 4. 踩坑集锦（全部实测验证过，按主题）

### Windows / Git Bash
1. **adb 输出带 `\r` 且字段间是 TAB** → 设备检测必须
   `adb devices | tr -d '\r' | awk '$2=="device"'`，不能拿 `grep "device$"` 行尾锚定。
2. **tar/curl 等 unix 工具把 `D:/` 当远程主机** → 一律用 `/d/`；只有
   sdkmanager/java 这类 Windows 程序用 `D:/`。脚本里 SDKW/SDKU 两套路径。
3. Git-Bash 自带 tar 解不开 zip → 用 `unzip -o`。
4. `adb shell screencap -p /sdcard/x.png` + `adb pull` 会被 MSYS 路径转换坑 →
   用 `adb exec-out screencap -p > 文件`。

### 工具链安装
5. Adoptium JDK 直链/API 404 → GitHub API 运行时解析 Temurin 稳定发布链接。
6. sdkmanager（cmdline-tools 11076708）**不认 `--accept-licenses`** → `yes |` 喂 y。
7. **AGP 8 必须 JDK 17** → at.sh 无条件覆盖 JAVA_HOME（`${JAVA_HOME:-...}` 回退不生效）。
8. platform-tools 一次装多包时易漏 → 单独补装并校验 `adb.exe`。

### Gradle / 构建
9. AGP 8.5.2 只测到 compileSdk 34 → 先降 34；后因 Android 16 ANR 提回 35。
10. `connectedAndroidTest` 对设备名目录做快照哈希偶发 MD5 失败 → `doNotTrackState`。
11. androidTest classpath **不继承 main 的 Compose BOM** → 显式加 platform BOM。
12. `LaunchedEffect` 块非 @Composable，不能在里面取 `LocalContext.current`。

### 测试（本次最贵的一批教训）
13. **JVM/DEX 方法名禁中文、`.`、空格**（双重限制）→ 全 ASCII snake_case。
14. 仪器测试 runner 退化成 JUnit3 → `androidTest/AndroidManifest.xml`
    显式声明 `androidx.test.runner.AndroidJUnitRunner`。
15. **系统权限对话框盖住 Activity 不是失败，是挂死** → 预授权限 +
    已授权则不再请求。
16. **息屏/doze → Activity 只到 STARTED → 断言挂死** → 唤醒+stayon+解锁。
17. Robolectric vs targetSdk（见 §3.2 问题一）。
18. 早期 3 个失败排查结果全是**测试期望写错、代码是对的**——先怀疑测试。

### libdivecomputer / 算法
19. CR-4 是**随机地址**，`connectGatt` 同样要处理。
20. `DC_SAMPLE_TIME` 单位是**毫秒**。
21. 广播里的 `0xFFC0/0xFFF0` 与真实 service 不一致，**别拿广播 UUID 当依据**。
22. 减压公式两个数值坑见 §2.2。

### Android 16 / 兼容性
23. **16 KB 页对齐**：NDK r27 默认按 4 KB 链接，Android 15+ 要求 `.so` 的
    LOAD 段按 16 KB 对齐（`-Wl,-z,max-page-size=16384`）。未对齐时
    16 KB 页设备装不上/起不来，4 KB 设备弹「应用兼容性」警告。
    `at.sh build` 已内置 `llvm-readelf` 自动校验（要求 Align=0x4000）。

### 测试编排（10-26 新增，最容易被误判成"代码坏了"）
24. **`connectedDebugAndroidTest` 会整轮挂死**：跑到第 7/19 个用例后既不出结果也不超时，
    等了 8 分 49 秒只能手动杀掉；App 进程当时 CPU 0%（不是在算，是在等编排）。
    同一批 19 个用例、同一台机器，用 `adb shell am instrument -w -r` 直跑 **22 秒全绿**。
    → `at.sh device` 已改成「Gradle 只构建 + 安装，测试由 `am instrument` 直跑」，
    并给整轮加了 `timeout`（默认 600s，`DEVICE_TIMEOUT` 可改）；超时会打印
    「最后开始但没返回的用例名」，一眼定位挂在哪。
25. **测试点错了坐标会骗人**：手工 `input tap` 验证时点到了「多选合并」，
    界面进多选模式，看起来像"点卡片打不开详情"。用 `adb` 手工验证 UI 前，
    先截图确认真实坐标（列表顶部有统计/筛选/操作三行，卡片起点会随版本变）。

---

## 5. 资产清单

```
dive/
├── 00-总纲-产品路线图.md          # 产品路线与里程碑
├── 01-调研-libdivecomputer与Crest-CR4.md   # 协议调研
├── 02-架构设计-潜水记录管理软件.md          # 架构设计
├── 03-日志分析与算法设计.md                # 算法设计
├── 04-工作总结-安卓原生App与自动化测试.md   # 本文
├── raw/                        # 294 个文件：292 条 CR-4 原始日志(.bin)
├── data/                       # dives.csv + samples.csv（批量解析产物）
├── build/                      # MinGW 精简解析器：parse_cr4.exe + build.sh + 全部 .o
├── tools/                      # cr4_probe.py / dctool.py / batch_parse.py /
│   │                           #   analyze.py / deco.py / wheels/(离线 wheel)
├── web/                        # UI 原型：index.html + app.js + deco.js + app-data.js
└── android/                    # ★ 正式客户端（Gradle 工程）
    ├── at.sh                   # ★ 测试总入口（7 个子命令）
    ├── TESTING.md              # 测试使用指南
    └── app/src/
        ├── main/assets/        # dives.csv(253 条) + samples.csv(60,638 点) 真实数据
        ├── main/java/.../      # MainActivity / DiveData / Charts / DiveStore(条目/设置/持久化)
        │                       #   + DiveList/Detail/Analysis/Stats/Device 五个页面 + DiveDialogs
        │                       #   + DiveParser / Deco / DiveMerger
        ├── main/cpp/           # dc_bridge.cpp(JNI) + CMakeLists + stubs + config.h
        ├── test/java/.../      # L1：Deco / DiveMerger / DiveParserJson / DiveCsv /
        │                       #     Analytics / Entries / DecoNdl
        └── androidTest/java/.../ # L3：EnvScreenTest / NativeParseTest / SmokeLaunchTest
                                  #   + assets/golden_dive1.bin（真实黄金样本）
```

---

## 6. 下一步

### 近期（下次会话就能做完）
1. **Phase 3：接手表下载**（手头暂时没表，随时可接）：BLE 扫描/连接 CR-4 →
   下载字节 → `DiveParser.parse()` → 入库；注意**随机地址**，GATT 参数可参考
   桌面端 `devices.json`。
2. **数据持久化**：目前每次启动从 assets 解析（约 1 秒）；接手表后改为 Room 存储，
   新下载的日志与内置样本合并展示。
3. **保持全绿**：改完逻辑先 `bash at.sh unit`（~90s），提交前 `bash at.sh device`（~70s）。

### 中期
4. 39 条解析失败数据补查（下载完整性 or 设备端脏数据）。
5. 黄金样本扩充：用更多真实 dive 扩充 `NativeParseTest` 对拍集。
6. 详情页再加「不同算法同屏对比」（当前是切换重算，看不到两条天花板叠加）。

### 远期
7. 本地持久化（Room）+ 数据导出。
8. 桌面端暂缓（用户决定只在安卓开发）。

---

## 7. 常用命令速查

```bash
# ===== 自动化测试（dive/android/）=====
bash at.sh            # 全量：L1 + L2 + L3
bash at.sh unit       # L1 单元测试（~60s，不需要手机，改代码后必跑）
bash at.sh build      # L2 构建 APK
bash at.sh device     # L3 真机测试（自动装 APK + 抓崩溃）
bash at.sh crash      # 抓设备上的崩溃堆栈
bash at.sh logs       # 实时跟踪 App 日志

# ===== 桌面端数据管道（dive/）=====
python tools/dctool.py scan                      # 扫表
python tools/dctool.py download --all-devices    # 批量下载（指纹续传）
python tools/batch_parse.py                      # 批量解析 → data/*.csv
python tools/analyze.py                          # 分析 → web/app-data.js
python tools/deco.py                             # 减压内核自检（黄金值）

# ===== 环境重装 =====
bash $ANDROID_HOME-setup.sh                # SDK/JDK17/NDK 整跑重装
```

---

## 8. 遗留事项备忘

- 39 条解析失败的原始数据（`raw/` 里）未查因。
- 桌面端 bleak 的代理坑：pip 走注册表 Clash 代理，Clash 没开时全失败；
  curl 直连通（必要时 `--ssl-no-revoke`）；离线 wheel 已存 `tools/wheels/`。
- 本项目**未纳入 git**，重要变更靠本文档 + `.workbuddy/memory/` 日志留痕。
