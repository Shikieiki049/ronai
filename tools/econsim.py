#!/usr/bin/env python3
"""
A one-second economy model of a RON villagers start, to search for the build that has the most army at a
given minute while never letting the town centre or the barracks stand idle for want of anything we could
have planned for.

    py tools/econsim.py --target 15 [--iters 4000] [--seed 1]     search, print the best plan and its curve
    py tools/econsim.py --show '{"vilCap":34,...}' --target 15     run one plan

Numbers: RON 1.5.1c common config (costs, train times, population) and our own self-play telemetry
(38 food per worked field per minute, 40 wood per woodcutter per minute near a drop site, a hunter about
as fast as a woodcutter while game lasts). Build times and the hunt pool are estimates, see CAL below.
"""
import argparse, json, random

# --- RON costs (food, wood, ore, seconds, pop) ---
VILLAGER = (50, 0, 0, 15, 1)
VINDICATOR = (160, 0, 0, 32, 3)
PILLAGER = (110, 80, 0, 32, 3)
FARM_WOOD, HOUSE_WOOD, BARRACKS_WOOD, STOCK_WOOD = 150, 90, 150, 75

# --- calibration (telemetry and estimates) ---
CAL = {
    "farmRate": 38 / 60.0,     # food per second per worked field
    "woodRate": 40 / 60.0,     # wood per second per woodcutter
    "huntRate": 40 / 60.0,     # food per second per hunter while game lasts
    "huntPool": 1200,          # food in the game near home
    "buildFarm": 25, "buildHouse": 25, "buildBarracks": 60,   # seconds for one builder
    "walkLoss": 0.10,          # share of a new gatherer's first minute lost walking
    "eff": 1.0,                # everything else lost in a real game (idle hands, walks, fleeing), fitted
    "fieldGrow": 60,           # seconds before a new field gives its first wheat
    "startFood": 150, "startWood": 150, "startOre": 50, "startVil": 5, "startPop": 10,
}

# a plan: the knobs the bot would follow
DEFAULT = {
    "vilCap": 34,          # stop villagers at this many
    "vilStopMin": 99,      # ...or at this minute
    "fieldsPerVil": 0.45,  # fields wanted per villager
    "maxHunters": 4,
    "barracksAt": 7.0,     # minute of the first barracks
    "barracksFood": 400,   # another barracks once food banks above this (and no queue is short of food)
    "maxBarracks": 4,
    "houseAhead": 6,       # free population kept ahead
    "armyFrom": 7.5,       # minute from which soldiers are trained
    "pillagerShare": 0.0,  # share of soldiers that are pillagers (wood instead of food)
}
SPACE = {
    "vilCap": (18, 40, True), "vilStopMin": (6, 20, False), "fieldsPerVil": (0.3, 0.7, False),
    "maxHunters": (0, 6, True), "barracksAt": (2, 12, False), "barracksFood": (150, 900, True),
    "maxBarracks": (1, 7, True), "houseAhead": (2, 12, True), "armyFrom": (2, 14, False),
    "pillagerShare": (0.0, 0.6, False),
}


def run(plan, minutes, cal=CAL, trace=False, minArmy=None):
    c = cal
    t = 0
    food, wood = c["startFood"], c["startWood"]
    vil = c["startVil"]
    farms = 0
    farmsBuilding = []      # remaining build seconds
    fieldAge = []           # seconds each built field has stood
    gotF = gotW = 0.0
    houses, housesBuilding = 0, []
    barracks, barracksBuilding = 0, []
    builders = 0
    huntLeft = c["huntPool"]
    tcLeft = 0              # seconds left on the villager in training
    bq = []                 # per barracks: seconds left on its soldier (0 = idle)
    army = 0                # soldiers (pop 3 each)
    armyPop = 0
    tcIdle = bIdle = 0
    curve = []
    trainedVil = 0
    newGatherer = 0.0
    for t in range(minutes * 60):
        m = t / 60.0
        popCap = c["startPop"] + 10 * houses
        pop = vil + armyPop
        # --- decide what to build (one decision a second, cheapest-needed first) ---
        wantVil = vil < plan["vilCap"] and m < plan["vilStopMin"]
        fieldsWanted = int(plan["fieldsPerVil"] * vil + 0.5)
        hunters = min(plan["maxHunters"], max(0, vil - farms)) if huntLeft > 0 else 0
        free = popCap - pop + 10 * len(housesBuilding)
        if free < plan["houseAhead"] and wood >= HOUSE_WOOD and len(housesBuilding) < 2 and popCap < 150:
            wood -= HOUSE_WOOD; housesBuilding.append(c["buildHouse"])
        elif m >= plan["barracksAt"] and barracks + len(barracksBuilding) < plan["maxBarracks"] and wood >= BARRACKS_WOOD and (
                barracks + len(barracksBuilding) == 0 or (food > plan["barracksFood"] and all(x > 0 for x in bq))):
            wood -= BARRACKS_WOOD; barracksBuilding.append(c["buildBarracks"])
        elif farms + len(farmsBuilding) < fieldsWanted and wood >= FARM_WOOD:
            wood -= FARM_WOOD; farmsBuilding.append(c["buildFarm"])
        # builders: one per site, taken from the woodcutters
        builders = len(farmsBuilding) + len(housesBuilding) + len(barracksBuilding)
        # --- town centre ---
        if tcLeft > 0:
            tcLeft -= 1
            if tcLeft == 0:
                vil += 1; trainedVil += 1; newGatherer += 1
        if tcLeft == 0:
            if wantVil and pop < popCap and food >= VILLAGER[0]:
                food -= VILLAGER[0]; tcLeft = VILLAGER[3]
            elif wantVil:
                tcIdle += 1
        # --- barracks ---
        for i in range(len(bq)):
            if bq[i] > 0:
                bq[i] -= 1
                if bq[i] == 0:
                    army += 1; armyPop += 3
            if bq[i] == 0 and m >= plan["armyFrom"]:
                pil = army > 0 and (army * plan["pillagerShare"]) > 0.5 and random.random() < plan["pillagerShare"]
                unit = PILLAGER if pil and wood >= PILLAGER[1] else VINDICATOR
                if vil + armyPop + unit[4] <= popCap and food >= unit[0] and wood >= unit[1]:
                    food -= unit[0]; wood -= unit[1]; bq[i] = unit[3]
                else:
                    bIdle += 1
        # --- gathering ---
        workers = max(0, vil - builders)
        farmers = min(farms, workers)
        ripe = sum(1 for x in fieldAge[:farmers] if x >= c["fieldGrow"])
        fieldAge = [x + 1 for x in fieldAge]
        h = min(hunters, workers - farmers)
        cutters = workers - farmers - h
        eff = c["eff"] * (1.0 - c["walkLoss"] * min(1.0, newGatherer / max(1, workers)))
        newGatherer = max(0.0, newGatherer - 1 / 60.0)
        food += ripe * c["farmRate"] * eff; gotF += ripe * c["farmRate"] * eff
        got = h * c["huntRate"] * eff
        got = min(got, huntLeft); huntLeft -= got; food += got; gotF += got
        wood += cutters * c["woodRate"] * eff; gotW += cutters * c["woodRate"] * eff
        # --- construction ---
        for lst in (farmsBuilding, housesBuilding, barracksBuilding):
            for i in range(len(lst)):
                lst[i] -= 1
        done = [x for x in farmsBuilding if x <= 0]; farms += len(done); fieldAge += [0] * len(done); farmsBuilding = [x for x in farmsBuilding if x > 0]
        done = [x for x in housesBuilding if x <= 0]; houses += len(done); housesBuilding = [x for x in housesBuilding if x > 0]
        done = [x for x in barracksBuilding if x <= 0]
        for _ in done: barracks += 1; bq.append(0)
        barracksBuilding = [x for x in barracksBuilding if x > 0]
        if t % 60 == 59:
            curve.append((int(m) + 1, vil, army, farms, barracks, int(food), int(wood), int(gotF), int(gotW)))
    # the army at the end, counting soldiers half-trained; a hard floor on the army curve if asked
    score = army
    if minArmy:
        for mm, need in minArmy.items():
            row = curve[int(mm) - 1] if int(mm) - 1 < len(curve) else None
            if row and row[2] < need: score -= 2 * (need - row[2])
    return score, curve, {"tcIdle": tcIdle, "barracksIdle": bIdle}


def mutate(p, rng, sigma=0.2):
    q = dict(p)
    for k in rng.sample(list(SPACE), k=rng.randint(1, 4)):
        lo, hi, integer = SPACE[k]
        v = q[k] + rng.gauss(0, sigma * (hi - lo))
        v = min(hi, max(lo, v))
        q[k] = int(round(v)) if integer else round(v, 3)
    return q


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", type=int, default=15)
    ap.add_argument("--iters", type=int, default=4000)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--show", default=None)
    ap.add_argument("--cal", default=None, help="calibration overrides as JSON")
    ap.add_argument("--floor", default=None, help='army floor, e.g. {"10":4,"12":8}')
    a = ap.parse_args()
    floor = json.loads(a.floor) if a.floor else None
    if a.cal: CAL.update(json.loads(a.cal))
    random.seed(0)
    if a.show:
        plan = dict(DEFAULT); plan.update(json.loads(a.show))
        s, curve, st = run(plan, a.target, minArmy=floor)
        print("army", s, st)
        for r in curve: print("min %2d  villagers %2d  army %2d  fields %2d  barracks %d  food %5d  wood %5d  got %5d/%5d" % r)
        return
    rng = random.Random(a.seed)
    best = dict(DEFAULT)
    random.seed(0)
    bs = run(best, a.target, minArmy=floor)[0]
    for i in range(a.iters):
        cand = mutate(best, rng, 0.25 if i < a.iters // 2 else 0.1)
        random.seed(0)
        s = run(cand, a.target, minArmy=floor)[0]
        if s >= bs:
            best, bs = cand, s
    random.seed(0)
    s, curve, st = run(best, a.target, minArmy=floor)
    print("best army at %d min: %d   %s" % (a.target, s, st))
    print(json.dumps(best))
    for r in curve: print("min %2d  villagers %2d  army %2d  fields %2d  barracks %d  food %5d  wood %5d  got %5d/%5d" % r)


if __name__ == "__main__":
    main()
