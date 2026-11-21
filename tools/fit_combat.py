#!/usr/bin/env python3
"""
v13 step 2: fit the combat model on every arena fight we have (results/*.jsonl lines with aLeft/bLeft).

    py tools/fit_combat.py [--rank 2] [--steps 3000] [--out tuning/combat_model.json]

Model, for side X against side Y:
  e_Y(w)  = Y's spend share on unit type w
  eff_u   = exp( lv[u] + sum_r a[u][r] * sum_w b[w][r] * e_Y(w) + day[u] * isDay + sum_o opt[o] (o applies to u) )
  P_X     = sum_u n_u * eff_u                       (the side's fighting value against this enemy)
  y_hat   = tanh( alpha * (log P_A - log P_B) )     (y = aLeft - bLeft, hit points left: +1 A wins clean)
lv is the value of one unit of a type, a.b the counters (low rank, so 22 types need ~90 numbers, not 484),
day the burning units' loss by day, opt the upgrades. alpha carries Lanchester's exponent (a larger army
wins by more than its share). Pure python, analytic gradients, Adam. 20% of the fights (by name) held out.
Also prints the accuracy of the AI's current forecasts (CombatSim v1/v2, logged in the arena lines).
"""
import argparse, glob, json, math, pathlib, random, statistics as st, sys, zlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import arena  # noqa: E402

BURNS = {"zombie", "skeleton", "wraith", "necromancer", "stray", "drowned"}
WEARS = arena.WEARS
ENCH = {k: v[0] for k, v in arena.ENCHANT.items()}
RESEARCH = {"lingering": "witch", "healing": "witch", "vexes": "evoker", "windcallers": "windcaller", "golemsmith": "golem",
            "piercing": "pillager", "crossbows": "pillager", "axes": "vindicator", "vigor": "evoker"}


def parse(s):
    parts = s.split()
    units = {}
    for g in parts[0].split("+"):
        u, _, n = g.partition("*")
        units[u] = units.get(u, 0) + int(n or 1)
    return units, parts[1:]


def applies(o, u):
    if o in ("chain", "leather"):
        return u in WEARS
    if o in ENCH:
        return ENCH[o] == u
    return RESEARCH.get(o) == u


def load():
    rows = []
    for f in sorted(glob.glob(str(ROOT / "results" / "*.jsonl"))):
        try:
            with open(f, encoding="utf-8") as fh:
                for l in fh:
                    if '"aLeft"' not in l:
                        break
                    r = json.loads(l)
                    try:
                        ua, oa = parse(r["a"]); ub, ob = parse(r["b"])
                    except Exception:
                        continue
                    if any(u not in arena.COST for u in list(ua) + list(ub)):
                        continue
                    r["_A"], r["_B"] = (ua, oa), (ub, ob)
                    r["_day"] = "@day" in r["arena"]
                    rows.append(r)
        except OSError:
            pass
    return rows


def shares(units):
    c = {u: n * sum(arena.COST[u][:3]) for u, n in units.items()}
    t = sum(c.values()) or 1
    return {u: x / t for u, x in c.items()}


class Model:
    def __init__(self, types, opts, rank):
        self.T, self.O, self.R = types, opts, rank
        rnd = random.Random(7)
        self.p = {"alpha": 1.0}
        for u in types:
            self.p["lv:" + u] = math.log(1.0 / max(1, sum(arena.COST[u][:3])) * 160)
            self.p["day:" + u] = 0.0
            for r in range(rank):
                self.p[f"a:{u}:{r}"] = rnd.gauss(0, 0.05)
                self.p[f"b:{u}:{r}"] = rnd.gauss(0, 0.05)
        for o in opts:
            self.p["opt:" + o] = 0.0

    def side(self, X, Y, day, grad=None, scale=1.0):
        (ux, ox), (uy, _) = X, Y
        e = shares(uy)
        B = [sum(self.p[f"b:{w}:{r}"] * s for w, s in e.items()) for r in range(self.R)]
        eff = {}
        for u, n in ux.items():
            z = self.p["lv:" + u] + sum(self.p[f"a:{u}:{r}"] * B[r] for r in range(self.R))
            if day and u in BURNS:
                z += self.p["day:" + u]
            z += sum(self.p["opt:" + o] for o in ox if o in self.O and applies(o, u))
            eff[u] = n * math.exp(z)
        P = sum(eff.values())
        if grad is not None:
            # d logP / d param = (1/P) dP/dparam ; scaled by `scale` (dloss/dlogP)
            for u, v in eff.items():
                w = scale * v / P
                grad["lv:" + u] = grad.get("lv:" + u, 0) + w
                if day and u in BURNS:
                    grad["day:" + u] = grad.get("day:" + u, 0) + w
                for o in ox:
                    if o in self.O and applies(o, u):
                        grad["opt:" + o] = grad.get("opt:" + o, 0) + w
                for r in range(self.R):
                    grad[f"a:{u}:{r}"] = grad.get(f"a:{u}:{r}", 0) + w * B[r]
                    ar = self.p[f"a:{u}:{r}"]
                    for ww, s in e.items():
                        grad[f"b:{ww}:{r}"] = grad.get(f"b:{ww}:{r}", 0) + w * ar * s
        return math.log(P)

    def predict(self, r):
        d = self.side(r["_A"], r["_B"], r["_day"]) - self.side(r["_B"], r["_A"], r["_day"])
        return math.tanh(self.p["alpha"] * d)

    def step_grad(self, rows, l2):
        g, loss = {}, 0.0
        for r in rows:
            la = self.side(r["_A"], r["_B"], r["_day"]); lb = self.side(r["_B"], r["_A"], r["_day"])
            d = la - lb; yh = math.tanh(self.p["alpha"] * d); y = r["aLeft"] - r["bLeft"]
            err = yh - y; loss += err * err
            dz = 2 * err * (1 - yh * yh)
            g["alpha"] = g.get("alpha", 0) + dz * d
            s = dz * self.p["alpha"]
            self.side(r["_A"], r["_B"], r["_day"], g, s)
            self.side(r["_B"], r["_A"], r["_day"], g, -s)
        n = len(rows)
        for k in g:
            g[k] /= n
        for k, v in self.p.items():
            if k.startswith(("a:", "b:", "day:", "opt:")):
                g[k] = g.get(k, 0) + 2 * l2 * v
        return g, loss / n


def evaluate(pred, rows):
    ys = [r["aLeft"] - r["bLeft"] for r in rows]
    ps = [pred(r) for r in rows]
    t = [(y, p) for y, p in zip(ys, ps) if abs(y) > 0.05]
    acc = sum((y > 0) == (p > 0) for y, p in t) / max(1, len(t))
    corr = st.correlation(ys, ps) if len(rows) > 2 and st.pstdev(ps) > 0 else 0
    return acc, corr, len(t)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rank", type=int, default=2)
    ap.add_argument("--steps", type=int, default=3000)
    ap.add_argument("--l2", type=float, default=0.002)
    ap.add_argument("--lr", type=float, default=0.03)
    ap.add_argument("--out", default=str(ROOT / "tuning" / "combat_model.json"))
    a = ap.parse_args()
    rows = load()
    test = [r for r in rows if zlib.crc32(r["arena"].rstrip("~").encode()) % 5 == 0]
    train = [r for r in rows if zlib.crc32(r["arena"].rstrip("~").encode()) % 5 != 0]
    types = sorted({u for r in rows for s in (r["_A"], r["_B"]) for u in s[0]})
    opts = sorted({o for r in rows for s in (r["_A"], r["_B"]) for o in s[1]})
    print(f"{len(rows)} fights ({len(train)} train, {len(test)} held out), {len(types)} unit types, {len(opts)} options")
    sims = [r for r in test if "sim2A" in r]
    if sims:
        print("held-out, the AI's forecasts now:  CombatSim v1 acc %.3f corr %.3f (n %d)" % evaluate(lambda r: r["simA"] - r["simB"], sims))
        print("                                    CombatSim v2 acc %.3f corr %.3f (n %d)" % evaluate(lambda r: r["sim2A"] - r["sim2B"], sims))
    m = Model(types, opts, a.rank)
    mo, ve = {k: 0.0 for k in m.p}, {k: 0.0 for k in m.p}
    b1, b2, eps = 0.9, 0.999, 1e-8
    for t in range(1, a.steps + 1):
        g, loss = m.step_grad(train, a.l2)
        for k in m.p:
            gk = g.get(k, 0.0)
            mo[k] = b1 * mo[k] + (1 - b1) * gk; ve[k] = b2 * ve[k] + (1 - b2) * gk * gk
            m.p[k] -= a.lr * (mo[k] / (1 - b1 ** t)) / (math.sqrt(ve[k] / (1 - b2 ** t)) + eps)
        if t % 250 == 0 or t == 1:
            tr = evaluate(m.predict, train); te = evaluate(m.predict, test)
            print(f"step {t:5d} loss {loss:.4f}  train acc {tr[0]:.3f} corr {tr[1]:.3f}  held-out acc {te[0]:.3f} corr {te[1]:.3f}", flush=True)
    te = evaluate(m.predict, test)
    if sims:
        print("held-out, the model on the same fights as CombatSim: acc %.3f corr %.3f (n %d)" % evaluate(m.predict, sims))
    # one unit's value per 100 resources against an even mix, for reading
    print("\nvalue of 100 resources of a type (lv, no counters):")
    for u in sorted(types, key=lambda u: -m.p["lv:" + u]):
        c = sum(arena.COST[u][:3]) or 1
        print(f"  {u:15s} unit {math.exp(m.p['lv:' + u]):6.3f}  per 100 res {100 * math.exp(m.p['lv:' + u]) / c:6.3f}  day {m.p['day:' + u]:+.2f}")
    for o in opts:
        print(f"  option {o:12s} {m.p['opt:' + o]:+.3f}")
    json.dump({"rank": a.rank, "params": m.p, "types": types, "options": opts, "burns": sorted(BURNS),
               "heldOut": {"acc": te[0], "corr": te[1], "n": te[2]}, "fights": len(rows)},
              open(a.out, "w", encoding="utf-8"), indent=1)
    print("written", a.out)


if __name__ == "__main__":
    main()
