package com.evosim.mod.client;

import com.evosim.mod.gui.EvoMatchMenu;
import com.evosim.mod.gui.MateBoardSnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * 짝매칭 현황판 화면 (상자처럼 열리는 컨테이너 GUI). 주변 미믹별로 까다로움·상태·후보 순위·구애 기록을
 * 목록으로 보여준다. 서버가 열려 있는 동안 새 스냅샷을 밀어 실시간 갱신한다.
 */
public class EvoMatchScreen extends AbstractContainerScreen<EvoMatchMenu> {

    private static final int LINE_H = 10;
    private static final int PANEL_W = 340;
    private static final int PANEL_H = 210;

    private final List<Line> lines = new ArrayList<>();
    private int scrollRow;

    private record Line(String text, int color) { }

    public EvoMatchScreen(EvoMatchMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = PANEL_W;
        this.imageHeight = PANEL_H;
    }

    @Override
    protected void init() {
        super.init();
        rebuild();
    }

    /** 서버 갱신 패킷 도착 시 호출(스냅샷 반영). */
    public void onSnapshotUpdated() {
        rebuild();
    }

    private void rebuild() {
        lines.clear();
        MateBoardSnapshot snap = getMenu().snapshot();
        if (snap == null || snap.entries.isEmpty()) {
            lines.add(new Line("주변에 성년 미믹이 없습니다.", 0xAAAAAA));
        } else {
            lines.add(new Line("주변 미믹 " + snap.entries.size() + "명 · 까다로움/상태/후보/구애기록", 0xFFD700));
            for (MateBoardSnapshot.Entry e : snap.entries) {
                lines.add(new Line((e.female ? "♀ " : "♂ ") + "#" + e.id + "  "
                        + e.choice + "(k" + e.k + ")  ·  " + e.state,
                        e.female ? 0xFF9EC4 : 0x8FD3FF));
                lines.add(new Line("  후보 " + e.candidates.size() + ": " + candText(e), 0xB0B0B0));
                for (MateBoardSnapshot.Rec r : e.records) {
                    lines.add(new Line("  " + recText(r), r.accepted ? 0x77DD77 : 0xE06666));
                }
            }
        }
        int visible = visibleRows();
        if (scrollRow > Math.max(0, lines.size() - visible)) {
            scrollRow = Math.max(0, lines.size() - visible);
        }
    }

    private static String candText(MateBoardSnapshot.Entry e) {
        if (e.candidates.isEmpty()) {
            return "없음";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < e.candidates.size(); i++) {
            MateBoardSnapshot.Cand c = e.candidates.get(i);
            if (i > 0) {
                sb.append("  ");
            }
            sb.append(i + 1).append('.').append('#').append(c.id).append('(').append(c.charm).append(')');
        }
        return sb.toString();
    }

    private static String recText(MateBoardSnapshot.Rec r) {
        if (r.kind == 1) { // RECEIVED
            return "‹ #" + r.otherId + " 구애 " + (r.accepted ? "수락" : "거절")
                    + "  " + r.charm + "점 " + r.rank + "등/" + r.pool + "  " + r.percent + "%";
        }
        return "› #" + r.otherId + " 에게 구애 " + (r.accepted ? "수락됨" : "거절됨");
    }

    private int visibleRows() {
        return (PANEL_H - 24) / LINE_H;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        g.fill(x, y, x + imageWidth, y + imageHeight, 0xF00A0A14);
        g.fill(x, y, x + imageWidth, y + 14, 0xFF20204A);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, this.title, 8, 4, 0xFFFFFF, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);

        int left = leftPos + 6;
        int top = topPos + 18;
        int bottom = topPos + imageHeight - 6;
        g.enableScissor(leftPos + 3, top, leftPos + imageWidth - 3, bottom);
        for (int i = scrollRow; i < lines.size(); i++) {
            int y = top + (i - scrollRow) * LINE_H;
            if (y >= bottom) {
                break;
            }
            Line ln = lines.get(i);
            g.drawString(this.font, ln.text(), left, y, ln.color(), false);
        }
        g.disableScissor();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int max = Math.max(0, lines.size() - visibleRows());
        scrollRow = Math.max(0, Math.min(max, scrollRow - (int) Math.signum(delta)));
        return true;
    }
}
