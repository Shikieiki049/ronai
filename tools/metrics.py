#!/usr/bin/env python3
"""
Summarise the per-player metrics (Telemetry.java) of self-play results.

    py tools/metrics.py results/A.jsonl [results/B.jsonl ...] [--only expert] [--map Mirs_Landing]

One column per kind of player in each file (expert, expert@base, expert:rush7, hard ...), one row per
metric, averaged over the games. Times are minutes:seconds; "-" = never happened; (n) = in how many
games it happened when that is not all of them.
"""
import argparse, json, pathlib, statistics, sys

# the user's own 1v1 timings, for the rows that have one
HUMAN = {'farms 3:00': '3', 'farms 4:30': '6', 'farms 6:30': '10', 'farms 9:00': '14',
         'first Barracks': '4:00-9:00', 'army 15:00': '17-32', 'army 20:00': '30-60'}


def label(bot):
    return bot['difficulty'] + ('@' + bot['brain'] if bot.get('brain') else '') + (':' + bot['profile'] if bot.get('profile') else '')


def at(series, sec, col):
    """value of a series column at the last sample not after sec (None when the game ended before)"""
    if not series or series[-1][0] < sec:
        return None
    v = None
    for row in series:
        if row[0] > sec:
            break
        v = row[col]
    return v


def mmss(sec):
    return f"{int(sec) // 60}:{int(sec) % 60:02d}"


class Col:
    def __init__(self):
        self.vals = {}   # metric -> list of values
        self.games = 0
        self.wins = 0
        self.timeouts = 0

    def add(self, k, v):
        if v is not None:
            self.vals.setdefault(k, []).append(v)


ROWS = []  # (name, kind) in display order; kind: pct, time, num


def row(name, kind):
    if (name, kind) not in ROWS:
        ROWS.append((name, kind))
    return name


def collect(files, only, mapname):
    cols = {}
    for f in files:
        tag = pathlib.Path(f).stem
        for line in pathlib.Path(f).read_text(encoding='utf-8').splitlines():
            if not line.strip():
                continue
            r = json.loads(line)
            if mapname and mapname not in r.get('arena', ''):
                continue
            for ti, team in enumerate(r['teams']):
                seen = set()
                for bot in team:
                    m = bot.get('metrics')
                    lab = label(bot)
                    if m is None or (only and only not in lab):
                        continue
                    c = cols.setdefault((tag, lab), Col())
                    if lab not in seen:   # games and wins are counted once per team
                        seen.add(lab)
                        c.games += 1
                        if r.get('timeout'):
                            c.timeouts += 1
                        elif r.get('winnerTeam') == ti:
                            c.wins += 1
                            c.add(row('win at', 'time'), r.get('gameSeconds'))
                    s = m.get('series', [])
                    for k in ('lowFood', 'lowWood', 'lowOre', 'highFood', 'highWood', 'highOre'):
                        c.add(row(k.replace('low', 'under 50 ').replace('high', 'over 800 '), 'pct'), m[k])
                    c.add(row('town centre idle', 'pct'), m['tcIdle'])
                    c.add(row('idle villagers', 'pct'), m['idleWorkers'])
                    for n in (20, 30, 38):
                        c.add(row(f'{n} villagers', 'time'), m['reached'].get(f'workers{n}'))
                    for sec in (180, 270, 390, 540):
                        c.add(row(f'farms {mmss(sec)}', 'num'), at(s, sec, 6))
                    for sec in (300, 600, 900):
                        c.add(row(f'villagers {mmss(sec)}', 'num'), at(s, sec, 1))
                    c.add(row('first Barracks', 'time'), m['firstBuilt'].get('Barracks'))
                    c.add(row('first soldier', 'time'), m['reached'].get('army1'))
                    for sec in (600, 900, 1200, 1500):
                        c.add(row(f'army {mmss(sec)}', 'num'), at(s, sec, 2))
                    c.add(row('first attack arrives', 'time'), m['reached'].get('attack'))
                    c.add(row('attacks', 'num'), len(m.get('arrivals', [])))
                    c.add(row('retreats', 'num'), m.get('retreats'))
                    c.add(row('first Blacksmith', 'time'), m['firstBuilt'].get('Blacksmith'))
                    c.add(row('first Library', 'time'), m['firstBuilt'].get('Library'))
                    for sec in (900, 1200, 1500):
                        army = at(s, sec, 2)
                        if army:
                            c.add(row(f'armoured {mmss(sec)}', 'pct'), at(s, sec, 8) / army)
                            c.add(row(f'enchanted {mmss(sec)}', 'pct'), at(s, sec, 9) / army)
                # coordination: how far apart team mates arrive at the enemy (each arrival to the mate's nearest one)
                mates = [b for b in team if b.get('metrics') and (not only or only in label(b))]
                if len(mates) >= 2 and len({label(b) for b in mates}) == 1:
                    c = cols[(tag, label(mates[0]))]
                    for i, b in enumerate(mates):
                        for arr in b['metrics'].get('arrivals', []):
                            others = [a[0] for j, o in enumerate(mates) if j != i for a in o['metrics'].get('arrivals', [])]
                            gap = min((abs(arr[0] - t) for t in others), default=None)
                            c.add(row('team arrival gap (s)', 'num'), 600 if gap is None else min(gap, 600))
                            c.add(row('arrive within 10 s', 'pct'), 1.0 if gap is not None and gap <= 10 else 0.0)
    return cols


def fmt(kind, vals, games):
    if not vals:
        return '-'
    v = statistics.mean(vals)
    s = f"{100 * v:.0f}%" if kind == 'pct' else mmss(v) if kind == 'time' else f"{v:.1f}"
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('files', nargs='+')
    ap.add_argument('--only', default='')
    ap.add_argument('--map', default='')
    a = ap.parse_args()
    cols = collect(a.files, a.only, a.map)
    if not cols:
        sys.exit('no metrics in these results (they were made before Telemetry existed, or the filter matches nothing)')
    keys = list(cols)
    one_file = len({k[0] for k in keys}) == 1
    heads = [k[1] if one_file else f"{k[0]} {k[1]}" for k in keys]
    w = max(14, max(len(h) for h in heads) + 2)
    print(f"{'':<24}" + ''.join(f"{h:>{w}}" for h in heads) + f"{'user':>12}")
    print(f"{'games':<24}" + ''.join(f"{cols[k].games:>{w}}" for k in keys))
    print(f"{'wins / timeouts':<24}" + ''.join(f"{str(cols[k].wins) + ' / ' + str(cols[k].timeouts):>{w}}" for k in keys))
    for name, kind in ROWS:
        cells = []
        for k in keys:
            c = cols[k]
            vals = c.vals.get(name, [])
            s = fmt(kind, vals, c.games)
            if kind == 'time' and vals and name != 'win at' and len(vals) < len(c.vals.get('under 50 Food', [])):
                s += f" ({len(vals)})"
            cells.append(s)
        print(f"{name:<24}" + ''.join(f"{s:>{w}}" for s in cells) + f"{HUMAN.get(name, ''):>12}")


if __name__ == '__main__':
    main()
