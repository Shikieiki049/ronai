#!/usr/bin/env python3
"""
v14 step 2: the user's recorded games against the AI's, side by side (the match recorder, ronstrat_matches/*.jsonl).

    py tools/human_diff.py [files or dirs ...] [--faction villagers] [--ai-dirs run/...]

Default input: the game instance's ronstrat_matches. For every player of a game with a human in it: per phase
(0-5, 5-12, 12+ min) food / wood / ore gathered per minute, workers and how they are split (food, wood, ore,
build), food per food worker per minute (hunters bring more than farmers), fields and barracks built, army pop;
and the times of the first barracks, the 2nd/4th/6th field, army pop 10 and 25. The AIs of the same games
(teammates, opponents) are the comparison; --ai-dirs adds AI-only games (test servers) of the same faction.
The table ends with the gaps (human minus AI average), the biggest first.
"""
import argparse, glob, json, os, pathlib, statistics

GAME = pathlib.Path(r"C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\ronstrat_matches")
PHASES = [(0, 5), (5, 12), (12, 99)]
FIELDS = {"wheat_farm", "pumpkin_farm", "netherwart_farm", "beetroot_farm", "carrot_farm", "potato_farm", "melon_farm", "farm"}
BARRACKS = {"barracks", "graveyard", "portal_military", "portal_basic", "arcane_tower", "spider_lair"}


def load(path):
    ev = []
    for l in open(path, encoding="utf-8", errors="replace"):
        try:
            ev.append(json.loads(l))
        except ValueError:
            pass
    return ev


def player_stats(ev, name):
    snaps = [r for r in ev if r["ev"] == "snap" and r.get("p") == name]
    if len(snaps) < 6:
        return None
    end = snaps[-1]["t"]
    out = {"len": end / 60}

    def at(t):
        best = snaps[0]
        for s in snaps:
            if s["t"] <= t:
                best = s
        return best

    for a, b in PHASES:
        if a * 60 >= end:
            continue
        s0, s1 = at(a * 60), at(min(b * 60, end))
        mins = max(0.1, (s1["t"] - s0["t"]) / 60)
        inside = [s for s in snaps if s0["t"] <= s["t"] <= s1["t"]]
        g = [(s1["got"][i] - s0["got"][i]) / mins for i in range(3)]
        jobs = {k: statistics.mean(s["jobs"].get(k, 0) for s in inside) for k in ("food", "wood", "ore", "build", "idle")}
        tag = f"{a}-{b if b < 99 else ''}"
        out[f"food/min {tag}"] = g[0]
        out[f"wood/min {tag}"] = g[1]
        out[f"ore/min {tag}"] = g[2]
        out[f"workers {tag}"] = statistics.mean(s["workers"] for s in inside)
        for k, v in jobs.items():
            out[f"{k} workers {tag}"] = v
        out[f"food per food worker {tag}"] = g[0] / max(0.5, jobs["food"])
        # a carcass: 100+ food delivered within one 5 s snapshot (a hunter's whole animal; fields bring ~50)
        carc = 0
        for x, y in zip(inside, inside[1:]):
            if y["got"][0] - x["got"][0] >= 100:
                carc += 1
        out[f"carcasses/min {tag}"] = carc / mins
        hunts = [r for r in ev if r["ev"] == "hunt" and r.get("owner") == name and a * 60 <= r["t"] < min(b * 60, end)]
        if hunts:
            out[f"kills/min {tag}"] = len(hunts) / mins
        out[f"army pop end {tag}"] = s1.get("armyPop", 0)
        out[f"fields end {tag}"] = sum(v for k, v in s1.get("built", {}).items() if k in FIELDS)
        out[f"barracks end {tag}"] = sum(v for k, v in s1.get("built", {}).items() if k in BARRACKS)
    # timings (minutes)
    fields = sorted(r["t"] for r in ev if r["ev"] == "bld" and r.get("what") == "built" and r.get("owner") == name and r.get("b") in FIELDS)
    bar = sorted(r["t"] for r in ev if r["ev"] == "bld" and r.get("what") == "built" and r.get("owner") == name and r.get("b") in BARRACKS)
    for n in (2, 4, 6):
        if len(fields) >= n:
            out[f"t field {n}"] = fields[n - 1] / 60
    if bar:
        out["t 1st barracks"] = bar[0] / 60
    for pop in (10, 25):
        s = next((s for s in snaps if s.get("armyPop", 0) >= pop), None)
        if s:
            out[f"t army {pop}"] = s["t"] / 60
    return out


def games(paths):
    fs = []
    for p in paths:
        fs += glob.glob(os.path.join(p, "**", "*.jsonl"), recursive=True) if os.path.isdir(p) else [p]
    return [f for f in fs if f.endswith("_server.jsonl")]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("paths", nargs="*", default=[str(GAME)])
    ap.add_argument("--faction", default=None)
    ap.add_argument("--human", default="_Shikieiki_")
    ap.add_argument("--ai-dirs", nargs="*", default=[])
    ap.add_argument("--min-len", type=float, default=8, help="games shorter than this (minutes) are skipped")
    a = ap.parse_args()
    hum, ai = {}, {}   # faction -> list of stats
    for f in games(a.paths) + games(a.ai_dirs):
        ev = load(f)
        if not ev or ev[0].get("ev") != "start":
            continue
        players = ev[0]["players"]
        human_here = any(p["name"] == a.human for p in players)
        if not human_here and f not in games(a.ai_dirs):
            continue
        for p in players:
            if a.faction and p["faction"] != a.faction:
                continue
            st = player_stats(ev, p["name"])
            if not st or st["len"] < a.min_len:
                continue
            (hum if p["name"] == a.human else ai if p["bot"] else {}).setdefault(p["faction"], []).append(st)
            print(f"{os.path.basename(f)}  {ev[0]['map'][:22]:22s} {p['faction']:9s} {'YOU' if p['name'] == a.human else 'AI '}  {st['len']:.0f} min")
    for fac in sorted(set(hum) | set(ai)):
        H, A = hum.get(fac, []), ai.get(fac, [])
        print(f"\n=== {fac}: you {len(H)} games, AI {len(A)} player-games")
        keys = []
        for s in H + A:
            keys += [k for k in s if k not in keys and k != "len"]
        rows = []
        for k in keys:
            hv = [s[k] for s in H if k in s]
            av = [s[k] for s in A if k in s]
            if not hv or not av:
                continue
            h, m = statistics.mean(hv), statistics.mean(av)
            rel = (h - m) / max(abs(m), 1e-6)
            rows.append((k, h, m, rel))
            print(f"  {k:30s} you {h:8.1f}   AI {m:8.1f}   {100 * rel:+6.0f}%")
        print("  -- biggest gaps:")
        for k, h, m, rel in sorted(rows, key=lambda r: -abs(r[3]))[:10]:
            print(f"     {k:30s} you {h:8.1f}  AI {m:8.1f}  ({100 * rel:+.0f}%)")


if __name__ == "__main__":
    main()
