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
    public static final double FEMALE_COOLDOWN_DAYS = 2.5; // 1→3(A안 후속 — 부유층 상한): 정원 배율 → 2.5(사용자 승인, 런 21: 출산이 쿨다운 상한에 붙어 있었다 — 젖 떼고(유아 1.75일) 사흘 뒤)

    // ── 다태아(사용자 승인) — 세대로 쌓이는 장기 가속. 초반은 +10% 남짓, 다산 가문이 커지며
    //    곡선이 꺾이고, 후반은 유아 병듦(밀집)이 깎는다. 출산 판정을 통과한 뒤 굴리며, 둘째·셋째도
    //    아이당 출산비·식량 관문을 각각 넘어야 한다(호출부).
    /** 기본 쌍둥이 확률. */
    public static final double TWIN_RATE = 0.10;
    /** 기본 삼둥이 확률. */
    public static final double TRIPLET_RATE = 0.02;
    /** 다산 어머니의 쌍둥이 가산. */
    public static final double TWIN_PROLIFIC_FEMALE = 0.15;
    /** 다산 아버지의 쌍둥이 가산. */
    public static final double TWIN_PROLIFIC_MALE = 0.05;

    /** 쌍둥이 확률(삼둥이 제외) — 불임이 끼면 0. */
    public static double twinChance(Individual female, Individual male) {
        if (has(female, Trait.INFERTILE) > 0 || has(male, Trait.INFERTILE) > 0) {
            return 0.0;
        }
        return TWIN_RATE + TWIN_PROLIFIC_FEMALE * has(female, Trait.PROLIFIC)
                + TWIN_PROLIFIC_MALE * has(male, Trait.PROLIFIC);
    }

    /** 삼둥이 확률 — 불임이 끼면 0. */
    public static double tripletChance(Individual female, Individual male) {
        if (has(female, Trait.INFERTILE) > 0 || has(male, Trait.INFERTILE) > 0) {
            return 0.0;
        }
        return TRIPLET_RATE;
    }

    /** 추가 출생 수(0·1·2) — roll ∈ [0,1): 삼둥이 구간 → 2, 쌍둥이 구간 → 1, 그 외 0. */
    public static int extraBirths(double roll, Individual female, Individual male) {
        double tri = tripletChance(female, male);
        if (roll < tri) {
            return 2;
        }
        if (roll < tri + twinChance(female, male)) {
            return 1;
        }
        return 0;
    }
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
        // ── 판단 축(사용자 승인) — "얼마나 여유가 있어야 낳는가"를 특성이 가른다. ────────────
        // 하층이 많이 낳는 이유와 상층이 많이 낳는 이유를 <b>다른 자리</b>에 둔다: 상층은 곳간이
        // 넉넉해서 이 문턱을 저절로 넘고, 하층은 문턱 자체가 낮아 여유가 없어도 넘는다.
        // 축의 본뜻에 맞는 넷만 고른다 — 무능·게으름(수확 축)·근시안(시야 축)·낭비선호(배우자 취향
        // 축)는 낳는 판단과 무관하고, 안분지족·무욕은 오히려 멈추는 쪽이라 넣지 않는다.
        adj -= 0.8 * has(a, Trait.IRRESPONSIBLE) + 0.8 * has(b, Trait.IRRESPONSIBLE);   // 무책임 — 뒷감당을 안 진다
        adj -= 0.6 * has(a, Trait.PRESENT_ORIENTED) + 0.6 * has(b, Trait.PRESENT_ORIENTED); // 현재지향 — 내일의 굶주림을 할인한다
        adj -= 0.4 * has(a, Trait.IMPULSIVE) + 0.4 * has(b, Trait.IMPULSIVE);           // 즉흥적 — 예비를 안 쌓고 벌인다
        adj -= 0.2 * has(a, Trait.DEPENDENT) + 0.2 * has(b, Trait.DEPENDENT);           // 의탁 — 남이 도와주겠지
        adj += 0.8 * has(a, Trait.OVER_RESPONSIBLE) + 0.8 * has(b, Trait.OVER_RESPONSIBLE);
        adj += 0.6 * has(a, Trait.FUTURE_ORIENTED) + 0.6 * has(b, Trait.FUTURE_ORIENTED);
        adj += 0.4 * has(a, Trait.PREPARED) + 0.4 * has(b, Trait.PREPARED);
        // 아래로는 −3.0 에서 멈춘다 — 번식불호가 위로 +6 을 주는 것과 짝이 맞고, 문턱이 음수로
        // 깊이 내려가 "곳간이 비어도 낳는다"가 되면 굶겨 죽이는 기계가 된다.
        adj = Math.max(-3.0, adj);
        return Math.max(MIN_THRESHOLD, BASE_THRESHOLD + adj);
    }

    /**
     * <b>여성 출산 쿨다운</b> — 신체 축이 가른다(사용자 승인).
     *
     * <p>판단 축({@link #threshold})이 "여유가 얼마나 있어야 낳는가"를 정한다면, 이쪽은 "얼마나 자주
     * 낳을 수 있는가"를 정한다. 두 축은 겹치지 않는다. 몸이 거칠고 튼튼하면 출산 회복이 빠르고,
     * 빈약·병약하면 느리다는 생물학적 사유 그대로다.
     *
     * <p>단순무식은 <b>신체 축</b>이다(공격 +6%/등급 · 소모 +2.5%/등급 · 손재주·몰이 −0.25).
     * 자주 낳되 더 먹고 덜 버는 셈이라 공짜가 아니다 — 비용이 이미 특성에 붙어 있다.
     *
     * @return 쿨다운 일수. 하한 {@link #COOLDOWN_MIN_DAYS} · 상한 {@link #COOLDOWN_MAX_DAYS}.
     */
    public static double femaleCooldownDays(Individual mother) {
        if (mother == null) {
            return FEMALE_COOLDOWN_DAYS;
        }
        double m = 1.0;
        m -= 0.04 * ExpressionResolver.expressedGrade(mother, Trait.BRUTISH);
        m += 0.03 * ExpressionResolver.expressedGrade(mother, Trait.REFINED);
        m -= 0.03 * ExpressionResolver.expressedGrade(mother, Trait.TOUGH);
        m += 0.03 * ExpressionResolver.expressedGrade(mother, Trait.FRAIL);
        m -= 0.03 * ExpressionResolver.expressedGrade(mother, Trait.HARDY);
        m += 0.04 * ExpressionResolver.expressedGrade(mother, Trait.SICKLY);
        m -= 0.02 * ExpressionResolver.expressedGrade(mother, Trait.VIGOROUS);
        m += 0.02 * ExpressionResolver.expressedGrade(mother, Trait.LISTLESS);
        return Math.max(COOLDOWN_MIN_DAYS,
                Math.min(COOLDOWN_MAX_DAYS, FEMALE_COOLDOWN_DAYS * m));
    }

    /** 쿨다운 하한 — 아무리 튼튼해도 이보다 자주는 못 낳는다. */
    public static final double COOLDOWN_MIN_DAYS = 1.2;
    /** 쿨다운 상한 — 아무리 약해도 이보다 드물지는 않다. */
    public static final double COOLDOWN_MAX_DAYS = 5.0;

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
