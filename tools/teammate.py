"""v17: how much an AI teammate contributes in a 2v2, from results/<label>.jsonl.

usage: py tools/teammate.py LABEL [LABEL...] [--seat N]
       py tools/teammate.py --match FILE_server.jsonl   (a recorded game, real ones too: every player)
Seat: which player of team 0 to report (default: every expert with no faction suffix in the spec = villagers).
Per label: wins, and averaged over games for the chosen seat(s):
  army10/15/20  army pop at 10/15/20 min      wk15/wk20  workers at 15/20 min
  wkLoss        workers lost from the 12-24 min peak to the lowest point after it
  bank15        mean max(food, wood, ore) after 15 min (unspent)
  alive         share of games the seat survived
  helps/joint/follow  allyHelps / jointAttacks / allyFollows counters
"""
import json
import sys


def at(series, cols, t, col):
    i = cols.index(col)
    best = None
    for row in series:
        if row[0] <= t:
            best = row[i]
    return best if best is not None else 0


def seat_stats(pl):
    m = pl.get("metrics", {})
    cols = m.get("seriesColumns", "").split(",")
    ser = m.get("series", [])
    if not ser or "army" not in cols:
        return None
    ideas = m.get("ideas", {})
    wi = cols.index("workers")
    peak, peak_t = 0, 0
    for row in ser:
        if 720 <= row[0] <= 1440 and row[wi] > peak:
            peak, peak_t = row[wi], row[0]
    low = min([row[wi] for row in ser if row[0] >= peak_t] or [peak])
    late = [max(row[cols.index("food")], row[cols.index("wood")], row[cols.index("ore")]) for row in ser if row[0] >= 900]
    return {
        "army10": at(ser, cols, 600, "army"), "army15": at(ser, cols, 900, "army"), "army20": at(ser, cols, 1200, "army"),
        "wk15": at(ser, cols, 900, "workers"), "wk20": at(ser, cols, 1200, "workers"),
        "wkLoss": peak - low, "bank15": sum(late) / len(late) if late else 0,
        "alive": 1.0 if pl.get("alive") else 0.0,
        "helps": ideas.get("allyHelps", 0), "joint": ideas.get("jointAttacks", 0), "follow": ideas.get("allyFollows", 0),
    }


def match_stats(path):
    """the same numbers per player from a recorder file (snap events: t in seconds, army = units by type)"""
    snaps, deaths = {}, {}
    for line in open(path, encoding="utf-8"):
        e = json.loads(line)
        if e.get("ev") == "snap":
            snaps.setdefault(e["p"], []).append(e)
    keys = ["army10", "army15", "army20", "wk15", "wk20", "wkLoss", "bank15"]
    print("%-16s " % "player" + " ".join("%7s" % k for k in keys))
    for p, ss in snaps.items():
        def at(t, f):
            v = 0
            for e in ss:
                if e["t"] <= t:
                    v = f(e)
            return v
        army = lambda e: sum(e.get("army", {}).values())
        wk = lambda e: e.get("workers", 0)
        peak, pt = 0, 0
        for e in ss:
            if 720 <= e["t"] <= 1440 and wk(e) > peak:
                peak, pt = wk(e), e["t"]
        low = min([wk(e) for e in ss if e["t"] >= pt] or [peak])
        late = [max(e["bank"]) for e in ss if e["t"] >= 900]
        row = [at(600, army), at(900, army), at(1200, army), at(900, wk), at(1200, wk), peak - low, sum(late) / len(late) if late else 0]
        print("%-16s " % p[:16] + " ".join("%7.2f" % v for v in row))


def main():
    args = sys.argv[1:]
    if args and args[0] == "--match":
        match_stats(args[1])
        return
    seat = None
    if "--seat" in args:
        k = args.index("--seat")
        seat = int(args[k + 1])
        del args[k:k + 2]
    keys = ["army10", "army15", "army20", "wk15", "wk20", "wkLoss", "bank15", "alive", "helps", "joint", "follow"]
    print("%-10s %5s %4s " % ("label", "games", "wins") + " ".join("%7s" % k for k in keys))
    for label in args:
        games = wins = 0
        acc = {k: 0.0 for k in keys}
        n = 0
        for line in open(f"results/{label}.jsonl", encoding="utf-8"):
            r = json.loads(line)
            games += 1
            wins += r.get("winnerTeam") == 0
            team = r["teams"][0]
            spec0 = r["spec"].split(" vs ")[0].split(",")
            idx = [seat] if seat is not None else [i for i, s in enumerate(spec0) if "#" not in s]
            for i in idx:
                if i >= len(team):
                    continue
                st = seat_stats(team[i])
                if st is None:
                    continue
                n += 1
                for k in keys:
                    acc[k] += st[k]
        print("%-10s %5d %4d " % (label, games, wins) + " ".join("%7.2f" % (acc[k] / max(1, n)) for k in keys))


if __name__ == "__main__":
    main()
