package com.evosim.core;

import java.util.List;

/**
 * 거처 배치 (설계서 §13-D 이주/정착, §3 거처 포인터). 짝이 생겨 독립할 때 새 거처 좌표를 정한다.
 *
 * <p>이주자 = 더 멀리, 애향심 = 가까이, 중립/상쇄 = 기본. 새 거처는 기존 거처들과 겹치지 않게 배치.
 * 순수 함수 (2D x·z 좌표). 마크는 이 좌표에 상자를 놓아 가족 인벤토리로 삼는다.
 */
public final class Settlement {

    /** 독립 기본 거리(블록, 밸런싱 초기값 §). */
    public static final int BASE_DISTANCE = 16;
    /** 거처 간 최소 간격(겹침 방지). */
    public static final int MIN_GAP = 10;

    private Settlement() {
    }

    /**
     * 짝 조합별 독립 거리 (설계서 §13-D):
     * 이주 우세 → 2배(멀리), 애향 우세 → 절반(가까이), 중립/상쇄(이주×애향) → 기본.
     */
    public static int homeDistance(Individual a, Individual b) {
        int mig = migrantScore(a) + migrantScore(b); // 이주자 +1, 애향심 −1
        if (mig > 0) {
            return BASE_DISTANCE * 2;   // 개척 물결
        }
        if (mig < 0) {
            return BASE_DISTANCE / 2;   // 고향 근처 밀집
        }
        return BASE_DISTANCE;           // 중립 또는 이주×애향 상쇄
    }

    private static int migrantScore(Individual ind) {
        if (ExpressionResolver.isExpressed(ind, Trait.MIGRATORY)) {
            return 1;
        }
        if (ExpressionResolver.isExpressed(ind, Trait.HOMEBOUND)) {
            return -1;
        }
        return 0;
    }

    /**
     * 부모 거처에서 {@code distance}만큼 떨어진, 기존 거처들과 {@link #MIN_GAP} 이상 벌어진 새 좌표.
     * 링을 돌며 빈 각도를 찾고, 못 찾으면 거리를 늘려 재시도(고립 방지). (x, z) 반환.
     */
    public static int[] placeHome(int[] parentHome, int distance, List<int[]> existing,
                                  int minGap, DeterministicRng rng) {
        int startAngle = rng.nextInt(360);
        for (int ring = 0; ring < 6; ring++) {
            int d = distance + ring * minGap;
            for (int step = 0; step < 12; step++) {
                double ang = Math.toRadians((startAngle + step * 30) % 360);
                int x = parentHome[0] + (int) Math.round(Math.cos(ang) * d);
                int z = parentHome[1] + (int) Math.round(Math.sin(ang) * d);
                if (isClear(x, z, existing, minGap)) {
                    return new int[] {x, z};
                }
            }
        }
        // 최후: 부모에서 아주 멀리(겹침 무시) — 개척 최전선 도달.
        double ang = Math.toRadians(startAngle);
        return new int[] {
                parentHome[0] + (int) Math.round(Math.cos(ang) * distance * 6),
                parentHome[1] + (int) Math.round(Math.sin(ang) * distance * 6)
        };
    }

    private static boolean isClear(int x, int z, List<int[]> existing, int minGap) {
        long gapSq = (long) minGap * minGap;
        for (int[] h : existing) {
            long dx = x - h[0];
            long dz = z - h[1];
            if (dx * dx + dz * dz < gapSq) {
                return false;
            }
        }
        return true;
    }
}
