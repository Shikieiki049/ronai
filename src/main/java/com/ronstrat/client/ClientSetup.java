package com.ronstrat.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.ronstrat.Difficulty;
import com.ronstrat.Net;
import com.ronstrat.RonStrategist;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** F7 opens a small panel to add computer players without typing commands. */
public final class ClientSetup {
    private ClientSetup() {}

    public static final KeyMapping OPEN = new KeyMapping("ronstrat.key.panel", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F7, "ronstrat.key.category");

    @Mod.EventBusSubscriber(modid = RonStrategist.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent e) { e.register(OPEN); }
    }

    @Mod.EventBusSubscriber(modid = RonStrategist.MODID, value = Dist.CLIENT)
    public static final class GameBus {
        @SubscribeEvent
        public static void tick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            while (OPEN.consumeClick()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen == null && mc.player != null) mc.setScreen(new Panel());
            }
        }
    }

    static final class Panel extends Screen {
        private Difficulty difficulty = Difficulty.EXPERT;
        private int count = 1;
        private boolean teamUp = true;

        Panel() { super(Component.literal("RON Strategist")); }

        @Override
        protected void init() {
            int x = width / 2 - 100, y = height / 2 - 70;
            addRenderableWidget(Button.builder(label("难度 Difficulty: ", difficulty.zh + " / " + difficulty.key()), b -> {
                difficulty = Difficulty.values()[(difficulty.ordinal() + 1) % Difficulty.values().length];
                rebuildWidgets();
            }).bounds(x, y, 200, 20).build());
            addRenderableWidget(Button.builder(label("数量 Count: ", String.valueOf(count)), b -> {
                count = count % 7 + 1;
                rebuildWidgets();
            }).bounds(x, y + 24, 200, 20).build());
            addRenderableWidget(Button.builder(label("电脑之间结盟 Allied: ", teamUp ? "是 yes" : "否 no"), b -> {
                teamUp = !teamUp;
                rebuildWidgets();
            }).bounds(x, y + 48, 200, 20).build());
            addRenderableWidget(Button.builder(Component.literal("添加为对手（自动选出生点） Add as opponents"), b -> send(false, false))
                    .bounds(x, y + 76, 200, 20).build());
            addRenderableWidget(Button.builder(Component.literal("添加为盟友 Add as allies"), b -> send(true, false))
                    .bounds(x, y + 100, 200, 20).build());
            addRenderableWidget(Button.builder(Component.literal("在我当前位置添加 Add here"), b -> send(false, true))
                    .bounds(x, y + 124, 200, 20).build());
        }

        private static Component label(String a, String b) { return Component.literal(a + b); }

        private void send(boolean withMe, boolean atMe) {
            Net.CH.sendToServer(new Net.AddBots(difficulty, atMe ? 1 : count, teamUp, withMe, atMe));
            onClose();
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float pt) {
            renderBackground(g);
            g.drawCenteredString(font, "RON Strategist - 电脑玩家", width / 2, height / 2 - 92, 0xFFFFFF);
            super.render(g, mx, my, pt);
        }

        @Override
        public boolean isPauseScreen() { return false; }
    }
}
