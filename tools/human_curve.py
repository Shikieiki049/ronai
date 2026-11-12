#!/usr/bin/env python3
"""
The user's own games as a minute-by-minute benchmark curve for the expert's economy.

    py -I tools/human_curve.py <instance logs dir> <player name> [--out tools/human_curve.json]

Reads latest.log and the rotated .log.gz files, splits them into games (RON's startRTS line), and for
every game that lasted at least 10 minutes counts, minute by minute: fields, houses, barracks and
stockpiles placed, and the resource stock RON saved. Writes the median
over the games (and each game) to the json; tools/metrics.py compares the bots with it.
"""
import argparse, gzip, json, os, re, statistics
from collections import defaultdict

ap = argparse.ArgumentParser()
ap.add_argument('logdir')
ap.add_argument('player')
ap.add_argument('--out', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), 'human_curve.json'))
ap.add_argument('--minutes', type=int, default=20)
a = ap.parse_args()

lines = []
for f in os.listdir(a.logdir):
    if not (f.endswith('.log') or f.endswith('.log.gz')):
        continue
    p = os.path.join(a.logdir, f)
    op = gzip.open if p.endswith('.gz') else open
    try:
        with op(p, 'rt', encoding='utf-8', errors='replace') as fh:
            for ln in fh:
                if a.player in ln or 'startRTS: playerId' in ln:
                    lines.append(ln.rstrip('\n'))
    except OSError:
        pass

ts = re.compile(r'^\[(\d\d)(\d+)月(\d{4}) (\d\d):(\d\d):(\d\d)')


def t_of(ln):
    m = ts.match(ln)
    if not m:
        return None
    return int(m.group(1)) * 86400 + int(m.group(4)) * 3600 + int(m.group(5)) * 60 + int(m.group(6))


events, seen = [], set()
for ln in lines:
    t = t_of(ln)
    if t is None or ln in seen:
        continue
    seen.add(ln)
    events.append((t, ln))
events.sort()

games, cur = [], None
for t, ln in events:
    if 'startRTS' in ln and 'playerId' in ln:
        cur = {'start': t, 'ev': []}
        games.append(cur)
        continue
    if cur is not None and t - cur['start'] < 3600:
        cur['ev'].append((t - cur['start'], ln))

item = re.compile(r'itemName: reignofnether:([a-z_0-9]+)')
SOLDIERS = ('vindicator', 'pillager', 'witch', 'evoker', 'iron_golem', 'ravager', 'illusioner')
KINDS = {'farms': 'wheat_farm', 'houses': 'villager_house', 'barracks': 'barracks', 'stockpiles': 'stockpile'}

curves = []
for g in games:
    if not g['ev'] or g['ev'][-1][0] < 600:
        continue
    placed = defaultdict(list)
    queued = defaultdict(list)
    stock = {}
    for dt, ln in g['ev']:
        if 'ResourcesSaveData.save: ' + a.player + '|' in ln:
            parts = ln.split(a.player + '|')[1].split('|')
            try:
                stock[dt // 60] = [int(x) for x in parts[:3]]
            except ValueError:
                pass
            continue
        im = item.search(ln)
        if not im:
            continue
        it = im.group(1)
        if 'performed PLACE' in ln:
            placed[it].append(dt)
        elif 'performed START_PRODUCTION' in ln:
            queued[it].append(dt)
        elif 'performed CANCEL' in ln and queued[it]:
            queued[it].pop()
    c = {}
    for m in range(1, a.minutes + 1):
        lim = m * 60
        row = {k: sum(1 for it, xs in placed.items() if key in it for x in xs if x < lim) for k, key in KINDS.items()}
        # (villagers and soldiers are not counted: the log has every click on "train", also the refused ones)
        if (m in stock) or (m - 1 in stock):
            row['stock'] = stock.get(m, stock.get(m - 1))
        c[m] = row
    curves.append({'start': g['start'], 'length': g['ev'][-1][0], 'curve': c})

median = {}
for m in range(1, a.minutes + 1):
    rows = [g['curve'][m] for g in curves if g['length'] >= m * 60]
    if not rows:
        continue
    median[m] = {k: statistics.median(r[k] for r in rows) for k in ('farms', 'houses', 'barracks', 'stockpiles')}
    median[m]['games'] = len(rows)

json.dump({'player': a.player, 'games': len(curves), 'median': median, 'each': curves}, open(a.out, 'w', encoding='utf-8'), indent=1)
print(f'{len(curves)} games of {a.player} (10 min or longer)')
print(' min  farms houses barracks stockpiles  (median, games)')
for m, r in median.items():
    print(f" {m:>3}  {r['farms']:>5} {r['houses']:>6} {r['barracks']:>8} {r['stockpiles']:>10}   ({r['games']})")
