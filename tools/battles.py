#!/usr/bin/env python3
"""
v13 step 3: the fights of real games (the match recorder, -Dronstrat.recorder=true), checked against the combat model.

    py tools/battles.py <dir with ronstrat_matches/*.jsonl or match files ...> [--player T1-expert-1] [--list]

A battle = soldiers' deaths close in space and time (35 blocks, 20 s apart at most, 3 deaths or more). Its sides:
every soldier of each player within 30 blocks of the deaths in the 5 s snapshot before the first death. For each:
the model's forecast (tuning/combat_model.json, -1..+1 for the player), what each side lost (resources), where
(near whose base) and whether it was day. The summary says how often the player fought battles the model gave
it as lost, and what those cost - the decisions the model is meant to fix.
"""
import argparse, glob, json, math, os, pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import arena  # noqa: E402
import fit_combat as fc  # noqa: E402

ALIAS = {"iron_golem": "golem", "magma_cube": "magmacube", "wither_skeleton": "witherskeleton", "temporary_militia": "militia",
         "royal_guard": "guard", "poison_spider": "spider", "stray": "skeleton", "drowned": "zombie", "bogged": "skeleton"}
WORKERS = {"villager", "zombie_villager", "grunt"}


def norm(t):
    t = ALIAS.get(t, t)
    return t if t in arena.COST else None


def model():
    m = json.load(open(ROOT / "tuning" / "combat_model.json", encoding="utf-8"))
    md = fc.Model(m["types"], m["options"], m["rank"])
    md.p.update(m["params"])
    return md


def forecast(md, a, b, day):
    A = ({t: n for t, n in a.items() if t in md.T}, []); B = ({t: n for t, n in b.items() if t in md.T}, [])
    if not A[0] or not B[0]:
        return None
    r = {"_A": A, "_B": B, "_day": day}
    return md.predict(r)


def files(args):
    out = []
    for p in args:
        if os.path.isdir(p):
            out += glob.glob(os.path.join(p, "**", "*.jsonl"), recursive=True)
        else:
            out.append(p)
    return [f for f in out if "ronstrat_matches" in f.replace("\\", "/") or f.endswith("_server.jsonl")]


def battles(path, md, me=None):
    ev = []
    for l in open(path, encoding="utf-8"):
        try:
            ev.append(json.loads(l))
        except ValueError:
            pass
    players = [p["name"] for p in ev[0].get("players", [])] if ev and ev[0].get("ev") == "start" else []
    base = {}
    for r in ev:
        if r["ev"] == "bld" and r.get("what") == "placed" and r.get("owner") and r["owner"] not in base:
            base[r["owner"]] = (r["x"], r["z"])
    snaps = [r for r in ev if r["ev"] == "snap" and "units" in r]
    deaths = [r for r in ev if r["ev"] == "death" and r.get("owner") in players and norm(r.get("u", "")) and r.get("u") not in WORKERS]
    deaths.sort(key=lambda r: r["t"])
    groups = []
    for d in deaths:
        for g in groups:
            if d["t"] - g[-1]["t"] <= 20 and math.dist((d["x"], d["z"]), (g[-1]["x"], g[-1]["z"])) <= 35:
                g.append(d); break
        else:
            groups.append([d])
    out = []
    for g in groups:
        if len(g) < 3:
            continue
        t0 = g[0]["t"]; cx = sum(d["x"] for d in g) / len(g); cz = sum(d["z"] for d in g) / len(g)
        sides = {}
        for p in players:
            before = [s for s in snaps if s["p"] == p and s["t"] <= t0]
            if not before:
                continue
            s = before[-1]; c = {}
            for u in s["units"].split("|"):
                bits = u.split(":")
                if len(bits) < 3:
                    continue
                t = norm(bits[0])
                if t and math.dist((int(bits[1]), int(bits[2])), (cx, cz)) <= 30:
                    c[t] = c.get(t, 0) + 1
            sides[p] = c
        if len(sides) < 2 or any(not c for c in sides.values()):
            continue
        p1, p2 = players[0], players[1]
        lost = {p: sum(sum(arena.COST[norm(d["u"])][:3]) for d in g if d["owner"] == p) for p in players}
        day = ((t0 * 20) % 24000) < 12000
        f = forecast(md, sides[p1], sides[p2], day)
        if f is None:
            continue
        near = min(base, key=lambda o: math.dist(base[o], (cx, cz))) if base else "?"
        out.append({"t": t0, "x": int(cx), "z": int(cz), "near": near, "day": day, "sides": sides, "forecast": f, "lost": lost,
                    "players": (p1, p2)})
    return ev[0].get("map", "?"), players, out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("paths", nargs="+")
    ap.add_argument("--player", default="T1-expert-1")
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()
    md = model()
    tot = {"n": 0, "bad": 0, "badLost": 0, "badKilled": 0, "lost": 0, "killed": 0, "agree": 0}
    for f in files(a.paths):
        mp, players, bs = battles(f, md)
        if a.player not in players:
            continue
        other = [p for p in players if p != a.player][0]
        if a.list:
            print(f"== {mp}  {os.path.basename(f)}")
        for b in bs:
            fp = b["forecast"] if b["players"][0] == a.player else -b["forecast"]
            mine, theirs = b["lost"][a.player], b["lost"][other]
            tot["n"] += 1; tot["lost"] += mine; tot["killed"] += theirs
            won = theirs > mine
            if (fp > 0) == won:
                tot["agree"] += 1
            if fp < -0.2:
                tot["bad"] += 1; tot["badLost"] += mine; tot["badKilled"] += theirs
            if a.list:
                print(f"  {b['t'] // 60:2d}:{b['t'] % 60:02d} {'day  ' if b['day'] else 'night'} near {b['near'][:12]:12s} forecast {fp:+.2f}  lost {mine:5d} killed {theirs:5d}  "
                      f"ours {b['sides'][a.player]}  theirs {b['sides'][other]}")
    n = max(1, tot["n"])
    print(f"\n{tot['n']} battles for {a.player}: lost {tot['lost']} killed {tot['killed']} (resources); model called the winner in {100 * tot['agree'] / n:.0f}%")
    print(f"battles the model gave as lost (forecast < -0.2): {tot['bad']} ({100 * tot['bad'] / n:.0f}%), there lost {tot['badLost']} killed {tot['badKilled']}")


if __name__ == "__main__":
    main()
