package com.evosim.core;

/**
 * "공간 분리 금지" 설치 가드 (설계안 v2). 한 칸을 통행 불가로 바꾸는 설치(베리 심기)가 <b>통행 공간을 두
 * 조각으로 가르지 않는지</b>를 후보 칸의 8이웃만 보고 O(1)로 판정한다. 이 불변식이 유지되면 통행 공간은
 * 항상 하나로 연결되어 "바깥으로 통하는 길"이 자동 보장된다(길찾기·전역탐색 불필요).
 *
 * <p>정석 기법(단순점 판정 / Rutovitz 교차수)의 구현. 마인크래프트 이동은 <b>대각 코너컷 금지</b>이므로
 * (케이스 B) 대각 이웃은 양옆 직교 이웃이 모두 통행 가능일 때만 '연결자'로 인정한다.
 *
 * <p>순수 함수 — 월드 투영(지면 컬럼·높이·베리=막힘)은 표현층이 만들어 {@code ring}으로 넘긴다.
 */
public final class Connectivity {

    private Connectivity() {
    }

    /**
     * 후보 칸을 막아도 통행 공간이 갈라지지 않는가.
     *
     * @param ring 시계방향 8이웃 통행성 [N, NE, E, SE, S, SW, W, NW]. true=통행 가능.
     * @return true=설치 허용(연결 유지) / false=금지(두 공간을 잇는 통로일 수 있음)
     */
    public static boolean keepsConnectivity(boolean[] ring) {
        boolean[] r = new boolean[8];
        System.arraycopy(ring, 0, r, 0, 8);

        // 케이스 B 보정: 대각(1,3,5,7)은 양옆 직교가 모두 통행 가능일 때만 연결자.
        for (int d = 1; d < 8; d += 2) {
            boolean left = ring[(d + 7) % 8];
            boolean right = ring[(d + 1) % 8];
            if (!(left && right)) {
                r[d] = false;
            }
        }

        // arc(떨어진 통행 덩어리) 개수 = 0→1 전이 수. 원형 순회.
        int arcs = 0;
        for (int i = 0; i < 8; i++) {
            if (r[i] && !r[(i + 7) % 8]) {
                arcs++;
            }
        }
        // arcs<=1: 이웃 통행칸이 한 덩어리(또는 없음) → 막아도 분리 없음.
        return arcs <= 1;
    }
}
