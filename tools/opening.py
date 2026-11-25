#!/usr/bin/env python3
"""
v16 step 1: the villager AI's opening economy (first 10 minutes), per map, from the match recorder.

    py tools/opening.py [dirs or files ...] [--faction villagers] [--human _Shikieiki_]

Per player: workers at 3 / 6 / 9 min, fields at 3 / 4.5 / 6 / 8 min, the share of 0-10 min snapshots with the food
bank under 50 (the town centre starving), ore banked at 8 min (mined for nothing yet), wood bank under 30 share,
and hunted animals in 0-8 min (proxy for game near home). Grouped by map, the human's games shown apart.
Targets (the user's rhythm): 3 fields by 3 min, 6 by 4.5, >= 25 workers at 6 min.
"""
import argparse, collections, glob, json, os, pathlib, statistics as st

GAME = pathlib.Path(r"C:\fz\pcl\.minecraft\versions\RON1.20.1-Forge_47.4.28\ronstrat_matches")


def load(p):
    ev = []
    for l in open(p, encoding="utf-8", errors="replace"):
        try: ev.append(json.loads(l))
        except ValueError: pass
    return ev


def stats(ev, name):
    s = [e for e in ev if e["ev"] == "snap" and e.get("p") == name]
    if len(s) < 20 or s[-1]["t"] < 540: return None
    def at(t):
        b = s[0]
        for x in s:
            if x["t"] <= t: b = x
        return b
    early = [x for x in s if x["t"] < 600]
    farm = lambda x: x["built"].get("wheat_farm", 0) + x["built"].get("pumpkin_farm", 0) + x["built"].get("netherwart_farm", 0)
    hunted = sum(1 for e in ev if e["ev"] == "hunt" and e.get("owner") == name and e["t"] < 480)
    return {
        "w3": at(180)["workers"], "w6": at(360)["workers"], "w9": at(540)["workers"],
        "f3": farm(at(180)), "f45": farm(at(270)), "f6": farm(at(360)), "f8": farm(at(480)),
        "starve": sum(1 for x in early if x["bank"][0] < 50) / len(early),
        "woodDry": sum(1 for x in early if x["bank"][1] < 30) / len(early),
        "ore8": at(480)["bank"][2], "hunt8": hunted,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("paths", nargs="*")
    ap.add_argument("--faction", default="villagers")
    ap.add_argument("--human", default="_Shikieiki_")
    a = ap.parse_args()
    files = []
    for p in (a.paths or [str(GAME)]):
        if os.path.isdir(p): files += glob.glob(os.path.join(p, "**", "*_server.jsonl"), recursive=True)
        else: files += glob.glob(p)
    groups = collections.defaultdict(list)
    for f in files:
        ev = load(f)
        if not ev or ev[0].get("ev") != "start": continue
        mp = ev[0].get("map", "?").replace("[4p] ", "").replace("[2p] ", "")
        for pl in ev[0]["players"]:
            if pl["faction"] != a.faction: continue
            r = stats(ev, pl["name"])
            if not r: continue
            who = "YOU" if pl["name"] == a.human else "AI"
            groups[(who, mp)].append(r)
    keys = ["w3", "w6", "w9", "f3", "f45", "f6", "f8", "starve", "woodDry", "ore8", "hunt8"]
    print(f"{'who':4} {'map':22} {'n':>3} " + " ".join(f"{k:>7}" for k in keys))
    for (who, mp), rs in sorted(groups.items(), key=lambda kv: (kv[0][0], st.mean(r["w6"] for r in kv[1]))):
        vals = [st.mean(r[k] for r in rs) for k in keys]
        print(f"{who:4} {mp[:22]:22} {len(rs):3} " + " ".join(f"{v:7.2f}" if k in ("starve", "woodDry") else f"{v:7.1f}" for k, v in zip(keys, vals)))
    print("targets: f3 >= 3, f45 >= 6, w6 >= 25, starve < 0.15")


if __name__ == "__main__":
    main()
