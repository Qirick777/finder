package com.evosim.mod.client;

import com.evosim.core.Category;
import com.evosim.core.Trait;
import com.evosim.core.TraitInstance;
import com.evosim.mod.entity.TraitEditor;
import com.evosim.mod.net.EditTraitPacket;
import com.evosim.mod.net.ModNetwork;
import com.evosim.mod.net.OpenTraitEditorPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * 특성 편집 화면 (편집봉). 서버가 보낸 {@link OpenTraitEditorPacket} 사본만 그리고, 모든 조작은
 * {@link EditTraitPacket}으로 서버에 보낸 뒤 회신 패킷으로 갱신된다 — 화면에 로컬 상태 없음.
 *
 * <p>좌측 = 보유 인스턴스(등급 ±·우성 토글·삭제), 우측 = 추가 팔레트(카테고리 탭 + 목록 +
 * 등급/우성 선택). 스크롤은 마우스 휠(커서가 올라간 쪽 목록).
 */
public final class TraitEditorScreen extends Screen {

    private static final int PANEL_W = 360;
    private static final int PANEL_H = 226;
    private static final int ROW_H = 13;
    private static final int OWNED_ROWS = 11;
    private static final int PALETTE_ROWS = 10;

    private int entityId;
    private String headline;
    private List<OpenTraitEditorPacket.Entry> entries;
    private String status;

    private int ownedScroll;
    private int paletteScroll;
    private Category tab = Category.PHYSICAL;
    private int selectedOrdinal = -1;
    private int addGrade = 3;
    private boolean addDominant;

    private int px;
    private int py;
    private Button gradeBtn;
    private Button domBtn;
    private Button addBtn;

    private TraitEditorScreen(OpenTraitEditorPacket p) {
        super(Component.literal("특성 편집"));
        accept(p);
    }

    /** 패킷 수신 진입점 — 같은 개체 화면이 열려 있으면 갱신, 아니면 새로 연다. */
    public static void openOrRefresh(OpenTraitEditorPacket p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof TraitEditorScreen s && s.entityId == p.entityId) {
            s.accept(p);
            return;
        }
        mc.setScreen(new TraitEditorScreen(p));
    }

    private void accept(OpenTraitEditorPacket p) {
        this.entityId = p.entityId;
        this.headline = p.headline;
        this.entries = p.entries;
        this.status = p.status;
        this.ownedScroll = Mth.clamp(ownedScroll, 0, Math.max(0, entries.size() - OWNED_ROWS));
    }

    @Override
    protected void init() {
        px = (width - PANEL_W) / 2;
        py = (height - PANEL_H) / 2;
        int tabsY = py + 16;
        int tabW = 52;
        Category[] tabs = {Category.PHYSICAL, Category.DISPOSITION, Category.PREFERENCE};
        String[] names = {"신체", "성향·능력", "선호"};
        for (int i = 0; i < tabs.length; i++) {
            final Category c = tabs[i];
            addRenderableWidget(Button.builder(Component.literal(names[i]), b -> {
                        tab = c;
                        paletteScroll = 0;
                        selectedOrdinal = -1;
                    })
                    .bounds(px + 192 + i * (tabW + 2), tabsY, tabW, 14).build());
        }
        int by = py + PANEL_H - 34;
        gradeBtn = addRenderableWidget(Button.builder(gradeLabel(), b -> {
            addGrade = addGrade >= 5 ? 1 : addGrade + 1;
            b.setMessage(gradeLabel());
        }).bounds(px + 192, by, 52, 14).build());
        domBtn = addRenderableWidget(Button.builder(domLabel(), b -> {
            addDominant = !addDominant;
            b.setMessage(domLabel());
        }).bounds(px + 248, by, 52, 14).build());
        addBtn = addRenderableWidget(Button.builder(Component.literal("추가"), b -> {
            if (selectedOrdinal >= 0) {
                ModNetwork.CHANNEL.sendToServer(new EditTraitPacket(entityId,
                        TraitEditor.OP_ADD, selectedOrdinal, 0, addGrade, addDominant));
            }
        }).bounds(px + 304, by, 50, 14).build());
        addRenderableWidget(Button.builder(Component.literal("닫기"), b -> onClose())
                .bounds(px + PANEL_W - 54, py + PANEL_H - 17, 50, 14).build());
    }

    private Component gradeLabel() {
        return Component.literal("등급 " + TraitInstance.roman(addGrade));
    }

    private Component domLabel() {
        return Component.literal("우성 " + (addDominant ? "☑" : "☐"));
    }

    private List<Trait> paletteTraits() {
        List<Trait> out = new ArrayList<>();
        for (Trait t : Trait.values()) {
            if (t.category() == tab) {
                out.add(t);
            }
        }
        return out;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        g.fillGradient(px, py, px + PANEL_W, py + PANEL_H, 0xEE141A20, 0xEE0C1014);
        g.fill(px, py, px + PANEL_W, py + 1, 0xFF3D5260);
        g.fill(px, py + PANEL_H - 1, px + PANEL_W, py + PANEL_H, 0xFF3D5260);
        g.fill(px, py, px + 1, py + PANEL_H, 0xFF3D5260);
        g.fill(px + PANEL_W - 1, py, px + PANEL_W, py + PANEL_H, 0xFF3D5260);
        g.drawString(font, headline, px + 6, py + 5, 0xFFEFF5F8, true);

        // ── 좌: 보유 목록 ──
        int ly = py + 18;
        g.drawString(font, "보유 (" + entries.size() + ")", px + 6, ly, 0xFF8FA0AB, true);
        ly += 12;
        List<OpenTraitEditorPacket.Entry> es = entries;
        for (int r = 0; r < OWNED_ROWS; r++) {
            int i = ownedScroll + r;
            if (i >= es.size()) {
                break;
            }
            OpenTraitEditorPacket.Entry en = es.get(i);
            Trait t = Trait.values()[en.traitOrdinal()];
            int y = ly + r * ROW_H;
            boolean dom = (en.tagBits() & OpenTraitEditorPacket.BIT_DOMINANT) != 0;
            StringBuilder s = new StringBuilder(t.koreanName());
            if (en.grade() > 0) {
                s.append(TraitInstance.roman(en.grade()));
            }
            if ((en.tagBits() & OpenTraitEditorPacket.BIT_MALE) != 0) {
                s.append("♂");
            }
            if ((en.tagBits() & OpenTraitEditorPacket.BIT_FEMALE) != 0) {
                s.append("♀");
            }
            if (en.anti()) {
                s.append("(반발)");
            }
            int color = en.anti() ? 0xFF7FD6E8 : dom ? 0xFFFFE9B0 : 0xFFE8F0F4;
            g.drawString(font, s.toString(), px + 6, y, color, false);
            // 행 액션 글리프(수동 히트테스트): [−][+](등급 축만) [우] [✕]
            if (t.isGraded()) {
                glyph(g, px + 122, y, "−", 0xFFB9C9D2);
                glyph(g, px + 136, y, "+", 0xFFB9C9D2);
            }
            glyph(g, px + 150, y, "우", dom ? 0xFFFFE9B0 : 0xFF6E8492);
            glyph(g, px + 166, y, "✕", 0xFFE57373);
        }
        if (es.size() > OWNED_ROWS) {
            g.drawString(font, ownedScroll + 1 + "~" + Math.min(es.size(), ownedScroll + OWNED_ROWS)
                    + "/" + es.size(), px + 6, py + PANEL_H - 30, 0xFF6E8492, false);
        }

        // ── 우: 팔레트 ──
        List<Trait> pal = paletteTraits();
        int ry = py + 34;
        for (int r = 0; r < PALETTE_ROWS; r++) {
            int i = paletteScroll + r;
            if (i >= pal.size()) {
                break;
            }
            Trait t = pal.get(i);
            int y = ry + r * ROW_H;
            boolean sel = t.ordinal() == selectedOrdinal;
            if (sel) {
                g.fill(px + 190, y - 2, px + PANEL_W - 6, y + 10, 0x66FFE9B0);
            }
            String name = t.koreanName() + (t.isAbility() ? " [능력]" : t.isGraded() ? " [등급]" : "");
            g.drawString(font, name, px + 194, y, sel ? 0xFFFFE9B0 : 0xFFB9C9D2, false);
        }
        if (pal.size() > PALETTE_ROWS) {
            g.drawString(font, paletteScroll + 1 + "~"
                    + Math.min(pal.size(), paletteScroll + PALETTE_ROWS) + "/" + pal.size(),
                    px + 192, py + PANEL_H - 48, 0xFF6E8492, false);
        }
        boolean gradedSel = selectedOrdinal >= 0 && Trait.values()[selectedOrdinal].isGraded();
        gradeBtn.active = gradedSel;
        addBtn.active = selectedOrdinal >= 0;

        // ── 상태줄 ──
        if (!status.isEmpty()) {
            g.drawString(font, status, px + 6, py + PANEL_H - 17,
                    status.contains("초과") || status.contains("이미") || status.contains("없")
                            ? 0xFFE57373 : 0xFF9FE7A8, true);
        }
        super.render(g, mouseX, mouseY, partial);
    }

    private void glyph(GuiGraphics g, int x, int y, String s, int color) {
        g.fill(x - 2, y - 2, x + 10, y + 10, 0x552C3A44);
        g.drawString(font, s, x, y, color, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        int ly = py + 30;
        for (int r = 0; r < OWNED_ROWS; r++) {
            int i = ownedScroll + r;
            if (i >= entries.size()) {
                break;
            }
            int y = ly + r * ROW_H;
            if (my < y - 2 || my > y + 10) {
                continue;
            }
            Trait t = Trait.values()[entries.get(i).traitOrdinal()];
            if (t.isGraded() && hit(mx, px + 122)) {
                send(TraitEditor.OP_GRADE_DELTA, 0, i, -1);
                return true;
            }
            if (t.isGraded() && hit(mx, px + 136)) {
                send(TraitEditor.OP_GRADE_DELTA, 0, i, 1);
                return true;
            }
            if (hit(mx, px + 150)) {
                send(TraitEditor.OP_TOGGLE_DOMINANT, 0, i, 0);
                return true;
            }
            if (hit(mx, px + 166)) {
                send(TraitEditor.OP_REMOVE, 0, i, 0);
                return true;
            }
        }
        // 팔레트 선택
        List<Trait> pal = paletteTraits();
        int ry = py + 34;
        if (mx >= px + 190 && mx <= px + PANEL_W - 6) {
            for (int r = 0; r < PALETTE_ROWS; r++) {
                int i = paletteScroll + r;
                if (i >= pal.size()) {
                    break;
                }
                int y = ry + r * ROW_H;
                if (my >= y - 2 && my <= y + 10) {
                    selectedOrdinal = pal.get(i).ordinal();
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hit(double mx, int x) {
        return mx >= x - 2 && mx <= x + 10;
    }

    private void send(int op, int ordinal, int index, int value) {
        ModNetwork.CHANNEL.sendToServer(
                new EditTraitPacket(entityId, op, ordinal, index, value, false));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx < px + 188) {
            ownedScroll = Mth.clamp(ownedScroll - (int) Math.signum(delta), 0,
                    Math.max(0, entries.size() - OWNED_ROWS));
        } else {
            paletteScroll = Mth.clamp(paletteScroll - (int) Math.signum(delta), 0,
                    Math.max(0, paletteTraits().size() - PALETTE_ROWS));
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
