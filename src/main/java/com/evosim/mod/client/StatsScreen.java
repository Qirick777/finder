package com.evosim.mod.client;

import com.evosim.mod.gui.StatsSnapshot;
import com.evosim.mod.net.ModNetwork;
import com.evosim.mod.net.PedigreeRequestPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 인구 통계 화면 (플레인 Screen). 위: 생존 개체의 발현 특성 분포 가로 막대그래프(최다→최소,
 * 비율 = 해당 특성 보유자/생존 수 — 한 개체가 여러 특성을 가지므로 합계는 100%가 아니다).
 * 아래: 최다 후손 랭킹(원장 전수 — 죽은 조상 포함, 클릭 시 그 개체의 가계도로 이동). 스크롤 지원.
 */
public class StatsScreen extends Screen {

    private static final int LINE_H = 12;
    private static final int BAR_LEFT = 96;   // 특성 이름 칸 너비
    private static final int COUNT_W = 64;    // 수치 칸 너비(막대 오른쪽)

    private static final String[] TABS = {"인구", "학력·학위", "시설", "군", "영지"};

    private final StatsSnapshot snapshot;
    private int scrollRow;
    /** 현재 탭(UI P4) — 0 인구(특성 분포·후손 랭킹), 1 학력·학위, 2 시설, 3 군, 4 영지. */
    private int tab;
    /** 탭 라벨 클릭 판정 — 마지막 렌더 프레임의 x 구간. */
    private final List<int[]> tabHits = new ArrayList<>();
    /** 랭킹 행 클릭 판정 — 마지막 렌더 프레임의 (y0,y1,개체 id). */
    private final List<TopHit> topHits = new ArrayList<>();

    private record TopHit(int y0, int y1, long id) { }

    public StatsScreen(StatsSnapshot snapshot) {
        this(snapshot, 0);
    }

    public StatsScreen(StatsSnapshot snapshot, int tab) {
        super(Component.literal("인구 통계"));
        this.snapshot = snapshot;
        this.tab = Math.max(0, Math.min(TABS.length - 1, tab));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int totalRows() {
        if (tab != 0) {
            return Math.max(1, wrappedTabLines(this.width - 24).size());
        }
        // 제목행(분포) + 특성 바 + 빈 행 + 제목행(랭킹) + 랭킹(없으면 안내 1행)
        return 1 + snapshot.bars.size() + 1 + 1
                + Math.max(1, snapshot.tops.size());
    }

    private List<String> tabLines() {
        return switch (tab) {
            case 1 -> snapshot.edu;
            case 2 -> snapshot.fac;
            case 3 -> snapshot.army;
            case 4 -> snapshot.realm;
            default -> List.of();
        };
    }

    /** 탭 줄을 화면 폭에 맞춰 나눈다 — 구분자(" · ") 뒤에서 끊고 이어지는 줄은 들여쓴다. */
    private List<String> wrappedTabLines(int maxW) {
        List<String> out = new ArrayList<>();
        for (String l : tabLines()) {
            String s = l;
            while (this.font.width(s) > maxW) {
                int cut = -1;
                for (int i = 0; i < s.length(); i++) {
                    if ((s.charAt(i) == '·' || s.charAt(i) == ',')
                            && this.font.width(s.substring(0, i + 1)) <= maxW - 8) {
                        cut = i + 1;
                    }
                }
                if (cut <= 0) {
                    cut = s.length();
                    while (cut > 1 && this.font.width(s.substring(0, cut)) > maxW) {
                        cut--;
                    }
                }
                out.add(s.substring(0, cut).trim());
                s = "    " + s.substring(cut).trim();
            }
            out.add(s);
        }
        return out;
    }

    private int visibleRows() {
        return (this.height - 40) / LINE_H;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        g.drawCenteredString(this.font, this.title.getString() + " · 생존 " + snapshot.living + "명",
                this.width / 2, 8, 0xFFD700);

        topHits.clear();
        int left = 12;
        int right = this.width - 12;
        int top = 36;
        int bottom = this.height - 16;
        // ── 탭 줄 ──
        tabHits.clear();
        int tx = left;
        for (int i = 0; i < TABS.length; i++) {
            boolean on = i == tab;
            String label = TABS[i];
            int w = this.font.width(label) + 10;
            if (on) {
                g.fill(tx, 20, tx + w, 32, 0xFF2C3A44);
            }
            g.drawString(this.font, label, tx + 5, 22, on ? 0xFFE9B0 : 0x8FA0AB, false);
            tabHits.add(new int[] {tx, tx + w, i});
            tx += w + 4;
        }
        if (tab != 0) {
            g.enableScissor(left, top, right, bottom);
            List<String> ls = wrappedTabLines(right - left);
            for (int i = 0; i < ls.size(); i++) {
                int y = rowScreenY(i, top);
                if (y > bottom || y + LINE_H < top) {
                    continue;
                }
                String l = ls.get(i);
                int col = l.startsWith("    ") ? 0xA0A0A0 : (i == 0 ? 0xFFFFFF : 0xE0E0E0);
                g.drawString(this.font, l, left, y, col, false);
            }
            g.disableScissor();
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }
        int maxCount = 1;
        for (StatsSnapshot.Bar b : snapshot.bars) {
            maxCount = Math.max(maxCount, b.count());
        }

        g.enableScissor(left, top, right, bottom);
        int row = 0;
        int y;
        // ── 특성 분포 ──
        y = rowScreenY(row++, top);
        g.drawString(this.font, "발현 특성 분포 (보유자 수 / 생존 " + snapshot.living + ")",
                left, y, 0xFFFFFF, false);
        for (StatsSnapshot.Bar b : snapshot.bars) {
            y = rowScreenY(row++, top);
            if (y > bottom || y + LINE_H < top) {
                continue;
            }
            g.drawString(this.font, b.name(), left, y, 0xB0B0B0, false);
            int barMax = right - left - BAR_LEFT - COUNT_W;
            int barW = b.count() * barMax / maxCount;
            int x0 = left + BAR_LEFT;
            g.fill(x0, y, x0 + barMax, y + 9, 0xFF14142A);
            if (barW > 0) {
                g.fill(x0, y, x0 + barW, y + 9, b.count() == maxCount ? 0xFF77DD77 : 0xFF8FD3FF);
            }
            int pct = snapshot.living == 0 ? 0 : b.count() * 100 / snapshot.living;
            g.drawString(this.font, b.count() + " (" + pct + "%)",
                    x0 + barMax + 6, y, b.count() == 0 ? 0x707070 : 0xE0E0E0, false);
        }
        row++; // 빈 행
        // ── 후손 랭킹 ──
        y = rowScreenY(row++, top);
        g.drawString(this.font, "최다 후손 랭킹 (죽은 조상 포함 · 클릭 = 가계도)", left, y, 0xFFFFFF, false);
        if (snapshot.tops.isEmpty()) {
            y = rowScreenY(row, top);
            g.drawString(this.font, "아직 후손을 남긴 개체가 없습니다.", left, y, 0x808080, false);
        } else {
            for (int i = 0; i < snapshot.tops.size(); i++) {
                StatsSnapshot.Top t = snapshot.tops.get(i);
                y = rowScreenY(row++, top);
                if (y > bottom || y + LINE_H < top) {
                    continue;
                }
                boolean hover = mouseY >= y && mouseY < y + LINE_H
                        && mouseX >= left && mouseX < right;
                String line = (i + 1) + "위  " + (t.female() ? "♀ " : "♂ ") + t.name()
                        + (t.alive() ? "" : " (사망)") + "  G" + t.gen()
                        + "  자식 " + t.children() + " · 후손 " + t.descendants();
                g.drawString(this.font, line, left, y,
                        hover ? 0xFFFFA0 : (t.alive() ? 0xE0E0E0 : 0xA0A0A0), false);
                topHits.add(new TopHit(y, y + LINE_H, t.id()));
            }
        }
        g.disableScissor();
        super.render(g, mouseX, mouseY, partialTick);
    }

    private int rowScreenY(int row, int top) {
        return top + (row - scrollRow) * LINE_H;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int max = Math.max(0, totalRows() - visibleRows());
        scrollRow = Math.max(0, Math.min(max, scrollRow - (int) Math.signum(delta)));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseY >= 20 && mouseY < 32) {
            for (int[] h : tabHits) {
                if (mouseX >= h[0] && mouseX < h[1]) {
                    tab = h[2];
                    scrollRow = 0;
                    return true;
                }
            }
        }
        if (button == 0 && tab == 0) {
            for (TopHit h : topHits) {
                if (mouseY >= h.y0() && mouseY < h.y1()) {
                    ModNetwork.CHANNEL.sendToServer(new PedigreeRequestPacket(h.id()));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
