#!/usr/bin/env python3
"""
v14 step 5: how often the user's ideas come into play, from the result lines (metrics.ideas, build 224 on).

    py tools/ideas.py LABEL [LABEL ...]

Per label and seat-kind (our current code = players without a brain; frozen / RON opponents are left out):
pen breaks and the returns to the animal after them, wounded soldiers sent home and those back healed (60%),
ally defences, joint attacks, the share of army time with a witch along, and the evokers' share of the army.
"""
import json, pathlib, statistics, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent


def main():
    for lab in sys.argv[1:]:
        rows = {}
        for line in (ROOT / "results" / f"{lab}.jsonl").read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            r = json.loads(line)
            sides = [x.split(",") for x in r["spec"].split(" vs ")]
            sides[-1] = [sides[-1][0].split(" ")[0]] if len(sides[-1]) == 1 else sides[-1][:-1] + [sides[-1][-1].split(" ")[0]]
            for ti, t in enumerate(r["teams"]):
                for pi, p in enumerate(t):
                    tok = sides[ti][pi] if ti < len(sides) and pi < len(sides[ti]) else ""
                    p["faction"] = tok.split("#")[1].split("@")[0].split(":")[0] if "#" in tok else "villagers"
                    if p.get("brain"):
                        continue
                    ide = (p.get("metrics") or {}).get("ideas")
                    if not ide:
                        continue
                    fac = p.get("faction") or "?"
                    rows.setdefault(fac, []).append(ide)
        for fac, v in sorted(rows.items()):
            keys = sorted({k for x in v for k in x})
            print(f"{lab} {fac:10s} {len(v):3d} games  " + "  ".join(f"{k} {statistics.mean(x.get(k, 0) for x in v):.2f}" for k in keys))


if __name__ == "__main__":
    main()
