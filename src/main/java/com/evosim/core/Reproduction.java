package com.evosim.core;

/**
 * 번식 규칙 (설계서 §6). 밤에 가족 잉여식량이 임계 이상이면 자동 번식. 출산 상한·쿨다운으로 무한번식 방지.
 *
 * <p>순수 함수 — 임계치·출산상한 계산. "밤 판정·잉여 확정"의 타이밍은 표현층(엔티티)이 담당.
 */
public final class Reproduction {

    /** 기준 임계치(잉여) = 아이 하나 유아+소년기 부양 비용 (설계서 §6, 밸런싱). */
    public static final double BASE_THRESHOLD = 2.5;
    /** 출산 상한 기본. 5→10(사실상 무제한 — 물리 상한은 쿨다운 1일×성년 창 ~7): 실질 제한을
     *  출산 게이트(저장고 잉여)의 경제력으로 이관. 6명+는 지속 잉여 20~30/일이 필요해
     *  엘리트 지주 전용 영역 — 규칙이 아닌 능력 계단(무능력 소작 2~3·약초Ⅰ~Ⅱ 3~4)이 가른다. */
    public static final int BASE_BIRTH_LIMIT = 10;
    /** 여성 출산 쿨다운 (설계서 §6). */
    public static final int FEMALE_COOLDOWN_DAYS = 3; // 1→3(A안 후속 — 부유층 상한): 정원 배율
    // 계수 상향(M(5) 2.6→4.3)으로 엘리트 순잉여가 +3.16/일이 되면 자식 1명당 소요가
    // (게이트상승 1.8 + 출산비 3.0)/3.16 = 1.5일로 쿨다운 1일보다 짧아져, 가임창 13일 동안
    // 8~9명까지 간다(목표 "부유가문 3~4 포화" 위반). 쿨다운 3일이면 13/3 = 4.3명으로 묶인다.
    // 평민은 이미 흐름 제약(소작 순잉여 +0.44 → 10.9일/명)이 쿨다운보다 크므로 무영향 —
    // 즉 이 값은 <b>부유층에만 구속되는 상한</b>이고 사다리 하단은 건드리지 않는다.

    private Reproduction() {
    }

    /** 임계 하한 — 무모·번식선호가 겹쳐도 "잉여 0 무조건 통과"는 없게(기존 최저치와 동일). */
    public static final double MIN_THRESHOLD = 0.5;

    /**
     * 번식 임계치 보정 (설계서 §6): 번식선호 한쪽 −1/둘 −2, 번식불호 한쪽 +1/둘 +6(사실상 번식 안 함).
     * 무모 한쪽 −1/둘 −2(덜 준비해도 낳음) ↔ 신중 한쪽 +1/둘 +2(준비를 더 함) — 같은 축 반발이라
     * 한 개체가 둘 다 못 가지며, 무모+신중 부부는 상쇄(0). 하한 {@link #MIN_THRESHOLD}.
     */
    public static double threshold(Individual a, Individual b) {
        int eager = has(a, Trait.REPRODUCTION_EAGER) + has(b, Trait.REPRODUCTION_EAGER);
        int averse = has(a, Trait.REPRODUCTION_AVERSE) + has(b, Trait.REPRODUCTION_AVERSE);
        int reckless = has(a, Trait.RECKLESS) + has(b, Trait.RECKLESS);
        int prudent = has(a, Trait.PRUDENT) + has(b, Trait.PRUDENT);
        double adj = 0;
        adj -= (eager == 2 ? 2 : eager); // 둘 −2 / 한쪽 −1
        adj += (averse == 2 ? 6 : averse); // 둘 +6 / 한쪽 +1
        adj -= reckless; // 무모 — 준비량 감소
        adj += prudent;  // 신중 — 준비량 증가
        return Math.max(MIN_THRESHOLD, BASE_THRESHOLD + adj);
    }

    /**
     * 출산 상한 (설계서 §6): 다산 여+2/남+1/둘+3, 난임 여−1/남−2/둘−3.
     * 아이선호 축: 아이선호 각 +1 / 아이불호 각 −1. (기본 5, 최소 0)
     */
    public static int birthLimit(Individual female, Individual male) {
        int limit = BASE_BIRTH_LIMIT
                + 2 * has(female, Trait.PROLIFIC) + has(male, Trait.PROLIFIC)
                - has(female, Trait.INFERTILE) - 2 * has(male, Trait.INFERTILE)
                + has(female, Trait.CHILD_LOVING) + has(male, Trait.CHILD_LOVING)
                - has(female, Trait.CHILD_AVERSE) - has(male, Trait.CHILD_AVERSE);
        return Math.max(0, limit);
    }

    /** 잉여가 임계 이상이면 번식 (설계서 §6). */
    public static boolean canReproduce(double surplus, double threshold) {
        return surplus >= threshold;
    }

    private static int has(Individual ind, Trait t) {
        return ExpressionResolver.isExpressed(ind, t) ? 1 : 0;
    }
}
