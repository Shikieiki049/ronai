#!/usr/bin/env python3
"""
Calibrate the AI's fight forecast (CombatSim.java) against the arena.

    py tools/simfit.py results/AST.jsonl.stats results/AM2.jsonl results/AC1.jsonl results/AU1.jsonl

The stats file (ArenaTest.dumpStats) holds the forecast's numbers for every unit of every army spec;
the result files the real fights. This reimplements CombatSim.run (half-second steps on a line, melee
walks into reach, at most three melee on one target) and fits, per unit type, a multiplier on its
damage per second - and checks the rule "melee cannot hit flying units". Prints the fit and writes
tuning/sim_calibration.json, which CombatSim reads (parameter simV2).
"""
import json, pathlib, statistics as st, sys
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parent.parent
STEP, MAX_T, MELEE_PER = 0.5, 90.0, 3


def load_stats(path):
    stats = defaultdict(dict)  # spec -> type -> unit dict
    for l in open(path, encoding="utf-8"):
        if l.strip():
            r = json.loads(l)
            stats[r["spec"]][r["type"]] = r
    return stats


def army_units(spec, stats):
    """the units of an army spec ("vindicator*9+witch*3 lingering"): one entry per unit, from the dumped stats"""
    s = stats.get(spec)
    if not s:
        return None
    head = spec.split()[0]
    out = []
    for part in head.split("+"):
        name, _, n = part.partition("*")
        t = name + "_unit"
        if name == "golem": t = "iron_golem_unit"
        if t not in s:
            return None
        out += [s[t]] * int(n or 1)
    return out


def sim(a, b, mult, fly_rule, gap=32.0):
    """value for side a: share of hit points left on a minus on b (CombatSim.run on the arena's start)"""
    def mk(us, pos):
        return [{"hp": u["hp"] / (1 - u["armour"]), "hp0": u["hp"] / (1 - u["armour"]), "dps": u["dps"] * mult.get(u["type"], 1.0),
                 "range": max(1.5, u["range"]), "speed": u["speed"], "pos": pos, "fly": u["flying"], "t": u["type"]} for u in us]
    A, B = mk(a, 0.0), mk(b, gap)
    hA, hB = sum(u["hp"] for u in A), sum(u["hp"] for u in B)
    t = 0.0
    def step(fr, to, d):
        dmg = [0.0] * len(to)
        hit = [0] * len(to)
        for u in fr:
            if u["hp"] <= 0: continue
            melee = u["range"] <= 5
            best, bd = -1, 1e9
            for i, v in enumerate(to):
                if v["hp"] <= 0: continue
                if melee and hit[i] >= MELEE_PER: continue
                if melee and fly_rule and v["fly"]: continue
                dd = abs(v["pos"] - u["pos"]) + (50 if dmg[i] >= v["hp"] else 0)
                if dd < bd: bd, best = dd, i
            if best < 0: continue
            v = to[best]
            dist = abs(v["pos"] - u["pos"])
            if dist <= u["range"]:
                dmg[best] += u["dps"] * STEP
                if melee: hit[best] += 1
            else:
                u["pos"] += d * min(u["speed"] * STEP, dist - u["range"] + 0.1)
        return dmg
    while t < MAX_T and any(u["hp"] > 0 for u in A) and any(u["hp"] > 0 for u in B):
        dB = step(A, B, +1); dA = step(B, A, -1)
        for u, x in zip(B, dB): u["hp"] -= x
        for u, x in zip(A, dA): u["hp"] -= x
        t += STEP
    la = sum(max(0, u["hp"]) for u in A) / hA
    lb = sum(max(0, u["hp"]) for u in B) / hB
    return la - lb


def main():
    stats = load_stats(sys.argv[1])
    pairs = defaultdict(list)  # (specA, specB) -> real values for A
    for f in sys.argv[2:]:
        for l in open(f, encoding="utf-8"):
            if not l.strip(): continue
            r = json.loads(l)
            if "aLeft" not in r: continue
            pairs[(r["a"], r["b"])].append(r["aLeft"] - r["bLeft"])
    data = []
    for (sa, sb), vs in pairs.items():
        ua, ub = army_units(sa, stats), army_units(sb, stats)
        if ua and ub and sa != sb:
            data.append((ua, ub, st.mean(vs), len(vs), sa, sb))
    print(f"{len(data)} army pairs with stats ({sum(d[3] for d in data)} fights)")
    types = sorted({u["type"] for d in data for side in d[:2] for u in side})

    def err(mult, fly):
        e = n = 0
        agree = 0
        for ua, ub, real, k, *_ in data:
            v = sim(ua, ub, mult, fly)
            e += k * (v - real) ** 2; n += k
            agree += k * ((v > 0) == (real > 0))
        return e / n, agree / n

    base = err({}, False)
    print(f"as it is:                  mse {base[0]:.3f}  winner right {100 * base[1]:.0f}%")
    fly = err({}, True)
    print(f"+ melee cannot hit flyers:  mse {fly[0]:.3f}  winner right {100 * fly[1]:.0f}%")
    use_fly = fly[0] < base[0]
    mult = {t: 1.0 for t in types}
    best = err(mult, use_fly)[0]
    for rnd in range(2):
        for t in types:
            for m in (0.4, 0.6, 0.8, 1.25, 1.6, 2.0, 2.5, 3.0):
                trial = dict(mult); trial[t] = mult[t] * m if rnd else m
                e = err(trial, use_fly)[0]
                if e < best - 1e-4:
                    best, mult = e, trial
        print(f"round {rnd + 1}: mse {best:.3f}  " + " ".join(f"{t.replace('_unit', '')}={mult[t]:.2f}" for t in types))
    final = err(mult, use_fly)
    print(f"fitted:                    mse {final[0]:.3f}  winner right {100 * final[1]:.0f}%")
    out = {"flyingRule": use_fly, "dpsMultiplier": {t: round(m, 3) for t, m in mult.items()}, "mse": round(final[0], 4),
           "winnerRight": round(final[1], 3), "baseline": {"mse": round(base[0], 4), "winnerRight": round(base[1], 3)}}
    (ROOT / "tuning" / "sim_calibration.json").write_text(json.dumps(out, indent=1), encoding="utf-8")
    print("tuning/sim_calibration.json written")


if __name__ == "__main__":
    main()
