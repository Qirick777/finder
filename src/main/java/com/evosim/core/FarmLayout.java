package com.evosim.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 밭 배치 기하 (봉건 밭 경제 M0, 순수). 밭은 <b>덩어리의 격자</b>다.
 *
 * <pre>
 *   덩어리 = [길 1칸][재배 2칸]        (테두리를 빼면 3칸 주기)
 *   발자국 = [테두리][재배2][길][재배2][테두리]
 *
 *   4x5 (덩어리1·줄3)      7x5 (덩어리2·줄3)      7x8 (덩어리2·줄6)
 *     TTTT                   TTTTTTT                TTTTTTT
 *     TBBT                   TBBTBBT                TBBTBBT
 *     TBBT                   TBBTBBT                TBBTBBT  ×6
 *     TBBT                   TBBTBBT                TTTTTTT
 *     TTTT                   TTTTTTT
 * </pre>
 *
 * <p><b>왜 칸 단위 수열을 버렸나.</b> 종전에는 타일을 하나씩 이어 붙이는 수열이 모양을 만들었다.
 * 그러면 막힌 칸을 만날 때마다 모양이 무너져(계단·구멍·이웃과 붙음), 관문을 하나 추가할 때마다
 * 그 모양이 다시 깨졌다 — 실측에서 채움 80%, 줄 길이 1~12 까지 벌어졌다. 발자국을 <b>통째로</b>
 * 정하면 그 문제군이 구성적으로 사라진다. 집·학교·교회가 이미 그렇게 서 있다.
 *
 * <p><b>세로 길이 가로 고랑을 대신한다.</b> 덩어리가 2열뿐이라 모든 재배 칸이 길에 맞닿는다.
 * 그래서 재배줄 사이에 빈 고랑 줄을 둘 필요가 없고, 발자국 대비 재배 밀도는 종전과 같은 ~50%다.
 *
 * <p>월드 배치(높이)는 여기 없다 — 이 클래스는 평면 격자만 안다. 원목은 지면 +1, 재배 바닥
 * (잔디블록)도 지면 +1, 베리는 그 위 +2 에 놓인다.
 */
public final class FarmLayout {

    /** 덩어리 하나의 재배 열 수. */
    public static final int BED_COLS = 2;
    /** 덩어리 주기 = 길 1 + 재배 2. */
    public static final int BED_PITCH = BED_COLS + 1;
    /** 줄을 늘릴 때의 단위 — 한 번에 세 줄. */
    public static final int ROW_STEP = 3;
    /** 첫 밭의 줄 수. */
    public static final int ROWS_MIN = 3;

    /**
     * 단계 경계 타일 수 — 경제 문턱(자영 한계·첫 고용)이 여기 걸린다.
     *
     * <p>{6, 12, 24, 36, 54} = 단계 1~5의 재배 칸. 종전 {9, 15, 25, 35, 49} 에서 핵심 두
     * 지점(자영 한계 25→24, 첫 고용 35→36)은 거의 그대로고, 앞 둘만 덩어리 경계로 내려온다.
     */
    public static final int[] TIERS = {6, 12, 24, 36, 54};

    private FarmLayout() {
    }

    /**
     * 단계 s(1부터)의 {덩어리 수, 줄 수}.
     *
     * <p>덩어리 추가 ↔ 줄 늘리기가 번갈아 간다. 1단계 (1,3) 에서 시작해 <b>덩어리 추가가 먼저</b>다:
     * (1,3) → (2,3) → (2,6) → (3,6) → (3,9) → (4,9) …
     */
    public static int[] stage(int s) {
        int beds = 1;
        int rows = ROWS_MIN;
        boolean addBed = true;
        for (int i = 1; i < Math.max(1, s); i++) {
            if (addBed) {
                beds++;
            } else {
                rows += ROW_STEP;
            }
            addBed = !addBed;
        }
        return new int[] {beds, rows};
    }

    /** 재배 칸 수 = 2 × 덩어리수 × 줄수. */
    public static int tiles(int beds, int rows) {
        return BED_COLS * Math.max(0, beds) * Math.max(0, rows);
    }

    /** 발자국 {폭, 높이} — 폭 = 3×덩어리+1(테두리 둘 + 재배 + 사이 길), 높이 = 줄+2. */
    public static int[] footprint(int beds, int rows) {
        return new int[] {BED_PITCH * Math.max(0, beds) + 1, Math.max(0, rows) + 2};
    }

    /**
     * 발자국 안의 (열 c, 행 r)이 <b>재배 칸</b>인가. 아니면 원목(테두리 또는 길)이다.
     *
     * <p>발자국 안에 빈 잔디는 없다 — 모든 칸이 재배 아니면 원목이다.
     */
    public static boolean isCrop(int c, int r, int beds, int rows) {
        int[] fp = footprint(beds, rows);
        if (c <= 0 || r <= 0 || c >= fp[0] - 1 || r >= fp[1] - 1) {
            return false; // 테두리
        }
        return (c - 1) % BED_PITCH != BED_COLS; // 덩어리 주기의 마지막 칸이 길
    }

    /**
     * 재배 칸을 채우는 순서 — 위 줄부터, 각 줄은 왼쪽부터.
     *
     * <p>노동은 하루 몇 칸이라 한 단계를 여러 날에 걸쳐 채운다. 그동안 미완성인 부분이
     * <b>마지막 줄 하나</b>에만 남도록 줄 우선으로 훑는다(첨부 도면의 성장 모습 그대로).
     */
    public static List<int[]> cropOrder(int beds, int rows) {
        int[] fp = footprint(beds, rows);
        List<int[]> out = new ArrayList<>(tiles(beds, rows));
        for (int r = 1; r < fp[1] - 1; r++) {
            for (int c = 1; c < fp[0] - 1; c++) {
                if (isCrop(c, r, beds, rows)) {
                    out.add(new int[] {c, r});
                }
            }
        }
        return out;
    }

    /** 이 재배 칸 수를 담는 최소 단계(1부터). 상한 없음 — 계속 커진다. */
    public static int stageOf(int tiles) {
        for (int s = 1; s <= 64; s++) {
            int[] br = stage(s);
            if (tiles(br[0], br[1]) >= tiles) {
                return s;
            }
        }
        return 64;
    }

    /** 다음 단계에서 늘어나는 재배 칸 수 — 덩어리 추가면 2×줄, 줄 늘리기면 6×덩어리. */
    public static int growthOf(int s) {
        int[] a = stage(s);
        int[] b = stage(s + 1);
        return tiles(b[0], b[1]) - tiles(a[0], a[1]);
    }

    /** 다음 단계가 덩어리를 늘리는가(아니면 줄을 늘린다). */
    public static boolean nextAddsBed(int s) {
        return stage(s + 1)[0] > stage(s)[0];
    }
}
