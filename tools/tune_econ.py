#!/usr/bin/env python3
"""
(1+lambda) search over the expert's economy numbers, scored by short self-play games.

    py tools/tune_econ.py --generations 8 --lam 3 [--parallel 16] [--minutes 15]

Each candidate is a parameter profile (tuning/profiles/tuneN.json). It plays the current default expert
on 6 maps x 2 seeds x both seat orders, games stopped at --minutes. Score = mean over games of
  own (villagers + 2 x soldiers) / (own + theirs)   at the stop time   (0.5 = level)
The best profile so far is kept in tuning/profiles/tunebest.json; the log is tuning/tune_econ.log.
"""
import argparse, json, pathlib, random, statistics, subprocess, sys, time

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAPS = ["Mirs_Landing", "Berlingrad_Classic", "Cabana_Republic", "Infinity_Isle", "Snowplow", "Post-Youth"]

# knob: (low, high, integer?)
SPACE = {
    "openingLead":      (0.0, 1.5, False),
    "openingMinutes":   (8, 13, True),
    "hunters":          (2, 6, True),
    "houseBuffer":      (4, 14, True),
    "maxParallelHouses": (1, 4, True),
    "buildersPerBuilding": (1, 3, True),
    "workerQueue":      (1, 4, True),
    "econPhase2":       (0, 1, True),
    "econWorkers":      (28, 40, True),
    "phase2Ore":        (0.05, 0.3, False),
    "barracksPerWorkers": (6, 16, True),
    "maxBarracks":      (2, 6, True),
    "armyPerWorker":    (0.4, 1.4, False),
}
DEFAULT = {"openingLead": 0.5, "openingMinutes": 10, "hunters": 4, "houseBuffer": 9, "maxParallelHouses": 3,
           "buildersPerBuilding": 2, "workerQueue": 2, "econPhase2": 0, "econWorkers": 34, "phase2Ore": 0.15,
           "barracksPerWorkers": 10, "maxBarracks": 4, "armyPerWorker": 0.8}


def mutate(p, sigma, rng):
    q = dict(p)
    keys = rng.sample(list(SPACE), k=rng.randint(2, 5))
    for k in keys:
        lo, hi, integer = SPACE[k]
        v = q[k] + rng.gauss(0, sigma * (hi - lo))
        v = min(hi, max(lo, v))
        q[k] = int(round(v)) if integer else round(v, 3)
    return q


def evaluate(name, params, args):
    (ROOT / "tuning/profiles" / f"{name}.json").write_text(json.dumps(params, indent=2), encoding="utf-8")
    suite = {"description": f"tuner candidate {name}", "items": [
        {"name": "cand first", "spec": f"expert:{name} vs expert readied nohandicap", "us": 0, "pair": name,
         "maps": MAPS, "seeds": args.seeds, "extra": f"-Dronstrat.stopAt={args.minutes * 60}"},
        {"name": "cand second", "spec": f"expert vs expert:{name} readied nohandicap", "us": 1, "pair": name,
         "maps": MAPS, "seeds": args.seeds, "extra": f"-Dronstrat.stopAt={args.minutes * 60}"}]}
    (ROOT / "tools/suites" / f"{name}.json").write_text(json.dumps(suite, indent=1), encoding="utf-8")
    label = f"T_{name}"
    subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ROOT / "suite.ps1"),
                    "-Suite", name, "-Label", label, "-Full", "-Parallel", str(args.parallel), "-ParallelIdle", str(args.parallel)],
                   cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    shares = []
    out = ROOT / "results" / f"{label}.jsonl"
    if not out.exists():
        return 0.0, 0
    for line in out.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        r = json.loads(line)
        val = []
        for team in r["teams"]:
            b = team[0]
            s = (b.get("metrics") or {}).get("series") or []
            row = None
            for x in s:
                if x[0] <= args.minutes * 60:
                    row = x
            val.append(0 if row is None else row[1] + 2 * row[2])
        ours = 0 if r["teams"][0][0].get("profile") == name else 1
        tot = val[0] + val[1]
        if tot > 0:
            shares.append(val[ours] / tot)
    return (statistics.mean(shares) if shares else 0.0), len(shares)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--generations", type=int, default=8)
    ap.add_argument("--lam", type=int, default=3)
    ap.add_argument("--parallel", type=int, default=16)
    ap.add_argument("--minutes", type=int, default=15)
    ap.add_argument("--sigma", type=float, default=0.2)
    ap.add_argument("--seeds", type=int, nargs="+", default=[601, 602])
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()
    rng = random.Random(a.seed)
    log = ROOT / "tuning" / "tune_econ.log"
    best_file = ROOT / "tuning/profiles/tunebest.json"
    best = json.loads(best_file.read_text()) if best_file.exists() else dict(DEFAULT)
    best_score = 0.5  # the default against itself
    n = 0
    tried = []
    with log.open("a", encoding="utf-8") as lf:
        lf.write(f"\n== start {time.strftime('%Y-%m-%d %H:%M')} minutes={a.minutes} seeds={a.seeds}\n")
        for g in range(a.generations):
            for i in range(a.lam):
                n += 1
                cand = mutate(best, a.sigma, rng)
                name = f"tune{g}{chr(97 + i)}"
                score, games = evaluate(name, cand, a)
                diff = {k: v for k, v in cand.items() if v != DEFAULT.get(k)}
                lf.write(f"gen {g} {name} score {score:.4f} over {games} games  {json.dumps(diff)}\n")
                lf.flush()
                tried.append((score, name, cand))
                # a candidate replaces the best only when it beats the default by a clear margin and the best so far
                if games >= 20 and score > best_score + 0.005:
                    best, best_score = cand, score
                    best_file.write_text(json.dumps(best, indent=2), encoding="utf-8")
                    lf.write(f"   -> new best {best_score:.4f}\n")
                    lf.flush()
        # stage two: the best two of the screening (24 games each, noisy) play again on fresh seeds,
        # 60 games each; only a candidate that still beats the default there is kept (no winner's curse)
        verified = None
        for score, name, cand in sorted(tried, key=lambda x: -x[0])[:2]:
            if score <= 0.5:
                continue
            va = argparse.Namespace(**vars(a))
            va.seeds = [603, 604, 605, 606, 607]
            vscore, vgames = evaluate(name + "v", cand, va)
            lf.write(f"verify {name} -> {vscore:.4f} over {vgames} games\n")
            lf.flush()
            if vgames >= 50 and vscore > 0.51 and (verified is None or vscore > verified[0]):
                verified = (vscore, cand)
        if verified:
            best_file.write_text(json.dumps(verified[1], indent=2), encoding="utf-8")
            lf.write(f"== done: verified best {verified[0]:.4f} {json.dumps(verified[1])}\n")
        else:
            if best_file.exists():
                best_file.unlink()
            lf.write("== done: nothing beat the default on fresh seeds\n")


if __name__ == "__main__":
    main()
