#!/usr/bin/env python3
"""py tools/econcmp.py <suite> <results.jsonl>: the economy lines of the suite report plus villagers/food at 10:00 per map."""
import json, collections, statistics as st, subprocess, sys, pathlib
ROOT = pathlib.Path(__file__).resolve().parent.parent
rep = subprocess.run(["py", "tools/suite.py", "report", sys.argv[1], sys.argv[2]], cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
on = False
for l in rep.splitlines():
    if l.startswith("ECONOMY"): on = True
    if on and any(k in l for k in ("villagers", "gathered food")) and any(t in l for t in (" 5:00", "10:00", "12:00")): print(l)
d = collections.defaultdict(lambda: {"n": [], "b": []})
for l in open(sys.argv[2], encoding="utf-8"):
    r = json.loads(l)
    for t in r["teams"]:
        b = t[0]; s = {x[0]: x for x in b["metrics"]["series"]}
        if 600 in s: d[r["arena"][4:]]["n" if b.get("profile") else "b"].append((s[600][1], s[600][12]))
for m, v in sorted(d.items()):
    print(f"{m:20s} new {st.mean(x[0] for x in v['n']):5.1f} v {st.mean(x[1] for x in v['n']):5.0f} food | base {st.mean(x[0] for x in v['b']):5.1f} v {st.mean(x[1] for x in v['b']):5.0f} food")
