package com.evosim.core;

/**
 * 전투 3층위 판정 (설계서 §13-B). 우선순위 + 플래그 하나로 충분 — 상태기계 불필요(§18).
 *
 * <p>순수 함수: 마크 goal 은 이 결정을 호출해 실제 타겟/도망만 실행한다. 서로 다른 축이라 조합 가능
 * (예: 겁쟁이+무모 → 겁쟁이가 진입을 막아 무모 발동 안 함).
 */
public final class Combat {

    /** 퇴각 시작 체력 하한 (밸런싱 초기값 §). */
    public static final double RETREAT_HP = 0.30;
    /** 신중 복귀 체력 상한. */
    public static final double RETURN_HP = 0.70;

    /** ① 진입 결정. */
    public enum Entry { ENGAGE, FLEE, IGNORE }

    /** ② 퇴각 결정. */
    public enum Retreat { HOLD, RETREAT }

    private Combat() {
    }

    /**
     * ① 진입 (싸우냐/도망/무시). 겁쟁이=회피, 용감=적극(감지 범위 내 처치), 중립=온 것(인접)만.
     */
    public static Entry entry(Individual ind, boolean monsterAdjacent, boolean monsterInDetection) {
        if (isExpr(ind, Trait.COWARD)) {
            return Entry.FLEE;
        }
        if (isExpr(ind, Trait.BRAVE)) {
            return monsterInDetection ? Entry.ENGAGE : Entry.IGNORE;
        }
        return monsterAdjacent ? Entry.ENGAGE : Entry.IGNORE; // 중립: 온 것만
    }

    /**
     * ② 퇴각 (싸우다 물러나냐). 무모=안 물러남, 신중·중립=체력 하한 이하면 퇴각,
     * 단 신중은 가족 근처면 안 물러남.
     */
    public static Retreat retreat(Individual ind, double hpFraction, boolean nearFamily) {
        if (isExpr(ind, Trait.RECKLESS)) {
            return Retreat.HOLD;
        }
        if (hpFraction <= retreatHp(ind)) {
            if (isExpr(ind, Trait.PRUDENT) && nearFamily) {
                return Retreat.HOLD;
            }
            return Retreat.RETREAT;
        }
        return Retreat.HOLD;
    }

    /**
     * 이 개체의 퇴각선 — 비관은 높고(일찍 포기) 낙관은 낮다(더 버틴다).
     *
     * <p>용기 축(겁쟁이/용감)과 겹치지 않는다: 그쪽은 <b>진입</b>을 정하고 이쪽은 <b>지속</b>을
     * 정한다. 용감한 비관론자는 겁 없이 들어갔다가 일찍 물러나고, 겁 많은 낙관론자는 좀처럼
     * 들어가지 않되 한 번 붙으면 끝까지 간다.
     */
    public static double retreatHp(Individual ind) {
        if (isExpr(ind, Trait.PESSIMIST)) {
            return RETREAT_HP + EXPECTATION_HP;
        }
        if (isExpr(ind, Trait.OPTIMIST)) {
            return Math.max(0.05, RETREAT_HP - EXPECTATION_HP);
        }
        return RETREAT_HP;
    }

    /** 기대 축이 퇴각선을 미는 폭 — 0.30 기준으로 비관 0.45 · 낙관 0.15. */
    public static final double EXPECTATION_HP = 0.15;

    /** ③ 복귀 (다시 오냐). 신중만 체력 상한 회복 시 복귀(치고 빠지기), 중립은 복귀 안 함. */
    public static boolean returnsToCombat(Individual ind, double hpFraction) {
        return isExpr(ind, Trait.PRUDENT) && hpFraction >= RETURN_HP;
    }

    /** 몬스터 발각(감지) 범위 — 용감 넓게·겁쟁이 좁게, 대담 +·조심 - (밸런싱 초기값). */
    public static double detectionRange(Individual ind) {
        double r = 8.0;
        if (isExpr(ind, Trait.BRAVE)) {
            r += 6.0;
        }
        if (isExpr(ind, Trait.COWARD)) {
            r -= 3.0;
        }
        if (isExpr(ind, Trait.BOLD)) {
            r += 4.0;
        }
        if (isExpr(ind, Trait.CAUTIOUS)) {
            r -= 3.0;
        }
        if (isExpr(ind, Trait.SCATTERED)) {
            r += 4.0; // 산만 — 한 곳에 못 붙어 있는 눈이 <b>경계</b>에서는 값어치가 된다.
            // 이것이 산만의 군인 값어치다: 완력이 아니라 초병으로 뽑힌다.
        }
        if (isExpr(ind, Trait.BLOOD_FEARFUL)) {
            r += 3.0; // 피공포 — 피를 무서워해 위협을 멀리서 경계(사냥 ×0.5의 반대급부). 겁쟁이 −3과 상쇄.
        }
        // 시야 등급(천리안/근시안)이 감지 범위를 등급 비례로 늘리고 줄인다(설계서 §14).
        r *= Physique.vision(ind);
        return Math.max(2.0, r);
    }

    /**
     * 좀비 유인(어그로) 반경 배율 — 조심성은 몸을 사려 눈에 덜 띈다(−25%, 감지 −3의 반대급부).
     * 표현층 attractZombies 가 원거리·근접 두 반경 모두에 곱한다(2계층 일관).
     */
    public static double aggroRangeMult(Individual ind) {
        return isExpr(ind, Trait.CAUTIOUS) ? 0.75 : 1.0;
    }

    private static boolean isExpr(Individual ind, Trait t) {
        return ExpressionResolver.isExpressed(ind, t);
    }
}
