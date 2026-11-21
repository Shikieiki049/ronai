"""h2h.py name faction [profile] [--old b219] [--seeds 101,102,103]: a head-to-head suite - the current code
(with an optional parameter profile) against a frozen earlier build (brain @old) of the same faction, both
seat orders, the 7 test maps, night clock on. Writes tools/suites/<name>.json; score it with
paired.py against nothing (50% is even) or read the TOTAL line: above 50% = the new code is stronger."""
import argparse, json, pathlib
ap = argparse.ArgumentParser()
ap.add_argument("name"); ap.add_argument("faction", choices=["villagers", "monsters", "piglins"])
ap.add_argument("profile", nargs="?", default="")
ap.add_argument("--old", default="b219"); ap.add_argument("--seeds", default="101,102,103")
a = ap.parse_args()
fac = "" if a.faction == "villagers" else "#" + a.faction
new = "expert" + fac + (":" + a.profile if a.profile else "")
old = "expert" + fac + "@" + a.old
maps = ["Mirs_Landing", "Berlingrad_Classic", "Cabana_Republic", "Infinity_Isle", "Snowplow", "Post-Youth", "Oceanborn"]
seeds = [int(s) for s in a.seeds.split(",")]
items = [{"name": "new first", "spec": f"{new} vs {old} readied nohandicap night", "us": 0, "pair": "h2h", "maps": maps, "seeds": seeds},
         {"name": "old first", "spec": f"{old} vs {new} readied nohandicap night", "us": 1, "pair": "h2h", "maps": maps, "seeds": seeds}]
s = {"description": f"head-to-head: {new} against the frozen {a.old} ({a.faction})", "items": items}
p = pathlib.Path("tools/suites") / f"{a.name}.json"
p.write_text(json.dumps(s, indent=1), encoding="utf-8"); print(p, items[0]["spec"])
