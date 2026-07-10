package com.evosim.core;

/**
 * 신체 스칼라 능력 배수 (설계서 §14 신체 등급). 발동 중인 등급 특성(I~V)의 강도에 비례해 능력을 조정한다.
 *
 * <p>{@link Multipliers}(자원·매력)와 같은 순수-가산 패턴: 능력 하나당 함수 하나, 발동 특성의 등급만 읽어
 * 배수를 낸다. 축은 반발(exclusive)이라 각 축에서 양수(튼튼)·음수(빈약) 중 하나만 발동한다.
 *
 * <ul>
 *   <li><b>튼튼/빈약</b> → 최대 체력 (등급당 ±5%)</li>
 *   <li><b>재빠름/굼뜸</b> → 이동 속도 (등급당 ±3%)</li>
 *   <li><b>천리안/근시안</b> → 감지 범위 (천리안 +8%/등급, 근시안 −6%/등급)</li>
 *   <li><b>강건/병약</b> → 회복(재생) (강건 +30%/등급, 병약 −15%/등급)</li>
 * </ul>
 *
 * <p>등급이 없으면(무발동) 배수 1.0(중립). 헤드리스 {@code /evotest physique}로 등급별 값을 손계산 대조.
 */
public final class Physique {

    private static final double TOUGH_PER = 0.05;     // 튼튼/빈약 등급당 체력 ±
    private static final double AGILITY_PER = 0.03;   // 재빠름/굼뜸 등급당 속도 ±
    private static final double VISION_UP = 0.08;     // 천리안 등급당 감지 +
    private static final double VISION_DOWN = 0.06;   // 근시안 등급당 감지 −
    private static final double RECOVERY_UP = 0.30;   // 강건 등급당 재생 +
    private static final double RECOVERY_DOWN = 0.15; // 병약 등급당 재생 −

    private Physique() {
    }

    /** 최대 체력 배수 — 튼튼(+)/빈약(−). */
    public static double toughness(Individual ind) {
        return factor(ind, Trait.TOUGH, TOUGH_PER, Trait.FRAIL, TOUGH_PER);
    }

    /** 이동 속도 배수 — 재빠름(+)/굼뜸(−). */
    public static double agility(Individual ind) {
        return factor(ind, Trait.NIMBLE, AGILITY_PER, Trait.SLUGGISH, AGILITY_PER);
    }

    /** 감지 범위 배수 — 천리안(+)/근시안(−). */
    public static double vision(Individual ind) {
        return factor(ind, Trait.FARSIGHTED, VISION_UP, Trait.NEARSIGHTED, VISION_DOWN);
    }

    /** 재생(회복) 배수 — 강건(+)/병약(−). 1.0 = 기본 재생. */
    public static double recovery(Individual ind) {
        return factor(ind, Trait.HARDY, RECOVERY_UP, Trait.SICKLY, RECOVERY_DOWN);
    }

    /**
     * 양수 특성이 발동하면 {@code 1 + upPer·등급}, 음수 특성이면 {@code 1 − downPer·등급}(하한 0.1), 둘 다
     * 없으면 1.0. 축이 반발이라 동시 발동은 없다.
     */
    private static double factor(Individual ind, Trait up, double upPer, Trait down, double downPer) {
        int g = ExpressionResolver.expressedGrade(ind, up);
        if (g > 0) {
            return 1.0 + upPer * g;
        }
        g = ExpressionResolver.expressedGrade(ind, down);
        if (g > 0) {
            return Math.max(0.1, 1.0 - downPer * g);
        }
        return 1.0;
    }
}
