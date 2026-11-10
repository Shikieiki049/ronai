package com.ronstrat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.solegendary.reignofnether.player.PlayerServerEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * /ronstrat add &lt;difficulty&gt; [x z]   add a computer player (at your position, or at x z)
 * /ronstrat ally &lt;a&gt; &lt;b&gt;              make two players allies
 * /ronstrat list                       show every computer player
 * /ronstrat arena &lt;spec&gt;             build a test arena here and start a bot match, e.g. "expert vs normal*5"
 * /ronstrat verbose                    toggle decision logging
 * /ronstrat reload                     reload params-*.json
 */
public final class StratCommands {
    private StratCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent evt) {
        CommandDispatcher<CommandSourceStack> d = evt.getDispatcher();
        d.register(Commands.literal("ronstrat").requires(s -> s.hasPermission(2))
                .then(Commands.literal("add")
                        .then(Commands.argument("difficulty", StringArgumentType.word())
                                .executes(c -> add(c, null))
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(c -> add(c, new int[] { IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z") }))))))
                .then(Commands.literal("ally")
                        .then(Commands.argument("a", StringArgumentType.string())
                                .then(Commands.argument("b", StringArgumentType.string())
                                        .executes(c -> {
                                            BotManager.ally(StringArgumentType.getString(c, "a"), StringArgumentType.getString(c, "b"));
                                            c.getSource().sendSuccess(() -> Component.literal("allied"), true);
                                            return 1;
                                        }))))
                .then(Commands.literal("handicap")
                        .then(Commands.argument("player", StringArgumentType.string())
                                .then(Commands.argument("extraEnemies", IntegerArgumentType.integer(1, 7))
                                        .executes(c -> {
                                            String who = StringArgumentType.getString(c, "player");
                                            int n = IntegerArgumentType.getInteger(c, "extraEnemies");
                                            if (PlayerServerEvents.getRTSPlayer(who) == null) {
                                                c.getSource().sendFailure(Component.literal(who + " is not playing"));
                                                return 0;
                                            }
                                            BotManager.applyHandicap(c.getSource().getLevel(), who,
                                                    BlockPos.containing(c.getSource().getPosition()), n);
                                            c.getSource().sendSuccess(() -> Component.literal("handicap: +" + 2 * n
                                                    + " villagers, +" + (0.5 * n) + "x starting resources for " + who), true);
                                            return 1;
                                        }))))
                .then(Commands.literal("list").executes(StratCommands::list))
                .then(Commands.literal("arena")
                        .then(Commands.argument("spec", StringArgumentType.greedyString()).executes(StratCommands::arena)))
                .then(Commands.literal("verbose").executes(c -> {
                    boolean v = BotManager.bots().stream().noneMatch(b -> b.verbose);
                    for (Bot b : BotManager.bots()) b.verbose = v;
                    boolean fv = v;
                    c.getSource().sendSuccess(() -> Component.literal("verbose " + fv), false);
                    return 1;
                }))
                .then(Commands.literal("reload").executes(c -> {
                    BotParams.reload();
                    c.getSource().sendSuccess(() -> Component.literal("params reloaded (new bots use them)"), false);
                    return 1;
                })));
    }

    private static int add(CommandContext<CommandSourceStack> c, int[] xz) {
        Difficulty d;
        try { d = Difficulty.parse(StringArgumentType.getString(c, "difficulty")); }
        catch (IllegalArgumentException e) {
            c.getSource().sendFailure(Component.literal("difficulty: easy / normal / hard / expert"));
            return 0;
        }
        ServerLevel level = c.getSource().getLevel();
        int x, z;
        if (xz != null) { x = xz[0]; z = xz[1]; }
        else {
            x = (int) Math.floor(c.getSource().getPosition().x);
            z = (int) Math.floor(c.getSource().getPosition().z);
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        String name = BotManager.freeName(d);
        BotManager.start(level, name, d, new BlockPos(x, y, z));
        c.getSource().sendSuccess(() -> Component.literal("added " + name + " at " + x + " " + z), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        if (BotManager.bots().isEmpty()) {
            c.getSource().sendSuccess(() -> Component.literal("no computer players"), false);
            return 0;
        }
        for (Bot b : BotManager.bots()) c.getSource().sendSuccess(() -> Component.literal(b.status()), false);
        c.getSource().sendSuccess(() -> Component.literal("game time " + PlayerServerEvents.rtsGameTicks / 20 + "s"), false);
        return 1;
    }

    private static int arena(CommandContext<CommandSourceStack> c) {
        MatchSpec spec;
        try { spec = MatchSpec.parse(StringArgumentType.getString(c, "spec")); }
        catch (Exception e) {
            c.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        ServerLevel level = c.getSource().getLevel();
        BlockPos at = BlockPos.containing(c.getSource().getPosition());
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
        Match m = new Match(spec, level.getRandom().nextLong());
        m.start(level, new BlockPos(at.getX(), y, at.getZ()));
        c.getSource().sendSuccess(() -> Component.literal("arena match started: " + spec.text), true);
        return 1;
    }
}
