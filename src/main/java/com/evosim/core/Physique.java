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
 *   <li><b>힘센/약함</b> → 공격력 (힘센 +8%/등급, 약함 −6%/등급) + 소모 (±4%/등급 — 균형추)</li>
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
    private static final double STRENGTH_UP = 0.08;   // 힘센 등급당 공격 +
    private static final double STRENGTH_DOWN = 0.06; // 약함 등급당 공격 −
    private static final double APPETITE_PER = 0.04;  // 힘/약 등급당 소모 ±(V등급 = 종전 고정 ×1.2/×0.8)
    private static final double BRUTISH_UP = 0.06;    // 단순무식 등급당 공격 +(힘센 8%보다 낮게)
    private static final double REFINED_DOWN = 0.04;  // 섬세 등급당 공격 −
    /** 단순무식 등급당 소모 + — 힘센(4%)의 <b>절반</b>이다. 같은 비율이면 Ⅴ등급에서 하루
     *  소모가 0.98 이 되어 시혜 1유닛/일에 붙는다: 구걸에 닿아도 굶어 죽는다. 2% 면 0.90. */
    private static final double BRUTISH_APPETITE = 0.02;

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

    /** 공격력 배수 — 힘센(+8%/등급)/약함(−6%/등급). "잘 싸우지만 많이 먹는" 트레이드오프의 효과 쪽. */
    public static double strength(Individual ind) {
        // 세 축이 곱해진다 — 힘/약(신체) × 단순무식/섬세(조야) × 야성(결손 보상).
        // 야성은 결손이 0 이면 정확히 1.0 이라, 멀쩡한 자에게는 아무 일도 안 일어난다.
        return factor(ind, Trait.STRONG, STRENGTH_UP, Trait.WEAK, STRENGTH_DOWN)
                * factor(ind, Trait.BRUTISH, BRUTISH_UP, Trait.REFINED, REFINED_DOWN)
                * Multipliers.feralStrength(ind);
    }

    /**
     * <b>무장을 뺀 맨몸 완력 배수</b> — 군인 선발이 쓴다.
     *
     * <p>{@link #strength} 와 같은 값이지만 이름으로 의도를 못박는다. 철검이 공격력 +5 를
     * 얹으므로(기본 2.0 → 7.0) 무장 뒤 값으로 재면 힘 특성 차이가 10% 안으로 묻힌다 —
     * 단순무식·야성의 트레이드오프가 선발에서 사라진다.
     */
    public static double barehandMight(Individual ind) {
        return strength(ind);
    }

    /** 소모(식욕) 배수 — 힘셀수록 많이(+4%/등급)·약할수록 적게(−4%/등급) 먹는다(균형추 쪽). */
    public static double appetite(Individual ind) {
        // 야성은 여기 안 얹는다 — 대가는 이미 증폭된 결손으로 치렀다(Multipliers.feralStrength).
        return factor(ind, Trait.STRONG, APPETITE_PER, Trait.WEAK, APPETITE_PER)
                * factor(ind, Trait.BRUTISH, BRUTISH_APPETITE, Trait.REFINED, BRUTISH_APPETITE);
    }

    /** 행동 쿨다운 배수(채집 간격·타격 간격) — 재빠름 −4%/등급·굼뜸 +4%/등급(부수 효과:
     *  이동 속도에 더해 손놀림도 빠르다). Ⅴ = 쿨다운 ×0.8(행동량 +25%). 성장 가속 패키지. */
    public static double actionCooldown(Individual ind) {
        int g = grade(ind, Trait.NIMBLE);
        if (g > 0) {
            return Math.max(0.5, 1.0 - 0.04 * g);
        }
        g = grade(ind, Trait.SLUGGISH);
        if (g > 0) {
            return 1.0 + 0.04 * g;
        }
        return 1.0;
    }

    /**
     * 양수 특성이 발동하면 {@code 1 + upPer·등급}, 음수 특성이면 {@code 1 − downPer·등급}(하한 0.1), 둘 다
     * 없으면 1.0. 축이 반발이라 동시 발동은 없다.
     */
    private static double factor(Individual ind, Trait up, double upPer, Trait down, double downPer) {
        int g = grade(ind, up);
        if (g > 0) {
            return 1.0 + upPer * g;
        }
        g = grade(ind, down);
        if (g > 0) {
            return Math.max(0.1, 1.0 - downPer * g);
        }
        return 1.0;
    }

    /** 신체 양(+) 특성 — 단련이 미는 대상. 음(−)은 밀지 않는다(단련이 약함을 키우면 안 된다). */
    private static final Trait[] PHYSICAL_UP = {
        Trait.STRONG, Trait.TOUGH, Trait.NIMBLE,
        Trait.FARSIGHTED, Trait.GOOD_SPATIAL, Trait.HARDY,
    };

    /**
     * <b>신체 등급 — 단련·쇠약을 반영한 실효 등급.</b>
     *
     * <p>안목(눈썰미·무딤)이 능력 축에 하는 일을 신체 축에 한다. 종전에는 능력 쪽에만 촉매가
     * 있고 신체 쪽은 비어 있었다.
     *
     * <p><b>최고 등급 하나만</b> 민다({@link Multipliers#manageAbilityGrade} 가 최고 등급
     * 하나만 보는 것과 대칭). 보유한 신체 특성 전부를 올리면 특성 셋 가진 자가 셋 다 이득이라
     * 촉매치고 너무 세다.
     *
     * <p>음(−) 특성은 밀지 않는다 — 단련이 약함·굼뜸을 키우거나 쇠약이 그것을 덜어 주면
     * 방향이 뒤집힌다. 단련은 <b>가진 강점 하나를 벼리는 것</b>이다.
     */
    public static int grade(Individual ind, Trait trait) {
        int g = ExpressionResolver.expressedGrade(ind, trait);
        if (g <= 0) {
            return g;
        }
        boolean up = false;
        for (Trait t : PHYSICAL_UP) {
            if (t == trait) {
                up = true;
                break;
            }
        }
        if (!up) {
            return g; // 음(−) 신체 특성 — 촉매 대상이 아니다
        }
        int best = 0;
        Trait bestTrait = null;
        for (Trait t : PHYSICAL_UP) {
            int tg = ExpressionResolver.expressedGrade(ind, t);
            if (tg > best) {
                best = tg;
                bestTrait = t;
            }
        }
        if (bestTrait != trait) {
            return g; // 최고가 아닌 특성 — 그대로
        }
        if (ExpressionResolver.isExpressed(ind, Trait.CONDITIONED)) {
            return Math.min(5, g + 1);
        }
        if (ExpressionResolver.isExpressed(ind, Trait.DECONDITIONED)) {
            return Math.max(0, g - 1);
        }
        return g;
    }
}
