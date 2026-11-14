#!/usr/bin/env python3
"""
Solo economy games: one expert alone on the map for 15 minutes, no enemy. Compares parameter profiles on
what they gather and build - without the luck of fights, and at twice the games per CPU hour.

    py tools/solo.py base lo fs            base = the default expert; the others are tuning/profiles/<name>.json
    py tools/solo.py base cand --set val   the six validation maps instead of the eight tuning maps
    py tools/solo.py base cand --seeds 901 902 903 --minutes 15 --parallel 20 --label S1

Every profile plays the same (map, seed) pairs - the seed picks the seat - and is compared to the first
profile pair by pair. Score of a game = villagers at 10:00 + villagers at 15:00 + (food + wood gathered by
15:00) / 150 + 2 x soldiers at 15:00  (a soldier is about 160 food: counted once in what was gathered,
and again here for having been turned into army rather than left in the bank).
"""
import argparse, json, pathlib, statistics as st, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TUNE = ["Mirs_Landing", "Berlingrad_Classic", "Cabana_Republic", "Infinity_Isle", "Snowplow", "Post-Youth", "Crossings", "Industrial_Strength"]
VAL = ["Oceanborn", "4Rivers", "Corporate_Warfare", "Hostile_Hostel", "Jungle_Stalkers", "River_Madness"]
I = {c: i for i, c in enumerate("t,workers,army,food,wood,ore,farms,barracks,armoured,enchanted,idle,buildings,gotFood,gotWood,gotOre,woodWorkers,woodTrip".split(","))}


def at(series, minute, col):
    row = None
    for x in series:
        if x[0] <= minute * 60:
            row = x
    return 0 if row is None else row[I[col]]


def measures(r, minutes):
    s = r["teams"][0][0]["metrics"]["series"]
    end = minutes - 0.5
    m = {"v5": at(s, 5, "workers"), "v10": at(s, 10, "workers"), "vEnd": at(s, end, "workers"), "farms10": at(s, 10, "farms"),
         "food": at(s, end, "gotFood"), "wood": at(s, end, "gotWood"), "army": at(s, end, "army"),
         "bank": at(s, end, "food") + at(s, end, "wood") + at(s, end, "ore")}
    m["score"] = m["v10"] + m["vEnd"] + (m["food"] + m["wood"]) / 150.0 + 2 * m["army"]
    return m


def run(profiles, maps, seeds, minutes, parallel, label, dev=False, global_max=None):
    plan = []
    for p in profiles:
        spec = ("expert" if p == "base" else f"expert:{p}") + " solo readied nohandicap"
        for m in maps:
            for s in seeds:
                plan.append({"spec": spec, "seed": s, "map": str(ROOT / "tmp" / "maps" / m), "extra": f"-Dronstrat.stopAt={minutes * 60}"})
    pf = ROOT / "tmp" / f"{label}.plan.json"
    pf.write_text(json.dumps(plan, indent=1), encoding="utf-8")
    out = ROOT / "results" / f"{label}.jsonl"
    out.unlink(missing_ok=True)
    subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ROOT / "selfplay.ps1"), "-Plan", str(pf),
                    "-Out", str(out), "-Label", label] + ([] if dev else ["-Prod"]) + ["-Parallel", str(parallel), "-ParallelIdle", str(parallel), "-GlobalMax", str(global_max or parallel),
                    "-TimeoutMinutes", str(minutes + 5)], cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return out


def table(out, profiles, minutes):
    games = {}
    for line in out.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        r = json.loads(line)
        prof = r["teams"][0][0].get("profile") or "base"
        games[(prof, r["arena"], r["seed"])] = measures(r, minutes)
    keys = ["score", "v5", "v10", "vEnd", "farms10", "food", "wood", "army", "bank"]
    base = profiles[0]
    res = {}
    print(f"{'profile':10s} {'games':>5s} " + " ".join(f"{k:>8s}" for k in keys) + "    score vs " + base + " (paired, 95%)")
    for p in profiles:
        mine = {(a, s): m for (q, a, s), m in games.items() if q == p}
        if not mine:
            print(f"{p:10s} no results"); continue
        row = " ".join(f"{st.mean(m[k] for m in mine.values()):8.1f}" for k in keys)
        diff = [mine[k]["score"] - games[(base, k[0], k[1])]["score"] for k in mine if (base, k[0], k[1]) in games]
        tail = ""
        if p != base and len(diff) > 1:
            d, se = st.mean(diff), st.stdev(diff) / len(diff) ** 0.5
            tail = f"   {d:+6.1f} +- {1.96 * se:4.1f}" + ("  *" if abs(d) > 1.96 * se else "")
            res[p] = (d, 1.96 * se, len(diff))
        print(f"{p:10s} {len(mine):5d} {row}{tail}")
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("profiles", nargs="+")
    ap.add_argument("--set", choices=["tune", "val", "all"], default="tune")
    ap.add_argument("--seeds", type=int, nargs="+", default=[901, 902, 903])
    ap.add_argument("--minutes", type=int, default=15)
    ap.add_argument("--parallel", type=int, default=16)
    ap.add_argument("--label", default="SOLO")
    ap.add_argument("--dev", action="store_true", help="dev server with the compiled classes (no testJar needed)")
    ap.add_argument("--global-max", type=int, default=None, help="cap on all test servers of the machine (default: --parallel)")
    ap.add_argument("--report", action="store_true", help="only print the table of an earlier run with this label")
    a = ap.parse_args()
    maps = TUNE if a.set == "tune" else VAL if a.set == "val" else TUNE + VAL
    out = ROOT / "results" / f"{a.label}.jsonl"
    if not a.report:
        out = run(a.profiles, maps, a.seeds, a.minutes, a.parallel, a.label, a.dev, a.global_max)
    table(out, a.profiles, a.minutes)


if __name__ == "__main__":
    main()
