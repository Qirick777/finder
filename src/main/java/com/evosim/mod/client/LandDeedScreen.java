package com.evosim.mod.client;

import com.evosim.mod.net.OpenLandDeedPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 땅 문서 화면 — 밭 한 구획의 원장(창설·소유·소작·확장 이력·수확 분배)을 그린다. 관측 전용:
 * 서버가 보낸 {@link OpenLandDeedPacket} 사본만 표시하고 어떤 조작도 서버로 보내지 않는다.
 *
 * <p>부익부 시각화 — 규모 막대를 착공(founder)·자영(owner)·소작(tenant) 세 색으로 분할해
 * "소작이 밭을 얼마나 키웠는가"를 한눈에 보인다.
 */
public final class LandDeedScreen extends Screen {

    private static final int PANEL_W = 300;
    private static final int PANEL_H = 236; // 224→236: 마름 줄 추가분
    private static final int HIST_ROWS = 6;
    private static final int ROW_H = 11;

    private OpenLandDeedPacket d;
    private int histScroll;
    private int px;
    private int py;

    private LandDeedScreen(OpenLandDeedPacket d) {
        super(Component.literal("땅 문서"));
        this.d = d;
    }

    public static void open(OpenLandDeedPacket d) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof LandDeedScreen s && s.d.plotId == d.plotId) {
            s.d = d;
            return;
        }
        mc.setScreen(new LandDeedScreen(d));
    }

    @Override
    protected void init() {
        px = (width - PANEL_W) / 2;
        py = (height - PANEL_H) / 2;
        addRenderableWidget(Button.builder(Component.literal("닫기"), b -> onClose())
                .bounds(px + PANEL_W - 54, py + PANEL_H - 17, 50, 14).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        g.fillGradient(px, py, px + PANEL_W, py + PANEL_H, 0xEE17140E, 0xEE0D0B07);
        border(g, 0xFF6B5A38);
        int x = px + 8;
        int y = py + 6;
        g.drawString(font, String.format("땅 문서 · 구획 %d  @%d, %d", d.plotId, d.anchorX, d.anchorZ),
                x, y, 0xFFF3E6C4, true);
        y += 13;
        g.fill(px + 6, y, px + PANEL_W - 6, y + 1, 0xFF6B5A38);
        y += 4;

        line(g, x, y, "소유", d.ownerName, 0xFFEFE3C0);
        y += ROW_H;
        String fday = d.foundedDay < 0 ? "일자 미상" : "d" + d.foundedDay;
        line(g, x, y, "창설", d.founderName + " · " + fday, 0xFFEFE3C0);
        y += ROW_H;
        // 마름(위임 관리자) — 소작 명단과 별개의 직위라 따로 적는다. 종전 땅 문서에는 이 줄이
        // 아예 없어서, 누가 이 밭을 관리하는지(관리 효율 E 의 주체)를 확인할 방법이 없었다.
        String stw = d.stewardName == null || d.stewardName.isEmpty() ? "—" : d.stewardName;
        if (d.stewardDebt > 0.01) {
            stw = stw + String.format(" · 착공비 채무 %.0f", d.stewardDebt);
        }
        line(g, x, y, "마름", stw, "—".equals(stw) ? 0xFF8A7F63 : 0xFFEFE3C0);
        y += ROW_H + 2;

        // ── 규모 막대(부익부): 착공 | 자영 | 소작 ──
        g.drawString(font, String.format("규모 %d타일  (착공 %d · 자영 +%d · 소작 +%d)",
                d.tiles, d.tilesByFounder, d.tilesByOwner, d.tilesByTenant), x, y, 0xFFCBBE96, true);
        y += ROW_H;
        long sum = Math.max(1, d.tilesByFounder + d.tilesByOwner + d.tilesByTenant);
        int bw = PANEL_W - 16;
        int bx = x;
        int wf = (int) (bw * d.tilesByFounder / (double) sum);
        int wo = (int) (bw * d.tilesByOwner / (double) sum);
        int wt = bw - wf - wo;
        g.fill(bx, y, bx + bw, y + 7, 0xFF22201A);
        g.fill(bx, y, bx + wf, y + 7, 0xFF8C7B4A);            // 착공(창설자)
        g.fill(bx + wf, y, bx + wf + wo, y + 7, 0xFFD8B24A);  // 자영(주인)
        g.fill(bx + wf + wo, y, bx + bw, y + 7, 0xFF6FB36A);  // 소작(부익부)
        y += 11;
        int tenPct = (int) Math.round(100.0 * d.tilesByTenant / sum);
        g.drawString(font, "▮착공 §e▮자영 §a▮소작  §7— 소작 기여 " + tenPct + "%",
                x, y, 0xFF9A8E68, false);
        y += ROW_H + 2;

        // ── 수확 분배 ──
        g.drawString(font, String.format("누적 수확 %.1f  (지대 %.1f · 소작몫 %.1f) · %d회",
                d.totalYield, d.totalToOwner, d.totalToTenant, d.harvestCount),
                x, y, 0xFFCBBE96, true);
        y += ROW_H;
        g.drawString(font, String.format("밭 계정(미정산) %.2f", d.account), x, y, 0xFF9A8E68, true);
        y += ROW_H + 2;

        // ── 상시 소작 ──
        StringBuilder tb = new StringBuilder("상시 소작 (" + d.tenants.size() + "): ");
        if (d.tenants.isEmpty()) {
            tb.append("—");
        } else {
            tb.append(String.join(", ", d.tenants));
        }
        String tstr = trim(tb.toString(), PANEL_W - 16);
        g.drawString(font, tstr, x, y, 0xFFA8C79F, true);
        y += ROW_H + 2;
        g.fill(px + 6, y, px + PANEL_W - 6, y + 1, 0xFF6B5A38);
        y += 4;

        // ── 확장 이력(스크롤) ──
        g.drawString(font, "확장 이력 (" + d.history.size() + ")", x, y, 0xFF9A8E68, true);
        y += ROW_H;
        int n = d.history.size();
        int max = Math.max(0, n - HIST_ROWS);
        histScroll = Mth.clamp(histScroll, 0, max);
        for (int r = 0; r < HIST_ROWS; r++) {
            int i = n - 1 - (histScroll + r); // 최신부터 위로
            if (i < 0) {
                break;
            }
            OpenLandDeedPacket.Expand e = d.history.get(i);
            int ry = y + r * ROW_H;
            int col = e.byTenant() ? 0xFF9FD69A : 0xFFE7CE86;
            String tag = e.byTenant() ? "재투자" : "자영";
            g.drawString(font, String.format("d%d  %s  +%d  [%s]", e.day(), e.by(), e.tiles(), tag),
                    x, ry, col, false);
        }
        if (n > HIST_ROWS) {
            g.drawString(font, (histScroll + 1) + "~" + Math.min(n, histScroll + HIST_ROWS) + "/" + n,
                    px + PANEL_W - 60, y - ROW_H, 0xFF6E6142, false);
        }
        super.render(g, mouseX, mouseY, partial);
    }

    private void line(GuiGraphics g, int x, int y, String label, String value, int col) {
        g.drawString(font, label, x, y, 0xFF9A8E68, true);
        g.drawString(font, value, x + 34, y, col, true);
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
        g.fill(px, py + PANEL_H - 1, px + PANEL_W, py + PANEL_H, c);
        g.fill(px, py, px + 1, py + PANEL_H, c);
        g.fill(px + PANEL_W - 1, py, px + PANEL_W, py + PANEL_H, c);
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
