# CrestHelper

> 为 **Crest CR-4** 潜水电脑写的第三方 Android 客户端：扫描 → 连接 → 下载 → 复盘，全链路自持。
>
> [English](README.en.md)

<p align="center">  
  <img src="docs/shots/01-dives.png" width="180" alt="日志列表">  
  <img src="docs/shots/02-detail.png" width="180" alt="潜水详情">  
  <img src="docs/shots/03-stats.png" width="180" alt="统计">  
  <img src="docs/shots/04-device.png" width="180" alt="设备">  
</p>

## 为什么写这个

两个初衷，加上顺手治好的几个老毛病。

### 一、同步太慢

潜得越多越难受：原厂 App 每次同步都要把表里的记录**全盘扫一遍**才开始下载，而且只能停在前台干等；CR-4 的 BLE 本来就慢，一趟几分钟就这么没了。所以：

- **轻量增量下载** —— 不再全表扫描，只比对记录头部、挑出表里多出来的那几条日志直接下，同步耗时从「整本表」降到「新增几条」。
- **后台下载** —— 同步跑在进程级引擎 + 前台服务里，下载过程中可以切到别的页面、切 App、锁屏，通知栏上看进度，不用被「请保持在此页面」摁住。

### 二、这次潜水到底可不可复盘

潜水表的算法对用户是个黑盒：这次有没有进入**减压潜水**？当时的**保守程度**算高还是低？表上从来不告诉你。所以加了算法设置页：

- 内置 **ZHL-16C**，梯度因子（GF）可手动配置，默认 **30/85** —— 想更保守就把低 GF 调小；
- 用真实剖面重算 **NDL 剩余**、**最小安全深度**、**GF99** 曲线；
- 回头对着曲线看：那次潜水在哪一段越过了减压线，按当时的保守度是不是本可以再待一会儿。

### 三、顺手把脏记录清掉

- **过滤过短的潜水** —— 按深度/时长阈值筛掉误触发、随手按出来的记录；
- **碎片合并** —— 中途临时升水又下水，原本会被拆成好几条独立日志；按水面间隔把它们**贯穿回同一次潜水**；
- **误删可回收** —— 删除只打标记，原始记录和采样点都还在，随时从回收站捞回来。

### 现状

只覆盖水肺（SCUBA）模式，自由潜懒得搞了。手机兼容性只在自己那台机器上过过 —— 别的机型自己 clone 下来找个 AI agent 去调，边界见下面的适配声明。

## 适配与测试声明（先读这段）

| 项          | 说明                                                                                                                          |
| ---------- | --------------------------------------------------------------------------------------------------------------------------- |
| **适配设备**   | **Crest CR-4**。同硬件贴牌机（Deep Six Excursion / Genesis Centauri / Scorpena Alpha）走同一套 `deepsix_excursion` 记录格式，理论上通用，但**未逐台验证** |
| **实测机型**   | **仅三星 Galaxy S23（SM-S9110，Android 16 / API 36，arm64-v8a）**                                                                  |
| **未覆盖**    | 其他品牌/型号手机、Android 8~14、平板、模拟器、iOS、其他潜水表                                                                                     |
| **APK 架构** | 只编 `arm64-v8a`（`abiFilters`），32 位设备装不上                                                                                      |

> 一句话：**在 CR-4 + 三星 S23 这条路径上验证可用，别的一律按「未测」对待。**

## 功能

| 模块   | 说明                                                                                                         |
| ---- | ---------------------------------------------------------------------------------------------------------- |
| 数据解析 | libdivecomputer 的 `deepsix_excursion` 解析器经 NDK 编译，JNI 输出 JSON                                              |
| 日志列表 | 年份筛选、收藏筛选、顶栏联动统计、多选合并与批量删除（作用域 = 当前筛选结果）                                                                    |
| 回收站  | 删除只打 `user_deleted` 标记，原始记录与采样点都还在；可单条/多选/全部恢复，**不提供彻底删除**                                                 |
| 单次详情 | 顶部两栏指标（编号 / 开始 / 结束 / 时长，最大 / 平均深度与最低 / 最高水温）；呼吸气体与减压算法并排一张卡，各自就地标注与配置；再往下是最小安全深度、NDL 剩余、GF99 与上升速度；剖面 + 水温曲线，其上为**潜水信息**（潜点、能见度、湿衣/干衣、地形与水域多选） |
| 设置   | 合并阈值、垃圾阈值、GF 两个值直配（默认 30/85）、默认气体、同步默认模式、回收站入口                                                             |
| 统计   | 年度/月度潜次、深度与时长直方图、最近 12 个月趋势                                                                              |
| 文件导入 | SAF 多选 `.bin`，指纹去重，落库后与已有数据合并展示                                                                            |
| 手表同步 | 增量按「手表最新编号 − 本地最大编号」对差，锚点指纹校验，异常自动降级全量                                                                     |
| 后台同步 | 同步跑在进程级引擎 + 前台服务里，退出页面、切 App、锁屏都不中断，通知栏看进度                                                                 |
| 手表设置 | 时间校准三步下发（写日期/时间/落盘，逐字节回读校验）；其余项按能力矩阵标注「固件未开放」                                                              |
| 元数据  | 合并、过滤、删除、收藏、气体、手填标注全部以**标记位**落库，不物理删除                                                                      |

## 目录

```
CrestHelper/
├── android/                  Android App（Kotlin + Compose + NDK）
│   ├── app/src/main/java/com/divelog/app/    UI 与业务（data/ 为 SQLite 层，sync/ 为 BLE 同步）
│   ├── app/src/main/cpp/                     libdc JNI 桥 + CMake
│   ├── app/src/main/res/                     图标（mipmap-*）与主题
│   ├── app/src/test/                         L1 JVM 单测
│   ├── app/src/androidTest/                  L3 真机仪器测试
│   └── at.sh                                 一条命令跑完三层测试
├── data/                     示例数据集（dives.csv：40 条精选摘要，一次潜水一行）
├── raw/                      3 条原始 .bin 样本（验证解析链路用）
├── tools/                    桌面端脚本（探测、批量解析、算法对拍、图标与网页数据生成）
├── web/                      手机尺寸 UI 原型（先定 UI 再平移安卓）
├── build/                    免 autotools 的独立解析器（parse_cr4.c + build.sh）
├── third_party/              libdivecomputer（解析器源码，协议实现参考）
├── docs/                     调研 / 架构 / 算法 / 工作总结 / 同步设计 / 测试指南
└── docs/shots/               README 配图（Galaxy S23 实机截屏）
```

## 构建

环境：JDK 17、Android SDK（platform 35）、NDK 27.0.12077973、CMake 3.22.1。

```bash
# 1) 取第三方解析器源码（必须先跑，否则 NDK 编译缺头文件）
git submodule update --init --recursive

# 2) 构建
cd android
./gradlew :app:assembleDebug          # 或 bash at.sh build（含 16 KB 页对齐校验）
./gradlew :app:assembleRelease        # 有 android/keystore.properties 时才会签名
```

`at.sh` 不写死本机路径：JDK 依次找 `$JDK17_HOME` → `$JAVA_HOME` → 常见安装位置，  
SDK 找 `$ANDROID_HOME` → `$ANDROID_SDK_ROOT` → 常见安装位置，Gradle 优先 `./gradlew`。  
都能用环境变量覆盖；末尾还留了几个开发机常见目录当回退，换机器时直接用环境变量指定即可。

> 网络受限时 `assembleRelease` 可能卡在 `lintVitalAnalyzeRelease`（要拉 `lint-gradle`）：  
> 加 `-x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease` 跳过即可，  
> 不影响产物；有网时建议跑一次完整 lint。

## 测试

三层测试替代「装 APK 手点」，详见 [docs/06-自动化测试指南.md](docs/06-自动化测试指南.md)。

```bash
cd android
bash at.sh          # L1 + L2 + 安装 + L3（没有手机会自动跳过 L3）
bash at.sh unit     # 只跑单元测试，秒级
bash at.sh device   # 只跑真机仪器测试
```

- **L1** JVM 单测：减压数值、NDL、合并/过滤规则、CSV、JSON 容错、同步计划器、标记在重同步后不丢
- **L2** 构建：Kotlin/NDK 编译、`.so` 产出、**16 KB 页对齐**、APK 打包
- **L3** 真机：`am instrument` 直跑，抓「装上打不开」的崩溃堆栈与 JNI 解析数值

## 数据

`data/dives.csv` 是**摘要**：一次潜水一行的数字（编号、开始时间、时长、最大深度、最低水温）。
**只有 debug 包**会在首次启动时把它导入 SQLite（release 包不带示例数据，装出来是空库，
要靠蓝牙同步或导入 `.bin` 自己积累）；列表、筛选、统计都吃这张表。

里面是 **40 条精选样本**，从一份真实的 CR-4 日志里抽出来的：2020–2026 每年都有，
保留最深 34.7 m、最长 101 min、以及几条一两秒的误触发记录（垃圾过滤跑得起来才看得出效果）。
抽样只留摘要数字，**没有潜点、没有 GPS、没有逐点轨迹**。

`raw/` 带 3 条代表样本（最深 34.7 m、最长 102 min、最早 2020 年各一条），
用来验证「原始字节 → 解析器 → CSV」这条链路跑得通。想把 `.bin` 重新过一遍：

```bash
bash build/build.sh          # 先编出解析器（免 autotools 的独立版本）
python tools/batch_parse.py  # 批量解析 raw/，重写 data/dives.csv
```

单条调试用 `tools/dctool.py`，GATT 探测用 `tools/cr4_probe.py`。

`web/app-data.js` 是原型的演示数据，由 `tools/make_web_demo.py` 生成：
40 条摘要全量 + 6 条带剖面的示例，34 KB。

## 设备协议（CR-4）

记录体各字段的含义与取值，参考 [libdivecomputer](https://github.com/libdivecomputer/libdivecomputer)
的 `deepsix_excursion` 解析器 —— 本项目的解析层就是它编译进来的，协议基准值以那份实现为准。
CR-4 走 BLE，而 libdivecomputer 不含 BLE 传输层，传输部分由 App 侧实现。下表是实测量：

| 项                 | 值                                                                                 |
| ----------------- | --------------------------------------------------------------------------------- |
| 广播名               | `Crest-CR4`（同硬件贴牌：Deep Six Excursion / Genesis Centauri / Scorpena Alpha）         |
| 地址类型              | **随机私有地址**（Android 侧连接需注意，否则 Unreachable）                                         |
| 数据 service        | `f000ffe0-ab12-45ec-84c8-46483f4626e9`                                            |
| 数据 characteristic | `f000ffe1-ab12-45ec-84c8-46483f4626e9`（notify + write）                            |
| 包格式               | `[grp][cmd][dir][len][payload][csum]`，`csum = sum ^ 0xFF`，响应 group = 请求 group + 1 |

命令组：`0xA0` 设备信息、`0xB0` 设置（日期/时间/存储/加载）、`0xC0` 日志（count / index / header / profile）。

## 已知限制

- 只认 CR-4 系（含贴牌）的 `deepsix_excursion` 记录格式，别的表不支持
- 只在 Galaxy S23 上验证过；其他机型未测，遇到问题请附机型 + Android 版本
- 只出 `arm64-v8a`，不含 `armeabi-v7a`
- 无账号、无云同步，数据全在手机本地 SQLite
- CR-4 的老记录格式不带气体信息，气体种类依赖用户标注

## 免责声明

本项目是**日志复盘工具**，不是潜水计划工具。App 内重算的 NDL、减压时间、最小安全深度、GF99  
全部基于事后回放的剖面与假设参数（ZHL-16C + 可调 GF，单气体），  
**不能替代潜水电脑的实时计算，也不能作为下水前的计划依据，更不能用来判断某一次潜水是否安全**。  
这些数字的用途是横向对比「同一条件下换一组 GF 会怎样」，不是给某次潜水下安全结论。  
任何潜水决策请以受过训练的潜水计划方法与你自己的潜水电脑为准。

减压内核在 **v0.1.4** 做过一次口径校准（标准 ZHL-16C 参数表、肺泡水蒸气压扣除、
GF 天花板的冻结锚点解析解）。因此**不同版本之间的 GF99 / 最小安全深度 / 减压时长
不能直接横向比较**，只有同一版本内换 GF 做对比才有意义。当前公式与数值见
[03-日志分析与算法设计](docs/03-日志分析与算法设计.md)。

## 文档

| 文档                                               | 内容                                         |
| ------------------------------------------------ | ------------------------------------------ |
| [00-总纲](docs/00-总纲-产品路线图.md)                     | 产品目标、里程碑、当前进度                              |
| [01-调研](docs/01-调研-libdivecomputer与Crest-CR4.md) | libdivecomputer 能力边界、CR-4 的 GATT 实测、BLE 缺口 |
| [02-架构设计](docs/02-架构设计-潜水记录管理软件.md)              | 分层与数据流                                     |
| [03-日志分析与算法设计](docs/03-日志分析与算法设计.md)             | 合并/过滤口径、ZHL-16C + GF、NDL 判据                |
| [04-工作总结](docs/04-工作总结-安卓原生App与自动化测试.md)         | 安卓端落地过程与踩坑                                 |
| [05-同步与元数据](docs/05-数据同步与元数据设计.md)               | 存储 schema、增量/全量策略、手表设置边界、协议速查              |
| [06-自动化测试指南](docs/06-自动化测试指南.md)                 | 三层测试与 `at.sh` 用法                           |

## 第三方与许可

- [libdivecomputer](https://github.com/libdivecomputer/libdivecomputer) —— LGPL-2.1，  
  `.so` 只链接其解析器；按其许可要求，替换 `.so` 即可换回上游版本
- [bleak](https://github.com/hbldh/bleak)（`tools/wheels/`，仅桌面端调试脚本用）

本项目自身代码采用 MIT。
