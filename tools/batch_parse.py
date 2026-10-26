#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
批量解析：把 raw/ 下所有 .bin 交给 build/parse_cr4.exe，汇总成 CSV + 基础画像。

产出
----
    data/dives.csv     每次潜水一行：时间/时长/最大深度/最低温/采样数/事件数
    data/samples.csv   每个采样点一行：file,t_sec,depth_m,temp_c
    data/events.csv    每个事件一行：file,t_sec,type,flags,value

用法
----
    python batch_parse.py                      # 解析 raw/ 下全部
    python batch_parse.py --raw ../raw --out ../data
    python batch_parse.py --no-samples         # 只要汇总，不出采样点（快）

不依赖任何第三方库。
"""

import argparse
import csv
import os
import re
import subprocess
import sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
EXE = os.path.abspath(os.path.join(HERE, "..", "build", "parse_cr4.exe"))
DEFAULT_RAW = os.path.abspath(os.path.join(HERE, "..", "raw"))
DEFAULT_OUT = os.path.abspath(os.path.join(HERE, "..", "data"))

RE_FILE = re.compile(r"^=== (.+?) \((\d+) 字节\) ===$")
RE_NUM = re.compile(r"dive[_-]?(\d+)", re.I)


def find_bins(raw):
    out = []
    for root, _, files in os.walk(raw):
        for fn in sorted(files):
            if fn.lower().endswith(".bin"):
                out.append(os.path.join(root, fn))
    return sorted(out)


def run_exe(paths):
    """分块调用，避免 Windows 命令行 8191 字符上限。"""
    proc = subprocess.run([EXE] + paths, capture_output=True, text=True,
                          encoding="utf8", errors="replace")
    if proc.returncode != 0 and not proc.stdout:
        print(f"[warn] parse_cr4 返回 {proc.returncode}: {proc.stderr[:300]}")
    return proc.stdout or ""


def chunked(seq, n=40):
    for i in range(0, len(seq), n):
        yield seq[i:i + n]


def parse_output(text, want_samples=True):
    dives, samples, events = [], [], []
    cur = None
    for line in text.splitlines():
        m = RE_FILE.match(line)
        if m:
            if cur:
                dives.append(cur)
            path = m.group(1)
            nm = RE_NUM.search(os.path.basename(path))
            cur = {"file": os.path.basename(path),
                   "path": path,
                   "dive_no": int(nm.group(1)) if nm else "",
                   "bytes": int(m.group(2)),
                   "datetime": "", "divetime_s": "", "maxdepth_m": "",
                   "temp_min_c": "", "gasmixes": "", "nsamples": 0, "nevents": 0}
            continue
        if cur is None:
            continue
        if line.startswith("datetime   : "):
            cur["datetime"] = line.split(": ", 1)[1].strip()
        elif line.startswith("divetime   : "):
            cur["divetime_s"] = int(re.search(r"(\d+)", line).group(1))
        elif line.startswith("maxdepth   : "):
            cur["maxdepth_m"] = float(re.search(r"([\d.]+)", line).group(1))
        elif line.startswith("temp_min   : "):
            cur["temp_min_c"] = float(re.search(r"([\-\d.]+)", line).group(1))
        elif line.startswith("gasmixes   : "):
            cur["gasmixes"] = int(re.search(r"(\d+)", line).group(1))
        elif line.startswith("# event: "):
            cur["nevents"] += 1
            g = re.findall(r"(\w+)=(-?\d+)", line)
            d = dict(g)
            events.append({"file": cur["file"], "t_sec": d.get("at", ""),
                           "type": d.get("type", ""), "flags": d.get("flags", ""),
                           "value": d.get("value", "")})
        elif line[:1].isdigit():
            cur["nsamples"] += 1
            if want_samples:
                parts = line.split(",")
                samples.append({"file": cur["file"],
                                "t_sec": parts[0],
                                "depth_m": parts[1],
                                "temp_c": parts[2] if len(parts) > 2 else ""})
    if cur:
        dives.append(cur)
    return dives, samples, events


def write_csv(path, rows, fields):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", newline="", encoding="utf8") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)
    print(f"  -> {path}  ({len(rows)} 行)")


def summarize(dives):
    print("\n=== 基础画像 ===")
    print(f"潜水次数       : {len(dives)}")
    dt = sorted((d for d in dives if d["datetime"]), key=lambda x: x["datetime"])
    if dt:
        print(f"时间跨度       : {dt[0]['datetime']}  ~  {dt[-1]['datetime']}")
    yrs = Counter(d["datetime"][:4] for d in dives if d["datetime"])
    print("按年分布       : " + "  ".join(f"{y}:{c}" for y, c in sorted(yrs.items())))
    t = [d["divetime_s"] for d in dives if isinstance(d["divetime_s"], int)]
    if t:
        t.sort()
        print(f"时长(秒)       : min={t[0]}  中位={t[len(t)//2]}  max={t[-1]}")
    dp = [d["maxdepth_m"] for d in dives if isinstance(d["maxdepth_m"], float)]
    if dp:
        dp.sort()
        print(f"最大深度(m)    : min={dp[0]:.2f}  中位={dp[len(dp)//2]:.2f}  max={dp[-1]:.2f}")

    # 任务3 的无效日志过滤雏形：先只做标记，不删数据
    shallow = [d for d in dives if isinstance(d["maxdepth_m"], float) and d["maxdepth_m"] < 2.0]
    short = [d for d in dives if isinstance(d["divetime_s"], int) and d["divetime_s"] < 60]
    print(f"\n疑似无效（浅于2m）: {len(shallow)} 条")
    print(f"疑似无效（短于60s）: {len(short)} 条")
    print("（策略：默认只标记不删，raw 原始 blob 永不删，改阈值可全库重跑）")


def main():
    ap = argparse.ArgumentParser(description="批量解析潜水原始数据")
    ap.add_argument("--raw", default=DEFAULT_RAW)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--no-samples", action="store_true", help="不输出采样点 CSV")
    args = ap.parse_args()

    if not os.path.exists(EXE):
        sys.exit(f"找不到 {EXE}，先在 build/ 目录执行 bash build.sh")

    bins = find_bins(args.raw)
    if not bins:
        sys.exit(f"{args.raw} 下没有 .bin，先下载")
    print(f"待解析 {len(bins)} 个文件 ...")

    all_dives, all_samples, all_events = [], [], []
    for i, chunk in enumerate(chunked(bins, 40)):
        print(f"  第 {i + 1} 批（{len(chunk)} 个）...")
        d, s, e = parse_output(run_exe(chunk), want_samples=not args.no_samples)
        all_dives += d
        all_samples += s
        all_events += e

    all_dives.sort(key=lambda x: (x["datetime"] or "", x["dive_no"] if x["dive_no"] != "" else 0))
    print(f"\n解析成功 {len(all_dives)} 条潜水")

    write_csv(os.path.join(args.out, "dives.csv"), all_dives,
              ["file", "dive_no", "datetime", "divetime_s", "maxdepth_m",
               "temp_min_c", "gasmixes", "nsamples", "nevents", "bytes", "path"])
    if not args.no_samples:
        write_csv(os.path.join(args.out, "samples.csv"), all_samples,
                  ["file", "t_sec", "depth_m", "temp_c"])
    if all_events:
        write_csv(os.path.join(args.out, "events.csv"), all_events,
                  ["file", "t_sec", "type", "flags", "value"])

    summarize(all_dives)


if __name__ == "__main__":
    main()
