#!/usr/bin/env python3
"""
How well the fight forecast (CombatSim) matched real fights in self-play results.

    py tools/simcheck.py results/A.jsonl [...]

Each result line carries simChecks: start s, own n, enemy n, towers, forecast own/enemy share left,
real own/enemy share left, seconds, how it ended (end / retreat).
"""
import json, pathlib, statistics, sys

rows = []
for f in sys.argv[1:]:
    for line in pathlib.Path(f).read_text(encoding='utf-8').splitlines():
        if line.strip():
            for c in json.loads(line).get('simChecks', []):
                p = c.split(',')
                rows.append(dict(t=int(p[0]), own=int(p[1]), en=int(p[2]), fo=float(p[4]), fe=float(p[5]),
                                 ro=float(p[6]), re=float(p[7]), secs=int(p[8]), how=p[9]))
if not rows:
    sys.exit('no simChecks in these results')


def show(name, rs):
    if not rs:
        return
    fmargin = [r['fo'] - r['fe'] for r in rs]
    rmargin = [r['ro'] - r['re'] for r in rs]
    err = [abs(a - b) for a, b in zip(fmargin, rmargin)]
    bias = statistics.mean(a - b for a, b in zip(fmargin, rmargin))
    clear = [(a, b) for a, b in zip(fmargin, rmargin) if abs(b) > 0.2]
    right = sum(1 for a, b in clear if (a > 0) == (b > 0))
    print(f"{name:<26} n={len(rs):>4}  margin error mean {statistics.mean(err):.2f}  bias {bias:+.2f}  "
          f"winner right {right}/{len(clear)} of the clear fights")


show('all fights', rows)
show('ended by retreat', [r for r in rows if r['how'] == 'retreat'])
show('fought out', [r for r in rows if r['how'] == 'end'])
show('small (<=6 each)', [r for r in rows if r['own'] <= 6 and r['en'] <= 6])
show('large (>=10 a side)', [r for r in rows if r['own'] >= 10 and r['en'] >= 10])
show('we outnumber 1.5x', [r for r in rows if r['own'] >= 1.5 * r['en']])
show('they outnumber 1.5x', [r for r in rows if r['en'] >= 1.5 * r['own']])
print('\nforecast margin -> real margin (mean), by forecast band')
for lo in (-1.0, -0.6, -0.2, 0.2, 0.6):
    b = [r for r in rows if lo <= r['fo'] - r['fe'] < lo + 0.4 + (0.01 if lo == 0.6 else 0)]
    if b:
        print(f"  {lo:+.1f}..{lo + 0.4:+.1f}  n={len(b):>4}  real {statistics.mean(r['ro'] - r['re'] for r in b):+.2f}")
