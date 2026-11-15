#!/usr/bin/env python3
"""
Multiplayer games recorded by the client (client/MatchRecorder.java): per player the build order and the army.

    py tools/mp_report.py <.minecraft/versions/<ver>/ronstrat_matches> [file ...]

For every game: the players and factions, then per player the buildings in the order they were placed
(time, and when finished), the Nth field / barracks-type building milestones, and the units seen once a minute.
"""
import json, pathlib, sys
from collections import defaultdict

FIELDS = {"wheat_farm", "pumpkin_farm", "nether_wart_farm"}
BARRACKS = {"barracks", "graveyard", "portal_military", "basalt_springs"}


def mmss(t):
    return f"{t // 60}:{t % 60:02d}"


def report(path):
    evs = [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]
    print(f"=== {path.name}")
    start = next((e for e in evs if e["ev"] == "start"), {})
    print(f"server {start.get('server', '?')}, recorded by {start.get('me', '?')}")
    for e in evs:
        if e["ev"] == "players":
            print(f"  players at {mmss(e['t'])}: {e['list']}")
    placed = defaultdict(list)
    built = {}
    for e in evs:
        if e["ev"] == "placed" and e["owner"]:
            placed[e["owner"]].append(e)
            if e.get("built"): built[(e["x"], e["z"])] = e["t"]
        elif e["ev"] == "built":
            built[(e["x"], e["z"])] = e["t"]
    units = [e for e in evs if e["ev"] == "units"]
    for owner, ps in placed.items():
        print(f"\n  {owner}")
        line = []
        for e in ps:
            b = built.get((e["x"], e["z"]))
            line.append(f"{mmss(e['t'])} {e['b']}" + (f" (done {mmss(b)})" if b is not None and b != e["t"] else ""))
        for i in range(0, len(line), 4):
            print("    " + " | ".join(line[i:i + 4]))
        for kind, names in (("fields", FIELDS), ("barracks", BARRACKS)):
            ts = [e["t"] for e in ps if e["b"] in names]
            if ts:
                ms = [f"#{n}@{mmss(ts[n - 1])}" for n in (1, 3, 6, 10, 14) if n <= len(ts)]
                print(f"    {kind}: {' '.join(ms)} (total {len(ts)})")
        for u in units:
            mine = u["by"].get(owner)
            if mine and u["t"] % 120 < 60:
                print(f"    {mmss(u['t'])} units: " + ", ".join(f"{k} {v}" for k, v in sorted(mine.items(), key=lambda x: -x[1])))


def main():
    p = pathlib.Path(sys.argv[1])
    files = [pathlib.Path(f) for f in sys.argv[2:]] if len(sys.argv) > 2 else sorted(p.glob("*.jsonl"))
    for f in files:
        report(f)


if __name__ == "__main__":
    main()
