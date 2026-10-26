#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Crest CR-4 / Deep Six Excursion / Genesis Centauri / Scorpena Alpha
BLE GATT 探测 + 原始潜水数据下载原型。

背景
----
libdivecomputer 中这些设备共用一个后端：
    {"Crest", "CR-4", DC_FAMILY_DEEPSIX_EXCURSION, 0, DC_TRANSPORT_BLE, dc_filter_deepsix}
BLE 广播名匹配表（src/descriptor.c: dc_filter_deepsix）：
    "EXCURSION", "Crest-CR4", "CENTAURI", "ALPHA"

libdivecomputer 只实现了「设备协议层」（src/deepsix_excursion.c），
**没有内置 BLE 传输层**，必须由应用通过 dc_custom_open() 提供一个字节流。
本脚本就是用来先摸清这个字节流下面到底是哪个 GATT service / characteristic。

用法
----
    python cr4_probe.py scan                 # 扫描，列出所有 BLE 设备（CR-4 会附带打印广播 UUID）
    python cr4_probe.py scan --all-adv       # 打印所有设备的广播数据（service UUID / 厂商数据）
    python cr4_probe.py watch 60             # 持续监听 CR-4 广播，边跑边操作表，判断它何时可连接
    python cr4_probe.py probe                # 连上 CR-4，dump 全部 GATT service/characteristic
    python cr4_probe.py probe --address AA:BB:CC:DD:EE:FF --retries 5   # 直连 + 重试
    python cr4_probe.py info                 # 读硬件版本/固件版本/序列号/潜水数
    python cr4_probe.py download [--out DIR] # 下载全部潜水原始数据（.bin）
    python cr4_probe.py download --limit 3   # 只下最近 3 条

依赖：pip install bleak
"""

import argparse
import asyncio
import struct
import sys
import os
from datetime import datetime

try:
    from bleak import BleakScanner, BleakClient
except ImportError:
    sys.exit("缺少依赖：pip install bleak")

# ---------------------------------------------------------------- 常量

# libdivecomputer src/descriptor.c: dc_filter_deepsix()
CR4_NAMES = ("EXCURSION", "Crest-CR4", "CENTAURI", "ALPHA")

DIR_WRITE = 0x00
DIR_READ = 0x01

GRP_INFO = 0xA0
CMD_INFO_HARDWARE = 0x01
CMD_INFO_SOFTWARE = 0x02
CMD_INFO_SERIAL = 0x03
CMD_INFO_LASTDIVE = 0x04

GRP_SETTINGS = 0xB0
CMD_SETTINGS_DATE = 0x01
CMD_SETTINGS_TIME = 0x03
CMD_SETTINGS_STORE = 0x27
CMD_SETTINGS_LOAD = 0x28

GRP_DIVE = 0xC0
CMD_DIVE_HEADER = 0x02
CMD_DIVE_PROFILE = 0x03
CMD_DIVE_COUNT = 0x05
CMD_DIVE_INDEX_LAST = 0x06
CMD_DIVE_INDEX_FIRST = 0x07
CMD_DIVE_INDEX_PREVIOUS = 0x08
CMD_DIVE_INDEX_NEXT = 0x09

HEADERSIZE_MIN = 128
HEADERSIZE_V0 = 156
FP_SIZE = 6
FP_OFFSET = 12

# 2026-10-11 实机抓包确认：Crest-CR4 广播里带的两个 16-bit 私有 service。
# 0xFFC0 / 0xFFF0 —— 一个是数据通道(notify+write)，一个多半是设备信息/OTA。
# 连上后 select_service() 会优先选这两个，并在输出里标出到底是哪个。
CR4_SERVICE_UUIDS = [
    "0000ffc0-0000-1000-8000-00805f9b34fb",
    "0000fff0-0000-1000-8000-00805f9b34fb",
]

# Subsurface core/qt-ble.cpp 里登记过的「已知串口类 GATT service」，
# 按优先级排列。CR-4 不在此列，所以现实里多半走下面的启发式兜底。
KNOWN_SERIAL_SERVICES = [
    "0000fefb-0000-1000-8000-00805f9b34fb",  # Heinrichs-Weikamp (Telit/Stollmann)
    "2456e1b9-26e2-8f83-e744-f34f01e9d701",  # Heinrichs-Weikamp (U-Blox)
    "544e326b-5b72-c6b0-1c46-41c1bc448118",  # Mares BlueLink Pro
    "98ae7120-e62e-11e3-badd-0002a5d5c51b",  # Suunto
    "cb3c4555-d670-4670-bc20-b61dbc851e9a",  # Pelagic
    "ca7b0001-f785-4c38-b599-c7c5fbadb034",  # Pelagic i330R
    "fdcdeaaa-295d-470e-bf15-04217b7aa0a0",  # ScubaPro G2/G3
    "fe25c237-0ece-443c-b0aa-e02033e7029d",  # Shearwater
    "1aa44039-1667-4b29-87cc-dfecaaf31d97",  # Shearwater Perdix 3
    "0000fcef-0000-1000-8000-00805f9b34fb",  # Divesoft
    "6e400001-b5a3-f393-e0a9-e50e24dc10b8",  # Cressi
    "6e400001-b5a3-f393-e0a9-e50e24dcca9e",  # Nordic Semi UART
    "00000001-8c3b-4f2c-a59e-8c08224f3253",  # Halcyon Symbios
    "84968ffe-d26d-478a-b953-5010bcf58bca",  # Seac
]
KNOWN_UPGRADE_SERVICES = [
    "00001530-1212-efde-1523-785feabcd123",
    "9e5d1e47-5c13-43a0-8635-82ad38a1386f",
    "a86abc2d-d44c-442e-99f7-80059a873e36",
]


def is_standard_uuid(u: str) -> bool:
    """16-bit Bluetooth SIG 标准 UUID 形如 0000xxxx-0000-1000-8000-00805f9b34fb"""
    u = u.lower()
    return u.endswith("-0000-1000-8000-00805f9b34fb") and u.startswith("0000")


# ---------------------------------------------------------------- 协议封包

def build_packet(grp: int, cmd: int, direction: int, data: bytes = b"") -> bytes:
    """请求包: [grp][cmd][dir][len][data...][checksum]
    checksum = (sum(前 4+len 字节) & 0xFF) ^ 0xFF    —— 见 src/deepsix_excursion.c"""
    pkt = bytearray([grp, cmd, direction, len(data)])
    pkt += data
    csum = (sum(pkt) & 0xFF) ^ 0xFF
    pkt.append(csum)
    return bytes(pkt)


def parse_packet(raw: bytes, expect_grp: int, expect_cmd: int, expect_dir: int):
    """返回 (payload, error)。注意响应包 grp = 请求 grp + 1。"""
    if len(raw) < 4:
        return None, f"包太短 ({len(raw)} 字节): {raw.hex()}"
    grp, cmd, direction, ln = raw[0], raw[1], raw[2], raw[3]
    if grp != expect_grp + 1 or cmd != expect_cmd or direction != expect_dir:
        return None, (f"包头不符: 期望 grp=0x{expect_grp+1:02x} cmd=0x{expect_cmd:02x} "
                      f"dir=0x{expect_dir:02x}，实际 grp=0x{grp:02x} cmd=0x{cmd:02x} dir=0x{direction:02x} "
                      f"raw={raw.hex()}")
    if len(raw) < 4 + ln + 1:
        return None, f"数据不完整: 需要 {4+ln+1} 字节，实际 {len(raw)}"
    payload = raw[4:4 + ln]
    csum = (sum(raw[:4 + ln]) & 0xFF) ^ 0xFF
    if raw[4 + ln] != csum:
        return None, f"校验和错误: 期望 0x{csum:02x}，实际 0x{raw[4+ln]:02x}"
    return payload, None


# ---------------------------------------------------------------- BLE 连接封装

class Cr4Link:
    """把 BLE GATT 伪装成一个「按包收发」的字节流，供 deepsix_excursion 协议使用。"""

    def __init__(self, client: BleakClient):
        self.client = client
        self.rx_queue: asyncio.Queue[bytes] = asyncio.Queue()
        self.write_char = None
        self.notify_char = None
        self.service_uuid = None

    def _on_notify(self, _char, data: bytearray):
        self.rx_queue.put_nowait(bytes(data))

    async def select_service(self, verbose=True):
        """挑一个「像串口」的 service：有 notify(或 read) + write 特性。"""
        svcs = list(self.client.services)
        if verbose:
            print("\n=== GATT 服务清单 ===")
        candidates = []
        for s in svcs:
            uuid = s.uuid.lower()
            if verbose:
                print(f"\n[service] {uuid}  ({s.description or '?'})")
            if uuid in KNOWN_UPGRADE_SERVICES:
                if verbose:
                    print("   -> 已知固件升级服务，跳过")
                continue
            chars = list(s.characteristics)
            notify_c = None
            write_c = None
            for c in chars:
                props = c.properties
                if verbose:
                    print(f"   [char] {c.uuid.lower()}  props={props}")
                    for d in c.descriptors:
                        print(f"        [desc] {d.uuid}")
                if "notify" in props or "indicate" in props:
                    notify_c = notify_c or c
                if "write" in props or "write-without-response" in props:
                    write_c = write_c or c
            if notify_c is not None and write_c is not None:
                is_cr4 = uuid in CR4_SERVICE_UUIDS          # 实机确认过的，最高优先
                known = uuid in KNOWN_SERIAL_SERVICES
                standard = is_standard_uuid(uuid)
                score = (0 if is_cr4 else (1 if known else 2), 0 if not standard else 1)
                candidates.append((score, uuid, notify_c, write_c))

        if not candidates:
            raise RuntimeError("没找到同时具备 notify + write 特性的 GATT service")

        candidates.sort(key=lambda x: x[0])
        _, uuid, notify_c, write_c = candidates[0]
        self.service_uuid = uuid
        self.notify_char = notify_c
        self.write_char = write_c
        if verbose:
            print(f"\n>>> 选用 service {uuid}")
            print(f"    notify/indicate 特性: {notify_c.uuid}")
            print(f"    write 特性         : {write_c.uuid}")
        await self.client.start_notify(notify_c.uuid, self._on_notify)

    async def send(self, pkt: bytes):
        """写一包。BLE 有 MTU 上限，默认 20~517 字节；协议单包最大 4+255+1=260。"""
        await self.client.write_gatt_char(
            self.write_char.uuid, pkt,
            response=("write" in self.write_char.properties),
        )

    async def recv(self, timeout=5.0):
        try:
            return await asyncio.wait_for(self.rx_queue.get(), timeout)
        except asyncio.TimeoutError:
            return None

    async def purge(self):
        while not self.rx_queue.empty():
            try:
                self.rx_queue.get_nowait()
            except asyncio.QueueEmpty:
                break

    async def transfer(self, grp, cmd, direction, data=b"", timeout=5.0, retries=2):
        """发一包、等对应的响应包。忽略掉任何夹在中间的非预期包。"""
        for attempt in range(retries + 1):
            await self.purge()
            await self.send(build_packet(grp, cmd, direction, data))
            deadline = asyncio.get_running_loop().time() + timeout
            while True:
                remain = deadline - asyncio.get_running_loop().time()
                if remain <= 0:
                    break
                raw = await self.recv(remain)
                if raw is None:
                    break
                payload, err = parse_packet(raw, grp, cmd, direction)
                if err is None:
                    return payload
                print(f"   (忽略/异常包 #{attempt}: {err})")
            if attempt < retries:
                print(f"   ...重试 {grp:02x}/{cmd:02x}")
        raise TimeoutError(f"命令 grp=0x{grp:02x} cmd=0x{cmd:02x} 超时或无有效响应")


# ---------------------------------------------------------------- 子命令

def _dev_rssi(dev, adv):
    """bleak 各版本 rssi 位置不一样（BLEDevice.rssi -> AdvertisementData.rssi），统一兜底。"""
    if adv is not None:
        r = getattr(adv, "rssi", None)
        if r is not None:
            return r
    r = getattr(dev, "rssi", None)
    if r is None:
        md = getattr(dev, "metadata", {}) or {}
        r = md.get("rssi")
    return r


def _print_adv(adv, indent="      "):
    if adv is None:
        return
    if getattr(adv, "local_name", None):
        print(f"{indent}local_name        : {adv.local_name}")
    uuids = list(getattr(adv, "service_uuids", []) or [])
    if uuids:
        print(f"{indent}service_uuids     : {uuids}      <<<< 重点")
    md = getattr(adv, "manufacturer_data", {}) or {}
    for k, v in md.items():
        print(f"{indent}manufacturer_data : 0x{k:04x} = {bytes(v).hex()}")
    sd = getattr(adv, "service_data", {}) or {}
    for k, v in sd.items():
        print(f"{indent}service_data      : {k} = {bytes(v).hex()}")


async def _discover(timeout):
    """返回 [(BLEDevice, AdvertisementData)]。用主动扫描，才能拿到 scan response 里的 UUID。"""
    try:
        res = await BleakScanner.discover(timeout=timeout, return_adv=True, scanning_mode="active")
    except TypeError:
        # 老版本 bleak 不支持 return_adv / scanning_mode
        res = await BleakScanner.discover(timeout=timeout)
        return [(d, None) for d in res]
    return list(res.values())


async def find_device(timeout=12.0, show_adv_all=False):
    print(f"扫描 BLE 设备 {timeout:.0f} 秒 ...")
    pairs = await _discover(timeout)
    matches = []
    for dev, adv in pairs:
        nm = dev.name or (getattr(adv, "local_name", None) if adv else "") or ""
        tag = ""
        if any(n.lower() in nm.lower() for n in CR4_NAMES):
            tag = "   <<<< 疑似 Crest CR-4 家族"
            matches.append((dev, adv))
        rssi = _dev_rssi(dev, adv)
        print(f"  {dev.address}  rssi={rssi if rssi is not None else '?':>4}  {nm}{tag}")
        if tag or show_adv_all:
            _print_adv(adv)
    if not pairs:
        print("  （一个设备都没扫到，检查：蓝牙开关 / 电脑表是否在广播 / 是否已连上官方 App）")
    return matches


async def cmd_scan(args):
    await find_device(args.timeout, show_adv_all=args.all_adv)


async def cmd_watch(args):
    """持续监听 CR-4 的广播包：判断它是否一直可连接、什么时候停止广播。"""
    seen = {}

    def cb(dev, adv):
        nm = dev.name or getattr(adv, "local_name", "") or ""
        if not any(n.lower() in nm.lower() for n in CR4_NAMES):
            return
        key = (dev.address, tuple(getattr(adv, "service_uuids", []) or []),
               tuple(sorted((getattr(adv, "manufacturer_data", {}) or {}).items())))
        now = datetime.now().strftime("%H:%M:%S")
        if key in seen and (datetime.now() - seen[key]).total_seconds() < 3:
            return
        seen[key] = datetime.now()
        print(f"  [{now}] {dev.address} rssi={_dev_rssi(dev, adv)} {nm}")
        _print_adv(adv)

    print(f"监听 CR-4 广播 {args.seconds} 秒（Ctrl+C 提前结束）...")
    print("提示：现在去操作电脑表（进蓝牙/同步菜单、按键唤醒），看输出什么时候出现/消失。")
    async with BleakScanner(detection_callback=cb, scanning_mode="active"):
        await asyncio.sleep(args.seconds)
    print("监听结束。若整段都没有输出，说明表没在广播。")


UNREACHABLE_HINT = """
[连接失败排查清单]
  1. 电脑表是否在「蓝牙/同步」模式并保持唤醒？很多表只在同步菜单里才可被连接，
     息屏后立刻停止广播 -> Windows 报 Unreachable。
  2. 手机上的官方 App 是否已经连着这块表？BLE 从设备一般只能连一个主机，
     先关掉手机 App（或关手机蓝牙）再试。
  3. Windows 是否需要先配对：设置 -> 蓝牙和设备 -> 添加设备 -> 配一次 Crest-CR4，
     配对成功后再跑 probe。
  4. 距离/遮挡：放到离电脑 1 米内再试；RSSI <= -85 时很容易连不上。
  5. 还不行就跑 `python cr4_probe.py watch 60`，边跑边操作表，看广播包什么时候出现。
"""


async def _connect(args):
    dev = None
    if args.address:
        print(f"扫描定位 {args.address} ...")
        for d, adv in await _discover(args.timeout):
            if d.address.upper() == args.address.upper():
                dev, dev_adv = d, adv
                break
        if dev is None:
            print(f"  本次扫描没看到 {args.address}，仍尝试直连。")
    else:
        matches = await find_device(args.timeout)
        if not matches:
            sys.exit("未发现 CR-4 家族设备。请确认电脑表处于广播状态，或用 --address 指定。")
        if len(matches) > 1:
            print("发现多个候选，取第一个。可用 --address 指定。")
        dev, dev_adv = matches[0]

    target = dev if dev is not None else args.address
    name = getattr(dev, "name", None) or ""
    print(f"\n连接 {getattr(dev, 'address', args.address)} ({name}) ...")

    # Windows(WinRT) 下逐个试不同连接参数：随机地址 / public 地址 / 不用 GATT 缓存。
    # CR-4 广播出来的是随机地址(首字节高两位为 01)，默认按 public 连会直接 Unreachable。
    variants = []
    if sys.platform == "win32" and hasattr(BleakClient, "pair"):
        order = ["random", "public"] if args.address_type == "random" else ["public", "random"]
        for at in order:
            variants.append({"address_type": at, "use_cached_services": False})
            variants.append({"address_type": at})
    else:
        variants.append({})

    last_err = None
    for attempt in range(max(1, args.retries)):
        v = variants[attempt % len(variants)]
        extra = {"winrt": dict(v)} if v else {}
        if v:
            print(f"  参数: address_type={v.get('address_type')} "
                  f"use_cached_services={v.get('use_cached_services')}")
        client = BleakClient(target, timeout=30.0, **extra)
        try:
            await client.connect()
            print(f"已连接（第 {attempt + 1} 次尝试）。MTU =", client.mtu_size)
            link = Cr4Link(client)
            await link.select_service()
            await asyncio.sleep(0.3)
            await link.purge()
            return client, link
        except Exception as e:
            last_err = e
            print(f"  第 {attempt + 1}/{args.retries} 次连接失败: {type(e).__name__}: {e}")

            # Windows 专用补救：connect() 在 GATT 发现阶段失败时，底层 requester 其实已建好，
            # 此时还能发起一次系统配对（Just Works），配对成功往往第二次就能连上。
            if args.pair and sys.platform == "win32" and hasattr(client, "pair"):
                try:
                    print("  -> 尝试 Windows 侧配对（Just Works）...")
                    ok = await client.pair()
                    print(f"  -> 配对结果: {ok}")
                except Exception as pe:
                    print(f"  -> 配对失败: {type(pe).__name__}: {pe}")

            try:
                await client.disconnect()
            except Exception:
                pass
            if attempt + 1 < args.retries:
                print("  2 秒后重试（请趁这个时间让表保持唤醒/进同步模式）...")
                await asyncio.sleep(2.0)

    print(UNREACHABLE_HINT)
    raise last_err


async def cmd_probe(args):
    client, link = await _connect(args)
    print("\n探测完成。可以把上面打印的 service/characteristic UUID 记录下来，")
    print("后续写原生传输层时直接硬编码，避免每次都靠启发式猜。")
    await client.disconnect()


async def cmd_info(args):
    client, link = await _connect(args)

    # 载入设置到内存（协议要求的第一步，见 deepsix_excursion_device_foreach）
    await link.transfer(GRP_SETTINGS, CMD_SETTINGS_LOAD, DIR_WRITE, b"")
    print("settings load OK")

    hw = await link.transfer(GRP_INFO, CMD_INFO_HARDWARE, DIR_READ, b"")
    print(f"hardware : {hw.hex()}   ascii={''.join(chr(b) if 32 <= b < 127 else '.' for b in hw)}")

    sw = await link.transfer(GRP_INFO, CMD_INFO_SOFTWARE, DIR_READ, b"")
    print(f"software : {sw.hex()}   ascii={''.join(chr(b) if 32 <= b < 127 else '.' for b in sw)}")
    fw_ver = struct.unpack(">H", sw[4:6])[0] if len(sw) >= 6 else 0
    fw6 = sw[:3] == b"D01" and len(sw) > 4 and sw[4] >= ord("6")
    print(f"firmware : 0x{fw_ver:04x}  ({fw_ver:04x})   fw6(新指令集)={fw6}")

    ser = await link.transfer(GRP_INFO, CMD_INFO_SERIAL, DIR_READ, b"")
    print(f"serial   : {ser.hex()}   ascii={''.join(chr(b) if 32 <= b < 127 else '.' for b in ser)}")

    if fw6:
        ndiv_raw = await link.transfer(GRP_DIVE, CMD_DIVE_COUNT, DIR_READ, b"")
        last_raw = await link.transfer(GRP_DIVE, CMD_DIVE_INDEX_LAST, DIR_READ, b"")
        ndives = struct.unpack("<H", ndiv_raw[:2])[0]
        last = struct.unpack("<H", last_raw[:2])[0]
    else:
        last_raw = await link.transfer(GRP_INFO, CMD_INFO_LASTDIVE, DIR_READ, b"\x00\x00")
        ndives = last = struct.unpack("<H", last_raw[:2])[0]
    print(f"dives    : count={ndives}  last_index={last}")

    await client.disconnect()


async def cmd_download(args):
    client, link = await _connect(args)
    out = args.out or os.path.join(os.path.dirname(__file__), "..", "raw")
    out = os.path.abspath(out)
    os.makedirs(out, exist_ok=True)

    await link.transfer(GRP_SETTINGS, CMD_SETTINGS_LOAD, DIR_WRITE, b"")
    sw = await link.transfer(GRP_INFO, CMD_INFO_SOFTWARE, DIR_READ, b"")
    fw6 = sw[:3] == b"D01" and len(sw) > 4 and sw[4] >= ord("6")
    print(f"firmware={sw.hex()} fw6={fw6}")

    if fw6:
        ndives = struct.unpack("<H", (await link.transfer(GRP_DIVE, CMD_DIVE_COUNT, DIR_READ, b""))[:2])[0]
        last = struct.unpack("<H", (await link.transfer(GRP_DIVE, CMD_DIVE_INDEX_LAST, DIR_READ, b""))[:2])[0]
    else:
        ndives = last = struct.unpack("<H", (await link.transfer(GRP_INFO, CMD_INFO_LASTDIVE, DIR_READ, b"\x00\x00"))[:2])[0]

    # 注意：last 是「最新一条」的编号，limit 只限制条数，不能拿它当编号用，
    # 否则 --limit 2 会去抓最旧的 #1 #2。
    count = min(ndives, args.limit) if args.limit else ndives
    print(f"共 {ndives} 条潜水（last={last}），本次下载最新 {count} 条 -> {out}")

    number = last
    saved = []
    for i in range(count):
        if fw6:
            if i > 0:
                cmd = struct.pack("<I", number)
                rsp = await link.transfer(GRP_DIVE, CMD_DIVE_INDEX_PREVIOUS, DIR_READ, cmd)
                number = struct.unpack("<I", rsp[:4])[0]
        else:
            number = last - i

        header = await link.transfer(GRP_DIVE, CMD_DIVE_HEADER, DIR_READ, struct.pack("<H", number))
        if len(header) < HEADERSIZE_MIN:
            print(f"  [{i}] header 太短 ({len(header)})，跳过")
            continue
        expected = header[2] if fw6 else HEADERSIZE_V0
        if len(header) != expected:
            print(f"  [{i}] header 长度不符: {len(header)} != {expected}，仍继续")

        length = struct.unpack("<I", header[8:12])[0]
        fp = header[FP_OFFSET:FP_OFFSET + FP_SIZE]
        print(f"  [{i}] dive#{number} header={len(header)}B profile={length}B fp={fp.hex()}")

        buf = bytearray(header)
        offset = 0
        while offset < length:
            chunk = await link.transfer(GRP_DIVE, CMD_DIVE_PROFILE, DIR_READ,
                                        struct.pack("<H", number) + struct.pack("<I", offset))
            n = min(len(chunk), length - offset)
            buf += chunk[:n]
            offset += n

        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
        path = os.path.join(out, f"cr4_dive{number:05d}_{ts}.bin")
        with open(path, "wb") as f:
            f.write(buf)
        saved.append(path)
        print(f"      -> {path} ({len(buf)} 字节)")

    print(f"\n完成，共保存 {len(saved)} 条。")
    print("提示：这些 .bin 可以直接喂给 libdivecomputer 的 dctool：")
    print("      dctool parse -d 'Crest CR-4' <file.bin>")
    await client.disconnect()


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description="Crest CR-4 BLE 探测与数据抓取原型")
    ap.add_argument("cmd", choices=["scan", "watch", "probe", "info", "download"])
    ap.add_argument("seconds", nargs="?", type=float, default=60.0, help="watch 监听时长（秒）")
    ap.add_argument("--address", help="直接指定 BLE MAC 地址（如 AA:BB:CC:DD:EE:FF）")
    ap.add_argument("--timeout", type=float, default=12.0, help="扫描时长（秒）")
    ap.add_argument("--retries", type=int, default=3, help="连接重试次数")
    ap.add_argument("--no-pair", dest="pair", action="store_false",
                    help="连接失败时不要尝试系统配对（默认会自动尝试一次）")
    ap.add_argument("--address-type", choices=["random", "public"], default="random",
                    help="BLE 地址类型，默认 random（CR-4 广播的是随机地址）")
    ap.add_argument("--all-adv", action="store_true", help="scan 时打印所有设备的广播数据")
    ap.add_argument("--limit", type=int, help="download 时限制条数")
    ap.add_argument("--out", help="download 输出目录")
    args = ap.parse_args()

    fn = {"scan": cmd_scan, "watch": cmd_watch,
          "probe": cmd_probe, "info": cmd_info, "download": cmd_download}[args.cmd]
    try:
        asyncio.run(fn(args))
    except KeyboardInterrupt:
        print("\n已取消")
    except Exception as e:
        print(f"\n[失败] {type(e).__name__}: {e}")
        raise


if __name__ == "__main__":
    main()
