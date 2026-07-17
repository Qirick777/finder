package com.evosim.mod.client;

import com.evosim.mod.gui.PedigreeSnapshot;
import com.evosim.mod.net.ModNetwork;
import com.evosim.mod.net.PedigreeRequestPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 가계도 화면 (플레인 Screen — 컨테이너 없음). 아래가 포커스, 위로 조상 3세대를 상자·연결선으로
 * 그린다. 기록 있는 조상 상자를 클릭하면 그 조상을 포커스로 서버에 재조회해 위로 계속 올라간다
 * (스냅샷 갱신은 {@link ClientPedigree#open}). 미상(id 0)은 회색 — 1세대의 부모거나 기록 이전 개체.
 */
public class PedigreeScreen extends Screen {

    private static final int NODE_H = 24;
    private static final int ROW_GAP = 22;
    private static final int TOP_MARGIN = 28;

    private PedigreeSnapshot snapshot;
    /** 클릭 판정용 — 마지막 렌더 프레임의 노드 상자(래스터와 동일 좌표로 매 프레임 재계산). */
    private final List<Box> boxes = new ArrayList<>();

    private record Box(int x0, int y0, int x1, int y1, PedigreeSnapshot.Node node) {
        boolean contains(double mx, double my) {
            return mx >= x0 && mx < x1 && my >= y0 && my < y1;
        }
    }

    public PedigreeScreen(PedigreeSnapshot snapshot) {
        super(Component.literal("가계도"));
        this.snapshot = snapshot;
    }

    /** 재조회 응답 도착 — 새 포커스의 그리드로 교체. */
    public void setSnapshot(PedigreeSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** d행(0=포커스 최하단)의 세로 위치 — 조상이 위로 쌓이도록 역순 배치. */
    private int rowY(int d) {
        int rows = snapshot.rows.length;
        return TOP_MARGIN + (rows - 1 - d) * (NODE_H + ROW_GAP);
    }

    /** d행 i칸 상자의 가로 중심 — 행을 2^d 등분한 각 칸의 중앙. */
    private int slotCenterX(int d, int i) {
        int slots = snapshot.rows[d].length;
        return this.width * (2 * i + 1) / (2 * slots);
    }

    private int nodeWidth(int d) {
        int slots = snapshot.rows[d].length;
        return Math.min(96, this.width / slots - 6);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFD700);
        g.drawCenteredString(this.font, "조상 상자 클릭 = 그 조상 기준으로 위로 이동",
                this.width / 2, this.height - 16, 0x808080);

        boxes.clear();
        // 연결선을 상자보다 먼저 — 선이 상자 아래로 깔린다.
        for (int d = 0; d + 1 < snapshot.rows.length; d++) {
            for (int i = 0; i < snapshot.rows[d].length; i++) {
                if (snapshot.rows[d][i].id == 0) {
                    continue; // 미상 칸의 위쪽 가지는 긋지 않는다(허공 선 방지)
                }
                drawElbow(g, d, i, 2 * i);
                drawElbow(g, d, i, 2 * i + 1);
            }
        }
        for (int d = 0; d < snapshot.rows.length; d++) {
            for (int i = 0; i < snapshot.rows[d].length; i++) {
                drawNode(g, d, i, mouseX, mouseY);
            }
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    /** 자식(d,i) 상단 중앙 → 부모(d+1,pi) 하단 중앙을 ㄱ자(세로-가로-세로)로 잇는다. */
    private void drawElbow(GuiGraphics g, int d, int i, int pi) {
        int cx = slotCenterX(d, i);
        int cy = rowY(d);                       // 자식 상자 윗변
        int px = slotCenterX(d + 1, pi);
        int py = rowY(d + 1) + NODE_H;          // 부모 상자 아랫변
        int midY = (cy + py) / 2;
        int color = snapshot.rows[d + 1][pi].id == 0 ? 0xFF3A3A3A : 0xFF6A6A9A;
        g.fill(cx, midY, cx + 1, cy, color);                              // 자식 쪽 세로
        g.fill(Math.min(cx, px), midY, Math.max(cx, px) + 1, midY + 1, color); // 가로
        g.fill(px, py, px + 1, midY, color);                              // 부모 쪽 세로
    }

    private void drawNode(GuiGraphics g, int d, int i, int mouseX, int mouseY) {
        PedigreeSnapshot.Node n = snapshot.rows[d][i];
        int w = nodeWidth(d);
        int x0 = slotCenterX(d, i) - w / 2;
        int y0 = rowY(d);
        int x1 = x0 + w;
        int y1 = y0 + NODE_H;

        if (n.id == 0) {
            g.fill(x0, y0, x1, y1, 0xFF1A1A1A);
            drawBorder(g, x0, y0, x1, y1, 0xFF3A3A3A);
            g.drawCenteredString(this.font, "?", (x0 + x1) / 2, y0 + (NODE_H - 8) / 2, 0x606060);
            return;
        }
        Box box = new Box(x0, y0, x1, y1, n);
        boxes.add(box);
        boolean hover = box.contains(mouseX, mouseY);
        boolean focus = d == 0;
        g.fill(x0, y0, x1, y1, hover ? 0xFF25254A : 0xFF14142A);
        drawBorder(g, x0, y0, x1, y1,
                focus ? 0xFFFFD700 : (n.alive ? (n.female ? 0xFFFF9EC4 : 0xFF8FD3FF) : 0xFF707070));
        String name = (n.female ? "♀ " : "♂ ") + n.name; // 성명 표시 — 번호(N#·#eid)는 툴팁 병기
        String sub = "G" + n.gen + (n.alive ? " 생존" : (n.diedDay >= 0 ? " †" + n.diedDay + "일" : " 사망"));
        g.drawCenteredString(this.font, name, (x0 + x1) / 2, y0 + 3,
                n.alive ? (n.female ? 0xFF9EC4 : 0x8FD3FF) : 0xA0A0A0);
        g.drawCenteredString(this.font, sub, (x0 + x1) / 2, y0 + 13, 0x909090);
        if (hover) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(n.name + " (N" + n.serial
                    + (n.alive ? " · #" + n.entityId : "") + ") · " + (n.female ? "암컷" : "수컷")
                    + " · " + n.gen + "세대"));
            tip.add(Component.literal("출생 " + n.bornDay + "일차"
                    + (n.alive ? " · 생존" : (n.diedDay >= 0 ? " · 사망 " + n.diedDay + "일차" : " · 사망"))));
            tip.add(Component.literal("자식 " + n.children + " · 총 후손 " + n.descendants));
            if (d > 0) {
                tip.add(Component.literal("클릭: 이 조상 기준 가계도").withStyle(s -> s.withColor(0x77DD77)));
            }
            g.renderComponentTooltip(this.font, tip, mouseX, mouseY);
        }
    }

    private static void drawBorder(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0, y0, x1, y0 + 1, color);
        g.fill(x0, y1 - 1, x1, y1, color);
        g.fill(x0, y0, x0 + 1, y1, color);
        g.fill(x1 - 1, y0, x1, y1, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (Box b : boxes) {
                // 포커스(0행) 상자는 재조회 대상이 아님 — 자기 자신
                if (b.contains(mouseX, mouseY) && b.node() != snapshot.rows[0][0]) {
                    ModNetwork.CHANNEL.sendToServer(new PedigreeRequestPacket(b.node().id));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
