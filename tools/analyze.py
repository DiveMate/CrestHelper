# -*- coding: utf-8 -*-
"""
analyze.py —— 日志分析流水线：合并 / 过滤 / 多算法对拍 / 导出给 Web

输入：data/dives.csv + data/samples.csv（由 batch_parse.py 产出）
输出：web/app.json（Web 原型唯一数据源）

三个核心能力：
  1. 日志合并  —— 中途升水又下水，SI 小于阈值判为同一次潜水
  2. 无效过滤  —— 浅/短/空数据，只标记不删
  3. 多算法对拍 —— 同一条剖面用 5 档 GF 各算一遍，找出"隐藏风险"
"""

import os
import sys
import csv
import json
import argparse
from datetime import datetime, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import deco
from deco import DecoModel, AIR, ALGORITHMS, analyze_profile, depth_to_pressure

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEFAULT_DATA = os.path.join(ROOT, "data")
DEFAULT_OUT = os.path.join(ROOT, "web", "app.json")

# 合并判据：水面间隔小于此值视为同一次潜水（连续下潜）
DEFAULT_SI_THRESHOLD = 600  # 秒
# 无效判据
MIN_DEPTH_M = 2.0
MIN_DURATION_S = 60
MIN_SAMPLES = 5


# ---------------------------------------------------------------------------
# 读取
# ---------------------------------------------------------------------------

def read_dives(path):
    rows = []
    with open(path, "r", encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f):
            try:
                r["dive_no"] = int(r["dive_no"])
                r["divetime_s"] = int(r["divetime_s"] or 0)
                r["maxdepth_m"] = float(r["maxdepth_m"] or 0)
                r["temp_min_c"] = float(r["temp_min_c"] or 0) if r["temp_min_c"] else None
                r["nsamples"] = int(r["nsamples"] or 0)
                r["bytes"] = int(r["bytes"] or 0)
                r["dt"] = datetime.strptime(r["datetime"], "%Y-%m-%d %H:%M:%S")
            except Exception:
                continue
            rows.append(r)
    rows.sort(key=lambda x: x["dt"])
    return rows


def read_samples(path):
    """返回 {file: [(t_sec, depth_m, temp_c), ...]}"""
    by_file = {}
    if not os.path.exists(path):
        return by_file
    with open(path, "r", encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f):
            fn = r["file"]
            try:
                t = int(float(r["t_sec"]))
                d = float(r["depth_m"])
                tp = float(r["temp_c"]) if r.get("temp_c") else 0.0
            except Exception:
                continue
            by_file.setdefault(fn, []).append((t, d, tp))
    for k in by_file:
        by_file[k].sort(key=lambda x: x[0])
    return by_file


# ---------------------------------------------------------------------------
# 合并：把水面间隔过短的相邻潜次拼成一次
# ---------------------------------------------------------------------------

def build_groups(dives, si_threshold):
    """返回分组 [[idx, idx, ...], ...]"""
    groups = []
    cur = []
    for i, d in enumerate(dives):
        if not cur:
            cur.append(i)
            continue
        prev = dives[cur[-1]]
        prev_end = prev["dt"] + timedelta(seconds=prev["divetime_s"])
        si = (d["dt"] - prev_end).total_seconds()
        if si <= si_threshold:
            cur.append(i)
        else:
            groups.append(cur)
            cur = [i]
    if cur:
        groups.append(cur)
    return groups


def merge_profile(parts, si_threshold):
    """把多条潜水的采样点拼成一条连续剖面。

    parts: [(dt_start, duration_s, samples), ...] 按时间升序
    中间的水面段用 (0m, 温=沿用) 补齐 —— 这一步是合并功能的价值所在：
    水面段要作为真实间隔参与排气积分，否则会低估组织余氮。
    """
    out = []
    baseline = 0  # 累计时间偏移（秒）
    t_prev_end = None
    for k, (dt_s, dur, s) in enumerate(parts):
        if k > 0 and t_prev_end is not None:
            si = (dt_s - t_prev_end).total_seconds()
            if si > 0:
                # 水面段：头尾各一个 0m 采样即可表达"停留"
                out.append([baseline, 0.0, None])
                baseline += int(si)
                out.append([baseline, 0.0, None])
        if s:
            t0 = s[0][0]
            for (t, d, tp) in s:
                out.append([baseline + (t - t0), d, tp])
            baseline = out[-1][0]
        t_prev_end = dt_s + timedelta(seconds=dur)
    return out


# ---------------------------------------------------------------------------
# 分析
# ---------------------------------------------------------------------------

def run_model(profile, max_points=160):
    """对一条剖面跑全部算法档位。

    profile: [[t_sec, depth_m, temp_c], ...]
    返回 dict，包含每个算法的汇总指标 + 默认算法的曲线。
    """
    pts = [(p[0], p[1]) for p in profile]
    res = {}
    series = None
    for alg in ALGORITHMS:
        r = analyze_profile(pts, alg, AIR, max_points=max_points)
        res[alg["id"]] = {
            "gf99_max": r["gf99_max"],
            "ceiling_max": r["ceiling_max"],
            "deco_sec": r["deco_sec"],
            "deco_duty": r["deco_duty"],
        }
        if alg["id"] == "gf50_70":
            series = r
    return res, (series or {"gf99_series": [], "ceiling_series": []})


def decimate_profile(profile, n=160):
    if len(profile) <= n:
        return [[p[0], round(p[1], 2), (round(p[2], 1) if p[2] is not None else None)]
                for p in profile]
    step = len(profile) / float(n)
    out = []
    for i in range(n):
        p = profile[int(i * step)]
        out.append([p[0], round(p[1], 2),
                    (round(p[2], 1) if p[2] is not None else None)])
    out.append([profile[-1][0], round(profile[-1][1], 2),
                (round(profile[-1][2], 1) if profile[-1][2] is not None else None)])
    return out


def flags_for(dive, group_size):
    flags = []
    if dive["maxdepth_m"] < MIN_DEPTH_M:
        flags.append("shallow")
    if dive["divetime_s"] < MIN_DURATION_S:
        flags.append("short")
    if dive["nsamples"] < MIN_SAMPLES:
        flags.append("nodata")
    if group_size > 1:
        flags.append("merged")
    return flags


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description="潜水日志分析流水线")
    ap.add_argument("--data", default=DEFAULT_DATA)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--si", type=int, default=DEFAULT_SI_THRESHOLD,
                    help="合并判据：水面间隔阈值（秒），默认 600")
    ap.add_argument("--min-depth", type=float, default=MIN_DEPTH_M)
    ap.add_argument("--min-duration", type=int, default=MIN_DURATION_S)
    ap.add_argument("--max-points", type=int, default=160,
                    help="每条剖面抽稀点数")
    args = ap.parse_args()

    dives = read_dives(os.path.join(args.data, "dives.csv"))
    samples = read_samples(os.path.join(args.data, "samples.csv"))
    print("读入 %d 条潜水，%d 个文件的采样点" % (len(dives), len(samples)))

    groups = build_groups(dives, args.si)
    merged_groups = [g for g in groups if len(g) > 1]
    print("合并：%d 组 -> %d 条（其中 %d 组被合并，涉及 %d 条原始记录）"
          % (len(groups), len(groups), len(merged_groups),
             sum(len(g) for g in merged_groups)))

    out_dives = []
    for gi, g in enumerate(groups):
        parts = []
        for i in g:
            d = dives[i]
            parts.append((d["dt"], d["divetime_s"], samples.get(d["file"], [])))
        profile = merge_profile(parts, args.si)

        # 汇总信息取"主潜次"（组内最深的那条）
        main_idx = max(g, key=lambda i: dives[i]["maxdepth_m"])
        md = dives[main_idx]

        # 合并后的总时长 = 最后一条结束 - 第一条开始
        first, last = dives[g[0]], dives[g[-1]]
        total_dur = int((last["dt"] + timedelta(seconds=last["divetime_s"])
                         - first["dt"]).total_seconds())
        max_depth = max(dives[i]["maxdepth_m"] for i in g)
        temps = [dives[i]["temp_min_c"] for i in g if dives[i]["temp_min_c"]]
        min_temp = min(temps) if temps else None
        n_samples = sum(dives[i]["nsamples"] for i in g)

        if not profile:
            continue

        res, series = run_model(profile, args.max_points)

        flags = flags_for(md, len(g))
        for i in g:
            for fl in flags_for(dives[i], 1):
                if fl not in flags and fl in ("shallow", "short"):
                    flags.append(fl)
        # 组级别的无效判定：整组合并后仍然浅/短才算无效
        if max_depth < args.min_depth:
            if "shallow" not in flags:
                flags.append("shallow")
        if total_dur < args.min_duration:
            if "short" not in flags:
                flags.append("short")

        out_dives.append({
            "id": gi + 1,
            "no": md["dive_no"],
            "dt": first["dt"].strftime("%Y-%m-%dT%H:%M:%S"),
            "raw_ids": [dives[i]["dive_no"] for i in g],
            "merged": len(g) > 1,
            "duration": total_dur,
            "maxdepth": round(max_depth, 2),
            "min_temp": round(min_temp, 1) if min_temp else None,
            "nsamples": n_samples,
            "algo": res,
            "flags": flags,
            "profile": decimate_profile(profile, args.max_points),
            "gf99_series": series["gf99_series"],
            "ceiling_series": series["ceiling_series"],
            "ascent_max": series.get("ascent_max", 0),
            "ascent_violations": series.get("ascent_violations", 0),
        })

    out_dives.sort(key=lambda x: x["dt"], reverse=True)

    # ---- 全局统计 -------------------------------------------------------
    valid = [d for d in out_dives if "shallow" not in d["flags"]
             and "short" not in d["flags"]]
    total_time = sum(d["duration"] for d in valid)
    by_month = {}
    for d in valid:
        k = d["dt"][:7]
        by_month[k] = by_month.get(k, 0) + 1

    # 多算法对拍：统计各档位下"有减压义务"的潜次
    algo_stats = {}
    for alg in ALGORITHMS:
        aid = alg["id"]
        n_deco = sum(1 for d in valid if d["algo"][aid]["deco_duty"])
        worst = max((d["algo"][aid]["gf99_max"] for d in valid), default=0)
        algo_stats[aid] = {
            "name": alg["name"],
            "deco_dives": n_deco,
            "deco_ratio": round(100.0 * n_deco / max(1, len(valid)), 1),
            "gf99_max": round(worst, 1),
        }

    # 隐藏风险：保守算法下有减压义务、但宽松算法下没有
    hidden = []
    for d in valid:
        a = d["algo"]
        if not a["zhl16c"]["deco_duty"] and \
           (a["gf50_70"]["deco_duty"] or a["gf30_85"]["deco_duty"]):
            hidden.append({
                "id": d["id"], "dt": d["dt"],
                "maxdepth": d["maxdepth"], "duration": d["duration"],
                "zhl": a["zhl16c"]["ceiling_max"],
                "gf50_70": a["gf50_70"]["ceiling_max"],
                "gf30_85": a["gf30_85"]["ceiling_max"],
            })

    meta = {
        "generated": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "device": "Crest CR-4",
        "n_raw": len(dives),
        "n_groups": len(out_dives),
        "n_merged": len(merged_groups),
        "n_valid": len(valid),
        "n_invalid": len(out_dives) - len(valid),
        "total_time_h": round(total_time / 3600.0, 1),
        "si_threshold": args.si,
        "date_from": out_dives[-1]["dt"][:10] if out_dives else "",
        "date_to": out_dives[0]["dt"][:10] if out_dives else "",
    }

    payload = {
        "meta": meta,
        "algorithms": ALGORITHMS,
        "algo_stats": algo_stats,
        "hidden_risks": hidden,
        "by_month": [{"month": k, "count": v}
                     for k, v in sorted(by_month.items())],
        "dives": out_dives,
    }

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, separators=(",", ":"))

    # 同时写一份 JS 包装，让 file:// 直接双击也能跑（script 标签不受 CORS 限制，
    # 而 fetch('app.json') 在 file:// 下会被拦）
    js_path = os.path.join(os.path.dirname(args.out), "app-data.js")
    with open(js_path, "w", encoding="utf-8") as f:
        f.write("window.APP_DATA=")
        json.dump(payload, f, ensure_ascii=False, separators=(",", ":"))
        f.write(";\n")

    size = os.path.getsize(args.out)
    print("\n写出 %s (%.1f KB)" % (args.out, size / 1024.0))
    print("写出 %s (%.1f KB)" % (js_path, os.path.getsize(js_path) / 1024.0))
    print("\n=== 全局统计 ===")
    print("原始记录   : %d 条" % meta["n_raw"])
    print("合并后     : %d 条（合并掉 %d 组）" % (meta["n_groups"], meta["n_merged"]))
    print("有效/无效  : %d / %d" % (meta["n_valid"], meta["n_invalid"]))
    print("累计水下时间: %.1f 小时" % meta["total_time_h"])
    print("\n=== 多算法对拍（有效潜次 %d 条）===" % len(valid))
    for aid, s in algo_stats.items():
        print("  %-12s 有减压义务 %3d 条 (%4.1f%%)   峰值 GF99 = %6.1f"
              % (s["name"], s["deco_dives"], s["deco_ratio"], s["gf99_max"]))
    print("\n=== 隐藏风险：宽松算法说没事、保守算法说有事 ===")
    print("  共 %d 条" % len(hidden))
    for h in hidden[:8]:
        print("  #%-4d %s  %.1fm %4dmin   ZHL16C=%.1fm  GF50/70=%.1fm  GF30/85=%.1fm"
              % (h["id"], h["dt"], h["maxdepth"], h["duration"] // 60,
                 h["zhl"], h["gf50_70"], h["gf30_85"]))
    if len(hidden) > 8:
        print("  ... 还有 %d 条" % (len(hidden) - 8))


if __name__ == "__main__":
    main()
