#!/usr/bin/env python3
"""
Game-by-game report of what went wrong, from a results file (no logs needed: the per-game series).

    py tools/loss_report.py results/R213.jsonl [--all]

Each lost or timed-out game is put in one class:
  A  early rush      lost before minute 15 with the enemy army ahead by minute 10
  B  lost the fight  lost later, the enemy army ahead when it went down
  C  economy stuck   fewer than 22 villagers at minute 10, or under 1500 wood gathered by then
  D  ahead, no kill  timeout while clearly ahead (our villagers + 2 x soldiers over 60% of the total)
  E  stalemate       any other timeout
"Us" is the side that is not a RON bot (names starting "Bot-") in RON games, else the profile side.
"""
import argparse, collections, json

COLS = "t,workers,army,food,wood,ore,farms,barracks,armoured,enchanted,idle,buildings,gotFood,gotWood,gotOre,woodWorkers,woodTrip".split(",")
I = {c: i for i, c in enumerate(COLS)}


def us_index(r):
    teams = r["teams"]
    for i, t in enumerate(teams):
        if not t[0]["name"].startswith("Bot"):
            if any(x[0]["name"].startswith("Bot") for x in teams):
                return i
    for i, t in enumerate(teams):
        if t[0].get("profile"):
            return i
    return 0


def at(series, minute, col):
    row = None
    for x in series:
        if x[0] <= minute * 60:
            row = x
    return None if row is None else row[I[col]]


def first(series, col):
    for x in series:
        if x[I[col]] > 0:
            return x[0]
    return None


def mmss(s):
    return "-" if s is None else f"{s // 60}:{s % 60:02d}"


def classify(r, us):
    me, them = r["teams"][us][0], r["teams"][1 - us][0]
    s, o = me["metrics"]["series"], them["metrics"]["series"]
    mins = r["gameSeconds"] / 60
    lost = not r.get("timeout") and r["winnerTeam"] != us
    vil10, wood10 = at(s, 10, "workers"), at(s, 10, "gotWood")
    if (vil10 is not None and vil10 < 22) or (wood10 is not None and wood10 < 1500):
        econ = True
    else:
        econ = False
    if lost:
        a10, e10 = at(s, 10, "army") or 0, at(o, 10, "army") or 0
        if mins < 15 and e10 > a10:
            return "A"
        if econ:
            return "C"
        return "B"
    if r.get("timeout"):
        last, lo = s[-1], o[-1] if o else None
        mine = last[I["workers"]] + 2 * last[I["army"]]
        theirs = (lo[I["workers"]] + 2 * lo[I["army"]]) if lo else 0
        if econ:
            return "C"
        return "D" if mine > 0.6 * (mine + theirs) else "E"
    return "W"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("results", nargs="+")
    ap.add_argument("--all", action="store_true", help="list won games too")
    a = ap.parse_args()
    counts = collections.Counter()
    bymap = collections.defaultdict(collections.Counter)
    n = 0
    for path in a.results:
        for line in open(path, encoding="utf-8"):
            if not line.strip():
                continue
            r = json.loads(line)
            us = us_index(r)
            c = classify(r, us)
            n += 1
            counts[c] += 1
            m = r["arena"].replace("map:", "")
            bymap[m][c] += 1
            if c == "W" and not a.all:
                continue
            s, o = r["teams"][us][0]["metrics"]["series"], r["teams"][1 - us][0]["metrics"]["series"]
            curve = " ".join(f"{t}:{at(s, t, 'workers') or 0}/{at(s, t, 'army') or 0}-{at(o, t, 'workers') or 0}/{at(o, t, 'army') or 0}"
                             for t in (6, 8, 10, 12, 15) if at(s, t, "workers") is not None)
            print(f"{c} {m[:16]:16s} seed {r['seed']:<4} seat {us} {r['gameSeconds'] // 60:>2} min  "
                  f"barracks {mmss(first(s, 'barracks'))} soldier {mmss(first(s, 'army'))}  (us/them villagers/army) {curve}")
    names = {"W": "won", "A": "early rush", "B": "lost the fight", "C": "economy stuck", "D": "ahead, no kill", "E": "stalemate"}
    print(f"\n{n} games")
    for k in "WABCDE":
        if counts[k]:
            print(f"  {k} {names[k]:15s} {counts[k]:3d}  ({100 * counts[k] / n:.0f}%)")
    print()
    for m, c in sorted(bymap.items()):
        print(f"  {m:22s} " + "  ".join(f"{k}{c[k]}" for k in "WABCDE" if c[k]))


if __name__ == "__main__":
    main()
