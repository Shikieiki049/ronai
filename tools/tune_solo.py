#!/usr/bin/env python3
"""
(1+lambda) search over the expert's economy numbers on solo games (tools/solo.py): no enemy, no luck of
fights, twice the games per CPU hour. The winner must also win on the validation maps it never saw.

    py tools/tune_solo.py --generations 12 --lam 3 [--parallel 16] [--start prof]

Each generation: the current best and --lam mutations play the 8 tuning maps x 2 fresh seeds; a mutation
replaces the best when its paired score difference is above one standard error. At the end the best and
the start point play the 6 validation maps x 4 seeds and the tuning maps on fresh seeds.
Writes tuning/profiles/tunebest_solo.json and tuning/tune_solo.log.
"""
import argparse, json, pathlib, random, sys, time

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import solo  # noqa: E402

SPACE = {
    "fieldsScale":         (1.0, 1.9, False),
    "villagersFirst":      (0, 1, True),
    "villagersFirstGap":   (0, 6, True),
    "boomWorkers":         (20, 40, True),
    "vilCap":              (34, 50, True),
    "houseBuffer":         (5, 14, True),
    "maxParallelHouses":   (1, 4, True),
    "farmWorkersShare":    (0.30, 0.55, False),
    "oreShare":            (0.05, 0.25, False),
    "oreFromMinute":       (3, 9, True),
    "openFood":            (0.35, 0.65, False),
    "openFood2":           (0.35, 0.60, False),
    "hunters":             (2, 6, True),
    "smithFrom":           (9, 17, True),
    "shrineEarlyMinute":   (2, 6, True),
    "buildersPerBuilding": (1, 3, True),
    "workerQueue":         (1, 3, True),
    "dropDistance":        (12, 24, True),
    "woodDropSlack":       (3, 10, True),
    "barracksPerWorkers":  (8, 14, True),
    "buildWoodReserve":    (0, 60, True),
    "openingLead":         (0, 1.5, False),
}


def defaults(keys):
    src = (ROOT / "src/main/java/com/ronstrat/BotParams.java").read_text(encoding="utf-8")
    out = {}
    for k in keys:
        i = src.find(f'p.put("{k}",')
        if i >= 0:
            out[k] = float(src[i:src.index(")", i)].split(",")[4])
    return out


def mutate(p, sigma, rng):
    q = dict(p)
    for k in rng.sample(list(SPACE), k=rng.randint(1, 3)):
        lo, hi, integer = SPACE[k]
        v = min(hi, max(lo, q[k] + rng.gauss(0, sigma * (hi - lo))))
        if integer and int(round(v)) == int(round(q[k])):
            v = q[k] + (1 if rng.random() < 0.5 else -1)
            v = min(hi, max(lo, v))
        q[k] = int(round(v)) if integer else round(v, 3)
    return q


def write(name, params, extra):
    prof = dict(extra); prof.update(params)
    (ROOT / "tuning/profiles" / f"{name}.json").write_text(json.dumps(prof, indent=1), encoding="utf-8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--generations", type=int, default=12)
    ap.add_argument("--lam", type=int, default=3)
    ap.add_argument("--parallel", type=int, default=16)
    ap.add_argument("--start", default="")
    ap.add_argument("--seed", type=int, default=11)
    ap.add_argument("--minutes", type=int, default=15)
    a = ap.parse_args()
    rng = random.Random(a.seed)
    extra = json.loads((ROOT / "tuning/profiles" / f"{a.start}.json").read_text(encoding="utf-8")) if a.start else {}
    start = defaults(SPACE.keys())
    start.update({k: v for k, v in extra.items() if k in SPACE})
    best = dict(start)
    log = (ROOT / "tuning" / "tune_solo.log").open("a", encoding="utf-8")
    log.write(f"\n== start {time.strftime('%Y-%m-%d %H:%M')} start={json.dumps(best)}\n"); log.flush()
    for g in range(a.generations):
        write("tsbest", best, extra)
        names = ["tsbest"]
        cands = {}
        for i in range(a.lam):
            n = f"ts{g + 1}{chr(97 + i)}"
            cands[n] = mutate(best, 0.2, rng)
            write(n, cands[n], extra)
            names.append(n)
        label = f"TS_g{g + 1}"
        out = solo.run(names, solo.TUNE, [1000 + 2 * g, 1001 + 2 * g], a.minutes, a.parallel, label)
        res = solo.table(out, names, a.minutes)
        pick = None
        for n, (d, ci, cnt) in res.items():
            diff = {k: v for k, v in cands[n].items() if v != best.get(k)}
            log.write(f"gen {g + 1} {n} {d:+.1f} +- {ci:.1f} over {cnt}  {json.dumps(diff)}\n")
            if cnt >= 12 and d > ci / 1.96 and (pick is None or d > pick[0]):
                pick = (d, n)
        if pick:
            best = cands[pick[1]]
            log.write(f"   -> new best {pick[1]} ({pick[0]:+.1f})\n")
        log.flush()
    # the test the search never saw: validation maps, and the tuning maps on fresh seeds
    write("tsstart", start, extra)
    write("tsfinal", best, extra)
    ok = True
    for label, maps, seeds in (("TS_val", solo.VAL, [2001, 2002, 2003, 2004]), ("TS_fresh", solo.TUNE, [2011, 2012, 2013])):
        out = solo.run(["tsstart", "tsfinal"], maps, seeds, a.minutes, a.parallel, label)
        res = solo.table(out, ["tsstart", "tsfinal"], a.minutes)
        d, ci, cnt = res.get("tsfinal", (0, 0, 0))
        log.write(f"{label}: final vs start {d:+.1f} +- {ci:.1f} over {cnt}\n"); log.flush()
        ok = ok and d > 0
    diff = {k: v for k, v in best.items() if v != start.get(k)}
    if ok and diff:
        write("tunebest_solo", best, extra)
        log.write(f"== done: better on both sets: {json.dumps(diff)}\n")
    else:
        log.write(f"== done: not confirmed on unseen games: {json.dumps(diff)}\n")
    log.close()


if __name__ == "__main__":
    main()
