package com.evosim.core;

/**
 * 생애단계 능력 + 성별 페널티 (설계서 §7 생애주기, §1 여성 페널티). 순수 규칙 — 엔티티가 이걸 읽어
 * 이동속도·전투 가능 여부·채집 가능 여부를 정한다.
 *
 * <ul>
 *   <li>유아: 스스로 못 움직임(매우 느림)·전투 불가·자가 섭취 불가·무방비.</li>
 *   <li>소년: 성인보다 느림·채취 X(만혼이면 채집 가능 1.2배)·전투 불가.</li>
 *   <li>성년: 채집·사냥·번식·전투 가능.</li>
 *   <li>여성: 신체능력 60% 약함(배율 0.4 — 힘/튼튼 보너스도 동일 배율) → 남성 보호 없으면 교전 필패.</li>
 * </ul>
 */
public final class SurvivalRules {

    /** 여성 신체 배율 — 60% 약함 (설계서 §1, 밸런싱: 홀로는 좀비도 못 이김). */
    public static final double FEMALE_PHYSICAL_FACTOR = 0.4;
    /** 만혼 소년 채집 배율 (설계서 §7). */
    public static final double LATE_MARRIAGE_BOY_GATHER = 1.2;

    private SurvivalRules() {
    }

    /** 전투 가능? 청년·노년 (설계서 §7 — 노년은 신체 페널티만, 완전 무방비는 아님). */
    public static boolean canFight(LifeStage stage) {
        return stage == LifeStage.ADULT || stage == LifeStage.ELDER;
    }

    /**
     * 성장 기간 배율 (혼기·모성애 축). 조혼 소년은 20% 빨리 성년(조기 혼인),
     * 어미가 모성애강함(+1)이면 자식 성장 ×1.25(품에 오래) / 모성애없음(−1)이면 ×0.8(빨리 독립).
     * 표현층 growthTick이 단계 임계에 곱한다 — 순수라 evotest로 값 대조.
     */
    public static double growthMult(LifeStage stage, Individual ind, int maternalCare) {
        double m = 1.0;
        if (stage == LifeStage.BOY && ExpressionResolver.isExpressed(ind, Trait.EARLY_MARRIAGE)) {
            m *= 0.8;
        }
        if ((stage == LifeStage.INFANT || stage == LifeStage.BOY) && maternalCare != 0) {
            m *= maternalCare > 0 ? 1.25 : 0.8; // 자식 단계에만 — 노년 진입에 어미 배율 누출 방지
        }
        return m;
    }

    /** 채집 가능? 성년, 또는 만혼 발현 소년 (설계서 §7 소년기 채집). */
    public static boolean canGather(LifeStage stage, Individual ind) {
        if (stage == LifeStage.ADULT || stage == LifeStage.ELDER) {
            return true; // 노년도 채집 가능(수확 ×0.5·쿼터 노동은 표현층)
        }
        return stage == LifeStage.BOY && ExpressionResolver.isExpressed(ind, Trait.LATE_MARRIAGE);
    }

    /** 자가 섭취 가능? 유아는 불가(성인이 먹여야 함, 설계서 §7). */
    public static boolean canSelfFeed(LifeStage stage) {
        return stage != LifeStage.INFANT;
    }

    /** 스스로 이동 가능? 유아는 사실상 불가(매우 느림, 설계서 §7). */
    public static boolean canMoveSelf(LifeStage stage) {
        return stage != LifeStage.INFANT;
    }

    /** 생애단계 이동속도 배율 — 유아 거의 정지, 소년 느림, 청년 기본, 노년 노쇠. */
    public static double moveSpeedFactor(LifeStage stage) {
        return switch (stage) {
            case INFANT -> 0.05;
            case BOY -> 0.6;
            case ADULT -> 1.0;
            case ELDER -> Elder.SPEED_MULT; // 0.8
        };
    }

    /** 성별 신체 배율 — 여성 0.6, 남성 1.0 (전투 힘/체력에 적용). */
    public static double physicalFactor(Sex sex) {
        return sex == Sex.FEMALE ? FEMALE_PHYSICAL_FACTOR : 1.0;
    }
}
