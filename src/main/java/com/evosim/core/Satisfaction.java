package com.evosim.core;

/**
 * 만족 규칙 (계층화의 심리 엔진, v2.3 §D). <b>기본값은 만족</b> — 잉여(저장고+밭계정)가
 * 하루소모 × comfortDays × σ(2.0)를 넘으면 잉여 활동(밭 노동·확장·개간)을 멈춘다. 재개는
 * 기준의 0.8배 미만(히스테리시스 — 경계 진동 차단 F18). 동기 특성만이 이 정지를 무력화해
 * "멈추지 않는 소수"가 계층을 만든다. 순수 — {@code /evotest satisfaction}.
 */
public final class Satisfaction {

    /** 만족 계수(기본) — 넉넉선(comfortDays)의 배수. */
    public static final double SIGMA_BASE = 2.0;
    /** 안분지족·무욕의 계수 — 일찍 만족. */
    public static final double SIGMA_CONTENT = 1.0;
    /** 재개 히스테리시스 — 만족 중엔 기준 × 이 값 미만으로 떨어져야 재개. */
    public static final double RESUME_FACTOR = 0.8;
    /** 야망가의 만족 기준 — 소유 밭 타일 합이 이 값 이상이어야(대지주 규모 T5). */
    public static final int AMBITION_TILE_GOAL = 49;

    private Satisfaction() {
    }

    /**
     * 만족 판정. wealth = 가구 저장고 + 소유 밭 계정 합. maxNeighborWealth = 인지 범위 내
     * 타 가구 최대 잉여(경쟁용). farmTiles = 소유 밭 타일 합(야망용). wasSatisfied = 직전
     * 상태(히스테리시스).
     */
    public static boolean satisfied(Individual ind, double dailyNeed, double wealth,
                                    double maxNeighborWealth, int farmTiles, boolean wasSatisfied) {
        var t = ExpressionResolver.expressedTraits(ind);
        if (t.contains(Trait.GREEDY) || t.contains(Trait.DILIGENT)) {
            return false; // 욕심: 만족 불가 / 부지런: 만족 개념 무시(습관 노동)
        }
        if (t.contains(Trait.COMPETITIVE) && wealth <= maxNeighborWealth) {
            return false; // 경쟁: 이웃을 넘어설 때까지
        }
        if (t.contains(Trait.AMBITIOUS) && farmTiles < AMBITION_TILE_GOAL) {
            return false; // 야망가: 부가 아니라 자산(밭)이 기준
        }
        double sigma = t.contains(Trait.CONTENT) || t.contains(Trait.ASCETIC)
                ? SIGMA_CONTENT : SIGMA_BASE;
        double bar = dailyNeed * FoodEconomy.comfortDays(ind) * sigma;
        return wealth > (wasSatisfied ? bar * RESUME_FACTOR : bar);
    }

    /** 밭 확장·개간을 아예 안 하는가 — 무욕(잉여는 나눔·생계로만). */
    public static boolean neverExpands(Individual ind) {
        return ExpressionResolver.isExpressed(ind, Trait.ASCETIC);
    }
}
