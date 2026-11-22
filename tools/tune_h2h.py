#!/usr/bin/env python3
"""
v13 step 5: (1+lambda) search over the fight thresholds, scored head-to-head against a frozen build (tools/freeze.py).

    py tools/tune_h2h.py monsters --old b223 --generations 4 --lam 2 [--parallel 16]

Each candidate is a profile tuning/profiles/th_<faction><g><x>.json; it plays the frozen build of the same faction
on the 7 test maps x 3 seeds x both seats (42 games); score = our share, timeouts half (0.5 = as good as the old).
A candidate replaces the best when it scores 4 points above it over 36+ games; the best two are re-played on fresh
seeds (104-106) and kept only if they beat 0.5 there too. Writes tuning/profiles/tunebest_h2h_<faction>.json and
tuning/tune_h2h.log.
"""
import argparse, json, pathlib, random, subprocess, sys, time

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
from tune_ron import defaults  # noqa: E402

MAPS = ["Mirs_Landing", "Berlingrad_Classic", "Cabana_Republic", "Infinity_Isle", "Snowplow", "Post-Youth", "Oceanborn"]
# knob: (low, high, integer?) - the fight thresholds the combat model made meaningful
SPACE = {
    "attackAdvantage":    (1.0, 2.0, False),
    "retreatRatio":       (0.25, 0.7, False),
    "modelAttackMin":     (-0.5, 0.3, False),
    "attackMinArmyPop":   (20, 60, True),
    "attackWaveCooldown": (20, 120, True),
}


def mutate(p, sigma, rng):
    q = dict(p)
    for k in rng.sample(list(SPACE), k=rng.randint(1, 2)):
        lo, hi, integer = SPACE[k]
        v = min(hi, max(lo, q[k] + rng.gauss(0, sigma * (hi - lo))))
        q[k] = int(round(v)) if integer else round(v, 3)
    return q


def evaluate(name, params, faction, old, seeds, parallel):
    (ROOT / "tuning/profiles" / f"{name}.json").write_text(json.dumps(params, indent=1), encoding="utf-8")
    fac = "" if faction == "villagers" else "#" + faction
    new, ref = f"expert{fac}:{name}", f"expert{fac}@{old}"
    night = " night"
    suite = {"description": f"tuner {name} vs {old}", "items": [
        {"name": "new first", "spec": f"{new} vs {ref} readied nohandicap{night}", "us": 0, "pair": "h2h", "maps": MAPS, "seeds": seeds},
        {"name": "old first", "spec": f"{ref} vs {new} readied nohandicap{night}", "us": 1, "pair": "h2h", "maps": MAPS, "seeds": seeds}]}
    (ROOT / "tools/suites" / f"{name}.json").write_text(json.dumps(suite, indent=1), encoding="utf-8")
    label = f"TH_{name}"
    subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ROOT / "suite.ps1"), "-Suite", name, "-Label", label,
                    "-Full", "-Parallel", str(parallel), "-ParallelIdle", str(parallel), "-GlobalMax", "24"],
                   cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    out = ROOT / "results" / f"{label}.jsonl"
    sc = []
    if out.exists():
        for line in out.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            r = json.loads(line)
            us = 0 if "@" + old not in r["spec"].split(" vs ")[0] else 1
            sc.append(0.5 if r.get("timeout") else (1.0 if r["winnerTeam"] == us else 0.0))
    return (sum(sc) / len(sc) if sc else 0.0), len(sc)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("faction", choices=["villagers", "monsters", "piglins"])
    ap.add_argument("--old", default="b223")
    ap.add_argument("--generations", type=int, default=4)
    ap.add_argument("--lam", type=int, default=2)
    ap.add_argument("--parallel", type=int, default=16)
    ap.add_argument("--seed", type=int, default=13)
    a = ap.parse_args()
    rng = random.Random(a.seed)
    tag = a.faction[0]
    start = defaults(SPACE.keys())
    best, best_score = dict(start), 0.5   # the start point is the old build itself: 0.5 by definition
    log = (ROOT / "tuning" / "tune_h2h.log").open("a", encoding="utf-8")
    log.write(f"\n== start {time.strftime('%Y-%m-%d %H:%M')} {a.faction} vs {a.old} start={json.dumps(start)}\n"); log.flush()
    tried = []
    for g in range(a.generations):
        for i in range(a.lam):
            cand = mutate(best, 0.25, rng)
            name = f"th_{tag}{g + 1}{chr(97 + i)}"
            s, n = evaluate(name, cand, a.faction, a.old, [101, 102, 103], a.parallel)
            diff = {k: v for k, v in cand.items() if v != start.get(k)}
            log.write(f"gen {g + 1} {name} {s:.4f} over {n}  {json.dumps(diff)}\n"); log.flush()
            tried.append((s, name, cand))
            if n >= 36 and s > best_score + 0.04:
                best, best_score = cand, s
                log.write(f"   -> new best {s:.4f}\n"); log.flush()
    verified = None
    for s, name, cand in sorted(tried, key=lambda x: -x[0])[:2]:
        if s <= 0.52:
            continue
        vs, vn = evaluate(name + "v", cand, a.faction, a.old, [104, 105, 106], a.parallel)
        log.write(f"verify {name} ({s:.4f}) -> {vs:.4f} over {vn} on fresh seeds\n"); log.flush()
        if vn >= 36 and vs > 0.53 and (verified is None or (s + vs) > verified[0]):
            verified = (s + vs, cand)
    if verified:
        (ROOT / f"tuning/profiles/tunebest_h2h_{a.faction}.json").write_text(json.dumps(verified[1], indent=1), encoding="utf-8")
        log.write(f"== done {a.faction}: kept {json.dumps({k: v for k, v in verified[1].items() if v != start.get(k)})} (two rounds {verified[0] / 2:.4f})\n")
    else:
        log.write(f"== done {a.faction}: nothing beat the old build on both seed sets\n")
    log.close()


if __name__ == "__main__":
    main()
