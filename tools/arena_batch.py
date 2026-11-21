#!/usr/bin/env python3
"""
v13 step 1: the fights the combat model is fitted on (tools/fit_combat.py).

    py tools/arena_batch.py make  [--mixed 400] [--seed 1]   -> tmp/arena_batch.txt (name | side A | side B)
    then: py tools/arena.py file tmp/arena_batch.txt --reps 1 --servers 12 --label AB1

Every side spends the same budget (arena.BUDGET, 1920) unless the name says otherwise. Kinds of lines:
  pure__<a>__<b>          one unit type against another, night (every pair of the producible units)
  day__<a>__<b>@day       the burning units (zombie, skeleton, wraith) by day against a few opponents
  mix__<n>                two or three types a side, random shares and factions (most of the data)
  scale<k>__<a>__<b>      the same pure pair at k x the budget (Lanchester's exponent)
Witches always throw lingering potions and heal (the research given), like in the AI's games.
"""
import argparse, pathlib, random, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import arena  # noqa: E402

FACTIONS = {
    "villagers": ["vindicator", "pillager", "golem", "witch", "evoker"],
    "monsters": ["zombie", "husk", "skeleton", "creeper", "spider", "wraith", "slime"],
    "piglins": ["brute", "headhunter", "hoglin", "blaze", "witherskeleton", "magmacube"],
}
UNITS = [u for us in FACTIONS.values() for u in us]
BURNS = ["zombie", "skeleton", "wraith"]
OPTS = {"witch": "lingering healing"}


def side(mix, budget=None):
    """mix {unit: share} -> 'u*n+v*m options' spending the budget"""
    old = arena.BUDGET
    if budget:
        arena.BUDGET = budget
    opts = " ".join(sorted({o for u in mix for o in OPTS.get(u, "").split() if o}))
    s = arena.army(mix, opts)
    arena.BUDGET = old
    return s


def make(a):
    rnd = random.Random(a.seed)
    lines = []
    for i, x in enumerate(UNITS):
        for y in UNITS[i + 1:]:
            lines.append((f"pure__{x}__{y}", side({x: 1}), side({y: 1})))
    for x in BURNS:
        for y in ["vindicator", "pillager", "evoker", "brute", "headhunter", "blaze", "zombie", "skeleton"]:
            if x != y:
                lines.append((f"day__{x}__{y}@day", side({x: 1}), side({y: 1})))
    for n in range(a.mixed):
        sides = []
        for _ in range(2):
            fac = rnd.choice(list(FACTIONS))
            k = rnd.choice([2, 2, 3])
            us = rnd.sample(FACTIONS[fac], k)
            w = [rnd.uniform(0.2, 1.0) for _ in us]
            t = sum(w)
            sides.append(side({u: wi / t for u, wi in zip(us, w)}))
        lines.append((f"mix__{n}", sides[0], sides[1]))
    for x, y in [("vindicator", "zombie"), ("vindicator", "brute"), ("pillager", "skeleton"), ("evoker", "zombie"), ("brute", "zombie")]:
        for k in (2, 3):
            lines.append((f"scale{k}__{x}__{y}", side({x: 1}, arena.BUDGET * k), side({y: 1}, arena.BUDGET * k)))
    out = ROOT / "tmp" / "arena_batch.txt"
    out.write_text("\n".join(f"{n} | {x} | {y}" for n, x, y in lines) + "\n", encoding="utf-8")
    print(f"{len(lines)} fights -> {out} (x2 seats with --reps 1)")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["make"])
    ap.add_argument("--mixed", type=int, default=400)
    ap.add_argument("--seed", type=int, default=1)
    make(ap.parse_args())
