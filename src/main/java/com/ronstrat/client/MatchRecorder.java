package com.ronstrat.client;

import com.ronstrat.RonStrategist;
import com.solegendary.reignofnether.building.BuildingClientEvents;
import com.solegendary.reignofnether.building.BuildingPlacement;
import com.solegendary.reignofnether.player.PlayerClientEvents;
import com.solegendary.reignofnether.player.RTSPlayer;
import com.solegendary.reignofnether.unit.UnitClientEvents;
import com.solegendary.reignofnether.unit.interfaces.Unit;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Client only: records multiplayer games (a remote server, not the own single player world - those have the
 * server log) so the AI can learn from other players' build orders. It only writes what this client already
 * received from the server - buildings placed / finished / gone (RON sends every placement to every client),
 * the units in sight every 10 s, units seen dying, the own bank every 10 s, the server's game messages, the players and their factions - to
 * .minecraft/.../ronstrat_matches/<time>.jsonl. Nothing is shown in game, nothing is sent anywhere.
 * Time t is RON's game clock in seconds.
 */
@Mod.EventBusSubscriber(modid = RonStrategist.MODID, value = Dist.CLIENT)
public final class MatchRecorder {
    private MatchRecorder() {}

    private static BufferedWriter out;
    private static long lastGameTicks = -1, lastUnits = -1;
    private static final Map<BlockPos, String> seen = new HashMap<>();      // origin -> "owner|building"
    private static final Set<BlockPos> finished = new HashSet<>();
    private static String playersSig = "";
    private static int tickCounter;

    private static boolean multiplayer(Minecraft mc) {
        // (11-26: play.reignofnether.net moves players between instances - getCurrentServer() is not relied on)
        return mc.level != null && mc.player != null && !mc.hasSingleplayerServer();
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (!multiplayer(mc)) { close(); return; }
        if (++tickCounter % 10 != 0) return; // twice a second is plenty
        try {
            long gt = PlayerClientEvents.rtsGameTicks;
            boolean active = !PlayerClientEvents.rtsPlayers.isEmpty() && gt > 0;
            if (!active && out == null && tickCounter % 1200 == 0)
                RonStrategist.LOG.info("RON Strategist: match recorder waiting (rts players {}, clock {})", PlayerClientEvents.rtsPlayers.size(), gt);
            // a new game: the clock went back (or the first game seen) - start a new file
            if (active && (out == null || gt < lastGameTicks - 200)) open(mc);
            if (!active) { lastGameTicks = gt; return; }
            lastGameTicks = gt;
            int t = (int) (gt / 20);
            players(t);
            buildings(t);
            deaths(t);
            if (lastUnits < 0 || gt - lastUnits >= 200) { lastUnits = gt; units(t); bank(t); }
            out.flush();
        } catch (Exception ex) {
            RonStrategist.LOG.warn("RON Strategist: match recorder stopped ({})", ex.toString());
            close();
        }
    }

    private static void open(Minecraft mc) throws IOException {
        close();
        Path dir = mc.gameDirectory.toPath().resolve("ronstrat_matches");
        Files.createDirectories(dir);
        String name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".jsonl";
        out = Files.newBufferedWriter(dir.resolve(name), StandardCharsets.UTF_8);
        String server = mc.getCurrentServer() == null ? "" : mc.getCurrentServer().ip;
        write("{\"ev\":\"start\",\"server\":" + q(server) + ",\"me\":" + q(mc.player.getName().getString()) + "}");
        seen.clear(); finished.clear(); playersSig = ""; lastUnits = -1; dead.clear();
        RonStrategist.LOG.info("RON Strategist: recording this multiplayer game to {}", dir.resolve(name));
    }

    private static void close() {
        if (out == null) return;
        try { out.close(); } catch (IOException ignored) {}
        out = null;
        seen.clear(); finished.clear();
    }

    private static void players(int t) throws IOException {
        StringBuilder sb = new StringBuilder();
        synchronized (PlayerClientEvents.rtsPlayers) {
            for (RTSPlayer p : PlayerClientEvents.rtsPlayers)
                sb.append(p.name).append('/').append(p.faction == null ? "?" : p.faction.getName()).append('/').append(p.startPosColorId).append(';');
        }
        String sig = sb.toString();
        if (sig.equals(playersSig)) return;
        playersSig = sig;
        write("{\"t\":" + t + ",\"ev\":\"players\",\"list\":" + q(sig) + "}");
    }

    private static void buildings(int t) throws IOException {
        Set<BlockPos> now = new HashSet<>();
        for (BuildingPlacement b : BuildingClientEvents.getBuildings()) {
            if (b == null || b.getBuilding() == null) continue;
            BlockPos o = b.originPos;
            now.add(o);
            String owner = b.ownerName == null ? "" : b.ownerName;
            String what = com.ronstrat.Builder.name(b.getBuilding());
            if (!seen.containsKey(o)) {
                seen.put(o, owner + "|" + what);
                write("{\"t\":" + t + ",\"ev\":\"placed\",\"owner\":" + q(owner) + ",\"b\":" + q(what) + ",\"x\":" + o.getX() + ",\"z\":" + o.getZ()
                        + ",\"built\":" + b.isBuilt + "}");
                if (b.isBuilt) finished.add(o);
            } else if (b.isBuilt && finished.add(o)) {
                write("{\"t\":" + t + ",\"ev\":\"built\",\"owner\":" + q(owner) + ",\"b\":" + q(what) + ",\"x\":" + o.getX() + ",\"z\":" + o.getZ() + "}");
            }
        }
        for (BlockPos o : new HashSet<>(seen.keySet())) {
            if (now.contains(o)) continue;
            String[] ow = seen.remove(o).split("\\|", 2);
            finished.remove(o);
            write("{\"t\":" + t + ",\"ev\":\"gone\",\"owner\":" + q(ow[0]) + ",\"b\":" + q(ow.length > 1 ? ow[1] : "") + ",\"x\":" + o.getX() + ",\"z\":" + o.getZ() + "}");
        }
    }

    /** the units this client knows of (its own, allies, enemies in sight): count per owner and type */
    private static void units(int t) throws IOException {
        Map<String, Map<String, Integer>> by = new TreeMap<>();
        for (LivingEntity e : UnitClientEvents.getAllUnits()) {
            if (!(e instanceof Unit u)) continue;
            String owner = u.getOwnerName() == null ? "" : u.getOwnerName();
            String type = net.minecraft.world.entity.EntityType.getKey(e.getType()).getPath();
            by.computeIfAbsent(owner, k -> new TreeMap<>()).merge(type, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("{\"t\":" + t + ",\"ev\":\"units\",\"by\":{");
        boolean first = true;
        for (var e : by.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(q(e.getKey())).append(":{");
            boolean f2 = true;
            for (var u : e.getValue().entrySet()) { if (!f2) sb.append(','); f2 = false; sb.append(q(u.getKey())).append(':').append(u.getValue()); }
            sb.append('}');
        }
        write(sb.append("}}").toString());
    }

    private static final Set<Integer> dead = new HashSet<>();

    /** units of any owner in sight that are dying (health 0 shows for a second before the server removes them) */
    private static void deaths(int t) throws IOException {
        for (LivingEntity e : UnitClientEvents.getAllUnits()) {
            if (!(e instanceof Unit u) || !e.isDeadOrDying() || !dead.add(e.getId())) continue;
            String owner = u.getOwnerName() == null ? "" : u.getOwnerName();
            String type = net.minecraft.world.entity.EntityType.getKey(e.getType()).getPath();
            write("{\"t\":" + t + ",\"ev\":\"death\",\"owner\":" + q(owner) + ",\"u\":" + q(type) + ",\"x\":" + e.getBlockX() + ",\"z\":" + e.getBlockZ() + "}");
        }
        if (dead.size() > 20000) dead.clear();
    }

    /** the own bank (RON sends every player's resources to every client; only our own is written) */
    private static void bank(int t) throws IOException {
        com.solegendary.reignofnether.resources.Resources r = com.solegendary.reignofnether.resources.ResourcesClientEvents.getOwnResources();
        if (r == null) return;
        write("{\"t\":" + t + ",\"ev\":\"bank\",\"food\":" + r.food + ",\"wood\":" + r.wood + ",\"ore\":" + r.ore + "}");
    }

    /** the server's game messages (hero deaths, defeats, surrender, night and day) */
    @SubscribeEvent
    public static void chat(net.minecraftforge.client.event.ClientChatReceivedEvent e) {
        if (out == null || e.getMessage() == null) return;
        String m = e.getMessage().getString();
        if (m.isBlank() || m.length() > 200) return;
        try { write("{\"t\":" + (int) (PlayerClientEvents.rtsGameTicks / 20) + ",\"ev\":\"msg\",\"text\":" + q(m) + "}"); out.flush(); } catch (IOException ignored) {}
    }

    private static void write(String line) throws IOException {
        if (out == null) return;
        out.write(line);
        out.newLine();
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace('\n', ' ').replace('\r', ' ').replace('\t', ' ') + "\"";
    }
}
