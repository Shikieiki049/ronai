package com.ronstrat;

import com.solegendary.reignofnether.alliance.AlliancesServerEvents;
import com.solegendary.reignofnether.faction.Faction;
import com.solegendary.reignofnether.faction.Factions;
import com.solegendary.reignofnether.fogofwar.FogOfWarServerEvents;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import com.solegendary.reignofnether.player.RTSPlayer;
import com.solegendary.reignofnether.resources.ResourcesServerEvents;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import com.solegendary.reignofnether.util.MiscUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns all bots on the server, starts them like a human player starts, ticks them, saves them. */
public final class BotManager {
    private BotManager() {}

    private static final Map<String, Agent> BOTS = new LinkedHashMap<>();
    private static MinecraftServer server;
    private static long serverTicks;

    public static Collection<Agent> bots() { return BOTS.values(); }

    public static Agent get(String name) { return BOTS.get(name); }

    public static boolean isRtsName(String name) {
        synchronized (PlayerServerEvents.rtsPlayers) {
            for (RTSPlayer p : PlayerServerEvents.rtsPlayers) if (p.name.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public static String freeName(Difficulty d) {
        String base = "AI-" + d.zh;
        for (int i = 1; ; i++) {
            String n = base + "-" + i;
            if (!BOTS.containsKey(n) && !isRtsName(n)) return n;
        }
    }

    /**
     * Start a computer player exactly like a human starts: the faction's five workers (plus the scout
     * when fog of war is on), the normal starting resources, and no buildings. The bot then has to
     * place and build its own capitol.
     */
    public static Agent start(ServerLevel level, String name, Difficulty d, BlockPos pos) {
        return start(level, name, d, pos, "", "");
    }

    /**
     * The brain behind a computer player: the current one, or (self-play only) a frozen copy of an older
     * version in package com.ronstrat.&lt;brain&gt;, made by tools/freeze.ps1.
     */
    static Agent create(String brain, String profile, String name, Difficulty d, Faction faction, BlockPos pos, ServerLevel level) {
        if (brain == null || brain.isEmpty()) return new Bot(name, d, faction, pos, level, level.getSeed(), profile);
        if (brain.startsWith("ron")) return new RonBot(name, d, faction, pos, level, level.getSeed(), brain);
        try {
            return (Agent) Class.forName("com.ronstrat." + brain + ".Bot")
                    .getConstructor(String.class, Difficulty.class, Faction.class, BlockPos.class, ServerLevel.class, long.class, String.class)
                    .newInstance(name, d, faction, pos, level, level.getSeed(), profile);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("brain '" + brain + "' is not on the classpath (run tools/freeze.ps1 and build the baseline classes)", e);
        }
    }

    public static Agent start(ServerLevel level, String name, Difficulty d, BlockPos pos, String brain, String profile) {
        Faction faction = Factions.VILLAGERS;
        if (isRtsName(name)) throw new IllegalStateException(name + " is already playing");
        synchronized (PlayerServerEvents.rtsPlayers) {
            PlayerServerEvents.rtsPlayers.add(RTSPlayer.getNewBot(name, faction));
        }
        // (RON 1.5.1c dropped the fog's rts-player cache: nothing to invalidate any more)
        ResourcesServerEvents.assignResources(name);

        EntityType<?> worker = ForgeRegistries.ENTITY_TYPES.getValue(faction.workerEntityType);
        // the bot builds its town centre on `pos`: start the villagers beside that spot, not on it, or
        // the foundation goes up around them and they stand boxed in for the rest of the game
        BlockPos at = beside(level, pos);
        int[][] offs = { { 0, 0 }, { 1, 0 }, { 0, 1 }, { -1, 0 }, { 0, -1 } };
        for (int[] o : offs) spawn(level, worker, name, at.offset(o[0], 0, o[1]));
        if (false) { // no scout: the computer players see the whole map
            EntityType<?> scout = ForgeRegistries.ENTITY_TYPES.getValue(faction.scoutEntityType);
            spawn(level, scout, name, at.offset(-1, 0, -1));
        }
        ResourcesServerEvents.resetResources(name, false);
        PlayerServerEvents.saveRTSPlayers();
        Agent bot = create(brain, profile, name, d, faction, pos, level);
        BOTS.put(name, bot);
        save(level.getServer());
        PlayerServerEvents.sendMessageToAllPlayers("ronstrat.joined", true, name, d.zh);
        return bot;
    }

    /**
     * Start a computer player from the RON lobby, exactly like RON starts a human from a start
     * position ("readied start"): capitol placed by the game, five workers next to it, the readied
     * starting resources, and the team colour that decides alliances.
     */
    public static Agent startReadied(ServerLevel level, String name, Difficulty d, BlockPos pos, int colorId) {
        return startReadied(level, name, d, pos, colorId, "", "");
    }

    public static Agent startReadied(ServerLevel level, String name, Difficulty d, BlockPos pos, int colorId, String brain, String profile) {
        Faction faction = Factions.VILLAGERS;
        if (isRtsName(name)) throw new IllegalStateException(name + " is already playing");
        RTSPlayer rp = RTSPlayer.getNewBot(name, faction);
        rp.startPosColorId = colorId;
        synchronized (PlayerServerEvents.rtsPlayers) { PlayerServerEvents.rtsPlayers.add(rp); }
        // (RON 1.5.1c dropped the fog's rts-player cache: nothing to invalidate any more)
        ResourcesServerEvents.assignResources(name);
        com.solegendary.reignofnether.building.Building capitol =
                com.solegendary.reignofnether.api.ReignOfNetherRegistries.BUILDING.get(faction.capitolBuilding);
        EntityType<?> worker = ForgeRegistries.ENTITY_TYPES.getValue(faction.workerEntityType);
        List<Integer> ids = new ArrayList<>();
        BlockPos origin = pos;
        if (capitol != null) {
            var blocks = capitol.getRelativeBlockData(level);
            origin = PlayerServerEvents.getBuildingOriginPos(pos, blocks);
        }
        for (int i = 0; i < 5; i++) {
            Entity e = worker == null ? null : worker.create(level);
            if (!(e instanceof Unit u)) continue;
            u.setOwnerName(name);
            e.moveTo(origin.offset(-1, 1, i), 0, 0);
            level.addFreshEntity(e);
            ids.add(e.getId());
        }
        if (capitol != null) {
            RonStrategist.LOG.info("RON Strategist: {} town centre origin {} (start block {}, ground at the start {})", name, origin.toShortString(), pos.toShortString(),
                    level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()));
            com.solegendary.reignofnether.building.BuildingServerEvents.placeBuilding(capitol, origin,
                    net.minecraft.world.level.block.Rotation.NONE, name, ids.stream().mapToInt(Integer::intValue).toArray(),
                    false, false, true, true);
        }
        // the capitol now stands where the villagers were put (RON's own offsets): on some seats they end up
        // inside its walls with no way out (a test game with 11 villagers and no income all game). Step
        // every one of them out beside the building.
        {
            BlockPos out = beside(level, origin);
            int k = 0;
            for (Integer id : ids) {
                Entity e = level.getEntity(id);
                if (e == null) continue;
                BlockPos at = out.offset(k % 3 - 1, 0, k / 3);
                BlockPos ground = MiscUtil.getHighestNonAirBlock(level, new BlockPos(at.getX(), 0, at.getZ())).above();
                e.teleportTo(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5);
                k++;
            }
        }
        // a human starting from a seat also gets a scout when fog of war is on
        if (FogOfWarServerEvents.isEnabled() && brain != null && !brain.isEmpty() && !brain.startsWith("ron")) spawn(level, ForgeRegistries.ENTITY_TYPES.getValue(faction.scoutEntityType), name, origin.offset(-1, 1, -1));
        ResourcesServerEvents.resetResources(name, true);
        synchronized (PlayerServerEvents.rtsPlayers) {
            for (RTSPlayer other : PlayerServerEvents.rtsPlayers)
                if (!other.name.equals(name) && other.startPosColorId == colorId) ally(name, other.name);
        }
        PlayerServerEvents.saveRTSPlayers();
        Agent bot = create(brain, profile, name, d, faction, pos, level);
        BOTS.put(name, bot);
        save(level.getServer());
        return bot;
    }

    /**
     * 1vN handicap for the outnumbered side (bot or human): for every enemy beyond the first,
     * two more starting villagers and another half of the normal starting resources.
     * extraEnemies = N - 1 in a 1vN.
     */
    public static void applyHandicap(ServerLevel level, String owner, BlockPos near, int extraEnemies) {
        if (extraEnemies <= 0) return;
        Faction faction = Factions.VILLAGERS;
        RTSPlayer rp = PlayerServerEvents.getRTSPlayer(owner);
        if (rp != null && rp.faction != null) faction = rp.faction;
        EntityType<?> worker = ForgeRegistries.ENTITY_TYPES.getValue(faction.workerEntityType);
        int extraWorkers = 2 * extraEnemies;
        BlockPos at = beside(level, near);
        for (int i = 0; i < extraWorkers; i++) {
            int dx = (i % 4) - 1, dz = 2 + i / 4;
            spawn(level, worker, owner, at.offset(dx, 0, dz));
        }
        double k = 0.5 * extraEnemies; // starting stock is 150 food / 500 wood / 300 ore
        ResourcesServerEvents.addSubtractResources(new com.solegendary.reignofnether.resources.Resources(owner,
                (int) Math.round(150 * k), (int) Math.round(500 * k), (int) Math.round(300 * k)));
        RonStrategist.LOG.info("RON Strategist: handicap for {}: +{} villagers, +{}x starting resources", owner, extraWorkers, k);
    }

    /**
     * A spot about 11 blocks from a future town centre site, on dry ground at about the same height,
     * clear of the building's footprint.
     */
    static BlockPos beside(ServerLevel level, BlockPos pos) {
        int[][] dirs = { { 11, 0 }, { -11, 0 }, { 0, 11 }, { 0, -11 }, { 9, 9 }, { -9, 9 }, { 9, -9 }, { -9, -9 } };
        for (int[] d : dirs) {
            int x = pos.getX() + d[0], z = pos.getZ() + d[1];
            BlockPos top = MiscUtil.getHighestNonAirBlock(level, new BlockPos(x, 0, z));
            if (Math.abs(top.getY() - pos.getY()) > 3) continue;
            if (!level.getFluidState(top).isEmpty() || !level.getFluidState(top.above()).isEmpty()) continue;
            return new BlockPos(x, pos.getY(), z);
        }
        return pos;
    }

    private static void spawn(ServerLevel level, EntityType<?> type, String owner, BlockPos at) {
        if (type == null) return;
        Entity e = type.create(level);
        if (!(e instanceof Unit u)) return;
        BlockPos ground = MiscUtil.getHighestNonAirBlock(level, new BlockPos(at.getX(), 0, at.getZ())).above().above();
        u.setOwnerName(owner);
        e.moveTo(ground, 0, 0);
        level.addFreshEntity(e);
    }

    public static void ally(String a, String b) {
        AlliancesServerEvents.addAlliance(a, b);
    }

    public static void remove(String name) {
        BOTS.remove(name);
        if (server != null) save(server);
    }

    // ------------------------------------------------------------------ ticking

    /** a bot's unit died: log what killed it and where (finds map hazards and lost fights) */
    @SubscribeEvent
    public static void onDeath(net.minecraftforge.event.entity.living.LivingDeathEvent evt) {
        if (!(evt.getEntity() instanceof Unit u) || evt.getEntity().level().isClientSide) return;
        var src = evt.getSource();
        Entity killer = src.getEntity();
        Agent b = BOTS.get(u.getOwnerName());
        // a kill is credited to the bot that made it, also when the victim was a human's unit: otherwise
        // the attack attrition check sees every even trade against a human as a one-sided bleed
        Agent kb = killer instanceof Unit kh && kh.getOwnerName() != null && !kh.getOwnerName().isEmpty() ? BOTS.get(kh.getOwnerName()) : null;
        if (b == null) {
            if (kb != null && u.getOwnerName() != null && !u.getOwnerName().isEmpty()) kb.onKill();
            return;
        }
        b.onUnitDied(evt.getEntity(), src, killer);
        if (kb != null) kb.onKill();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent evt) {
        if (evt.phase != TickEvent.Phase.END) return;
        try { WaterRescue.tick(); } catch (Exception ex) { RonStrategist.LOG.error("RON Strategist: water rescue failed", ex); }
        if (BOTS.isEmpty()) return;
        serverTicks++;
        List<String> gone = new ArrayList<>();
        for (Agent b : BOTS.values()) {
            if (!isRtsName(b.name())) { gone.add(b.name()); continue; }
            try {
                b.tick(serverTicks);
            } catch (Exception ex) {
                RonStrategist.LOG.error("RON Strategist: bot {} crashed this tick", b.name(), ex);
            }
        }
        for (String g : gone) {
            RonStrategist.LOG.info("RON Strategist: {} is out of the game", g);
            BOTS.remove(g);
        }
        if (!gone.isEmpty() && server != null) save(server);
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent evt) {
        server = evt.getServer();
        BOTS.clear();
        ServerLevel level = server.overworld();
        Data data = level.getDataStorage().computeIfAbsent(Data::load, Data::new, "ronstrat_bots");
        for (Data.Entry e : data.entries) {
            if (!isRtsName(e.name)) continue; // the game was reset meanwhile
            try {
                BOTS.put(e.name, create(e.brain, e.profile, e.name, e.difficulty, Factions.VILLAGERS, e.pos, level));
            } catch (IllegalStateException ex) {
                RonStrategist.LOG.warn("RON Strategist: {} not resumed: {}", e.name, ex.getMessage());
            }
        }
        if (!BOTS.isEmpty()) RonStrategist.LOG.info("RON Strategist: resumed {} computer players", BOTS.size());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent evt) {
        save(evt.getServer());
        BOTS.clear();
        server = null;
    }

    static void save(MinecraftServer s) {
        if (s == null) return;
        Data data = s.overworld().getDataStorage().computeIfAbsent(Data::load, Data::new, "ronstrat_bots");
        data.entries.clear();
        for (Agent b : BOTS.values()) data.entries.add(new Data.Entry(b.name(), b.difficulty(), b.startPos(), b.brain(), b.profile()));
        data.setDirty();
    }

    static final class Data extends SavedData {
        record Entry(String name, Difficulty difficulty, BlockPos pos, String brain, String profile) {}
        final List<Entry> entries = new ArrayList<>();

        static Data load(CompoundTag tag) {
            Data d = new Data();
            ListTag l = tag.getList("bots", Tag.TAG_COMPOUND);
            for (int i = 0; i < l.size(); i++) {
                CompoundTag t = l.getCompound(i);
                try {
                    d.entries.add(new Entry(t.getString("name"), Difficulty.valueOf(t.getString("difficulty")),
                            new BlockPos(t.getInt("x"), t.getInt("y"), t.getInt("z")), t.getString("brain"), t.getString("profile")));
                } catch (IllegalArgumentException ignored) { }
            }
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag l = new ListTag();
            for (Entry e : entries) {
                CompoundTag t = new CompoundTag();
                t.putString("name", e.name);
                t.putString("difficulty", e.difficulty.name());
                t.putInt("x", e.pos.getX());
                t.putInt("y", e.pos.getY());
                t.putInt("z", e.pos.getZ());
                t.putString("brain", e.brain);
                t.putString("profile", e.profile);
                l.add(t);
            }
            tag.put("bots", l);
            return tag;
        }
    }
}
