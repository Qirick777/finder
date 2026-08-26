package com.evosim.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 밭 배치 수열 (봉건 밭 경제 M0, 순수 §17). 재배줄 + 한 칸 고랑 구조 — 격자 (col, row)에서
 * row 는 재배줄 번호(월드 깊이 = row×2, 홀수 줄은 고랑). 성장 규칙 하나로 사용자 지정 순서를 재현:
 *
 * <p>"열 폭 W 가 min(2K−1, cap) 미만이면 열을 넓히고(각 줄에 1칸씩), 아니면 새 줄을 연다.
 * (W,K)가 (cap,cap)에 닿으면 cap += 2." — 결과: 1칸 → 둘째 줄 → 3×2(6) → 정사각 3×3(9)
 * → 5칸3줄(15) → 5칸4줄(20) → 5칸5줄(25) → 7칸5줄(35) → 7×7(49)…
 *
 * <p>부지 선정만 랜덤, 수열은 앵커 기준 결정론 — {@code /evotest farm}으로 좌표 전수 대조.
 */
public final class FarmLayout {

    /** 티어 경계 타일 수 — 경제 문턱(자영 한계 25, 첫 고용 35)과 맞물린 이정표. */
    public static final int[] TIERS = {9, 15, 25, 35, 49};
    /** 시작 열 폭 상한(5칸) — (5,5) 도달 시 +2. */
    public static final int START_CAP = 5;

    private FarmLayout() {
    }

    /** 처음 n개 타일의 (col, row) 목록 — 설치 순서 그대로. row 는 재배줄 index(고랑 제외). */
    public static List<int[]> layout(int n) {
        List<int[]> out = new ArrayList<>(Math.max(0, n));
        int w = 0;
        int k = 1;
        int cap = START_CAP;
        while (out.size() < n) {
            if (out.isEmpty()) {
                out.add(new int[] {0, 0});
                w = 1;
                continue;
            }
            if (w == cap && k == cap) {
                cap += 2;
            }
            if (w < Math.min(2 * k - 1, cap)) { // 열 확장 — 새 열을 각 재배줄에 위→아래
                for (int r = 0; r < k && out.size() < n; r++) {
                    out.add(new int[] {w, r});
                }
                w++;
            } else { // 새 재배줄 — 왼→오른쪽으로 채움
                for (int c = 0; c < w && out.size() < n; c++) {
                    out.add(new int[] {c, k});
                }
                k++;
            }
        }
        return out;
    }

    /**
     * 네 사분면 부호 조합 — <b>배치에는 더 이상 쓰지 않는다</b>(순수 함수라 남겨 둔다).
     *
     * <p>한때 "막힌 칸의 대체 방향"으로 칸마다 이것을 훑었다. 그러면 이상 칸이 막힐 때마다
     * 그 타일이 <b>앵커 반대편</b>에 놓여, 폭 7까지 자란 구획에서 c=5 가 막히면 몸통에서 10칸
     * 떨어진 허공에 박혔다 — 실측 공중 덤프에서 두 판에 각각 5·8개의 "홀로 선 한두 칸"이
     * 그렇게 생겼다. 방향은 <b>구획의 성질</b>이지 칸의 성질이 아니므로, 지금은 착공 때
     * 사분면을 한 번 고르고({@code FarmTicker.pickDir}) 그 뒤로는 뒤집지 않는다.
     */
    public static int[][] mirrors(int c, int r) {
        return new int[][] {{c, r}, {-c, r}, {c, -r}, {-c, -r}};
    }

    /** n타일 밭의 발자국 (가로, 세로 — 고랑 포함 월드 칸수). */
    public static int[] footprint(int n) {
        int maxC = 0;
        int maxR = 0;
        for (int[] t : layout(n)) {
            maxC = Math.max(maxC, t[0]);
            maxR = Math.max(maxR, t[1]);
        }
        return n <= 0 ? new int[] {0, 0} : new int[] {maxC + 1, maxR * 2 + 1};
    }
}
