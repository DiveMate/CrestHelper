#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
通用潜水电脑表 BLE 工具：扫描 / 探测 / 读信息 / 下载。

和 cr4_probe.py 的区别
----------------------
cr4_probe.py 是「单表一次性调试脚本」；dctool.py 是「多表可复用工具」：

  1. 不写死任何 MAC —— 永远按广播名/UUID 现扫，--address 只是加速用。
  2. 支持 --all-devices：一次把周围所有同款/同家族表全下完，按序列号分目录。
  3. 有设备缓存 devices.json：第一次探测出的 service/characteristic 会记下来，
     以后跳过启发式猜测，连得更快更稳。
  4. 支持断点续传：按 header 里的 6 字节指纹去重，已下过的自动跳过。
  5. 设备表可扩展：在 DEVICE_DB 里加一行就能支持新贴牌/新协议。

用法
----
    python dctool.py scan                      # 扫描，列出所有疑似潜水电脑表
    python dctool.py scan --match Crest        # 只看名字含 Crest 的
    python dctool.py probe --all-devices       # 探测所有候选，写入设备缓存
    python dctool.py info  --address AA:BB:CC:... # 读单表信息
    python dctool.py download --all-devices    # 全部下载（断点续传）
    python dctool.py download --address AA:BB:CC:DD:EE:FF --limit 5

依赖：pip install bleak
"""

import argparse
import asyncio
import json
import os
import struct
import sys
from datetime import datetime

# 复用 cr4_probe.py 里的协议层（封包/解包/链路），该文件 import 时无副作用。
from cr4_probe import (
    Cr4Link, build_packet, parse_packet,
    GRP_INFO, CMD_INFO_HARDWARE, CMD_INFO_SOFTWARE, CMD_INFO_SERIAL, CMD_INFO_LASTDIVE,
    GRP_SETTINGS, CMD_SETTINGS_LOAD,
    GRP_DIVE, CMD_DIVE_HEADER, CMD_DIVE_PROFILE, CMD_DIVE_COUNT,
    CMD_DIVE_INDEX_LAST, CMD_DIVE_INDEX_PREVIOUS,
    DIR_READ, DIR_WRITE,
    HEADERSIZE_MIN, HEADERSIZE_V0, FP_SIZE, FP_OFFSET,
    _discover, _print_adv, _dev_rssi,
)

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE_PATH = os.path.join(HERE, "devices.json")
DEFAULT_OUT = os.path.abspath(os.path.join(HERE, "..", "raw"))

# ---------------------------------------------------------------- 设备数据库
# 新增一台表 = 加一行。name_keywords 用于广播名匹配；protocol 决定用哪套命令。
DEVICE_DB = [
    {
        "id": "deepsix_excursion",
        "protocol": "deepsix",
        "desc": "Deep Six Excursion 贴牌家族（同一套硬件）",
        "name_keywords": ["EXCURSION", "Crest-CR4", "CENTAURI", "ALPHA", "Crest-CR"],
        "models": ["Crest CR-4", "Deep Six Excursion", "Genesis Centauri",
                   "Tusa TC1", "Scorpena Alpha"],
    },
    # 以后接 Shearwater / Suunto / Mares 等，在这里继续加：
    # {"id": "shearwater", "protocol": "shearwater", "name_keywords": ["Shearwater", "Perdix"], ...}
]


def match_device(name: str, extra_keywords=None):
    """按广播名判断属于 DEVICE_DB 里的哪一类。返回 (entry, keyword) 或 (None, None)。"""
    nm = (name or "").lower()
    for entry in DEVICE_DB:
        for kw in entry["name_keywords"]:
            if kw.lower() in nm:
                return entry, kw
    if extra_keywords:
        for kw in extra_keywords:
            if kw.lower() in nm:
                return None, kw          # 用户自定义匹配，协议未知
    return None, None


# ---------------------------------------------------------------- 设备缓存

def load_cache():
    if os.path.exists(CACHE_PATH):
        try:
            with open(CACHE_PATH, "r", encoding="utf8") as f:
                return json.load(f)
        except Exception:
            return {}
    return {}


def save_cache(cache):
    with open(CACHE_PATH, "w", encoding="utf8") as f:
        json.dump(cache, f, indent=2, ensure_ascii=False)


# ---------------------------------------------------------------- 扫描 / 连接

async def scan_candidates(timeout=12.0, match=None, show_adv=False):
    """返回 [(BLEDevice, AdvertisementData, entry, keyword)]"""
    pairs = await _discover(timeout)
    found = []
    print(f"扫描 BLE 设备 {timeout:.0f} 秒 ...")
    for dev, adv in pairs:
        nm = dev.name or (getattr(adv, "local_name", None) if adv else "") or ""
        entry, kw = match_device(nm, [match] if match else None)
        rssi = _dev_rssi(dev, adv)
        if entry:
            tag = f"   <<<< {entry['id']}  匹配名: {kw}"
        elif kw:
            tag = f"   <<<< 用户匹配: {kw}（协议未知）"
        else:
            tag = ""
        if match and not tag:
            continue
        print(f"  {dev.address}  rssi={rssi if rssi is not None else '?':>4}  {nm}{tag}")
        if tag and show_adv:
            _print_adv(adv)
        if tag:
            found.append((dev, adv, entry, kw))
    if not found:
        print("  没有匹配的潜水电脑表。可用 --match 放宽，或先 scan 看看周围有什么。")
    return found


def _connect_variants(address_type):
    """Windows 下逐个试：随机/public 地址 × 不用/用 GATT 缓存。"""
    from bleak import BleakClient
    if sys.platform != "win32" or not hasattr(BleakClient, "pair"):
        return [{}]
    order = ["random", "public"] if address_type == "random" else ["public", "random"]
    out = []
    for at in order:
        out.append({"winrt": {"address_type": at, "use_cached_services": False}})
        out.append({"winrt": {"address_type": at}})
    return out


async def connect_one(dev, args, cache=None):
    """连接 + 选通道。优先用缓存里的 service/char，失败再退回启发式。"""
    from bleak import BleakClient
    addr = dev.address
    nm = dev.name or ""
    cached = (cache or {}).get(addr, {})
    if cached:
        print(f"  [{addr}] 用缓存: service={cached.get('service', '?')[:8]}... "
              f"char={cached.get('notify', '?')[:8]}... addr_type={cached.get('address_type')}")

    at = cached.get("address_type", getattr(args, "address_type", "random"))
    variants = _connect_variants(at)
    last_err = None

    for i, extra in enumerate(variants):
        print(f"  [{addr}] 连接尝试 {i + 1}/{len(variants)} {extra or ''}")
        client = BleakClient(addr if dev is None else dev, timeout=30.0, **extra)
        try:
            await client.connect()
            print(f"  [{addr}] 已连接，MTU={client.mtu_size}")
            link = Cr4Link(client)

            # 有缓存就直接用，不再做启发式
            if cached.get("service") and cached.get("notify") and cached.get("write"):
                try:
                    link.service_uuid = cached["service"]
                    link.notify_char = cached["notify"]
                    link.write_char = cached["write"]
                    await client.start_notify(cached["notify"], link._on_notify)
                    print(f"  [{addr}] 已按缓存开启 notify")
                except Exception as e:
                    print(f"  [{addr}] 缓存失效({type(e).__name__})，退回枚举")
                    await link.select_service(verbose=True)
            else:
                await link.select_service(verbose=True)

            await asyncio.sleep(0.3)
            await link.purge()
            used_at = (extra.get("winrt", {}) or {}).get("address_type", at)
            return client, link, used_at
        except Exception as e:
            last_err = e
            print(f"  [{addr}] 失败: {type(e).__name__}: {e}")
            if args.pair and sys.platform == "win32" and hasattr(client, "pair"):
                try:
                    print(f"  [{addr}] 尝试系统配对 ...")
                    print(f"  [{addr}] 配对结果: {await client.pair()}")
                except Exception as pe:
                    print(f"  [{addr}] 配对失败: {type(pe).__name__}: {pe}")
            try:
                await client.disconnect()
            except Exception:
                pass
            await asyncio.sleep(1.5)
    raise last_err


# ---------------------------------------------------------------- 协议：deepsix

async def read_info(link):
    await link.transfer(GRP_SETTINGS, CMD_SETTINGS_LOAD, DIR_WRITE, b"")
    hw = await link.transfer(GRP_INFO, CMD_INFO_HARDWARE, DIR_READ, b"")
    sw = await link.transfer(GRP_INFO, CMD_INFO_SOFTWARE, DIR_READ, b"")
    ser = await link.transfer(GRP_INFO, CMD_INFO_SERIAL, DIR_READ, b"")
    fw6 = sw[:3] == b"D01" and len(sw) > 4 and sw[4] >= ord("6")
    if fw6:
        ndives = struct.unpack("<H", (await link.transfer(GRP_DIVE, CMD_DIVE_COUNT, DIR_READ, b""))[:2])[0]
        last = struct.unpack("<H", (await link.transfer(GRP_DIVE, CMD_DIVE_INDEX_LAST, DIR_READ, b""))[:2])[0]
    else:
        ndives = last = struct.unpack("<H", (await link.transfer(GRP_INFO, CMD_INFO_LASTDIVE, DIR_READ, b"\x00\x00"))[:2])[0]
    return {
        "hardware": hw.decode("ascii", "ignore"),
        "software": sw.decode("ascii", "ignore"),
        "serial": ser.decode("ascii", "ignore").strip(),
        "fw6": fw6, "ndives": ndives, "last": last,
    }


def existing_fingerprints(outdir):
    """扫描输出目录，收集已下载过的指纹，用于断点续传。"""
    fps = set()
    if not os.path.isdir(outdir):
        return fps
    for fn in os.listdir(outdir):
        if not fn.endswith(".bin"):
            continue
        try:
            with open(os.path.join(outdir, fn), "rb") as f:
                head = f.read(FP_OFFSET + FP_SIZE)
            if len(head) >= FP_OFFSET + FP_SIZE:
                fps.add(head[FP_OFFSET:FP_OFFSET + FP_SIZE].hex())
        except Exception:
            pass
    return fps


async def download_one(client, link, info, outdir, limit=None, resume=True, name=""):
    os.makedirs(outdir, exist_ok=True)
    fw6, last, total = info["fw6"], info["last"], info["ndives"]
    count = min(total, limit) if limit else total
    known = existing_fingerprints(outdir) if resume else set()
    print(f"  [{name}] 共 {total} 条（last={last}），本次处理 {count} 条，"
          f"已有 {len(known)} 个指纹将跳过 -> {outdir}")

    number, saved, skipped = last, 0, 0
    for i in range(count):
        if fw6:
            if i > 0:
                rsp = await link.transfer(GRP_DIVE, CMD_DIVE_INDEX_PREVIOUS, DIR_READ,
                                          struct.pack("<I", number))
                number = struct.unpack("<I", rsp[:4])[0]
        else:
            number = last - i

        header = await link.transfer(GRP_DIVE, CMD_DIVE_HEADER, DIR_READ,
                                     struct.pack("<H", number))
        if len(header) < HEADERSIZE_MIN:
            print(f"    [{i}] dive#{number} header 太短({len(header)})，跳过")
            continue
        fp = header[FP_OFFSET:FP_OFFSET + FP_SIZE].hex()
        if fp in known:
            skipped += 1
            continue
        expected = header[2] if fw6 else HEADERSIZE_V0
        if len(header) != expected:
            print(f"    [{i}] header 长度 {len(header)} != {expected}（仍继续）")
        length = struct.unpack("<I", header[8:12])[0]

        buf = bytearray(header)
        offset = 0
        while offset < length:
            chunk = await link.transfer(GRP_DIVE, CMD_DIVE_PROFILE, DIR_READ,
                                        struct.pack("<H", number) + struct.pack("<I", offset))
            n = min(len(chunk), length - offset)
            buf += chunk[:n]
            offset += n

        path = os.path.join(outdir, f"dive_{number:05d}_{fp}.bin")
        with open(path, "wb") as f:
            f.write(buf)
        saved += 1
        print(f"    [{i}] dive#{number} fp={fp} {len(buf)}B -> {os.path.basename(path)}")

    print(f"  [{name}] 完成：新增 {saved} 条，跳过 {skipped} 条")
    return saved, skipped


# ---------------------------------------------------------------- 子命令

async def cmd_scan(args):
    await scan_candidates(args.timeout, args.match, args.all_adv)


async def cmd_probe(args):
    cache = load_cache()
    cands = await scan_candidates(args.timeout, args.match)
    if not cands:
        return
    for dev, adv, entry, kw in cands:
        if args.address and dev.address.upper() != args.address.upper():
            continue
        print(f"\n=== 探测 {dev.address} ({dev.name}) ===")
        try:
            client, link, used_at = await connect_one(dev, args, cache)
            info = await read_info(link)
            print(f"  hardware={info['hardware']} software={info['software']} "
                  f"serial={info['serial']} dives={info['ndives']} last={info['last']} fw6={info['fw6']}")
            cache[dev.address] = {
                "name": dev.name, "serial": info["serial"], "hardware": info["hardware"],
                "software": info["software"], "protocol": (entry or {}).get("protocol", "unknown"),
                "service": link.service_uuid,
                "notify": link.notify_char.uuid if hasattr(link.notify_char, "uuid") else str(link.notify_char),
                "write": link.write_char.uuid if hasattr(link.write_char, "uuid") else str(link.write_char),
                "address_type": used_at,
                "updated": datetime.now().isoformat(timespec="seconds"),
            }
            save_cache(cache)
            print(f"  已写入缓存 {os.path.basename(CACHE_PATH)}")
            await client.disconnect()
        except Exception as e:
            print(f"  [失败] {type(e).__name__}: {e}")


async def cmd_info(args):
    cache = load_cache()
    cands = await scan_candidates(args.timeout, args.match)
    if not cands:
        return
    for dev, adv, entry, kw in cands:
        if args.address and dev.address.upper() != args.address.upper():
            continue
        try:
            client, link, used_at = await connect_one(dev, args, cache)
            info = await read_info(link)
            print(json.dumps({"address": dev.address, **info}, ensure_ascii=False, indent=2))
            await client.disconnect()
        except Exception as e:
            print(f"  [失败] {dev.address}: {type(e).__name__}: {e}")


async def cmd_download(args):
    cache = load_cache()
    cands = await scan_candidates(args.timeout, args.match)
    if not cands:
        return
    for dev, adv, entry, kw in cands:
        if args.address and dev.address.upper() != args.address.upper():
            continue
        print(f"\n=== 下载 {dev.address} ({dev.name}) ===")
        try:
            client, link, used_at = await connect_one(dev, args, cache)
            info = await read_info(link)
            print(f"  serial={info['serial']} hardware={info['hardware']} dives={info['ndives']}")
            serial = info["serial"] or dev.address.replace(":", "")
            outdir = args.out or os.path.join(DEFAULT_OUT, serial)
            await download_one(client, link, info, outdir,
                               limit=args.limit, resume=not args.no_resume, name=dev.address)
            cache[dev.address] = cache.get(dev.address, {})
            cache[dev.address].update({"serial": serial, "address_type": used_at})
            save_cache(cache)
            await client.disconnect()
        except Exception as e:
            print(f"  [失败] {dev.address}: {type(e).__name__}: {e}")


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description="通用潜水电脑表 BLE 工具（多表 / 断点续传）")
    ap.add_argument("cmd", choices=["scan", "probe", "info", "download"])
    ap.add_argument("seconds", nargs="?", type=float, default=12.0, help="扫描时长（秒）")
    ap.add_argument("--address", help="只处理指定 MAC（不写死，仅用于加速/筛选）")
    ap.add_argument("--match", help="额外按广播名子串匹配，如 --match Crest")
    ap.add_argument("--timeout", type=float, default=12.0, help="扫描时长（秒），同位置参数")
    ap.add_argument("--limit", type=int, help="每表最多下载条数")
    ap.add_argument("--out", help="下载输出目录（默认 raw/<序列号>/）")
    ap.add_argument("--no-resume", action="store_true", help="不做指纹去重，全部重下")
    ap.add_argument("--no-pair", dest="pair", action="store_false", help="失败时不尝试系统配对")
    ap.add_argument("--address-type", choices=["random", "public"], default="random")
    ap.add_argument("--all-adv", action="store_true", help="scan 时打印广播数据")
    args = ap.parse_args()

    if args.seconds and args.cmd in ("scan", "probe", "info", "download"):
        args.timeout = args.seconds

    fn = {"scan": cmd_scan, "probe": cmd_probe, "info": cmd_info, "download": cmd_download}[args.cmd]
    try:
        asyncio.run(fn(args))
    except KeyboardInterrupt:
        print("\n已取消")
    except Exception as e:
        print(f"\n[失败] {type(e).__name__}: {e}")
        raise


if __name__ == "__main__":
    main()
