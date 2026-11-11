#!/usr/bin/env python3
"""
Test suites for the self-play harness (see suite.ps1).

    py tools/suite.py plan   <suite> <mapsDir> <plan.json> [--games N]
    py tools/suite.py report <suite> <results.jsonl> [more.jsonl ...]
    py tools/suite.py sprt   <suite> <results.jsonl>      exit code 10 when the result is decided

A suite is tools/suites/<suite>.json:
    { "items": [ { "name": "new first", "spec": "expert vs expert@base readied nohandicap", "us": 0,
                   "pair": "duel", "extra": "-Dronstrat.stopAt=720",
                   "maps": ["Mirs_Landing", ...], "seeds": [1, 2, 3] } ] }
"us" is the index of the team whose win counts (the code under test). A timeout counts as half a win.
"pair": items with the same pair name are the same games with the seats swapped. Games of the same map
and seed are then judged together (one pair = one observation), which takes the start position out of
the result. "extra": JVM arguments for these games. "report": "econ" at the top level adds the economy
comparison (short games).
"""
import argparse, json, math, pathlib, statistics, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
S0, S1 = 0.48, 0.58          # sequential test: "no better than 48%" against "58% or better" (paired score)
BOUND = math.log(0.95 / 0.05)
MIN_PAIRS = 8


def load(name):
    p = pathlib.Path(name)
    if not p.exists():
        p = ROOT / 'tools' / 'suites' / (name + '.json')
    return json.loads(p.read_text(encoding='utf-8'))


def games(suite):
    """every game of the suite, interleaved so that the first N are spread over items, maps and seeds"""
    per_item = []
    for it in suite['items']:
        g = [dict(spec=it['spec'], seed=s, map=m, item=it.get('name', it['spec']), us=it.get('us', 0),
                  pair=it.get('pair', ''), extra=it.get('extra', ''))
             for s in it['seeds'] for m in it['maps']]
        per_item.append(g)
    out = []
    for i in range(max(len(g) for g in per_item)):
        for g in per_item:
            if i < len(g):
                out.append(g[i])
    return out


def wilson(score, n, z=1.96):
    if n == 0:
        return 0.0, 1.0
    p = score / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return max(0.0, c - h), min(1.0, c + h)


def mean_ci(vals):
    """mean and half width of its 95% interval"""
    n = len(vals)
    if n == 0:
        return 0.0, 0.0
    m = statistics.mean(vals)
    if n < 2:
        return m, float('inf')
    t = {2: 12.7, 3: 4.30, 4: 3.18, 5: 2.78, 6: 2.57, 7: 2.45, 8: 2.36, 9: 2.31, 10: 2.26, 12: 2.20, 15: 2.14, 20: 2.09, 30: 2.05}
    k = next((v for kk, v in sorted(t.items()) if n <= kk), 1.98)
    return m, k * statistics.stdev(vals) / math.sqrt(n)


def plan(a):
    suite = load(a.suite)
    gs = games(suite)
    if a.games:
        gs = gs[:a.games]
    maps = pathlib.Path(a.maps)
    for g in gs:
        if not (maps / g['map']).is_dir():
            sys.exit(f"map {g['map']} is not in {maps}")
    pathlib.Path(a.out).write_text(json.dumps(
        [dict(spec=g['spec'], seed=g['seed'], map=str(maps / g['map']), extra=g['extra']) for g in gs], indent=1), encoding='utf-8')
    print(f"{len(gs)} games")


def read(suite, files):
    """[(game, result)] for the result lines that belong to the suite, the first line of each game"""
    key = {(g['spec'], g['seed'], g['map']): g for g in games(suite)}
    seen, out = set(), []
    for f in files:
        p = pathlib.Path(f)
        if not p.exists():
            continue
        for line in p.read_text(encoding='utf-8').splitlines():
            if not line.strip():
                continue
            r = json.loads(line)
            k = (r['spec'], r['seed'], r.get('arena', '').replace('map:', ''))
            if k in key and k not in seen:
                seen.add(k)
                out.append((key[k], r))
    return out, len(key)


def score(g, r):
    if r.get('stopped'):
        return None   # a short game has no winner
    return 0.5 if r.get('timeout') else 1.0 if r.get('winnerTeam') == g['us'] else 0.0


def col(metrics, name):
    cols = metrics.get('seriesColumns', '').split(',')
    return cols.index(name) if name in cols else -1


def at(metrics, sec, name):
    """a series column at the last sample not after sec; None when the column or the sample is missing"""
    i = col(metrics, name)
    v = None
    if i < 0:
        return None
    for row in metrics.get('series', []):
        if row[0] > sec:
            break
        v = row[i]
    return v


def strength(team, sec):
    """what a team has standing at a moment: units, and buildings counted three times (the game's own tie-break)"""
    s = 0
    for bot in team:
        m = bot.get('metrics')
        if not m:
            return None
        w, a, b = at(m, sec, 'workers'), at(m, sec, 'army'), at(m, sec, 'buildings')
        if w is None:
            return None
        s += w + a + 3 * (b or 0)
    return s


def share(g, r, sec):
    """our part of the two teams' strength at a moment (0.5 = level)"""
    sec = min(sec, r.get('gameSeconds', sec) - 30)
    us, them = strength(r['teams'][g['us']], sec), strength(r['teams'][1 - g['us']], sec)
    if us is None or them is None or us + them == 0:
        return None
    return us / (us + them)


def pairs(rows, value):
    """one value per complete pair: the mean over the games of the same map and seed in a pair group"""
    groups = {}
    for g, r in rows:
        if g['pair']:
            groups.setdefault((g['pair'], g['map'], g['seed']), {})[g['item']] = value(g, r)
    sizes = {}
    for g, r in rows:
        sizes.setdefault(g['pair'], set()).add(g['item'])
    return [statistics.mean(v.values()) for k, v in groups.items()
            if len(v) == len(sizes[k[0]]) and len(v) > 1 and all(x is not None for x in v.values())]


def llr(vals):
    n = len(vals)
    if n < 2:
        return 0.0
    var = max(statistics.pvariance(vals), 0.004)
    return n * (S1 - S0) * (2 * statistics.mean(vals) - S0 - S1) / (2 * var)


def verdict(vals):
    if len(vals) < MIN_PAIRS:
        return ''
    x = llr(vals)
    return 'better' if x >= BOUND else 'not better' if x <= -BOUND else ''


def sprt(a):
    suite = load(a.suite)
    rows, _ = read(suite, a.results)
    vals = pairs(rows, score)
    v = verdict(vals)
    print(f"{len(vals)} pairs, mean {100 * statistics.mean(vals) if vals else 0:.1f}%, LLR {llr(vals):+.2f} (bound {BOUND:.2f}) {v}")
    sys.exit(10 if v else 0)


def report(a):
    suite = load(a.suite)
    rows, planned = read(suite, a.results)
    table = {}
    order = [it.get('name', it['spec']) for it in suite['items']]
    for g, r in rows:
        for name in (g['item'], 'map ' + g['map']):
            row = table.setdefault(name, dict(n=0, w=0, t=0, secs=0))
            row['n'] += 1
            if r.get('timeout'):
                row['t'] += 1
            elif r.get('winnerTeam') == g['us']:
                row['w'] += 1
                row['secs'] += r.get('gameSeconds', 0)

    def line(name, row):
        n, w, t = row['n'], row['w'], row['t']
        sc = w + 0.5 * t
        lo, hi = wilson(sc, n)
        avg = f"{row['secs'] / w / 60:5.1f}" if w else '    -'
        return f"{name:<28} {w:3d}/{n:<3d} timeouts {t:2d}  score {100 * sc / max(1, n):5.1f}%  95% [{100 * lo:4.1f}, {100 * hi:4.1f}]  win at {avg} min"

    total = dict(n=0, w=0, t=0, secs=0)
    for name in order:
        if name in table:
            print(line(name, table[name]))
            for k in total:
                total[k] += table[name][k]
    if len([n for n in order if n in table]) > 1:
        print(line('TOTAL', total))

    # seats swapped: one pair of games = one observation, the start position cancels out
    vals = pairs(rows, score)
    if vals:
        m, h = mean_ci(vals)
        print(f"\nPAIRED (same map and seed, seats swapped): {len(vals)} pairs  score {100 * m:5.1f}% +- {100 * h:4.1f}")
        for label, sec in (('strength share at 12:00', 720), ('strength share at 20:00', 1200)):
            sh = pairs(rows, lambda g, r, sec=sec: share(g, r, sec))
            if sh:
                m, h = mean_ci(sh)
                print(f"  {label:<26} {100 * m:5.1f}% +- {100 * h:4.1f}   ({len(sh)} pairs; 50% = level)")
        v = verdict(vals)
        print(f"  sequential test: LLR {llr(vals):+.2f} of +-{BOUND:.2f}" + (f" -> {v}" if v else " -> undecided"))
    seat = [0, 0, 0]
    for g, r in rows:
        if len(r['teams']) == 2:
            seat[2 if r.get('timeout') else r.get('winnerTeam', 0)] += 1
    print(f"  first team won {seat[0]}, second team won {seat[1]}, timeouts {seat[2]}")
    adj = sum(1 for g, r in rows if r.get('adjudged'))
    if rows:
        print(f"  mean game {statistics.mean(r.get('gameSeconds', 0) for g, r in rows) / 60:.1f} min ({statistics.mean(r.get('realSeconds', 0) for g, r in rows):.0f} s real), {adj} ended by early verdict")
    print()
    for name in sorted(n for n in table if n.startswith('map ')):
        print(line(name, table[name]))
    if suite.get('report') == 'econ':
        econ(rows)
    if planned - len(rows):
        print(f"\n{planned - len(rows)} of {planned} games have no result")


ECON = [  # label, function of (metrics, second)
    ('villagers', lambda m, t: at(m, t, 'workers')),
    ('soldiers', lambda m, t: at(m, t, 'army')),
    ('farms', lambda m, t: at(m, t, 'farms')),
    ('buildings', lambda m, t: at(m, t, 'buildings')),
    ('gathered food', lambda m, t: at(m, t, 'gotFood')),
    ('gathered wood', lambda m, t: at(m, t, 'gotWood')),
    ('gathered ore', lambda m, t: at(m, t, 'gotOre')),
    ('gathered total', lambda m, t: None if at(m, t, 'gotFood') is None else at(m, t, 'gotFood') + at(m, t, 'gotWood') + at(m, t, 'gotOre')),
    ('unspent stock', lambda m, t: at(m, t, 'food') + at(m, t, 'wood') + at(m, t, 'ore')),
]


def econ(rows):
    """short games: the code under test against the other side of the same game, seats swapped and averaged"""
    print("\nECONOMY (us against them in the same game; difference averaged over each pair of swapped seats)")
    print(f"{'':<22}{'at':>6}{'us':>9}{'them':>9}{'difference':>22}")
    for sec in (300, 600, 720):
        for label, fn in ECON:
            ours, theirs = [], []

            def diff(g, r):
                a, b = r['teams'][g['us']][0].get('metrics'), r['teams'][1 - g['us']][0].get('metrics')
                if not a or not b or r.get('gameSeconds', 0) < sec:
                    return None
                x, y = fn(a, sec), fn(b, sec)
                if x is None or y is None:
                    return None
                ours.append(x); theirs.append(y)
                return x - y
            d = pairs(rows, diff)
            if not d:
                continue
            m, h = mean_ci(d)
            mark = '  *' if abs(m) > h else ''
            print(f"{label:<22}{sec // 60:>3}:{sec % 60:02d}{statistics.mean(ours):>9.1f}{statistics.mean(theirs):>9.1f}{m:>+12.1f} +- {h:<6.1f}{mark}")
        print()
    print("* = the 95% interval of the difference excludes zero")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    p = sub.add_parser('plan'); p.add_argument('suite'); p.add_argument('maps'); p.add_argument('out'); p.add_argument('--games', type=int, default=0)
    p.set_defaults(fn=plan)
    p = sub.add_parser('report'); p.add_argument('suite'); p.add_argument('results', nargs='+')
    p.set_defaults(fn=report)
    p = sub.add_parser('sprt'); p.add_argument('suite'); p.add_argument('results', nargs='+')
    p.set_defaults(fn=sprt)
    a = ap.parse_args()
    a.fn(a)


if __name__ == '__main__':
    main()
