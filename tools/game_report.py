#!/usr/bin/env python3
"""
Report of a game recorded by GameRecorder.java (server side) - for finding out why a game went the way it did.

    py tools/game_report.py [file.jsonl | dir]       default: the newest *_server.jsonl in the game's ronstrat_matches

Prints:
  1. the players (faction, team, computer or human) and who was left at the end
  2. side by side at minutes 3/5/8/10/12/15/20/25/30: workers, army population, gathered per minute
     (food+wood+ore over the last minute), banked, population/cap, production buildings
  3. per player, minute by minute: workers by job, army by type, bank, income, where the army stood
     (distance from its own capitol: an attack shows as a jump)
  4. the build order (first of each building, every capitol/house/barracks-type count milestone) and research
  5. the fights: units lost by type and who killed them, kills made, and the trade (resources killed vs lost)
"""
import json, math, pathlib, sys
from collections import defaultdict, Counter

GAME = pathlib.Path(r"C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\ronstrat_matches")
CAPITOLS = {"town_centre", "mausoleum", "central_portal"}
PRODUCTION = {"barracks", "graveyard", "portal_military", "dungeon", "spider_lair", "slime_pit", "arcane_tower",
              "witch_hut", "blacksmith", "iron_golem_building", "castle", "laboratory", "stronghold", "bastion",
              "hoglin_stables", "flame_sanctuary", "wither_shrine", "basalt_springs", "fortress"}
COST = {"villager": 50, "zombie_villager": 50, "grunt": 50, "vindicator": 160, "pillager": 190, "iron_golem": 300, "witch": 270,
        "evoker": 260, "ravager": 600, "zombie": 75, "husk": 75, "skeleton": 95, "stray": 95, "creeper": 160, "spider": 120,
        "wraith": 150, "slime": 150, "warden": 400, "brute": 120, "headhunter": 150, "hoglin": 210, "blaze": 180,
        "wither_skeleton": 325, "marauder": 325, "magma_cube": 120, "ghast": 450}


def mmss(t):
    return f"{t // 60}:{t % 60:02d}"


def load(path):
    return [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]


def main():
    arg = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else GAME
    if arg.is_dir():
        files = sorted(arg.glob("*_server.jsonl"))
        if not files:
            print("no recorded games in", arg); return
        arg = files[-1]
    evs = load(arg)
    print(f"=== {arg.name}")
    start = next((e for e in evs if e["ev"] == "start"), {"players": []})
    end = next((e for e in evs if e["ev"] == "end"), None)
    players = [p["name"] for p in start["players"]]
    print(f"map {start.get('map', '?')}")
    for p in start["players"]:
        print(f"  {p['name']:24s} {p['faction']:10s} team {p['color']:<9} {'computer' if p['bot'] else 'human'}")
    if end:
        print(f"ended at {mmss(end['t'])}, still in: {', '.join(end['alive'])}")

    snaps = defaultdict(list)
    for e in evs:
        if e["ev"] == "snap":
            snaps[e["p"]].append(e)

    def at(p, t):
        best = None
        for s in snaps[p]:
            if s["t"] <= t: best = s
        return best

    def income(p, t):
        a, b = at(p, t - 60), at(p, t)
        if not a or not b: return 0
        return sum(b["got"]) - sum(a["got"])

    def prod(s):
        return sum(v for k, v in s["built"].items() if k in PRODUCTION)

    # 2. side by side
    print("\nside by side (workers / army pop / gathered last minute / bank / pop/cap / production buildings)")
    print(f"{'min':>4s}  " + "  ".join(f"{p[:22]:>30s}" for p in players))
    for m in (3, 5, 8, 10, 12, 15, 20, 25, 30, 35, 40):
        row = []
        for p in players:
            s = at(p, m * 60)
            if not s or s["t"] < m * 60 - 20:
                row.append(f"{'-':>30s}"); continue
            row.append(f"{s['workers']:>3d}w {s.get('armyPop', 0):>3d}a {income(p, m * 60):>5d}/m {sum(s['bank']):>5d}b {s['pop']:>3d}/{s['cap']:<3d} {prod(s):>2d}p")
        if any(r.strip() != "-" for r in row):
            print(f"{m:>4d}  " + "  ".join(row))

    # 3. per player
    caps = {}
    for e in evs:
        if e["ev"] == "bld" and e["what"] in ("placed", "built") and e["b"] in CAPITOLS and e["owner"] not in caps:
            caps[e["owner"]] = (e["x"], e["z"])
    for p in players:
        print(f"\n--- {p}")
        print(f"{'min':>4s} {'wrk':>3s} {'fd/wd/or/bd/id':>15s} {'bank f/w/o':>14s} {'inc/m':>6s} {'spent':>6s} {'pop':>7s} {'army':>4s} {'from home':>9s}  army")
        prev = None
        for s in snaps[p]:
            if s["t"] % 60 >= 10 and s is not snaps[p][-1]:
                continue
            j = s["jobs"]
            inc = sum(s["got"]) - (sum(prev["got"]) if prev else 0)
            sp = sum(s["spent"]) - (sum(prev["spent"]) if prev else 0)
            prev = s
            dist = ""
            if "armyAt" in s and p in caps:
                dist = f"{int(math.hypot(s['armyAt'][0] - caps[p][0], s['armyAt'][1] - caps[p][1])):>4d}+-{s.get('armySpread', 0):<3d}"
            army = " ".join(f"{k} {v}" for k, v in sorted(s["army"].items(), key=lambda kv: -kv[1]))
            print(f"{s['t'] // 60:>4d} {s['workers']:>3d} {j['food']:>3d}/{j['wood']:>2d}/{j['ore']:>2d}/{j['build']:>2d}/{j['idle']:>2d} "
                  f"{s['bank'][0]:>4d}/{s['bank'][1]:>4d}/{s['bank'][2]:>4d} {inc:>6d} {sp:>6d} {s['pop']:>3d}/{s['cap']:<3d} {s.get('armyPop', 0):>4d} {dist:>9s}  {army}")

        # 4. build order and research
        first = {}
        counts = Counter()
        order = []
        for e in evs:
            if e["ev"] == "bld" and e["owner"] == p and e["what"] in ("placed", "changed"):
                counts[e["b"]] += 1
                if e["b"] not in first:
                    first[e["b"]] = e["t"]
                    order.append(f"{mmss(e['t'])} {e['b']}")
        print("  build order (first of each): " + " | ".join(order))
        mile = []
        for e in evs:
            if e["ev"] == "bld" and e["owner"] == p and e["what"] == "built" and e["b"] in PRODUCTION | CAPITOLS:
                mile.append(f"{mmss(e['t'])} {e['b']}")
        if mile:
            print("  finished production/capitols: " + " | ".join(mile[:20]))
        res = [f"{mmss(e['t'])} {e['item'].split(':')[-1]}" for e in evs if e["ev"] == "res" and e["owner"] == p]
        if res:
            print("  research: " + " | ".join(res))

    # 5. fights
    print("\nlosses and kills (units, resources' worth)")
    lost, killed = defaultdict(Counter), defaultdict(Counter)
    by = defaultdict(Counter)
    for e in evs:
        if e["ev"] != "death": continue
        lost[e["owner"]][e["u"]] += 1
        if e["by"]:
            if e["owner"]: killed[e["by"]][e["u"]] += 1   # neutral camps are counted apart
            by[e["owner"]][f"{e['by'][:12]}:{e['byU']}"] += 1
        else:
            by[e["owner"]][e["cause"]] += 1
    made = defaultdict(Counter)
    for e in evs:
        if e["ev"] == "born": made[e["owner"]][e["u"]] += 1
    neutral = sum(lost[""].values())
    if neutral:
        print(f"  neutral monsters / animals killed: {neutral} - {', '.join(f'{u} {n}' for u, n in lost[''].most_common(6))}")
    for p in players:
        print(f"  {p}: made {', '.join(f'{u} {n}' for u, n in made[p].most_common(10))}")
        lw = sum(COST.get(u, 100) * n for u, n in lost[p].items())
        kw = sum(COST.get(u, 100) * n for u, n in killed[p].items())
        print(f"  {p}: lost {sum(lost[p].values())} ({lw} res) - {', '.join(f'{u} {n}' for u, n in lost[p].most_common(8))}")
        print(f"  {' ' * len(p)}  killed {sum(killed[p].values())} ({kw} res) - {', '.join(f'{u} {n}' for u, n in killed[p].most_common(8))}")
        print(f"  {' ' * len(p)}  killed by: {', '.join(f'{k} {n}' for k, n in by[p].most_common(6))}")


if __name__ == "__main__":
    main()
