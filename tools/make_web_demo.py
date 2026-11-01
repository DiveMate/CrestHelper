#!/usr/bin/env python3
"""生成 web/app-data.js —— UI 原型用的小体积演示数据。

为什么不直接搬全量？
----------------------
逐点剖面属于个人潜水轨迹（和 data/samples.csv 同一份数据）。
公开仓库里只放**摘要**（每潜一行的数字，无潜点、无轨迹），
再挑少量几条带剖面，够把界面跑起来、够看曲线就行。

输入：data/dives.csv（摘要，40 条精选样本，入库）
      data/samples.csv（逐点剖面，不入库；缺了就只出摘要）
输出：web/app-data.js

用法：
    python tools/make_web_demo.py            # 默认 8 条带剖面
    python tools/make_web_demo.py --profiles 12
"""
import argparse
import csv
import json
import os
from datetime import datetime

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIVES = os.path.join(ROOT, "data", "dives.csv")
SAMPLES = os.path.join(ROOT, "data", "samples.csv")
OUT = os.path.join(ROOT, "web", "app-data.js")


def load_dives():
    with open(DIVES, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def load_samples(wanted):
    """只读我们真正要的那几条的剖面，避免把整份 3 MB 读进来。"""
    out = {}
    if not os.path.exists(SAMPLES):
        return out
    with open(SAMPLES, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            name = row.get("file", "")
            if name not in wanted:
                continue
            try:
                t = int(float(row["t_sec"]))
                d = float(row["depth_m"])
            except (KeyError, TypeError, ValueError):
                continue
            temp = row.get("temp_c")
            try:
                temp = float(temp) if temp not in (None, "") else None
            except ValueError:
                temp = None
            out.setdefault(name, []).append([t, round(d, 2), temp])
    for pts in out.values():
        pts.sort(key=lambda p: p[0])
        # 温度断点向前填充，省掉 null 让前端少写一层判断
        last = None
        for p in pts:
            if p[2] is None:
                p[2] = last
            else:
                last = p[2]
    return out


def pick_profiles(dives, n):
    """挑剖面示例：最深、最长各一条，其余按年份均匀取，保证界面上年份筛选有东西看。"""
    if n <= 0:
        return []
    by_depth = sorted(dives, key=lambda r: -float(r["maxdepth_m"]))
    by_time = sorted(dives, key=lambda r: -int(r["divetime_s"]))
    picked, seen = [], set()

    def take(row):
        if row["file"] in seen:
            return
        seen.add(row["file"])
        picked.append(row)

    take(by_depth[0])
    take(by_time[0])
    # 年份均匀铺开
    years = sorted({r["datetime"][:4] for r in dives})
    per = max(1, (n - len(picked)) // max(1, len(years)))
    for y in years:
        bucket = [r for r in dives if r["datetime"][:4] == y]
        bucket.sort(key=lambda r: -float(r["maxdepth_m"]))
        for r in bucket[:per]:
            if len(picked) < n:
                take(r)
    for r in dives:
        if len(picked) >= n:
            break
        take(r)
    return picked[:n]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--profiles", type=int, default=8, help="带逐点剖面的示例条数")
    args = ap.parse_args()

    dives = load_dives()
    picked = pick_profiles(dives, args.profiles)
    profiles = load_samples({r["file"] for r in picked})

    rows = []
    for r in dives:
        dt = r["datetime"]
        dur = int(float(r["divetime_s"]))
        prof = profiles.get(r["file"])
        item = {
            "id": r["file"],
            "no": int(float(r["dive_no"])),
            "dt": dt,
            "dur": dur,
            "maxd": round(float(r["maxdepth_m"]), 2),
            "mint": float(r["temp_min_c"]) if r["temp_min_c"] else None,
            "n": int(float(r["nsamples"])),
        }
        if prof:
            depths = [p[1] for p in prof]
            temps = [p[2] for p in prof if p[2] is not None]
            item["profile"] = prof
            item["avgd"] = round(sum(depths) / len(depths), 2)
            if temps:
                item["maxt"] = round(max(temps), 1)
        rows.append(item)

    rows.sort(key=lambda x: x["dt"], reverse=True)

    total_s = sum(r["dur"] for r in rows)
    payload = {
        "meta": {
            "generated": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "device": "Crest CR-4",
            "n_dives": len(rows),
            "n_profile": len(profiles),
            "total_time_h": round(total_s / 3600.0, 1),
            "date_from": rows[-1]["dt"][:10] if rows else "",
            "date_to": rows[0]["dt"][:10] if rows else "",
            "note": "摘要为精选样本；逐点剖面只挑了少量示例，个人轨迹不入库",
        },
        "dives": rows,
    }

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("/* 由 tools/make_web_demo.py 生成，请勿手改 */\n")
        f.write("window.APP_DATA=")
        json.dump(payload, f, ensure_ascii=False, separators=(",", ":"))
        f.write(";\n")

    size = os.path.getsize(OUT)
    print(f"{len(rows)} 条摘要 / {len(profiles)} 条带剖面 → {OUT}  {size/1024:.0f} KB")


if __name__ == "__main__":
    main()
