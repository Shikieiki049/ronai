#!/usr/bin/env python3
"""
(1+lambda) search over the expert's strategic numbers, scored against RON's own BRUTAL bot (and a mirror
check for the winner). Like tune_econ.py: screening, then the best two re-played on fresh seeds.

    py tools/tune_ron.py --generations 6 --lam 2 [--parallel 16] [--base lgs]

Each candidate is a profile tuning/profiles/tr<g><x>.json layered on --base (a profile name or ""). It plays
RON BRUTAL (-AltRon) on the 7 RON maps x seeds x both seats; score = win share, timeouts count half.
Writes tuning/profiles/tunebest_ron.json and the log tuning/tune_ron.log.
"""
import argparse, json, pathlib, random, subprocess, time

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAPS = ["Mirs_Landing", "Berlingrad_Classic", "Cabana_Republic", "Infinity_Isle", "Snowplow", "Post-Youth", "Oceanborn"]

SPACE = {
    "fieldsScale":       (1.0, 1.7, False),
    "villagersFirst":    (0, 1, True),
    "villagersFirstGap": (0, 5, True),
    "boomWorkers":       (20, 34, True),
    "armyPerWorker":     (0.4, 1.2, False),
    "vilCap":            (32, 46, True),
    "maxBarracks":       (3, 6, True),
    "openingBreak":      (1, 3, False),
    "rushSoldiers":      (2, 4, True),
    "openingMinutes":    (8, 13, True),
    "boomPunishPop":     (8, 30, True),
    "houseBuffer":       (5, 13, True),
}


def load(name):
    if not name:
        return {}
    return json.loads((ROOT / "tuning/profiles" / f"{name}.json").read_text(encoding="utf-8"))


def defaults(keys):
    """the expert column of BotParams for these keys"""
    src = (ROOT / "src/main/java/com/ronstrat/BotParams.java").read_text(encoding="utf-8")
    out = {}
    for k in keys:
        i = src.find(f'p.put("{k}",')
        if i < 0:
            continue
        nums = src[i:src.index(")", i)].split(",")[1:]
        out[k] = float(nums[3])
    return out


def mutate(p, sigma, rng):
    q = dict(p)
    for k in rng.sample(list(SPACE), k=rng.randint(1, 3)):
        lo, hi, integer = SPACE[k]
        v = q[k] + rng.gauss(0, sigma * (hi - lo))
        v = min(hi, max(lo, v))
        q[k] = int(round(v)) if integer else round(v, 3)
    return q


def evaluate(name, params, base, seeds, parallel):
    prof = dict(load(base)); prof.update(params)
    (ROOT / "tuning/profiles" / f"{name}.json").write_text(json.dumps(prof, indent=1), encoding="utf-8")
    suite = {"description": f"tuner {name} vs RON brutal", "items": [
        {"name": "first", "spec": f"expert:{name} vs expert@ron readied nohandicap", "us": 0, "pair": name, "maps": MAPS, "seeds": seeds},
        {"name": "second", "spec": f"expert@ron vs expert:{name} readied nohandicap", "us": 1, "pair": name, "maps": MAPS, "seeds": seeds}]}
    (ROOT / "tools/suites" / f"{name}.json").write_text(json.dumps(suite, indent=1), encoding="utf-8")
    label = f"TR_{name}"
    subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ROOT / "suite.ps1"),
                    "-Suite", name, "-Label", label, "-Full", "-AltRon", "-Parallel", str(parallel), "-ParallelIdle", str(parallel),
                    "-GlobalMax", "16"],
                   cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    out = ROOT / "results" / f"{label}.jsonl"
    sc = []
    if out.exists():
        for line in out.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            r = json.loads(line)
            us = next((i for i, t in enumerate(r["teams"]) if not t[0]["name"].startswith("Bot")), 0)
            sc.append(0.5 if r.get("timeout") else (1.0 if r["winnerTeam"] == us else 0.0))
    return (sum(sc) / len(sc) if sc else 0.0), len(sc)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--generations", type=int, default=6)
    ap.add_argument("--lam", type=int, default=2)
    ap.add_argument("--parallel", type=int, default=16)
    ap.add_argument("--base", default="")
    ap.add_argument("--seed", type=int, default=5)
    a = ap.parse_args()
    rng = random.Random(a.seed)
    base = load(a.base)
    best = defaults(SPACE.keys())
    best.update({k: v for k, v in base.items() if k in SPACE})
    log = (ROOT / "tuning" / "tune_ron.log").open("a", encoding="utf-8")
    log.write(f"\n== start {time.strftime('%Y-%m-%d %H:%M')} base={a.base} start={json.dumps(best)}\n")
    best_score, games = evaluate("tr00", best, a.base, [401, 402], a.parallel)
    log.write(f"start point {best_score:.4f} over {games}\n"); log.flush()
    tried = []
    for g in range(a.generations):
        for i in range(a.lam):
            cand = mutate(best, 0.2, rng)
            name = f"tr{g + 1}{chr(97 + i)}"
            s, n = evaluate(name, cand, a.base, [401, 402], a.parallel)
            diff = {k: v for k, v in cand.items() if v != best.get(k)}
            log.write(f"gen {g + 1} {name} {s:.4f} over {n}  {json.dumps(diff)}\n"); log.flush()
            tried.append((s, name, cand))
            if n >= 20 and s > best_score + 0.02:
                best, best_score = cand, s
                log.write(f"   -> new best {s:.4f}\n"); log.flush()
    # fresh seeds for the best two against the start point
    verified = None
    start_v, _ = evaluate("tr00v", defaults(SPACE.keys()) | {k: v for k, v in base.items() if k in SPACE}, a.base, [403, 404, 405], a.parallel)
    log.write(f"verify start point -> {start_v:.4f}\n"); log.flush()
    for s, name, cand in sorted(tried, key=lambda x: -x[0])[:2]:
        vs, vn = evaluate(name + "v", cand, a.base, [403, 404, 405], a.parallel)
        log.write(f"verify {name} -> {vs:.4f} over {vn}\n"); log.flush()
        if vn >= 36 and vs > start_v + 0.03 and (verified is None or vs > verified[0]):
            verified = (vs, cand)
    if verified:
        prof = dict(base); prof.update(verified[1])
        (ROOT / "tuning/profiles/tunebest_ron.json").write_text(json.dumps(prof, indent=1), encoding="utf-8")
        log.write(f"== done: verified {verified[0]:.4f} vs start {start_v:.4f}: {json.dumps(verified[1])}\n")
    else:
        log.write(f"== done: nothing beat the start point ({start_v:.4f}) on fresh seeds\n")
    log.close()


if __name__ == "__main__":
    main()
