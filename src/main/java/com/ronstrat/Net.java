package com.ronstrat;

import com.solegendary.reignofnether.player.PlayerServerEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** The panel's one request: "add these computer players". */
public final class Net {
    private Net() {}

    private static final String V = "2";
    public static final SimpleChannel CH = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RonStrategist.MODID, "main"), () -> V, V::equals, V::equals);

    public static void register() {
        CH.registerMessage(0, AddBots.class, AddBots::write, AddBots::read, AddBots::handle);
        CH.registerMessage(1, LobbySeat.class, LobbySeat::write, LobbySeat::read, LobbySeat::handle);
        CH.registerMessage(2, LobbySeats.class, LobbySeats::write, LobbySeats::read, LobbySeats::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        CH.registerMessage(3, LobbyConfig.class, LobbyConfig::write, LobbyConfig::read, LobbyConfig::handle);
        CH.registerMessage(4, LobbySettingsSync.class, LobbySettingsSync::write, LobbySettingsSync::read, LobbySettingsSync::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
    }

    /** lobby, client -> server: the host sets a seat's team (0 = by colour) and handicap level */
    public record LobbyConfig(BlockPos pos, int team, int handicap) {
        void write(FriendlyByteBuf b) { b.writeBlockPos(pos); b.writeVarInt(team); b.writeVarInt(handicap); }

        static LobbyConfig read(FriendlyByteBuf b) { return new LobbyConfig(b.readBlockPos(), b.readVarInt(), b.readVarInt()); }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer sp = ctx.get().getSender();
            ctx.get().enqueueWork(() -> {
                if (sp == null) return;
                if (!sp.hasPermissions(2) && !sp.server.isSingleplayerOwner(sp.getGameProfile())) {
                    sp.sendSystemMessage(Component.literal("[RON Strategist] only the host can change lobby settings"));
                    return;
                }
                LobbySettings.set(pos, team, handicap);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** lobby, server -> clients: every seat's team and handicap */
    public record LobbySettingsSync(List<BlockPos> pos, List<Integer> team, List<Integer> handicap) {
        void write(FriendlyByteBuf b) {
            b.writeVarInt(pos.size());
            for (int i = 0; i < pos.size(); i++) { b.writeBlockPos(pos.get(i)); b.writeVarInt(team.get(i)); b.writeVarInt(handicap.get(i)); }
        }

        static LobbySettingsSync read(FriendlyByteBuf b) {
            int n = b.readVarInt();
            List<BlockPos> p = new ArrayList<>();
            List<Integer> t = new ArrayList<>(), h = new ArrayList<>();
            for (int i = 0; i < n; i++) { p.add(b.readBlockPos()); t.add(b.readVarInt()); h.add(b.readVarInt()); }
            return new LobbySettingsSync(p, t, h);
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                java.util.Map<BlockPos, int[]> m = new java.util.HashMap<>();
                for (int i = 0; i < pos.size(); i++) m.put(pos.get(i), new int[] { team.get(i), handicap.get(i) });
                LobbySettings.clientSettings = m;
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /**
     * lobby, server -> clients: which start positions hold a computer player and of what difficulty.
     * RON's "hide enemy picks" rule sends other teams' seats as "?" with no name, so the client cannot
     * tell an AI seat from the name alone.
     */
    public record LobbySeats(List<BlockPos> pos, List<Integer> difficulty) {
        void write(FriendlyByteBuf b) {
            b.writeVarInt(pos.size());
            for (int i = 0; i < pos.size(); i++) { b.writeBlockPos(pos.get(i)); b.writeVarInt(difficulty.get(i)); }
        }

        static LobbySeats read(FriendlyByteBuf b) {
            int n = b.readVarInt();
            List<BlockPos> p = new ArrayList<>();
            List<Integer> d = new ArrayList<>();
            for (int i = 0; i < n; i++) { p.add(b.readBlockPos()); d.add(b.readVarInt()); }
            return new LobbySeats(p, d);
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                java.util.Map<BlockPos, Difficulty> m = new java.util.HashMap<>();
                for (int i = 0; i < pos.size(); i++) {
                    int d = difficulty.get(i);
                    if (d >= 0 && d < Difficulty.values().length) m.put(pos.get(i), Difficulty.values()[d]);
                }
                LobbyAi.clientSeats = m;
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** lobby: put a computer player on a RON start position (difficulty -1 = clear the seat) */
    public record LobbySeat(BlockPos pos, int difficulty) {
        void write(FriendlyByteBuf b) { b.writeBlockPos(pos); b.writeVarInt(difficulty + 1); }

        static LobbySeat read(FriendlyByteBuf b) { return new LobbySeat(b.readBlockPos(), b.readVarInt() - 1); }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer sp = ctx.get().getSender();
            ctx.get().enqueueWork(() -> {
                if (sp == null) return;
                if (!sp.hasPermissions(2) && !sp.server.isSingleplayerOwner(sp.getGameProfile())) {
                    sp.sendSystemMessage(Component.literal("[RON Strategist] only the host can seat computer players"));
                    return;
                }
                Difficulty d = difficulty < 0 || difficulty >= Difficulty.values().length ? null : Difficulty.values()[difficulty];
                LobbyAi.setSeat(pos, d);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** add N bots of one difficulty; teamUp = they ally with each other; withMe = they ally with the sender */
    public record AddBots(Difficulty difficulty, int count, boolean teamUp, boolean withMe, boolean atMe) {
        void write(FriendlyByteBuf b) {
            b.writeEnum(difficulty); b.writeVarInt(count); b.writeBoolean(teamUp); b.writeBoolean(withMe); b.writeBoolean(atMe);
        }

        static AddBots read(FriendlyByteBuf b) {
            return new AddBots(b.readEnum(Difficulty.class), b.readVarInt(), b.readBoolean(), b.readBoolean(), b.readBoolean());
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer sp = ctx.get().getSender();
            ctx.get().enqueueWork(() -> {
                if (sp == null) return;
                boolean allowed = sp.hasPermissions(2) || sp.server.isSingleplayerOwner(sp.getGameProfile());
                if (!allowed) {
                    sp.sendSystemMessage(Component.literal("[RON Strategist] only operators can add computer players"));
                    return;
                }
                ServerLevel level = sp.serverLevel();
                String me = sp.getName().getString();
                BlockPos around = sp.blockPosition();
                List<BlockPos> taken = new ArrayList<>();
                List<String> made = new ArrayList<>();
                int n = Math.max(1, Math.min(7, count));
                for (int i = 0; i < n; i++) {
                    BlockPos p = atMe && n == 1 ? new BlockPos(around.getX(), level.getHeight(
                            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, around.getX(), around.getZ()), around.getZ())
                            : StartFinder.find(level, around, 70, 150, taken);
                    if (p == null) {
                        sp.sendSystemMessage(Component.literal("[RON Strategist] no free start position found"));
                        break;
                    }
                    taken.add(p);
                    String name = BotManager.freeName(difficulty);
                    BotManager.start(level, name, difficulty, p);
                    made.add(name);
                    if (withMe && PlayerServerEvents.getRTSPlayer(me) != null) BotManager.ally(name, me);
                }
                if (teamUp)
                    for (int i = 0; i < made.size(); i++)
                        for (int j = i + 1; j < made.size(); j++) BotManager.ally(made.get(i), made.get(j));
                // you against an allied team of N: the 1vN handicap goes to you
                if (teamUp && !withMe && made.size() >= 2 && PlayerServerEvents.getRTSPlayer(me) != null) {
                    BotManager.applyHandicap(level, me, around, made.size() - 1);
                    sp.sendSystemMessage(Component.literal("[RON Strategist] 1v" + made.size() + " handicap: +"
                            + 2 * (made.size() - 1) + " villagers, +" + 0.5 * (made.size() - 1) + "x starting resources"));
                }
                sp.sendSystemMessage(Component.literal("[RON Strategist] added " + String.join(", ", made)));
            });
            ctx.get().setPacketHandled(true);
        }
    }
}
