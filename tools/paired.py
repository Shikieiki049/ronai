#!/usr/bin/env python3
"""
Paired comparison of two test runs that played the same games (same suite shape: maps x seeds x sides).

    py tools/paired.py <A label|file> <B label|file>

Every game is keyed by (map, seed, which side we were on); our score per game is 1 win, 0.5 timeout /
stalemate, 0 loss. Only games present in both runs count. The difference B - A per pair cancels the map
and seed luck both runs share, so it needs far fewer games than comparing two separate win rates.
Prints the mean difference, its 95% interval and how the pairs split (B better / same / worse).
"""
import json, math, pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent


def load(arg):
    p = pathlib.Path(arg)
    if not p.exists():
        p = ROOT / "results" / f"{arg}.jsonl"
    out = {}
    for line in open(p, encoding="utf-8"):
        if not line.strip():
            continue
        r = json.loads(line)
        sides = [s.strip() for s in r["spec"].split(" vs ")]
        # our side: the one that is not RON's bot; in a mirror (both ours) the first side counts as "us"
        us = next((i for i, s in enumerate(sides) if "@ron" not in s.split()[0]), 0)
        if sum("@ron" not in s.split()[0] for s in sides) == len(sides):
            us = 0
        w = r.get("winnerTeam")
        if r.get("timeout") or w is None or w < 0:
            score = 0.5
        else:
            score = 1.0 if w == us else 0.0
        out[(r.get("arena"), r.get("seed"), us)] = score
    return out


def main():
    a, b = load(sys.argv[1]), load(sys.argv[2])
    keys = sorted(set(a) & set(b), key=str)
    if not keys:
        print("no common games")
        return
    d = [b[k] - a[k] for k in keys]
    n = len(d)
    mean = sum(d) / n
    sd = math.sqrt(sum((x - mean) ** 2 for x in d) / (n - 1)) if n > 1 else 0.0
    half = 1.96 * sd / math.sqrt(n) if n > 1 else float("nan")
    sa, sb = sum(a[k] for k in keys) / n, sum(b[k] for k in keys) / n
    better = sum(x > 0 for x in d); worse = sum(x < 0 for x in d)
    print(f"{n} common games: A {sa:.1%}  B {sb:.1%}  B-A {mean:+.1%}  95% [{mean - half:+.1%}, {mean + half:+.1%}]")
    print(f"  pairs: B better {better}, same {n - better - worse}, worse {worse}")
    verdict = "B better" if mean - half > 0 else "B worse" if mean + half < 0 else "no clear difference"
    print(f"  -> {verdict}")


if __name__ == "__main__":
    main()
