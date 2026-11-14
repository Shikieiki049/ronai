#!/usr/bin/env python3
"""
The arena: armies spawned on a flat platform fight each other, no economy (ArenaTest.java). Builds the
unit matrix the composition planner reads.

    py tools/arena.py matrix --reps 4 --servers 4 --label AM1     every army of ARMIES against every other
    py tools/arena.py file my.txt --reps 4 --label X              the fights of a file "name | a | b"
    py tools/arena.py --report --label AM1                        the table of an earlier run

Every fight is played in both orientations (the side that spawns west / gets its order first) and --reps
times. Value of a fight for A = share of A's hit points left - share of B's left (+1 = A untouched, -1 = B).
The matrix is written to tuning/unit_matrix.json: for each pair of armies the mean value, wins and games.
"""
import argparse, json, pathlib, statistics as st, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
COST = {"vindicator": (160, 0, 0, 3), "pillager": (110, 80, 0, 3), "golem": (0, 50, 250, 4), "witch": (90, 90, 90, 3),
        "evoker": (140, 0, 120, 3), "ravager": (400, 50, 150, 7), "windcaller": (140, 60, 60, 3), "militia": (0, 0, 0, 0), "guard": (0, 0, 0, 5)}
OPTION_UNIT = {"leather": 30, "chain": 30}  # ore per unit that can wear it
ENCHANT = {"sharpness": ("vindicator", 90), "maiming": ("vindicator", 50), "quickcharge": ("pillager", 50), "multishot": ("pillager", 90), "vigor": ("evoker", 100)}
OPTION_ONCE = {"axes": 600, "crossbows": 900, "vexes": 800, "windcallers": 600, "lingering": 0, "golemsmith": 0}
WEARS = {"vindicator", "pillager", "evoker", "militia"}
BUDGET = 1920  # 12 vindicators


def unit_price(u, options):
    """one unit with its per-unit options (armour, enchantments); one-off research is not counted"""
    c = sum(COST[u][:3])
    for o in options.split():
        if o in OPTION_UNIT and u in WEARS:
            c += OPTION_UNIT[o]
        if o in ENCHANT and ENCHANT[o][0] == u:
            c += ENCHANT[o][1]
    return c


def army(mix, options=""):
    """mix: {unit: share of the budget}; unit counts that spend BUDGET with their armour and enchantments,
    so every army costs the same apart from one-off research"""
    parts = []
    for u, share in mix.items():
        n = max(1, round(BUDGET * share / unit_price(u, options)))
        parts.append(f"{u}*{n}")
    return ("+".join(parts) + " " + options).strip()


def cost(side):
    """total resources of a side, research included; and its population"""
    parts = side.split()
    total = pop = 0
    for g in parts[0].split("+"):
        u, _, n = g.partition("*")
        n = int(n or 1)
        total += n * sum(COST[u][:3]); pop += n * COST[u][3]
        for o in parts[1:]:
            if o in OPTION_UNIT and u in WEARS:
                total += n * OPTION_UNIT[o]
            if o in ENCHANT and ENCHANT[o][0] == u:
                total += n * ENCHANT[o][1]
    total += sum(OPTION_ONCE.get(o, 0) for o in parts[1:])
    return total, pop


ARMIES = {
    "vin": army({"vindicator": 1}),
    "pil": army({"pillager": 1}),
    "golem": army({"golem": 1}),
    "witch": army({"witch": 1}, "lingering"),
    "evoker": army({"evoker": 1}),
    "ravager": army({"ravager": 1}),
    "wind": army({"windcaller": 1}),
    "vin_sharp": army({"vindicator": 1}, "sharpness"),
    "vin_maim": army({"vindicator": 1}, "maiming"),
    "vin_chain_sharp": army({"vindicator": 1}, "chain sharpness"),
    "vin_leather": army({"vindicator": 1}, "leather"),
    "vin_chain": army({"vindicator": 1}, "chain"),
    "pil_quick": army({"pillager": 1}, "quickcharge"),
    "pil_multi": army({"pillager": 1}, "multishot"),
    "evoker_vigor": army({"evoker": 1}, "vigor"),
    "golem_smith": army({"golem": 1}, "golemsmith"),
    "pil_chain": army({"pillager": 1}, "chain"),
    "evoker_vex": army({"evoker": 1}, "vexes"),
    "wind_up": army({"windcaller": 1}, "windcallers"),
    "vin7_pil3": army({"vindicator": .7, "pillager": .3}),
    "vin5_pil5": army({"vindicator": .5, "pillager": .5}),
    "vin3_pil7": army({"vindicator": .3, "pillager": .7}),
    "vin7_witch3": army({"vindicator": .7, "witch": .3}, "lingering"),
    "vin7_evoker3": army({"vindicator": .7, "evoker": .3}),
    "vin7_wind3": army({"vindicator": .7, "windcaller": .3}),
    "vin6_golem4": army({"vindicator": .6, "golem": .4}),
    "vin5_pil3_witch2": army({"vindicator": .5, "pillager": .3, "witch": .2}, "lingering"),
    "golem5_pil5": army({"golem": .5, "pillager": .5}),
    "rav5_pil5": army({"ravager": .5, "pillager": .5}),
}


def run(fights, reps, servers, label):
    lines = []
    for r in range(reps):
        for name, a, b in fights:
            lines.append(f"{name} | {a} | {b}")
            lines.append(f"{name}~ | {b} | {a}")
    d = ROOT / "tmp" / "arena"
    d.mkdir(parents=True, exist_ok=True)
    plan = []
    for i in range(servers):
        f = d / f"{label}_{i}.txt"
        f.write_text("\n".join(lines[i::servers]) + "\n", encoding="utf-8")
        plan.append({"spec": "easy@idle vs easy@idle readied nohandicap", "seed": 700 + i, "map": str(ROOT / "tmp" / "maps" / "Mirs_Landing"),
                     "extra": "-Dronstrat.arena=" + str(f).replace("\\", "/")})
    pf = ROOT / "tmp" / f"{label}.plan.json"
    pf.write_text(json.dumps(plan, indent=1), encoding="utf-8")
    out = ROOT / "results" / f"{label}.jsonl"
    out.unlink(missing_ok=True)
    subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ROOT / "selfplay.ps1"), "-Plan", str(pf),
                    "-Out", str(out), "-Label", label, "-Parallel", str(servers), "-ParallelIdle", str(servers), "-GlobalMax", "20",
                    "-TimeoutMinutes", "120"], cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return out


def report(out, label):
    pairs = {}
    for line in out.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        r = json.loads(line)
        name, flip = r["arena"], r["arena"].endswith("~")
        v = r["aLeft"] - r["bLeft"]
        if flip:
            name, v = name[:-1], -v
        a, b = (r["b"], r["a"]) if flip else (r["a"], r["b"])
        p = pairs.setdefault(name, {"a": a, "b": b, "v": [], "west": [], "secs": []})
        p["v"].append(v); p["west"].append(r["aLeft"] - r["bLeft"]); p["secs"].append(r["seconds"])
    rows = {}
    west = [w for p in pairs.values() for w in p["west"]]
    print(f"{len(pairs)} fights, {len(west)} games; the west side's mean value {st.mean(west):+.3f} (0 = no seat bias)")
    print(f"{'fight':34s} {'games':>5s} {'value':>7s} {'+-':>5s} {'A wins':>6s} {'secs':>5s}  costA/costB")
    for name, p in sorted(pairs.items()):
        v = p["v"]
        se = st.stdev(v) / len(v) ** 0.5 if len(v) > 1 else 0
        ca, cb = cost(p["a"]), cost(p["b"])
        rows[name] = {"a": p["a"], "b": p["b"], "value": round(st.mean(v), 3), "ci": round(1.96 * se, 3), "games": len(v),
                      "aWins": sum(1 for x in v if x > 0), "costA": ca[0], "costB": cb[0], "popA": ca[1], "popB": cb[1]}
        print(f"{name:34s} {len(v):5d} {st.mean(v):+7.3f} {1.96 * se:5.3f} {rows[name]['aWins']:6d} {st.mean(p['secs']):5.0f}  {ca[0]}/{cb[0]}")
    (ROOT / "tuning" / f"unit_matrix_{label}.json").write_text(json.dumps(rows, indent=1), encoding="utf-8")
    # the planner's table (Composition.java): pure armies of equal cost, matrix[a][b] = value of a against b
    PURE = {"vin": "vindicator", "pil": "pillager", "golem": "golem", "witch": "witch", "evoker": "evoker", "ravager": "ravager", "wind_up": "windcaller"}  # windcaller: the upgraded (flying) ones, the threat
    units = {u: {v: 0.0 for v in PURE.values()} for u in PURE.values()}
    found = 0
    for name, r in rows.items():
        x, _, y = name.partition("__")
        if x in PURE and y in PURE and x != y:
            units[PURE[x]][PURE[y]] = r["value"]; units[PURE[y]][PURE[x]] = -r["value"]; found += 1
    if found >= 10:
        (ROOT / "tuning" / "unit_matrix.json").write_text(json.dumps({"source": label, "units": units}, indent=1), encoding="utf-8")
        print(f"tuning/unit_matrix.json written from {found} pure pairs")
    # one line per army: its mean value against all the others it met
    score = {}
    for name, r in rows.items():
        x, _, y = name.partition("__")
        if y:
            score.setdefault(x, []).append(r["value"]); score.setdefault(y, []).append(-r["value"])
    if score:
        print("\narmy                 mean value against the field")
        for k, v in sorted(score.items(), key=lambda kv: -st.mean(kv[1])):
            print(f"{k:20s} {st.mean(v):+.3f}  ({len(v)} opponents)   {ARMIES.get(k, '')}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", nargs="?", default="matrix", choices=["matrix", "file", "vs"])
    ap.add_argument("arg", nargs="*")
    ap.add_argument("--reps", type=int, default=3)
    ap.add_argument("--servers", type=int, default=4)
    ap.add_argument("--label", default="ARENA")
    ap.add_argument("--report", action="store_true")
    a = ap.parse_args()
    out = ROOT / "results" / f"{a.label}.jsonl"
    if not a.report:
        if a.mode == "file":
            fights = [tuple(x.strip() for x in l.split("|")) for l in pathlib.Path(a.arg[0]).read_text(encoding="utf-8").splitlines() if l.strip() and not l.startswith("#")]
        elif a.mode == "vs":  # the armies named against each other only
            names = a.arg
            fights = [(f"{x}__{y}", ARMIES[x], ARMIES[y]) for i, x in enumerate(names) for y in names[i + 1:]]
        else:
            names = list(ARMIES)
            fights = [(f"{x}__{y}", ARMIES[x], ARMIES[y]) for i, x in enumerate(names) for y in names[i + 1:]]
            fights += [(f"{x}__{x}", ARMIES[x], ARMIES[x]) for x in ("vin", "pil")]
        print(f"{len(fights)} fights x 2 seats x {a.reps} reps = {len(fights) * 2 * a.reps} games on {a.servers} servers", flush=True)
        out = run(fights, a.reps, a.servers, a.label)
    report(out, a.label)


if __name__ == "__main__":
    main()
