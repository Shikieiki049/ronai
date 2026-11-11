package com.ronstrat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Headless self-play harness. Start a dedicated server with
 *   -Dronstrat.autotest="expert vs normal*5" -Dronstrat.seed=3 -Dronstrat.out=results.jsonl
 * and it builds an arena, plays the match with no human on the server (as fast as the CPU allows),
 * appends one JSON line with the result and shuts down. The tuner runs many of these in parallel.
 */
public final class AutoTest {
    private AutoTest() {}

    private static Match match;
    private static MinecraftServer server;
    private static long ticks;
    private static long realStart;
    private static boolean done;
    private static Field nextTickTime;
    private static long lastProgress;

    public static boolean enabled() { return System.getProperty("ronstrat.autotest") != null || System.getProperty("ronstrat.lobbytest") != null; }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent evt) {
        if (!enabled()) return;
        server = evt.getServer();
        ticks = 0;
        done = false;
        if (Boolean.parseBoolean(System.getProperty("ronstrat.fast", "true"))) {
            try {
                nextTickTime = ObfuscationReflectionHelper.findField(MinecraftServer.class, "f_129726_");
                nextTickTime.setAccessible(true);
            } catch (Exception e) {
                RonStrategist.LOG.warn("RON Strategist: fast mode unavailable: {}", e.toString());
            }
        }
    }

    /** -Dronstrat.lobbytest=normal: seat a computer player on every lobby position and check RON starts them */
    private static void lobbyTest() {
        if (ticks == 80) {
            Difficulty d = Difficulty.parse(System.getProperty("ronstrat.lobbytest"));
            int n = 0;
            for (var sp : new java.util.ArrayList<>(com.solegendary.reignofnether.startpos.StartPosServerEvents.startPoses)) {
                if (!sp.enabled) continue;
                LobbyAi.setSeat(sp.pos, d);
                // lobby settings check: the first two seats one team with a handicap on the first, the rest another team
                LobbySettings.set(sp.pos, n < 2 ? 1 : 2, n == 0 ? 2 : 0);
                n++;
            }
            RonStrategist.LOG.info("lobbytest: seated {} computer players, countdown running={}", n,
                    com.solegendary.reignofnether.startpos.StartPosServerEvents.isStartingGame());
        }
        if (ticks == 400 || ticks == 2400) {
            RonStrategist.LOG.info("lobbytest: t={} rtsPlayers={} bots={}", ticks, PlayerServerEvents.rtsPlayers.size(), BotManager.bots().size());
            for (Agent b : BotManager.bots()) RonStrategist.LOG.info("lobbytest:   {}", b.status());
            java.util.List<Agent> all = new java.util.ArrayList<>(BotManager.bots());
            for (int i = 0; i < all.size(); i++)
                for (int j = i + 1; j < all.size(); j++)
                    RonStrategist.LOG.info("lobbytest: allied {} {} = {}", all.get(i).name(), all.get(j).name(),
                            com.solegendary.reignofnether.alliance.AlliancesServerEvents.isAllied(all.get(i).name(), all.get(j).name()));
        }
        if (ticks == 2400) { done = true; server.halt(false); }
    }

    /**
     * -Dronstrat.waterTest: at 1:00 every bot's first four villagers are dropped into the nearest water
     * (6-60 blocks from home) and ordered home; at 2:00 the log says how many made it back to land
     * near the town centre (WATERTEST lines).
     */
    private static final java.util.Map<Integer, BlockPos> dunked = new java.util.HashMap<>();
    private static final int MOAT_W = Integer.getInteger("ronstrat.moatW", 6), MOAT_D = Integer.getInteger("ronstrat.moatD", 2);
    private static final java.util.Map<String, java.util.List<net.minecraft.world.entity.Mob>> dunkOwner = new java.util.HashMap<>();
    private static void waterTest(long gameTicks) {
        ServerLevel lv = server.overworld();
        if (gameTicks == 1200) {
            for (Agent b : BotManager.bots()) {
                BlockPos home = b.home();
                BlockPos water = null, shore = null;
                outer:
                for (int r = 6; r <= 120; r += 2)
                    for (int a = 0; a < 360; a += 10) {
                        int x = home.getX() + (int) Math.round(Math.cos(Math.toRadians(a)) * r), z = home.getZ() + (int) Math.round(Math.sin(Math.toRadians(a)) * r);
                        int y = lv.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
                        BlockPos q = new BlockPos(x, y - 1, z);
                        if (!lv.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER) || !lv.getFluidState(q.below()).is(net.minecraft.tags.FluidTags.WATER)) continue;
                        // deep water 5-12 blocks from a bank one can climb out on (at most 1 above the surface)
                        for (int d = 5; d <= 12; d++)
                            for (int b2 = 0; b2 < 360; b2 += 30) {
                                int sx2 = x + (int) Math.round(Math.cos(Math.toRadians(b2)) * d), sz2 = z + (int) Math.round(Math.sin(Math.toRadians(b2)) * d);
                                int sy = lv.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, sx2, sz2);
                                BlockPos top = new BlockPos(sx2, sy - 1, sz2);
                                if (lv.getFluidState(top).isEmpty() && sy >= y && sy <= y + 1 && lv.getBlockState(top).isSolid()) { water = q; shore = new BlockPos(sx2, sy, sz2); break outer; }
                            }
                    }
                if (water == null && !"moat".equals(System.getProperty("ronstrat.waterTest"))) { RonStrategist.LOG.info("WATERTEST {} no water near home", b.name()); continue; }
                if ("moat".equals(System.getProperty("ronstrat.waterTest"))) {
                    // a flat test bed beside the base: 8 blocks of ground, a channel MOAT_W wide and MOAT_D
                    // deep, 8 blocks of ground, all 200 long (no way round within reach)
                    int cx0 = home.getX() + 14, cz = home.getZ();
                    int base = lv.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx0 - 3, cz);
                    var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
                    var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                    var wat = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();
                    for (int x = cx0 - 8; x < cx0 + MOAT_W + 8; x++)
                        for (int z = cz - 100; z <= cz + 100; z++) {
                            boolean ch = x >= cx0 && x < cx0 + MOAT_W;
                            lv.setBlock(new BlockPos(x, base - MOAT_D - 1, z), stone, 2);
                            for (int y = base - MOAT_D; y < base; y++) lv.setBlock(new BlockPos(x, y, z), ch ? wat : stone, 2);
                            for (int y = base; y <= base + 12; y++) lv.setBlock(new BlockPos(x, y, z), air, 2);
                        }
                    water = new BlockPos(cx0 - 3, base - 1, cz);
                    shore = new BlockPos(cx0 + MOAT_W + 4, base, cz);
                    RonStrategist.LOG.info("WATERTEST {} test bed: channel x={}..{} y={}..{}", b.name(), cx0, cx0 + MOAT_W - 1, base - MOAT_D, base - 1);
                }
                if ("cross".equals(System.getProperty("ronstrat.waterTest"))) {
                    // a crossing: dry land, then 4-25 blocks of water, then dry land, near home
                    BlockPos from = null, farBank = null;
                    search:
                    for (int r = 4; r <= 120; r += 3)
                        for (int a = 0; a < 360; a += 12) {
                            int x = home.getX() + (int) Math.round(Math.cos(Math.toRadians(a)) * r), z = home.getZ() + (int) Math.round(Math.sin(Math.toRadians(a)) * r);
                            int y = lv.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
                            BlockPos top = new BlockPos(x, y - 1, z);
                            if (!lv.getFluidState(top).isEmpty() || !lv.getBlockState(top).isSolid()) continue;
                            for (int dir = 0; dir < 360; dir += 22) {
                                double ux = Math.cos(Math.toRadians(dir)), uz = Math.sin(Math.toRadians(dir));
                                int wet2 = 0; boolean started = false;
                                for (int k = 1; k <= 30; k++) {
                                    int fx = x + (int) Math.round(ux * k), fz = z + (int) Math.round(uz * k);
                                    int fy = lv.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, fx, fz);
                                    BlockPos t = new BlockPos(fx, fy - 1, fz);
                                    boolean isWater = lv.getFluidState(t).is(net.minecraft.tags.FluidTags.WATER);
                                    if (isWater) { started = true; wet2++; if (Math.abs(fy - y) > 2) break; continue; }
                                    if (!started) { if (k > 2) break; continue; }
                                    if (wet2 >= 4 && wet2 <= 25 && lv.getBlockState(t).isSolid() && Math.abs(fy - y) <= 1) { from = new BlockPos(x, y, z); farBank = new BlockPos(fx, fy, fz); break search; }
                                    break;
                                }
                            }
                        }
                    if (farBank == null) { RonStrategist.LOG.info("WATERTEST {} no crossing near home", b.name()); continue; }
                    shore = farBank;
                    water = from.below(); // villagers start standing on the near bank
                }
                java.util.List<net.minecraft.world.entity.Mob> ws = new java.util.ArrayList<>(b.workers());
                ws = ws.subList(0, Math.min(4, ws.size()));
                String extra = System.getProperty("ronstrat.waterUnits");
                if (extra != null) {
                    ws = new java.util.ArrayList<>();
                    for (String t : extra.split(",")) {
                        var type = switch (t) {
                            case "vindicator" -> com.solegendary.reignofnether.registrars.EntityRegistrar.VINDICATOR_UNIT.get();
                            case "pillager" -> com.solegendary.reignofnether.registrars.EntityRegistrar.PILLAGER_UNIT.get();
                            case "golem" -> com.solegendary.reignofnether.registrars.EntityRegistrar.IRON_GOLEM_UNIT.get();
                            case "ravager" -> com.solegendary.reignofnether.registrars.EntityRegistrar.RAVAGER_UNIT.get();
                            default -> null;
                        };
                        if (type == null) continue;
                        var ent = com.solegendary.reignofnether.unit.UnitServerEvents.spawnMob(type, lv, water.above(), b.name());
                        if (ent instanceof net.minecraft.world.entity.Mob mm) ws.add(mm);
                    }
                }
                for (net.minecraft.world.entity.Mob w : ws) {
                    w.teleportTo(water.getX() + 0.5, water.getY() + 1.05, water.getZ() + 0.5);
                    w.getNavigation().stop();
                    dunked.put(w.getId(), shore);
                    Economy.TEST_HOLD.add(w.getId());
                }
                Orders.move(b.name(), ws, shore);
                dunkOwner.put(b.name(), ws);
                RonStrategist.LOG.info("WATERTEST {} dropped {} villagers into water at {} and ordered them to the bank at {} (d={})", b.name(), ws.size(), water.toShortString(), shore.toShortString(), (int) Math.sqrt(water.distSqr(shore)));
            }
        }
        if (gameTicks > 1200 && gameTicks < 2400 && gameTicks % 100 == 0)
            for (var en : dunkOwner.entrySet()) { var alive = en.getValue().stream().filter(net.minecraft.world.entity.LivingEntity::isAlive).toList(); if (!alive.isEmpty()) Orders.move(en.getKey(), alive, dunked.get(alive.get(0).getId())); }
        if (gameTicks > 1500 && gameTicks < 2400 && gameTicks % 20 == 0) {
            for (net.minecraft.world.entity.LivingEntity e : com.solegendary.reignofnether.unit.UnitServerEvents.getAllUnits()) {
                if (!dunked.containsKey(e.getId()) || !(e instanceof net.minecraft.world.entity.Mob m) || !e.isInWater()) continue;
                var p0 = m.getNavigation().getPath();
                if (p0 != null && !p0.isDone()) {
                    var nn = p0.getNextNode();
                    RonStrategist.LOG.info("WATERNODE #{} pos={} next#{}={},{},{} type={} zza={} speed={} vel={} jump={} hcol={} onGround={} moveCtl={}", e.getId(), String.format("%.2f,%.2f,%.2f", e.getX(), e.getY(), e.getZ()), p0.getNextNodeIndex(), nn.x, nn.y, nn.z, nn.type,
                            String.format("%.3f", m.zza), String.format("%.3f", m.getSpeed()), m.getDeltaMovement(), m.getJumpControl().getClass().getSimpleName(), m.horizontalCollision, m.onGround(), m.getMoveControl().hasWanted() + "@" + String.format("%.2f,%.2f,%.2f", m.getMoveControl().getWantedX(), m.getMoveControl().getWantedY(), m.getMoveControl().getWantedZ()));
                    break;
                }
            }
        }
        if (gameTicks > 1200 && gameTicks < 2400 && gameTicks % 100 == 0) {
            for (net.minecraft.world.entity.LivingEntity e : com.solegendary.reignofnether.unit.UnitServerEvents.getAllUnits()) {
                if (!dunked.containsKey(e.getId()) || !(e instanceof net.minecraft.world.entity.Mob m)) continue;
                var mg = ((com.solegendary.reignofnether.unit.interfaces.Unit) e).getMoveGoal();
                var path = m.getNavigation().getPath();
                RonStrategist.LOG.info("WATERDIAG #{} at {} inWater={} malus={} mt={} path={} done={} canFloat={} nav={} vel={} goals={}", e.getId(), e.blockPosition().toShortString(), e.isInWater(),
                        m.getPathfindingMalus(net.minecraft.world.level.pathfinder.BlockPathTypes.WATER), mg == null ? null : mg.getMoveTarget(),
                        path == null ? "-" : (path.getNodeCount() + "/" + path.getNextNodeIndex() + (path.canReach() ? "R" : "x")), m.getNavigation().isDone(),
                        m.getNavigation().getNodeEvaluator().canFloat(), m.getNavigation().getClass().getSimpleName(), m.getDeltaMovement(),
                        m.goalSelector.getRunningGoals().map(g -> g.getGoal().getClass().getSimpleName()).toList());
                break;
            }
        }
        if (gameTicks == 2400) {
            int ok = 0, inWater = 0, n = 0;
            for (net.minecraft.world.entity.LivingEntity e : com.solegendary.reignofnether.unit.UnitServerEvents.getAllUnits()) {
                BlockPos home = dunked.get(e.getId());
                if (home == null) continue;
                n++;
                if (e.isInWater()) {
                    inWater++;
                    var m = (net.minecraft.world.entity.Mob) e;
                    var path = m.getNavigation().getPath();
                    StringBuilder around = new StringBuilder();
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                        BlockPos q = e.blockPosition().offset(dx, 0, dz);
                        around.append(lv.getBlockState(q).getBlock().getDescriptionId().replace("block.minecraft.", "")).append('/').append(lv.getBlockState(q.above()).getBlock().getDescriptionId().replace("block.minecraft.", "")).append(' ');
                    }
                    RonStrategist.LOG.info("WATERSTUCK #{} " + e.getType().getDescriptionId() + " at {} y={} target={} path={} around: {}", e.getId(), e.blockPosition().toShortString(), String.format("%.2f", e.getY()), home.toShortString(),
                            path == null ? "-" : (path.getNodeCount() + "/" + path.getNextNodeIndex() + (path.canReach() ? "R" : "x") + " end=" + (path.getEndNode() == null ? "-" : path.getEndNode().asBlockPos().toShortString())), around);
                }
                else if (e.blockPosition().distSqr(home) < 6 * 6) ok++;
                else {
                    var m = (net.minecraft.world.entity.Mob) e;
                    var path = m.getNavigation().getPath();
                    var mg = ((com.solegendary.reignofnether.unit.interfaces.Unit) e).getMoveGoal();
                    RonStrategist.LOG.info("WATERLAND #{} {} " + e.getType().getDescriptionId() + " at {} target={} d={} malus={} mt={} path={} goals={}", e.getId(), ((com.solegendary.reignofnether.unit.interfaces.Unit) e).getOwnerName(), e.blockPosition().toShortString(), home.toShortString(), (int) Math.sqrt(e.blockPosition().distSqr(home)),
                            m.getPathfindingMalus(net.minecraft.world.level.pathfinder.BlockPathTypes.WATER), mg == null ? null : mg.getMoveTarget(),
                            path == null ? "-" : (path.getNodeCount() + "/" + path.getNextNodeIndex() + (path.canReach() ? "R" : "x") + " end=" + (path.getEndNode() == null ? "-" : path.getEndNode().asBlockPos().toShortString())),
                            m.goalSelector.getRunningGoals().map(g -> g.getGoal().getClass().getSimpleName()).toList());
                }
            }
            RonStrategist.LOG.info("WATERTEST result: {} of {} dunked villagers reached the bank, {} still in water", ok, n, inWater);
        }
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent evt) {
        if (server == null || evt.phase != TickEvent.Phase.END || done) return;
        ticks++;
        if (System.getProperty("ronstrat.lobbytest") != null) {
            if (nextTickTime != null) try { nextTickTime.setLong(server, Util.getMillis()); } catch (Exception ignored) { }
            lobbyTest();
            return;
        }
        if (nextTickTime != null) {
            try { nextTickTime.setLong(server, Util.getMillis()); } catch (Exception ignored) { }
        }
        if (ticks == 50 && System.getProperty("ronstrat.dumpTerrain") != null) {
            // -Dronstrat.dumpTerrain=x1,z1,x2,z2: surface height and block per column (map study)
            String[] q = System.getProperty("ronstrat.dumpTerrain").split(",");
            int x1 = Integer.parseInt(q[0]), z1 = Integer.parseInt(q[1]), x2 = Integer.parseInt(q[2]), z2 = Integer.parseInt(q[3]);
            ServerLevel lv = server.overworld();
            for (int z = z1; z <= z2; z++) {
                StringBuilder hb = new StringBuilder(), bb = new StringBuilder();
                for (int x = x1; x <= x2; x++) {
                    int y = lv.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    hb.append(String.format("%3d", y));
                    String n = lv.getBlockState(new BlockPos(x, y - 1, z)).getBlock().getDescriptionId();
                    n = n.substring(n.lastIndexOf('.') + 1);
                    bb.append(n.contains("stair") ? 'S' : n.contains("slab") ? 's' : n.contains("water") ? '~' : n.contains("brick") ? 'B' : n.contains("grass") || n.contains("dirt") ? '.' : n.contains("log") || n.contains("leaves") ? 'T' : n.contains("fence") || n.contains("wall") ? '#' : '?');
                }
                RonStrategist.LOG.info("TERRAIN z={} H{}", z, hb);
                RonStrategist.LOG.info("TERRAIN z={} B {}", z, bb);
            }
        }
        if (ticks == 55 && System.getProperty("ronstrat.scanLogs") != null) {
            // -Dronstrat.scanLogs=x,z,r: every log block within r, grouped in 8x8 cells (map study)
            String[] q = System.getProperty("ronstrat.scanLogs").split(",");
            int cx = Integer.parseInt(q[0]), cz = Integer.parseInt(q[1]), r = Integer.parseInt(q[2]);
            ServerLevel lv = server.overworld();
            java.util.Map<String, int[]> cells = new java.util.TreeMap<>();
            for (int x = cx - r; x <= cx + r; x++)
                for (int z = cz - r; z <= cz + r; z++)
                    for (int y = 40; y <= 110; y++) {
                        var bs = lv.getBlockState(new BlockPos(x, y, z));
                        if (!bs.is(net.minecraft.tags.BlockTags.LOGS)) continue;
                        String k = Math.floorDiv(x, 8) * 8 + "," + Math.floorDiv(z, 8) * 8;
                        int[] c = cells.computeIfAbsent(k, kk -> new int[] { 0, 999, -999, 0 });
                        c[0]++; c[1] = Math.min(c[1], y); c[2] = Math.max(c[2], y);
                        boolean leafy = false;
                        for (int dy = 1; dy <= 3 && !leafy; dy++) if (lv.getBlockState(new BlockPos(x, y + dy, z)).is(net.minecraft.tags.BlockTags.LEAVES)) leafy = true;
                        if (!leafy) c[3]++;
                    }
            for (var e : cells.entrySet()) RonStrategist.LOG.info("LOGS cell {} n={} y={}..{} noLeaves={}", e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3]);
        }
        if (ticks == 60) {
            long seed = Long.getLong("ronstrat.seed", 1L);
            match = new Match(MatchSpec.parse(System.getProperty("ronstrat.autotest")), seed);
            ServerLevel level = server.overworld();
            if (System.getProperty("ronstrat.map") != null) match.useMapSeats(level);
            match.start(level, new BlockPos(0, 0, 0).atY(level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0)));
            realStart = System.currentTimeMillis();
            RonStrategist.LOG.info("RON Strategist autotest started: {} seed={}", match.spec.text, seed);
            return;
        }
        if (match == null) return;
        long gameTicks = PlayerServerEvents.rtsGameTicks - match.startTick;
        long timeout = Long.getLong("ronstrat.timeoutMinutes", 45L) * 60 * 20;
        if (gameTicks - lastProgress >= 20 * 60) {
            lastProgress = gameTicks;
            StringBuilder sb = new StringBuilder();
            for (Agent b : BotManager.bots()) {
                sb.append("\n  ").append(b.status());
                if (b.verbose() && gameTicks < 20 * 60 * 8) sb.append(b.workerDump());
            }
            RonStrategist.LOG.info("autotest t={}s real={}s{}", gameTicks / 20, (System.currentTimeMillis() - realStart) / 1000, sb);
        }
        if (System.getProperty("ronstrat.waterTest") != null) waterTest(gameTicks);
        // -Dronstrat.stopAt=720: a short game for economy measurements, ended at that game second
        long stopAt = Long.getLong("ronstrat.stopAt", 0L) * 20;
        if (stopAt > 0 && gameTicks >= stopAt && !match.decided()) { stopped = true; finish(gameTicks, true, -1); return; }
        if (match.decided() || gameTicks >= timeout) { finish(gameTicks, gameTicks >= timeout && !match.decided(), -1); return; }
        // decisive stop: once a side has no army left and is out-scored 5:1 for two straight minutes,
        // razing the remaining houses only costs wall time
        if (gameTicks % 200 == 0) match.trackPeaks();
        Telemetry.tick(gameTicks, BotManager.bots());
        if (gameTicks % 200 == 0 && gameTicks > 20 * 60 * 8 && !match.spec.ffa) {
            int lead = match.dominantTeam(5.0);
            if (lead >= 0 && lead == domTeam) {
                if (gameTicks - domSince >= 20 * 120) { finish(gameTicks, false, lead); return; }
            } else {
                domTeam = lead;
                domSince = gameTicks;
            }
            // equal teams: twice the units and the bigger army for 90 s (-Dronstrat.adjudicate=off to play on)
            if (!"off".equals(System.getProperty("ronstrat.adjudicate"))) {
                int ahead = match.popLeader(Double.parseDouble(System.getProperty("ronstrat.adjudicateRatio", "2.0")));
                if (ahead >= 0 && ahead == popTeam) {
                    if (gameTicks - popSince >= 20 * 90) { adjudged = true; finish(gameTicks, false, ahead); }
                } else {
                    popTeam = ahead;
                    popSince = gameTicks;
                }
            }
        }
    }

    private static int domTeam = -1, popTeam = -1;
    private static long popSince;
    private static boolean adjudged, stopped;
    private static long domSince;

    private static void finish(long gameTicks, boolean timedOut, int decisiveWinner) {
        done = true;
        List<Integer> alive = match.aliveTeams();
        int winner = decisiveWinner;
        if (!timedOut && !match.spec.ffa && alive.size() == 1) winner = alive.get(0);
        if (!timedOut && match.spec.ffa) {
            for (int i = 0; i < match.teams.size(); i++)
                for (Agent b : match.teams.get(i)) if (BotManager.isRtsName(b.name())) winner = i;
        }
        if (timedOut) {
            double best = -1;
            for (int i : alive) { double s = match.teamScore(i); if (s > best) { best = s; winner = i; } }
        }
        JsonObject o = new JsonObject();
        o.addProperty("spec", match.spec.text);
        o.addProperty("seed", match.seed);
        o.addProperty("winnerTeam", winner);
        o.addProperty("timeout", timedOut);
        o.addProperty("decisive", decisiveWinner >= 0);
        o.addProperty("adjudged", adjudged);
        o.addProperty("stopped", stopped);
        o.addProperty("arena", System.getProperty("ronstrat.map") != null ? "map:" + System.getProperty("ronstrat.map") : Arena.summary(match.spec.players()));
        o.addProperty("gameSeconds", gameTicks / 20);
        o.addProperty("realSeconds", (System.currentTimeMillis() - realStart) / 1000);
        o.addProperty("paramsExpert", System.getProperty("ronstrat.params.expert", ""));
        JsonArray teams = new JsonArray();
        for (int i = 0; i < match.teams.size(); i++) {
            JsonArray t = new JsonArray();
            for (Agent b : match.teams.get(i)) {
                JsonObject bo = new JsonObject();
                bo.addProperty("name", b.name());
                bo.addProperty("difficulty", b.difficulty().key());
                bo.addProperty("brain", b.brain());
                bo.addProperty("profile", b.profile());
                bo.addProperty("alive", BotManager.isRtsName(b.name()));
                for (String k : new String[] { "peakWorkers", "peakArmy", "attacks", "retreats", "buildings" })
                    bo.addProperty(k, (int) b.stat(k));
                JsonObject metrics = Telemetry.json(b.name());
                if (metrics != null) bo.add("metrics", metrics);
                t.add(bo);
            }
            teams.add(t);
        }
        o.add("teams", teams);
        String line = new Gson().toJson(o);
        RonStrategist.LOG.info("RON Strategist autotest result: {}", line);
        String out = System.getProperty("ronstrat.out");
        if (out != null) {
            try {
                Files.writeString(Path.of(out), line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                RonStrategist.LOG.error("could not write result", e);
            }
        }
        server.halt(false);
    }
}
