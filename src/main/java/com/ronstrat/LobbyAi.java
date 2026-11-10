package com.ronstrat;

import com.solegendary.reignofnether.faction.Factions;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import com.solegendary.reignofnether.startpos.StartPos;
import com.solegendary.reignofnether.startpos.StartPosServerEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Computer players in RON's own match lobby. A start position can be handed to a computer player;
 * the seat then shows the bot as a ready villager player, counts towards "everyone ready", and when
 * RON starts the match the bot starts on that position exactly like a human would.
 */
public final class LobbyAi {
    private LobbyAi() {}

    /** prefix every lobby bot name carries, so the client can recognise AI seats */
    public static final String PREFIX = "电脑";

    record Seat(Difficulty difficulty, BlockPos pos, int colorId) {}

    private static final Map<String, Seat> SEATS = new LinkedHashMap<>();
    private static boolean wasStarting;
    private static int nameCounter;

    /** client side: AI seats as last told by the server (start position -> difficulty) */
    public static volatile Map<BlockPos, Difficulty> clientSeats = Map.of();
    private static int lastSentHash = 0, broadcastTimer;
    /** computer seats ready up this many ticks after the last change to any of them */
    private static final int READY_DELAY = 100;
    private static long ticks, readyAt = -1;

    /** tell every client which seats are ours (RON may hide the names of other teams' seats) */
    static void broadcast() {
        java.util.List<BlockPos> p = new java.util.ArrayList<>();
        java.util.List<Integer> d = new java.util.ArrayList<>();
        for (Seat s : SEATS.values()) { p.add(s.pos()); d.add(s.difficulty().ordinal()); }
        lastSentHash = SEATS.hashCode();
        Net.CH.send(net.minecraftforge.network.PacketDistributor.ALL.noArg(), new Net.LobbySeats(p, d));
    }

    public static boolean isAiName(String n) { return n != null && n.startsWith(PREFIX); }

    public static Difficulty difficultyOf(String n) {
        for (Difficulty d : Difficulty.values()) if (n.startsWith(PREFIX + "·" + d.zh)) return d;
        return null;
    }

    /** put a computer player of the given difficulty on a start position, or clear it (d == null) */
    public static void setSeat(BlockPos pos, Difficulty d) {
        // during the countdown a change is still fine: un-readying the computer seats cancels it (RON's
        // own rule for a player who un-readies), and they ready up again a few seconds later
        if (PlayerServerEvents.isGameActive()) return;
        for (StartPos sp : StartPosServerEvents.startPoses) {
            if (!sp.pos.equals(pos) || !sp.enabled) continue;
            if (!sp.playerName.isBlank() && !isAiName(sp.playerName)) return; // a human sits here
            if (!sp.playerName.isBlank()) SEATS.remove(sp.playerName);
            sp.reset();
            if (d != null) {
                String name = PREFIX + "·" + d.zh + "·" + (++nameCounter);
                sp.playerName = name;
                sp.faction = Factions.VILLAGERS;
                // not ready yet: seating the last free seat would otherwise start the countdown at once,
                // before the host can pick this seat's difficulty. Ready up after a few quiet seconds.
                sp.ready = false;
                SEATS.put(name, new Seat(d, sp.pos, sp.colorId));
            }
            StartPosServerEvents.syncAll();
            broadcast();
            // re-evaluate "everyone ready" exactly the way a human readying up does
            readyAt = ticks + READY_DELAY;
            for (StartPos o : StartPosServerEvents.startPoses)
                if (SEATS.containsKey(o.playerName) && o.ready) StartPosServerEvents.setPlayerReady(o.playerName, false);
            if (d == null) for (StartPos o : StartPosServerEvents.startPoses)
                if (!o.playerName.isBlank()) { StartPosServerEvents.setPlayerReady(o.playerName, o.ready); break; }
            return;
        }
    }

    /** RON's countdown finished and it started the human players: start our bots on their seats */
    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent evt) {
        if (evt.phase != TickEvent.Phase.END) return;
        boolean starting = StartPosServerEvents.isStartingGame();
        ticks++;
        if (readyAt >= 0 && ticks >= readyAt && !starting && !PlayerServerEvents.isGameActive()) {
            readyAt = -1;
            for (StartPos o : StartPosServerEvents.startPoses)
                if (SEATS.containsKey(o.playerName) && !o.ready) StartPosServerEvents.setPlayerReady(o.playerName, true);
        }
        if (wasStarting && !starting && !SEATS.isEmpty()) {
            MinecraftServer server = evt.getServer();
            ServerLevel level = server.overworld();
            // RON resets every seat when it really starts; a cancelled countdown leaves our names in place
            boolean started = StartPosServerEvents.startPoses.stream().noneMatch(sp -> SEATS.containsKey(sp.playerName));
            if (!started) { wasStarting = false; return; }
            {
                for (Map.Entry<String, Seat> e : SEATS.entrySet()) {
                    Seat s = e.getValue();
                    try {
                        // the start block itself, as RON does for a human (startRTS gets startPos.pos): .above() put the
                        // AI's town centre one block above the ground, on stilts
                        BotManager.startReadied(level, e.getKey(), s.difficulty(), s.pos(), s.colorId());
                    } catch (Exception ex) {
                        RonStrategist.LOG.error("RON Strategist: could not start lobby bot {}", e.getKey(), ex);
                    }
                }
                PlayerServerEvents.sendMessageToAllPlayers("ronstrat.lobby_started", false, SEATS.size());
            }
            SEATS.clear();
        }
        // a seat that RON cleared (e.g. the position was disabled) is no longer ours
        if (!starting && !SEATS.isEmpty()) {
            SEATS.keySet().removeIf(n -> StartPosServerEvents.startPoses.stream().noneMatch(sp -> n.equals(sp.playerName)));
        }
        // keep clients in step (joiners, seats RON cleared, a started match) - cheap, a few bytes
        if (SEATS.hashCode() != lastSentHash || !SEATS.isEmpty() && ++broadcastTimer % 100 == 0) broadcast();
        wasStarting = starting;
    }
}
