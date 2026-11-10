package com.ronstrat.client;

import com.ronstrat.Difficulty;
import com.ronstrat.LobbyAi;
import com.ronstrat.LobbySettings;
import com.ronstrat.Net;
import com.solegendary.reignofnether.startpos.StartPos;
import com.solegendary.reignofnether.startpos.StartPosClientEvents;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Lobby settings, opened from the small button in RON's match lobby: for every start position its
 * team (alliances) and handicap. Only the host's clicks take effect; everyone sees the current values.
 */
public final class LobbySettingsScreen extends Screen {
    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private int top, left, rowH = 24;

    private record Row(StartPos sp, Button team, Button handicap, int y) {}

    public LobbySettingsScreen(Screen parent) {
        super(Component.literal("对局设置：结盟与让分"));
        this.parent = parent;
    }

    private static int[] valuesOf(BlockPos p) {
        int[] v = LobbySettings.clientSettings.get(p);
        return v == null ? new int[] { 0, 0 } : v;
    }

    private static Component teamLabel(int t) {
        return Component.literal(t == 0 ? "队伍：按颜色" : "队伍 " + t);
    }

    private static Component handicapLabel(int h) {
        if (h == 0) return Component.literal("让分：无");
        return Component.literal("让分：+" + 2 * h + "村民 +" + (h % 2 == 0 ? String.valueOf(h / 2) : h / 2 + ".5") + "倍资源");
    }

    @Override
    protected void init() {
        rows.clear();
        List<StartPos> seats = new ArrayList<>();
        for (StartPos sp : StartPosClientEvents.startPoses) if (sp.enabled) seats.add(sp);
        int w = 420;
        left = (width - w) / 2;
        top = Math.max(36, (height - seats.size() * rowH - 60) / 2);
        int y = top;
        for (StartPos sp : seats) {
            int[] v = valuesOf(sp.pos);
            final BlockPos pos = sp.pos;
            Button team = Button.builder(teamLabel(v[0]), b -> {
                int[] c = valuesOf(pos);
                Net.CH.sendToServer(new Net.LobbyConfig(pos, (c[0] + 1) % (LobbySettings.MAX_TEAM + 1), c[1]));
            }).bounds(left + 170, y, 110, 20).build();
            team.setTooltip(Tooltip.create(Component.literal("同一队伍的位置开局时结盟，不同队伍互为敌人。\n“按颜色”= RON 默认（同色结盟）。")));
            Button hc = Button.builder(handicapLabel(v[1]), b -> {
                int[] c = valuesOf(pos);
                Net.CH.sendToServer(new Net.LobbyConfig(pos, c[0], (c[1] + 1) % (LobbySettings.MAX_HANDICAP + 1)));
            }).bounds(left + 285, y, 135, 20).build();
            hc.setTooltip(Tooltip.create(Component.literal("开局时给这个位置的玩家额外村民和资源。\n每级：+2 村民、+0.5 倍初始资源（75 粮 / 250 木 / 150 矿）。")));
            addRenderableWidget(team);
            addRenderableWidget(hc);
            rows.add(new Row(sp, team, hc, y));
            y += rowH;
        }
        addRenderableWidget(Button.builder(Component.literal("完成"), b -> onClose())
                .bounds(width / 2 - 50, y + 12, 100, 20).build());
    }

    @Override
    public void tick() {
        // values change when the server answers (or another host clicks): keep the labels current
        for (Row r : rows) {
            int[] v = valuesOf(r.sp().pos);
            r.team().setMessage(teamLabel(v[0]));
            r.handicap().setMessage(handicapLabel(v[1]));
        }
    }

    private static String seatName(StartPos sp) {
        Difficulty d = LobbyAi.clientSeats.get(sp.pos);
        if (d != null) return "电脑（" + d.zh + "）";
        if (sp.hidden) return "?";
        if (sp.playerName == null || sp.playerName.isBlank()) return "（空位）";
        return sp.playerName;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, top - 24, 0xFFFFFF);
        for (Row r : rows) {
            int c = 0xFF000000 | (r.sp().getHexColor() & 0xFFFFFF);
            g.fill(left, r.y() + 2, left + 16, r.y() + 18, c);
            g.drawString(font, seatName(r.sp()), left + 22, r.y() + 6, 0xE0E0E0);
        }
        if (rows.isEmpty()) g.drawCenteredString(font, "这张地图没有可用的出生位置", width / 2, top + 10, 0xC0C0C0);
        super.render(g, mx, my, pt);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }
}
