"""round2.py suite [suite...]: copy each suite to <suite>2 on seeds 104-106 (second-round screening)"""
import json, sys, pathlib
for name in sys.argv[1:]:
    src = pathlib.Path("tools/suites") / f"{name}.json"
    s = json.loads(src.read_text(encoding="utf-8"))
    for it in s["items"]: it["seeds"] = [104, 105, 106]
    s["description"] = s.get("description", "") + " - round 2, seeds 104-106"
    (src.parent / f"{name}2.json").write_text(json.dumps(s, indent=1), encoding="utf-8")
    print(name + "2", len(s["items"]), s["items"][0]["spec"])
