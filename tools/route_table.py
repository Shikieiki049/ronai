#!/usr/bin/env python3
"""
Builds tuning/strategy_table.json from route-vs-route self-play: for each map, the route (1 golems,
2 witches) that beat the farms route (0) there, else farms.

    py tools/route_table.py results/P_duelrb.jsonl:1 results/P_duelrc.jsonl:2 [--min-games 6] [--min-score 0.6]

Each results file holds games of "expert:<profile> vs expert" in both seats; the profile is the route.
A map gets a route only with enough games and a clear score there (per-map samples are small).
"""
import argparse, collections, json, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent


def per_map(path):
    d = collections.defaultdict(list)
    for line in open(path, encoding="utf-8"):
        if not line.strip():
            continue
        r = json.loads(line)
        teams = r["teams"]
        ours = 0 if teams[0][0].get("profile") else 1
        sc = 0.5 if r.get("timeout") else (1.0 if r["winnerTeam"] == ours else 0.0)
        d[r["arena"].replace("map:", "")].append(sc)
    return d


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+", help="results.jsonl:route")
    ap.add_argument("--min-games", type=int, default=6)
    ap.add_argument("--min-score", type=float, default=0.6)
    a = ap.parse_args()
    best = {}
    for spec in a.runs:
        path, route = spec.rsplit(":", 1)
        for m, scores in per_map(path).items():
            s = sum(scores) / len(scores)
            print(f"{m:22s} route {route}: {s:5.1%} over {len(scores)} games")
            if len(scores) >= a.min_games and s >= a.min_score and s > best.get(m, (0, -1))[1]:
                best[m] = (int(route), s)
    table = {m: r for m, (r, s) in best.items()}
    out = ROOT / "tuning" / "strategy_table.json"
    out.write_text(json.dumps(table, indent=1), encoding="utf-8")
    print("wrote", out, table)


if __name__ == "__main__":
    main()
