package com.evosim.mod.client;

import com.evosim.mod.item.ScannerMode;
import com.evosim.mod.item.TraitScannerItem;
import com.evosim.mod.net.ScanSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.ArrayList;
import java.util.List;

/**
 * 검사봉 렌즈 HUD 카드 (P2~P4). 검사봉을 들고 미믹을 조준하면 조준점 옆에 카드가 페이드인 —
 * 헤더(개체·행동·게이지) + 모드 탭 본문(특성/짝/거처/가족) + 탭 인디케이터.
 * 데이터는 전부 {@link ClientScanCache}의 서버 스냅샷 — 클라 계산 없음.
 *
 * <p>애니메이션: 페이드(등장 150ms/이탈 300ms 유예), 탭 슬라이드(180ms), 위급 게이지 펄스,
 * 핀 상태 금색 테두리. 전부 클라 표시 계층 — 서버 배선 무관.
 */
public final class ScanHudOverlay implements IGuiOverlay {

    public static final ScanHudOverlay INSTANCE = new ScanHudOverlay();

    private static final int CARD_W = 152;
    private static final int PAD = 6;
    private static final int LINE = 10;
    private static final long FADE_IN_MS = 150;
    private static final long FADE_OUT_MS = 250;
    private static final long LOST_GRACE_MS = 350;   // 조준 이탈 유예(깜빡임 방지)
    private static final long SLIDE_MS = 180;

    // ── 표시 상태(클라 전용) ──
    private float alpha;                  // 카드 전체 투명도 0..1
    private long lastFrameMs;
    private ScanSnapshot drawn;           // 페이드아웃 중에도 그릴 마지막 스냅샷
    private int lastMode = -1;
    private int slideFrom;
    private long slideStartMs;

    private ScanHudOverlay() {
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics g, float partialTick, int sw, int sh) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui) {
            return;
        }
        long now = System.currentTimeMillis();
        float dt = lastFrameMs == 0 ? 16 : Math.min(100, now - lastFrameMs);
        lastFrameMs = now;

        ItemStack held = player.getMainHandItem().getItem() instanceof TraitScannerItem
                ? player.getMainHandItem()
                : (player.getOffhandItem().getItem() instanceof TraitScannerItem
                        ? player.getOffhandItem() : ItemStack.EMPTY);
        boolean holding = !held.isEmpty();

        ClientScanCache.refreshPinIfSame();
        boolean pinned = ClientScanCache.isPinned();
        ScanSnapshot live = ClientScanCache.get();
        boolean liveFresh = live != null && ClientScanCache.ageMillis() < LOST_GRACE_MS;
        ScanSnapshot want = pinned ? ClientScanCache.pinnedSnapshot() : (liveFresh ? live : null);
        boolean show = holding && want != null;
        if (want != null) {
            drawn = want;
        }
        float target = show ? 1.0F : 0.0F;
        float rate = dt / (float) (show ? FADE_IN_MS : FADE_OUT_MS);
        alpha = Mth.clamp(alpha + Math.signum(target - alpha) * rate, 0.0F, 1.0F);
        if (alpha <= 0.02F || drawn == null) {
            return;
        }
        ScanSnapshot s = drawn;
        Font font = gui.getFont();

        // ── 탭 모드(아이템 NBT — 서버와 동일 값이 클라로 동기) + 슬라이드 ──
        int mode = holding ? ScannerMode.of(held).ordinal() : lastMode;
        if (lastMode == -1) {
            lastMode = mode;
        }
        if (mode != lastMode) {
            slideFrom = lastMode;
            slideStartMs = now;
            lastMode = mode;
        }
        float slide = Mth.clamp((now - slideStartMs) / (float) SLIDE_MS, 0.0F, 1.0F);
        float ease = 1.0F - (1.0F - slide) * (1.0F - slide); // ease-out

        // ── 본문 사전 구성(높이 계산용) ──
        List<Runnable> body = new ArrayList<>();
        int[] bodyH = {0};
        int x0 = sw / 2 + 16;
        int y0;
        int innerW = CARD_W - PAD * 2;
        buildBody(mode, s, font, innerW, body, bodyH, x0 + PAD, g);

        int headerH = LINE * 2 + 9 + 7 + 3;       // 두 줄 + H바 + HP바
        int tabsH = 13;
        int cardH = PAD + headerH + 3 + bodyH[0] + 4 + tabsH + PAD;
        y0 = sh / 2 - cardH / 2;

        int a = (int) (alpha * 255);
        // ── 패널 ──
        int bgTop = color(a * 200 / 255, 0x141A20);
        int bgBot = color(a * 200 / 255, 0x0C1014);
        g.fillGradient(x0, y0, x0 + CARD_W, y0 + cardH, bgTop, bgBot);
        int border = pinned ? color(a, 0xE7B85B) : color(a, 0x3D5260);
        g.fill(x0, y0, x0 + CARD_W, y0 + 1, border);
        g.fill(x0, y0 + cardH - 1, x0 + CARD_W, y0 + cardH, border);
        g.fill(x0, y0, x0 + 1, y0 + cardH, border);
        g.fill(x0 + CARD_W - 1, y0, x0 + CARD_W, y0 + cardH, border);

        int tx = x0 + PAD;
        int ty = y0 + PAD;
        // ── 헤더: 정체성 ──
        String sex = s.female ? "♀" : "♂";
        int sexColor = s.female ? color(a, 0xFF8FB3) : color(a, 0x7CC0FF);
        g.drawString(font, sex, tx, ty, sexColor, true);
        String idLine = "N" + s.serial + " " + stageName(s.stage) + " · 세대" + s.generation
                + (s.stageActor ? " [무대]" : "");
        g.drawString(font, idLine, tx + 10, ty, color(a, 0xEFF5F8), true);
        if (pinned) {
            String pin = "고정";
            g.drawString(font, pin, x0 + CARD_W - PAD - font.width(pin), ty, color(a, 0xE7B85B), true);
        }
        ty += LINE;
        // ── 헤더: 행동 + 배지 ──
        String act = "▶ " + s.action + (s.hasNav ? " → " + s.navX + "," + s.navZ : "");
        g.drawString(font, act, tx, ty, color(a, 0x9FE7A8), true);
        int bx = tx + font.width(act) + 6;
        bx = badge(g, font, bx, ty, s.satisfied, "만족", 0x8FD3E8, a);
        bx = badge(g, font, bx, ty, s.critical, "위급", 0xFF6B5A, a);
        bx = badge(g, font, bx, ty, s.building, "건축", 0xD8C27A, a);
        bx = badge(g, font, bx, ty, s.courtTravel, "여행", 0xC79FE7, a);
        badge(g, font, bx, ty, s.tenantFarm != 0, "소작", 0xA8C79F, a);
        ty += LINE;
        // ── 게이지: H (위급 펄스) ──
        float hFrac = Mth.clamp(s.holding / 2.0F, 0.0F, 1.0F);
        int hColor = mix(0xE5533A, 0x53C46A, hFrac);
        if (s.critical) {
            float pulse = 0.5F + 0.5F * (float) Math.sin(now / 120.0);
            hColor = mix(0xE5533A, 0x7A1F12, pulse);
        }
        bar(g, tx, ty, innerW, 5, hFrac, hColor, a);
        String hTxt = String.format("H %.2f", s.holding);
        g.drawString(font, hTxt, tx + innerW - font.width(hTxt), ty - 1, color(a, 0xCFE3D4), true);
        ty += 9;
        // ── 게이지: 체력 ──
        bar(g, tx, ty, innerW, 3, s.maxHealth <= 0 ? 0 : s.health / s.maxHealth, 0xD9564A, a);
        ty += 7 + 3;

        // ── 본문(탭 슬라이드) ──
        int dir = slideDir(slideFrom, mode);
        float off = (1.0F - ease) * 14.0F * dir;
        g.pose().pushPose();
        g.pose().translate(off, 0, 0);
        bodyYBase = ty;
        bodyAlpha = (int) (a * (0.35F + 0.65F * ease));
        for (Runnable r : body) {
            r.run();
        }
        g.pose().popPose();
        ty += bodyH[0] + 4;

        // ── 탭 인디케이터 ──
        String[] tabs = {"특성", "짝", "거처", "가족"};
        int tw = innerW / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            boolean on = i == mode;
            int cx = tx + i * tw + tw / 2 - font.width(tabs[i]) / 2;
            g.drawString(font, tabs[i], cx, ty + 2, color(a, on ? 0xFFE9B0 : 0x6E8492), true);
        }
        float fromX = tx + slideFrom * tw;
        float toX = tx + mode * tw;
        int ix = (int) Mth.lerp(ease, fromX, toX);
        g.fill(ix + 4, ty + 11, ix + tw - 4, ty + 12, color(a, 0xFFE9B0));
    }

    // 본문 렌더 클로저가 공유하는 기준 좌표/알파(빌드 시점에 캡처하지 않도록 필드로).
    private int bodyYBase;
    private int bodyAlpha;

    /** 모드별 본문 구성 — 라인 러너를 쌓고 총높이를 계산(패널 높이 선계산용). */
    private void buildBody(int mode, ScanSnapshot s, Font font, int innerW,
                           List<Runnable> out, int[] h, int tx, GuiGraphics g) {
        if (mode == ScannerMode.TRAIT.ordinal()) {
            String[] chips = s.traits.isEmpty() || "무특성".equals(s.traits)
                    ? new String[0] : s.traits.split("·");
            int cx = 0;
            int row = 0;
            List<int[]> pos = new ArrayList<>(); // {row, x, chipIdx}
            for (int i = 0; i < chips.length && row < 3; i++) {
                int w = font.width(chips[i]) + 8;
                if (cx + w > innerW) {
                    row++;
                    cx = 0;
                    if (row >= 3) {
                        break;
                    }
                }
                pos.add(new int[] {row, cx, i});
                cx += w + 3;
            }
            int rows = Math.max(1, Math.min(3, row + 1));
            for (int[] p : pos) {
                String t = chips[p[2]].trim();
                int px = tx + p[1];
                int py0 = p[0] * (LINE + 3);
                out.add(() -> {
                    int y = bodyYBase + py0;
                    int w = font.width(t) + 8;
                    g.fill(px, y - 1, px + w, y + LINE - 1, color(bodyAlpha * 160 / 255, 0x2C3A44));
                    g.drawString(font, t, px + 4, y, color(bodyAlpha, 0xE8F0F4), false);
                });
            }
            int chipsH = rows * (LINE + 3);
            out.add(() -> g.drawString(font, "육아 " + s.parenting + " · 짝고름 " + s.mateChoice,
                    tx, bodyYBase + chipsH + 1, color(bodyAlpha, 0xB9C9D2), true));
            h[0] = chipsH + LINE + 2;
        } else if (mode == ScannerMode.MATE.ordinal()) {
            String l1 = s.spouseId != 0 ? "혼인 (배우자 N" + s.spouseId + ")" : "미혼";
            String l2 = "짝고름 " + s.mateChoice + (s.courtTravel ? " · 구혼여행 중" : "");
            String l3 = "자녀: 소년 " + s.boys + " · 유아 " + s.infants;
            addLines(out, g, font, tx, new String[] {l1, l2, l3},
                    new int[] {0xEFF5F8, 0xB9C9D2, 0xB9C9D2});
            h[0] = LINE * 3;
        } else if (mode == ScannerMode.HOME.ordinal()) {
            if (s.larder < 0) {
                addLines(out, g, font, tx, new String[] {"거처 없음 (방랑)"}, new int[] {0x8FA0AB});
                h[0] = LINE;
                return;
            }
            String l1 = String.format("저장고 %.1f · 정원 %d/%d · 밭 %s", s.larder, s.garden,
                    s.gardenCap, s.farmPlots == 0 ? "—" : s.farmPlots + "구획 " + s.farmTiles + "타일");
            addLines(out, g, font, tx, new String[] {l1}, new int[] {0xEFF5F8});
            int gy = LINE + 2;
            gy = gauge(out, g, font, tx, gy, innerW, "번식", s.reproNeed, s.reproLack, s.larder, true);
            gy = gauge(out, g, font, tx, gy, innerW, "베리", s.berryNeed, s.berryLack, s.larder, true);
            final int fy = gy;
            out.add(() -> {
                String mark = s.farmMotive ? "동기✓" : "동기✗";
                g.drawString(font, mark, tx + innerW - font.width(mark),
                        bodyYBase + fy - LINE + 1, color(bodyAlpha, s.farmMotive ? 0x9FE7A8 : 0x8FA0AB), true);
            });
            gy = gauge(out, g, font, tx, gy, innerW, "개간", s.farmNeed, s.farmLack, s.larder, false);
            h[0] = gy;
        } else { // INVENTORY(가족)
            String l1 = "구성: 성인 " + s.adults + " · 소년 " + s.boys
                    + " · 유아 " + s.infants + " · 노년 " + s.elders;
            String l2 = s.larder < 0 ? "저장고 — (무거처)" : String.format("저장고 %.1f", s.larder);
            String l3 = (s.satisfied ? "만족(노동 정지)" : "분발(노동 중)")
                    + (s.critical ? " · 위급!" : "");
            addLines(out, g, font, tx, new String[] {l1, l2, l3},
                    new int[] {0xEFF5F8, 0xB9C9D2, 0xB9C9D2});
            h[0] = LINE * 3;
        }
    }

    /** 문턱 게이지 한 줄 — 진행 fill(저장고/필요) + "현재/필요" 라벨. 센티널 -1=완료, -2=해당없음. */
    private int gauge(List<Runnable> out, GuiGraphics g, Font font, int tx, int y, int innerW,
                      String label, float need, float lack, float larder, boolean half) {
        final int yy = y;
        out.add(() -> {
            int by = bodyYBase + yy;
            g.drawString(font, label, tx, by, color(bodyAlpha, 0xB9C9D2), true);
            int bx = tx + 26;
            int bw = innerW - 26 - 52;
            if (need <= -1.5F) {
                g.drawString(font, "—", bx, by, color(bodyAlpha, 0x6E8492), true);
                return;
            }
            if (need <= -0.5F) {
                g.drawString(font, "완료", bx, by, color(bodyAlpha, 0x9FE7A8), true);
                return;
            }
            float frac = need <= 0 ? 1 : Mth.clamp(Math.max(0, larder) / need, 0.0F, 1.0F);
            boolean met = lack <= 0.0F;
            bar(g, bx, by + 1, bw, 6, frac, met ? 0x53C46A : 0xD8A84A, bodyAlpha);
            String t = met ? "충족" : String.format("%.1f/%.1f", Math.max(0, larder), need);
            g.drawString(font, t, tx + 26 + bw + 4, by, color(bodyAlpha, met ? 0x9FE7A8 : 0xD8C89A), true);
        });
        return y + LINE + 2;
    }

    private void addLines(List<Runnable> out, GuiGraphics g, Font font, int tx,
                          String[] lines, int[] colors) {
        for (int i = 0; i < lines.length; i++) {
            final int idx = i;
            out.add(() -> g.drawString(font, lines[idx], tx, bodyYBase + idx * LINE,
                    color(bodyAlpha, colors[idx]), true));
        }
    }

    private static int badge(GuiGraphics g, Font font, int x, int y, boolean on,
                             String text, int rgb, int a) {
        if (!on) {
            return x;
        }
        g.drawString(font, text, x, y, color(a, rgb), true);
        return x + font.width(text) + 5;
    }

    private static void bar(GuiGraphics g, int x, int y, int w, int h, float frac, int rgb, int a) {
        g.fill(x, y, x + w, y + h, color(a * 120 / 255, 0x22303A));
        int fw = (int) (w * Mth.clamp(frac, 0.0F, 1.0F));
        if (fw > 0) {
            g.fill(x, y, x + fw, y + h, color(a, rgb));
        }
    }

    private static int slideDir(int from, int to) {
        if (from == to) {
            return 0;
        }
        int d = Math.floorMod(to - from, 4);
        return d <= 2 ? 1 : -1;
    }

    private static String stageName(int ordinal) {
        return switch (ordinal) {
            case 0 -> "유아";
            case 1 -> "소년";
            case 3 -> "노년";
            default -> "성년";
        };
    }

    private static int color(int a, int rgb) {
        return (Mth.clamp(a, 4, 255) << 24) | (rgb & 0xFFFFFF);
    }

    private static int mix(int rgbA, int rgbB, float t) {
        int r = (int) Mth.lerp(t, (rgbA >> 16) & 0xFF, (rgbB >> 16) & 0xFF);
        int gg = (int) Mth.lerp(t, (rgbA >> 8) & 0xFF, (rgbB >> 8) & 0xFF);
        int b = (int) Mth.lerp(t, rgbA & 0xFF, rgbB & 0xFF);
        return (r << 16) | (gg << 8) | b;
    }
}
