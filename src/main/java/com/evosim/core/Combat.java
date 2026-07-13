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
        if (hpFraction <= RETREAT_HP) {
            if (isExpr(ind, Trait.PRUDENT) && nearFamily) {
                return Retreat.HOLD;
            }
            return Retreat.RETREAT;
        }
        return Retreat.HOLD;
    }

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
        if (isExpr(ind, Trait.BLOOD_FEARFUL)) {
            r += 3.0; // 피공포 — 피를 무서워해 위협을 멀리서 경계(사냥 ×0.5의 반대급부). 겁쟁이 −3과 상쇄.
        }
        // 시야 등급(천리안/근시안)이 감지 범위를 등급 비례로 늘리고 줄인다(설계서 §14).
        r *= Physique.vision(ind);
        return Math.max(2.0, r);
    }

    private static boolean isExpr(Individual ind, Trait t) {
        return ExpressionResolver.isExpressed(ind, t);
    }
}
