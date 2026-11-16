package com.ronstrat.client;

import com.ronstrat.RonStrategist;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Main menu: "reset all maps". Every map of a map pack unpacked in the game folder (any folder with a level.dat
 * under <game dir>/ditu, e.g. ReignOfNetherOfficialMapPack_v2.2) is copied fresh over the world of the same
 * name in saves - played maps keep their old buildings, fog and start positions otherwise. Only worlds that
 * have a twin in the pack are touched; a confirm screen comes first. The copy runs off the render thread.
 */
@Mod.EventBusSubscriber(modid = RonStrategist.MODID, value = Dist.CLIENT)
public final class MapResetButton {
    private MapResetButton() {}

    private static volatile boolean running;

    @SubscribeEvent
    public static void init(ScreenEvent.Init.Post evt) {
        if (!(evt.getScreen() instanceof TitleScreen title)) return;
        evt.addListener(Button.builder(Component.translatable("ronstrat.reset_maps"), b -> confirm(title))
                .bounds(4, 4, 110, 20).build());
    }

    private static void confirm(TitleScreen back) {
        Minecraft mc = Minecraft.getInstance();
        List<Path[]> pairs = pairs(mc);
        if (pairs.isEmpty()) {
            SystemToast.add(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION, Component.translatable("ronstrat.reset_maps"),
                    Component.translatable("ronstrat.reset_maps.none"));
            return;
        }
        mc.setScreen(new ConfirmScreen(yes -> {
            mc.setScreen(back);
            if (yes) start(mc, pairs);
        }, Component.translatable("ronstrat.reset_maps"), Component.translatable("ronstrat.reset_maps.confirm", pairs.size())));
    }

    /** pack map folder -> saves folder of the same name */
    private static List<Path[]> pairs(Minecraft mc) {
        List<Path[]> out = new ArrayList<>();
        Path game = mc.gameDirectory.toPath(), ditu = game.resolve("ditu"), saves = game.resolve("saves");
        if (!Files.isDirectory(ditu)) return out;
        try (Stream<Path> packs = Files.list(ditu)) {
            for (Path pack : packs.filter(Files::isDirectory).toList()) {
                try (Stream<Path> maps = Files.list(pack)) {
                    for (Path map : maps.filter(Files::isDirectory).toList())
                        if (Files.exists(map.resolve("level.dat"))) out.add(new Path[] { map, saves.resolve(map.getFileName().toString()) });
                }
            }
        } catch (IOException e) {
            RonStrategist.LOG.warn("RON Strategist: map packs not read ({})", e.toString());
        }
        return out;
    }

    private static void start(Minecraft mc, List<Path[]> pairs) {
        if (running) return;
        running = true;
        Thread t = new Thread(() -> {
            int ok = 0;
            for (Path[] p : pairs) {
                try {
                    if (Files.exists(p[1])) deleteTree(p[1]);
                    copyTree(p[0], p[1]);
                    ok++;
                } catch (IOException e) {
                    RonStrategist.LOG.warn("RON Strategist: could not reset map {} ({})", p[1].getFileName(), e.toString());
                }
            }
            int done = ok;
            running = false;
            RonStrategist.LOG.info("RON Strategist: reset {} of {} maps from the map pack", done, pairs.size());
            mc.execute(() -> SystemToast.add(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                    Component.translatable("ronstrat.reset_maps"), Component.translatable("ronstrat.reset_maps.done", done, pairs.size())));
        }, "ronstrat-map-reset");
        t.setDaemon(true);
        t.start();
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
