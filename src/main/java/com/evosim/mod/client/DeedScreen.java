package com.evosim.mod.client;

import com.evosim.mod.net.OpenDeedPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * 시설·가구 문서 화면(UI P4) — 땅 문서와 같은 양피지 톤. 위: 본문 줄, 아래: 이력 고리(스크롤,
 * 최신이 위). 관측 전용: 서버가 보낸 문장을 그대로 그린다.
 */
public final class DeedScreen extends Screen {

    private static final int PANEL_W = 320;
    private static final int ROW_H = 11;
    private static final int HIST_ROWS = 6;

    private OpenDeedPacket d;
    private int histScroll;
    private int px;
    private int py;
    private int panelH;
    private List<String> wrapped = new ArrayList<>();

    private DeedScreen(OpenDeedPacket d) {
        super(Component.literal(d.title));
        this.d = d;
    }

    public static void open(OpenDeedPacket d) {
        Minecraft mc = Minecraft.getInstance();
        if (d.title.isEmpty()) { // 빈 제목 = 열린 화면 닫기(evosim closescreen — 원격 촬영용)
            mc.setScreen(null);
            return;
        }
        if (mc.screen instanceof DeedScreen s && s.d.title.equals(d.title)) {
            s.d = d;
            s.init();
            return;
        }
        mc.setScreen(new DeedScreen(d));
    }

    @Override
    protected void init() {
        clearWidgets();
        wrapped = new ArrayList<>();
        for (String l : d.lines) {
            wrap(l, PANEL_W - 16, wrapped);
        }
        boolean hist = !d.history.isEmpty();
        panelH = 6 + 13 + 4 + wrapped.size() * ROW_H + 4
                + (hist ? 4 + ROW_H + Math.min(HIST_ROWS, d.history.size()) * ROW_H : 0) + 24;
        px = (width - PANEL_W) / 2;
        py = Math.max(4, (height - panelH) / 2);
        addRenderableWidget(Button.builder(Component.literal("닫기"), b -> onClose())
                .bounds(px + PANEL_W - 54, py + panelH - 17, 50, 14).build());
    }

    private void wrap(String s, int maxW, List<String> out) {
        if (font.width(s) <= maxW) {
            out.add(s);
            return;
        }
        // 구분자(" · ", ", ") 뒤에서 끊는다 — 낱말 중간에서 끊긴 숫자는 읽기 어렵다.
        int cut = -1;
        for (int i = 0; i < s.length(); i++) {
            if ((s.charAt(i) == '·' || s.charAt(i) == ',' || s.charAt(i) == ':')
                    && font.width(s.substring(0, i + 1)) <= maxW - 8) {
                cut = i + 1;
            }
        }
        if (cut <= 0) {
            int i = s.length();
            while (i > 1 && font.width(s.substring(0, i)) > maxW) {
                i--;
            }
            cut = i;
        }
        out.add(s.substring(0, cut).trim());
        wrap("  " + s.substring(cut).trim(), maxW, out);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        g.fillGradient(px, py, px + PANEL_W, py + panelH, 0xEE17140E, 0xEE0D0B07);
        border(g, 0xFF6B5A38);
        int x = px + 8;
        int y = py + 6;
        g.drawString(font, d.title, x, y, 0xFFF3E6C4, true);
        y += 13;
        g.fill(px + 6, y, px + PANEL_W - 6, y + 1, 0xFF6B5A38);
        y += 4;
        for (int i = 0; i < wrapped.size(); i++) {
            String l = wrapped.get(i);
            int col = l.startsWith("  ") ? 0xFFCBBE96 : (i == 0 ? 0xFFEFE3C0 : 0xFFDCCFA8);
            if (l.startsWith("♀") || l.startsWith("♂") || l.startsWith("  ")) {
                col = l.startsWith("  ") ? 0xFFCBBE96 : 0xFFA8C79F;
            }
            g.drawString(font, l, x, y, col, true);
            y += ROW_H;
        }
        y += 4;
        if (!d.history.isEmpty()) {
            g.fill(px + 6, y, px + PANEL_W - 6, y + 1, 0xFF6B5A38);
            y += 4;
            g.drawString(font, "이력 (" + d.history.size() + ")", x, y, 0xFF9A8E68, true);
            int n = d.history.size();
            int max = Math.max(0, n - HIST_ROWS);
            histScroll = Mth.clamp(histScroll, 0, max);
            if (n > HIST_ROWS) {
                String pg = (histScroll + 1) + "~" + Math.min(n, histScroll + HIST_ROWS) + "/" + n;
                g.drawString(font, pg, px + PANEL_W - 8 - font.width(pg), y, 0xFF6E6142, false);
            }
            y += ROW_H;
            for (int r = 0; r < HIST_ROWS; r++) {
                int i = n - 1 - (histScroll + r);
                if (i < 0) {
                    break;
                }
                g.drawString(font, trim(d.history.get(i), PANEL_W - 16), x, y, 0xFFE7CE86, false);
                y += ROW_H;
            }
        }
        super.render(g, mouseX, mouseY, partial);
    }

    private String trim(String s, int maxW) {
        if (font.width(s) <= maxW) {
            return s;
        }
        while (s.length() > 1 && font.width(s + "…") > maxW) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
    }

    private void border(GuiGraphics g, int c) {
        g.fill(px, py, px + PANEL_W, py + 1, c);
        g.fill(px, py + panelH - 1, px + PANEL_W, py + panelH, c);
        g.fill(px, py, px + 1, py + panelH, c);
        g.fill(px + PANEL_W - 1, py, px + PANEL_W, py + panelH, c);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int max = Math.max(0, d.history.size() - HIST_ROWS);
        histScroll = Mth.clamp(histScroll - (int) Math.signum(delta), 0, max);
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
