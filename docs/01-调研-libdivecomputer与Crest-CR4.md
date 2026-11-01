# 调研报告：libdivecomputer 与 Crest CR-4

> 调研时间：2026-10-10 · 仓库：`libdivecomputer/libdivecomputer@master`（`9e6c3c8`）
> 本地克隆位置：仓库根同级的 `libdivecomputer/`（或直接用 `third_party/libdivecomputer` 这个 submodule）

---

## 0. 结论速览

| 问题 | 结论 |
|---|---|
| CR-4 被支持吗？ | ✅ **支持**，而且是很完整的支持（设备协议 + 解析器都在主线） |
| 走什么传输？ | **Bluetooth Low Energy**（不是经典蓝牙、不是 USB） |
| libdivecomputer 能直接连吗？ | ❌ **不能**。它**故意不实现 BLE 传输层**，必须由你的应用补上 |
| 补的方式？ | `dc_custom_open()` 提供一个自定义 `dc_iostream_t`（字节流），再喂给 `dc_device_open()` |
| 能拿到什么数据？ | 非常丰富：逐秒深度剖面、温度、NDL/减压停留、GF 设置、CNS、气体切换、报警事件 |
| 主要工作量在哪？ | ① BLE GATT 传输层（每平台一份）② 上层应用（存储/UI/分析）全部自建 |

---

## 1. libdivecomputer 是什么

- 跨平台（Win/Linux/macOS/BSD/Android/iOS）的 C 库，LGPL-2.1，148 stars / 70 forks，2215 commits，仍在活跃维护（最近一次提交 2026-09-11）。
- 支持约 **30+ 品牌 / 200+ 型号** 的潜水电脑。
- 官方声明支持的传输：Serial、USB、USBHID、IrDA、Bluetooth Classic、**Bluetooth LE**。
- 它只做一件事：**设备协议 + 数据解析**。不做存储、不做 UI、不做减压算法、不做分析。

---

## 2. Crest CR-4 的支持证据

### 2.1 设备登记表（`src/descriptor.c:478-481`）

```c
/* Deep Six Excursion */
{"Deep Six", "Excursion", DC_FAMILY_DEEPSIX_EXCURSION, 0, DC_TRANSPORT_BLE, dc_filter_deepsix},
{"Crest",    "CR-4",      DC_FAMILY_DEEPSIX_EXCURSION, 0, DC_TRANSPORT_BLE, dc_filter_deepsix},
{"Genesis",  "Centauri",  DC_FAMILY_DEEPSIX_EXCURSION, 0, DC_TRANSPORT_BLE, dc_filter_deepsix},
{"Scorpena", "Alpha",     DC_FAMILY_DEEPSIX_EXCURSION, 0, DC_TRANSPORT_BLE, dc_filter_deepsix},
```

CR-4 与 Deep Six Excursion / Genesis Centauri / Scorpena Alpha 是**同一套硬件的贴牌**，共用 `DC_FAMILY_DEEPSIX_EXCURSION` 后端，固件代码在 `src/deepsix_excursion.c`（522 行）+ `src/deepsix_excursion_parser.c`（789 行）。

### 2.2 BLE 广播名过滤（`src/descriptor.c:875-889`）

```c
static int dc_filter_deepsix (const dc_descriptor_t *descriptor, dc_transport_t transport, const void *userdata)
{
    static const char * const bluetooth[] = {
        "EXCURSION",
        "Crest-CR4",
        "CENTAURI",
        "ALPHA",
    };
    if (transport == DC_TRANSPORT_BLE) {
        return DC_FILTER_INTERNAL (userdata, bluetooth, 0, dc_match_name);
    }
    return 1;
}
```

→ **你的表在 BLE 里广播出来的名字应该是 `Crest-CR4`**（其他三个是同硬件的贴牌名）。这个可以作为自动识别的依据。

### 2.3 版本史

v0.8.0 新增 backend `excursion`，明确写了 “Deep Six Excursion, Crest CR-4, Genesis Centauri, Tusa TC1, Scorpena Alpha”。Subsurface 官方支持列表里也列了 “Crest CR-4”。

---

## 3. 设备协议细节（`src/deepsix_excursion.c`）

### 3.1 数据包格式

```
请求： [grp][cmd][dir][len][payload...][csum]
响应： [grp+1][cmd][dir][len][payload...][csum]

csum = (sum(前 4+len 字节) & 0xFF) ^ 0xFF
dir  : 0x00 = WRITE, 0x01 = READ
```

注意响应包的 group 是 **请求 group + 1**（`deepsix_excursion_transfer()` 里 `grp + 1`），这是最容易踩的坑。

### 3.2 命令表

| Group | 值 | Cmd | 值 | 说明 |
|---|---|---|---|---|
| INFO | 0xA0 | HARDWARE / SOFTWARE / SERIAL / LASTDIVE | 0x01/0x02/0x03/0x04 | 读硬件版本、固件版本、序列号、最后一条潜水索引 |
| SETTINGS | 0xB0 | DATE / TIME / STORE / **LOAD** | 0x01/0x03/0x27/0x28 | 时间同步；**`LOAD`(0x28) 是下载前必须的第一步** |
| DIVE | 0xC0 | HEADER / PROFILE / COUNT / INDEX_LAST / INDEX_FIRST / INDEX_PREVIOUS / INDEX_NEXT | 0x02/0x03/0x05/0x06/0x07/0x08/0x09 | 潜水头、剖面数据、条数、索引导航 |

### 3.3 下载流程（`deepsix_excursion_device_foreach`）

```
1. SETTINGS_LOAD (0xB0/0x28, WRITE)          ← 把设置载入内存，必须先做
2. INFO_HARDWARE / INFO_SOFTWARE / INFO_SERIAL
3. 固件判断：rsp_software[0..2] == "D01" && rsp_software[4] >= '6'  → fw6 新指令集
   - fw6:   DIVE_COUNT(0xC0/0x05) → 2 字节条数
            DIVE_INDEX_LAST(0xC0/0x06) → 4 字节索引
   - 旧版:  INFO_LASTDIVE(0xA0/0x04)
4. 循环每条潜水：
   a. DIVE_HEADER(0xC0/0x02, 传 2 字节 dive number)
      → header 长度：fw6 用 header[2]，旧版固定 156 字节
      → 指纹 fingerprint = header[12..17]（6 字节），用于增量下载去重
      → profile 长度 = uint32_le(header[8..11])
   b. DIVE_PROFILE(0xC0/0x03, 传 2 字节 number + 4 字节 offset) 循环取完 length 字节
      单包最大 255 字节 payload
   c. 拼成 header+profile 的完整 blob 交给 callback
5. 指纹匹配则 break（增量下载到此为止）
```

**指纹机制很好用**：只要保存上一次最后一条的 6 字节指纹，用 `dc_device_set_fingerprint()` 就能实现增量下载，不用每次全量拉。

### 3.4 超时/速率

超时 3000ms；MTU 决定单包吞吐。协议本身是严格的「一问一答」，并发无用，只能串行。

---

## 4. 能拿到的数据（`src/deepsix_excursion_parser.c`）

### 4.1 字段（`dc_parser_get_field`）

| DC_FIELD | 有 | 说明 |
|---|---|---|
| `DIVETIME` / `MAXDEPTH` / `AVGDEPTH` | ✅ | 潜水时长、最大深度、平均深度 |
| `GASMIX_COUNT` / `GASMIX` | ✅ | 最多 20 个气体（含 O2/He 百分比、用途 O2/Diluent/Sidemount） |
| `SALINITY` / `ATMOSPHERIC` | ✅ | 淡水/海水 + 密度、大气压 |
| `TEMPERATURE_SURFACE` / `TEMPERATURE_MINIMUM` | ✅ | 水面温度、最低温度 |
| `DIVEMODE` | ✅ | `OC`（开放回路）/ `GAUGE`（仪表）/ `FREEDIVE`（自由潜）/ `CCR`（闭路） |
| **`DECOMODEL`** | ✅ | 算法类型 = `DC_DECOMODEL_BUHLMANN` + **GF low/high**（若表上未开启 GF 则都是 100；若开了但值读不到则是 0） |

> `DECOMODEL` 这一项对你的任务 3 特别关键：你能知道**这条潜水当时用的是什么算法和保守度**，从而做「切换不同算法重算」的对比。

### 4.2 采样序列（`dc_parser_samples_foreach`）

| 采样类型 | 有 | 说明 |
|---|---|---|
| `DC_SAMPLE_TIME` + `DC_SAMPLE_DEPTH` | ✅ | 逐 `samplerate` 秒的深度剖面（分析的基础） |
| `DC_SAMPLE_TEMPERATURE` | ✅ | 温度 |
| `DC_SAMPLE_DECO` | ✅ | NDL / 安全停留 / 减压停留 / 深停（含 depth、time、tts） |
| `DC_SAMPLE_CNS` | ✅ | 中枢神经氧中毒百分比 |
| `DC_SAMPLE_GASMIX` | ✅ | 气体切换事件 |
| `DC_SAMPLE_SETPOINT` | ✅ | CCR 设定点 |
| `DC_SAMPLE_PPO2` | ✅ | 氧分压 |
| `DC_SAMPLE_EVENT` | ✅ | 报警事件：上升过快、越过天花板、ppO2 超限、超最大深度、超潜水时间、CNS 报警；以及 BOOKMARK / SURFACE / 安全停留（自愿/强制）、深停 |

**没有的**：气瓶压力（tank pressure）——CR-4 这一支的解析器没有 `DC_FIELD_TANK`/`DC_SAMPLE_PRESSURE`。如果你的 CR-4 带 AI 发射器，需要确认固件是否上报；否则耗气率（SAC/RMV）分析就做不了，只能靠手工录入。

---

## 5. 关键缺口：没有内置 BLE 传输层（必须自己补）

### 5.1 证据

- 全库只有 `src/ble.c`（97 行，纯粹是 UUID 字符串转换工具）和 `src/bluetooth.c`（571 行，**仅蓝牙经典 RFCOMM**，只支持 Win32 `ws2bth` 和 Linux BlueZ）。
- `NEWS` 里明确写：
  > “allow an application to provide an **application defined one**. This is needed to support **Bluetooth Low Energy (BLE), for which there is no built-in implementation available**.”
- `src/deepsix_excursion.c` 全程只调用 `dc_iostream_read/write/purge/sleep/configure`，**没有用任何 `DC_IOCTL_BLE_*`** —— 说明它只要求你提供一个「字节流」，GATT 的分包/合包/notify 全部由你的传输层负责。

### 5.2 正确接法

```c
/* 1. 你实现 BLE：扫描 → 连接 → 发现 service → 订阅 notify → 拿到字节 */
/* 2. 把这些字节封装成 dc_custom_cbs_t：read/write/purge/sleep/poll/ioctl/... */
dc_custom_open(&iostream, ctx, DC_TRANSPORT_BLE, &cbs, my_ble_ctx);

/* 3. 找到描述符 */
dc_descriptor_iterator(&iter, ctx);
while (dc_iterator_next(iter, &desc)) {
    if (!strcmp(dc_descriptor_get_vendor(desc), "Crest") &&
        !strcmp(dc_descriptor_get_product(desc), "CR-4")) break;
}

/* 4. 打开设备并下载 */
dc_device_open(&device, ctx, desc, iostream);
dc_device_set_fingerprint(device, last_fp, 6);       // 增量下载
dc_device_set_events(device, DC_EVENT_PROGRESS|DC_EVENT_DEVINFO, on_event, NULL);
dc_device_foreach(device, on_dive, NULL);            // 每条潜水回调一次 raw blob

/* 5. 解析 */
dc_parser_new2(&parser, ctx, desc, blob, size);
dc_parser_get_field(parser, DC_FIELD_DIVEMODE, 0, &mode);
dc_parser_samples_foreach(parser, on_sample, NULL);
```

`read` 回调必须是**阻塞**的（等 notify 到来，带超时返回 `DC_STATUS_TIMEOUT`），并且**一次返回一个完整的协议包**（因为 `deepsix_excursion_recv()` 假设一次 read 拿到整包）。这是实现时最容易出错的点——需要自己维护一个 rx 队列 + 条件变量。

### 5.3 GATT service UUID 未知 —— 这是当前唯一的技术盲点

Subsurface（`core/qt-ble.cpp`）维护了一份「已知串口类 GATT service」清单：

```
0000fefb-... Heinrichs-Weikamp   2456e1b9-... HW u-blox   544e326b-... Mares
98ae7120-... Suunto              cb3c4555-... Pelagic      ca7b0001-... Pelagic i330R
fdcdeaaa-... ScubaPro G2/G3      fe25c237-... Shearwater  1aa44039-... Perdix 3
0000fcef-... Divesoft            6e400001-b5a3... Cressi  6e400001-...dcca9e Nordic UART
00000001-8c3b-... Halcyon        84968ffe-... Seac
```

**CR-4 / Excursion 不在这份清单里** → Subsurface 是靠启发式兜底连上的：
> 「挑第一个 **非标准** service，且同时具备 notify（或 read）+ write 特性的」

所以多半是 Nordic UART 或某个厂商自定义 UUID。**必须实测确认**，我为此写好了探测脚本（见第 8 节）。

### 5.4 【2026-10-11 实机验证】盲点已消除 —— 全链路跑通 ✅

已在真实 Crest CR-4 上完成：BLE 连接 → 协议握手 → 下载 → 解析。结论如下，**后续传输层可直接硬编码**。

**① BLE 广播**

```
name    : Crest-CR4
address : XX:XX:XX:XX:XX:XX   (随机私有地址：首字节高两位为 01，即 0x40~0x7F)
adv     : service_uuids = ['0000ffc0-0000-1000-8000-00805f9b34fb',
                           '0000fff0-0000-1000-8000-00805f9b34fb']
```

注意：广播里声明的 `0xFFC0/0xFFF0` **和连接后枚举出来的 service 并不一致**（后者是 128-bit 的
`f000ffe0-ab12-45ec-84c8-46483f4626e9`）。所以**别拿广播 UUID 当依据**，仍以连接后的
GATT 枚举为准；广播 UUID 只能用来做设备识别。

**② GATT 结构（连接后枚举）**

| Service | Characteristic | 属性 |
|---|---|---|
| `00001801-...` | GAP | — |
| `0000180a-...` | Device Information | read |
| **`f000ffe0-ab12-45ec-84c8-46483f4626e9`** | `f000ffe1-ab12-45ec-84c8-46483f4626e9` | read / write / write-without-response / **notify** |
| 同上 | `f000ffe2-...` / `f000ffe3-...` | 同 ffe1 |

**实测 `ffe1` 就是数据通道**（收发同一条 characteristic，CCCD `0x2902` 已开）。
`ffe2/ffe3` 用途未验证（疑似第二通道/OTA），暂不使用。

**③ Windows 上连接失败的真凶：`address_type`**

首次连接报 `BleakError: Could not get GATT services: Unreachable`。原因是 CR-4 用的是
**随机地址**，而 Windows 蓝牙栈默认按 public 地址去连。指定 `address_type="random"` 后
一次就连上。写原生传输层时这是必带参数（Android 侧同样要在 `BluetoothDevice.connectGatt`
时走 `TRANSPORT_LE` + 随机地址处理）。

**④ 设备信息（协议握手结果）**

```
hardware : "GB-004"
software : "C01-5C"      -> fw6 = memcmp(sw,"D01",3)==0 && sw[4]>='6'  => False
serial   : "CBK20400042"
dives    : count=292  last_index=292
```

`fw6=False` → 走 **v0 布局**（headersize=156，`CMD_INFO_LASTDIVE`，dive 编号 `last - i`），
不是 v1/fw6 那套新指令集。

**⑤ 下载与解析验证**

```
dive#2: header=156B profile=2312B fp=140b160e3621  -> 2468 B
解析结果: 2020-11-22 14:54:33, 62 min, max 7.51 m, 32.0 C, 382 个采样点 (rc=DC_STATUS_SUCCESS)
```

`DC_SAMPLE_TIME` 单位是**毫秒**（不是秒），输出时要 /1000 —— 踩过一次坑。

**⑥ 不用装 autotools 也能编 libdivecomputer**

只编译解析相关的源文件 + 一个空 `config.h` 即可（详细命令见 `build/` 目录）：
`context.c array.c buffer.c checksum.c common.c datetime.c parser.c *_parser.c` + 少量桩函数。
不需要 libusb / bluez / hidapi / autoconf。**MinGW gcc 直接编过。**

---

## 6. 参考项目评估

### 6.1 `venkateshshukla/dc_import_android` —— ❌ 参考价值低，不要抄

- 2014 年 GSoC 项目，**最后一次提交 2014-06-24，已废弃 12 年**。
- 走的是 **USB OTG + libusb + libftdi**，跟你的 BLE 路线**完全无关**。
- README 自己承认三次实验两次失败（FTDI 读不出数据、`ENOTTY: Not a typewriter`），最后靠 hack libusb 才勉强在 OSTC3 上跑通。
- 唯一可借鉴的思路：**在 Java 侧拿权限/连接，把句柄传给 native 层** —— 这个「Java 管连接、C 管协议」的分工思想在 Android BLE 上依然成立。

### 6.2 `Antjac/DiveComputerSimulator` —— ✅ 对任务 3 极有价值

- 不是硬件模拟器，**不碰蓝牙**，是浏览器里的潜水电脑算法/界面模拟器（TypeScript + Vite + three.js，AGPL-3.0）。
- 模拟 18 款电脑，明确标注**哪些是公开算法（✓，直接用 ZHL-16C + GF 已公开参数）**、**哪些是近似（≈，RGBM / ZH-L16 ADT MB / Pelagic Z+ 等专有算法，基于 ZHL-16C+GF 加惩罚项校准）**。
- **对你的用处**：
  1. **现成的多算法实现参考** —— 特别是 Shearwater Perdix 2 / Peregrine TX、Garmin Mk3i、Suunto Nautic、Mares Quad Ci/Quad 2/Genius 这几个是**公开参数**，可以直接对齐。
  2. **风险判定的现成规则** —— 它的 Exercises 模块把「耗尽 NDL、上升过快、做/不做安全停留、超过停留深度、超过 MOD、重复潜水」都做成了可判定场景，等价于一套风险检测器。
  3. **连续潜水的定义** —— 它的 logbook 已经做了三分类：
     > `consecutive`（水面间隔 < 15 分钟）、`repetitive`（< 12 小时）、`single`（其他）
     这直接就是你要的**日志合并**判定依据，可以照搬阈值再做成可配置。
  4. 有 CLI：`npm run calib`（生成 NDL 表）、`npm run scenario`（在每台电脑上重放剖面）—— 可以拿来给你的算法引擎做**回归测试基准**。
- ⚠️ AGPL-3.0：如果只是参考算法/思路自己做实现没问题；**直接复制代码**到你的项目会传染 AGPL。

---

## 7. 准备工作清单（已完成 / 待你操作）

| # | 事项 | 状态 |
|---|---|---|
| 1 | 克隆 libdivecomputer 源码 | ✅ 已作为 submodule 放在 `third_party/libdivecomputer` |
| 2 | 确认 CR-4 支持方式、协议、命令表 | ✅ 见本报告第 2–4 节 |
| 3 | 确认「无内置 BLE」这个关键约束和补法 | ✅ 见第 5 节 |
| 4 | Python `bleak` 安装（Windows BLE 栈） | ✅ 已装在托管 venv |
| 5 | BLE 探测/下载原型脚本 | ✅ `dive/tools/cr4_probe.py`，已在本机跑通扫描 |
| 6 | **实测 CR-4 的 GATT UUID** | ⏳ **需要你把表开机后跑 `probe`** |
| 7 | 编译 libdivecomputer（后续做原生集成时） | ⏳ 本机有 MinGW gcc/g++，但缺 `make`/`cmake`；建议装 MSYS2 或 `pip install cmake` |

---

## 8. 下一步：你只需要做一件事

把 CR-4 开机（进入可配对/广播状态，别让官方 App 占着连接），然后在同一台机器上运行：

```bash
cd tools
python cr4_probe.py scan        # 确认能看到 "Crest-CR4" 及其 MAC
python cr4_probe.py probe       # dump 全部 GATT service / characteristic
python cr4_probe.py info        # 读固件版本、序列号、潜水条数
python cr4_probe.py download    # 抓全部潜水原始数据到 dive/raw/
```

- `probe` 的输出请发给我，我据此确定 GATT service / notify / write 三个 UUID，之后就能把传输层写成硬编码（不再靠启发式猜）。
- `download` 抓下来的 `.bin` 是 header+profile 的完整 blob，可以直接喂给 `dctool parse`，也就能在写 App 之前先验证解析器输出。

如果本机 pip 装包被代理挡住（你机器上 `pip config` 里有个失效的 `127.0.0.1:7890`），用这条：

```bash
pip install --isolated --proxy http://127.0.0.1:8517 bleak
```
