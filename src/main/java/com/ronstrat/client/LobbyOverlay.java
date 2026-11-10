package com.ronstrat.client;

import com.ronstrat.Difficulty;
import com.ronstrat.LobbyAi;
import com.ronstrat.Net;
import com.ronstrat.RonStrategist;
import com.solegendary.reignofnether.matchstart.MatchStartScreen;
import com.solegendary.reignofnether.startpos.StartPos;
import com.solegendary.reignofnether.startpos.StartPosClientEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds one small "电脑/AI" chip to every free start position in RON's own match lobby. Clicking it
 * seats a computer player there and cycles Easy -> Normal -> Hard -> Expert -> off. The chip sits in
 * the slot row between the name and the faction icons, so the lobby layout does not change.
 * RON's screen is not modified: the row geometry is recomputed from the same constants it uses.
 */
@Mod.EventBusSubscriber(modid = RonStrategist.MODID, value = Dist.CLIENT)
public final class LobbyOverlay {
    private LobbyOverlay() {}

    private static final int CHIP_W = 26, CHIP_H = 16;

    private record Chip(StartPos sp, int x, int y) {}

    private static Field fx1, fy1, fx2, fy2, fScroll;
    private static boolean broken;

    private static int geti(Field f, Object o) throws IllegalAccessException { return f.getInt(o); }

    private static List<Chip> chips(MatchStartScreen s) {
        List<Chip> out = new ArrayList<>();
        if (broken) return out;
        try {
            if (fx1 == null) {
                Class<?> c = MatchStartScreen.class;
                fx1 = c.getDeclaredField("rosX1"); fy1 = c.getDeclaredField("rosY1");
                fx2 = c.getDeclaredField("rosX2"); fy2 = c.getDeclaredField("rosY2");
                fScroll = c.getDeclaredField("rosterScroll");
                for (Field f : new Field[] { fx1, fy1, fx2, fy2, fScroll }) f.setAccessible(true);
            }
            int x1 = geti(fx1, s), y1 = geti(fy1, s), x2 = geti(fx2, s), y2 = geti(fy2, s), scroll = geti(fScroll, s);
            int contentTop = y1 + 24, viewBottom = y2 - 4;
            Map<Integer, List<StartPos>> byColor = new LinkedHashMap<>();
            for (StartPos sp : StartPosClientEvents.startPoses)
                if (sp.enabled) byColor.computeIfAbsent(sp.colorId, k -> new ArrayList<>()).add(sp);
            int y = contentTop - scroll;
            int x = x1 + 8, width = x2 - x1 - 16;
            for (List<StartPos> team : byColor.values()) {
                y += 12;
                for (StartPos sp : team) {
                    int tileY = y + 1;
                    int readyX = x + width - 22 - 2;
                    int factionStartX = readyX - 20 - 70;
                    int cx = factionStartX - 6 - 4 - CHIP_W, cy = tileY + 3;
                    boolean free = sp.playerName.isBlank() && !sp.hidden;
                    if ((free || aiAt(sp) != null) && cy >= contentTop && cy + CHIP_H <= viewBottom)
                        out.add(new Chip(sp, cx, cy));
                    y += 26;
                }
                y += 4;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true; // a different RON version: stay out of the way rather than draw in the wrong place
            RonStrategist.LOG.warn("RON Strategist: lobby overlay disabled ({})", e.toString());
        }
        return out;
    }

    /** the difficulty of the computer player on this seat, or null (works for seats RON shows as "?") */
    private static Difficulty aiAt(StartPos sp) {
        Difficulty d = LobbyAi.clientSeats.get(sp.pos);
        if (d != null) return d;
        return LobbyAi.isAiName(sp.playerName) ? LobbyAi.difficultyOf(sp.playerName) : null;
    }

    private static final int SET_W = 52, SET_H = 14;

    /** top-left corner of the "settings" button: the right end of the roster's header line */
    private static int[] settingsAt(MatchStartScreen s) {
        if (broken) return null;
        chips(s); // resolves the fields
        if (broken || fx1 == null) return null;
        try {
            int y1 = geti(fy1, s), x2 = geti(fx2, s);
            return new int[] { x2 - 8 - SET_W, y1 + 5 };
        } catch (IllegalAccessException e) { return null; }
    }

    @SubscribeEvent
    public static void render(ScreenEvent.Render.Post evt) {
        if (!(evt.getScreen() instanceof MatchStartScreen s)) return;
        GuiGraphics g = evt.getGuiGraphics();
        Font font = Minecraft.getInstance().font;
        int mx = evt.getMouseX(), my = evt.getMouseY();
        int[] st = settingsAt(s);
        if (st != null) {
            boolean hover = mx >= st[0] && mx <= st[0] + SET_W && my >= st[1] && my <= st[1] + SET_H;
            g.fill(st[0], st[1], st[0] + SET_W, st[1] + SET_H, 0xFF000000);
            g.fill(st[0] + 1, st[1] + 1, st[0] + SET_W - 1, st[1] + SET_H - 1, hover ? 0xE0404060 : 0xC0202030);
            String label = "结盟/让分";
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            g.drawString(font, label, st[0] + (SET_W - font.width(label)) / 2, st[1] + 3, 0xFFE0E0FF, false);
            g.pose().popPose();
            if (hover) g.renderTooltip(font, Component.literal("打开对局设置：调整各位置的结盟（队伍）和让分"), mx, my);
        }
        for (Chip c : chips(s)) {
            Difficulty d = aiAt(c.sp());
            boolean ai = d != null;
            boolean hover = mx >= c.x() && mx <= c.x() + CHIP_W && my >= c.y() && my <= c.y() + CHIP_H;
            int bg = ai ? 0xE0305020 : 0xC0101010;
            if (hover) bg = ai ? 0xE0407030 : 0xE0303030;
            g.fill(c.x(), c.y(), c.x() + CHIP_W, c.y() + CHIP_H, 0xFF000000);
            g.fill(c.x() + 1, c.y() + 1, c.x() + CHIP_W - 1, c.y() + CHIP_H - 1, bg);
            String label = d != null ? d.zh : Component.translatable("ronstrat.lobby_button").getString();
            int w = font.width(label);
            float scale = w > CHIP_W - 4 ? (CHIP_W - 4) / (float) w : 1f;
            g.pose().pushPose();
            g.pose().translate(c.x() + CHIP_W / 2f, c.y() + CHIP_H / 2f, 300);
            g.pose().scale(scale, scale, 1);
            g.drawString(font, label, -w / 2, -4, ai ? 0xFFB8FF8A : 0xFFE0E0E0, false);
            g.pose().popPose();
            if (hover) g.renderTooltip(font, Component.translatable("ronstrat.lobby_tip"), mx, my);
        }
    }

    @SubscribeEvent
    public static void click(ScreenEvent.MouseButtonPressed.Pre evt) {
        if (evt.getButton() != 0 || !(evt.getScreen() instanceof MatchStartScreen s)) return;
        double mx = evt.getMouseX(), my = evt.getMouseY();
        int[] st = settingsAt(s);
        if (st != null && mx >= st[0] && mx <= st[0] + SET_W && my >= st[1] && my <= st[1] + SET_H) {
            Minecraft.getInstance().setScreen(new LobbySettingsScreen(s));
            evt.setCanceled(true);
            return;
        }
        for (Chip c : chips(s)) {
            if (mx < c.x() || mx > c.x() + CHIP_W || my < c.y() || my > c.y() + CHIP_H) continue;
            Difficulty d = aiAt(c.sp());
            int next = d == null ? 0 : d.ordinal() + 1;
            if (next >= Difficulty.values().length) next = -1;
            Net.CH.sendToServer(new Net.LobbySeat(c.sp().pos, next));
            evt.setCanceled(true); // do not let RON treat the click as "take this seat"
            return;
        }
    }
}
