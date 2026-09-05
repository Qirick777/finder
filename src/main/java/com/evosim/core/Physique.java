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
    /** 힘/약 등급당 소모 ± — 0.04 → <b>0.05</b>. 큰 몸은 많이 먹는다. */
    private static final double APPETITE_PER = 0.05;
    /**
     * 튼튼/빈약 등급당 소모 ± — <b>신설</b>.
     *
     * <p>종전에 튼튼함은 체력 +5%/등급을 <b>대가 없이</b> 받는 유일한 신체 특성이었다. 힘셈은
     * 소모를 물고 재빠름은 (활력과 달리) 얻는 것이 이동뿐인데, 튼튼만 순이득이라 신체 3칸의
     * 무난한 정답이 되어 있었다. 몸집이 크면 먹는 것도 는다 — 힘셈(5%)보다 낮게 둔 것은
     * 체력 이득이 공격력 이득보다 작기 때문이다.
     */
    private static final double TOUGH_APPETITE = 0.03;
    private static final double BRUTISH_UP = 0.06;    // 단순무식 등급당 공격 +(힘센 8%보다 낮게)
    private static final double REFINED_DOWN = 0.04;  // 섬세 등급당 공격 −
    /** 단순무식 등급당 소모 + — 힘센의 <b>절반</b>이라는 관계를 유지한다(4%→5% 인상에 맞춰
     *  2%→2.5%). 같은 비율이면 Ⅴ등급에서 시혜 1유닛/일에 붙어 구걸에 닿아도 굶어 죽는다. */
    private static final double BRUTISH_APPETITE = APPETITE_PER / 2.0;
    /** 활력/무기력 등급당 이동 속도 ± — 재빠름(3%)의 절반. 자기 힘만으로는
     *  {@link #VITALITY_GATE} 를 못 넘는다(Ⅴ 라도 1.075). */
    private static final double VITALITY_SPEED_PER = 0.015;
    /** 활력/무기력 등급당 행동 쿨다운 ∓. */
    private static final double VITALITY_COOL_PER = 0.03;
    /** 활력 등급당 소모 + · 무기력 등급당 소모 − — 활력 <b>단독을 적자로 묶는</b> 값이다.
     *  Ⅴ 에서 소모 +15% 인데 노동창 확대는 +10.7% 라, 재빠름이 붙어 쿨다운 게이트가 열리기
     *  전까지는 손해다. 튼튼함처럼 대가 없는 순이득이면 신체 3칸의 지배적 선택이 된다. */
    private static final double VITALITY_APPETITE_PER = 0.03;
    /**
     * 활력의 쿨다운 게이트 — 이동 배율이 이 값을 넘어야 손놀림이 열린다.
     *
     * <p>1.10 → <b>1.20</b>. 1.10 이면 재빠름Ⅳ + 활력Ⅰ(1.12 × 1.015 = 1.137)만으로 열려,
     * 야생 인구의 1~2%가 쿨다운 ×0.85 를 공짜로 얻었다 — 인구 계측에서 종합 상위 5%가
     * 기준선보다 2.0% 올라간 것이 이 누수였다. 1.20 이면 <b>양쪽 다 높은 등급</b>이라야 한다:
     * Ⅴ×Ⅴ 1.236 통과 · Ⅴ×Ⅳ 1.204 통과 · Ⅳ×Ⅴ 1.219 통과 · Ⅲ×Ⅲ 1.139 차단.
     */
    public static final double VITALITY_GATE = 1.20;
    /** 무기력의 대칭 게이트 — 이동이 이 아래로 떨어지면 손놀림까지 굳는다. 축 한쪽에만
     *  게이트를 달면 인구 평균이 그쪽으로 기운다(활력만 있고 무기력에 없던 것이 그 경우다). */
    public static final double LETHARGY_GATE = 1.0 / 1.20;
    /** 게이트가 열릴 때마다 곱해지는 쿨다운 감소(활력 게이트 · 상한 너머 능력 게이트). */
    private static final double GATE_COOL = 0.85;
    /** 무기력 게이트의 벌칙 — {@link #GATE_COOL} 의 역수(대칭). */
    private static final double GATE_COOL_PENALTY = 1.0 / 0.85;

    private Physique() {
    }

    /** 최대 체력 배수 — 튼튼(+)/빈약(−). */
    public static double toughness(Individual ind) {
        return factor(ind, Trait.TOUGH, TOUGH_PER, Trait.FRAIL, TOUGH_PER);
    }

    /** 이동 속도 배수 — 재빠름(+)/굼뜸(−) × 활력(+)/무기력(−). 두 축이 곱해져야
     *  {@link #VITALITY_GATE} 를 넘는다(재빠름Ⅴ 1.15 × 활력Ⅴ 1.075 = 1.236). */
    public static double agility(Individual ind) {
        return factor(ind, Trait.NIMBLE, AGILITY_PER, Trait.SLUGGISH, AGILITY_PER)
                * factor(ind, Trait.VIGOROUS, VITALITY_SPEED_PER, Trait.LISTLESS, VITALITY_SPEED_PER);
    }

    /**
     * 활력의 쿨다운 게이트가 열렸는가 — 이동 배율이 {@link #VITALITY_GATE} 이상인가.
     *
     * <p>활력은 <b>제 힘으로 이 문을 못 연다</b>(Ⅴ 라도 1.075). 재빠름이 열어 줘야 비로소
     * 손놀림이 붙는다 — 그 전까지 활력은 소모만 먹는 짐이다.
     */
    public static boolean vitalityGateOpen(Individual ind) {
        return grade(ind, Trait.VIGOROUS) > 0 && agility(ind) >= VITALITY_GATE;
    }

    /** 무기력의 대칭 게이트 — 굼뜬 몸에 무기력까지 겹치면 손놀림이 한 번 더 굳는다. */
    public static boolean lethargyGateOpen(Individual ind) {
        return grade(ind, Trait.LISTLESS) > 0 && agility(ind) <= LETHARGY_GATE;
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
                * factor(ind, Trait.TOUGH, TOUGH_APPETITE, Trait.FRAIL, TOUGH_APPETITE)
                * factor(ind, Trait.BRUTISH, BRUTISH_APPETITE, Trait.REFINED, BRUTISH_APPETITE)
                * factor(ind, Trait.VIGOROUS, VITALITY_APPETITE_PER,
                         Trait.LISTLESS, VITALITY_APPETITE_PER);
    }

    /**
     * 행동 쿨다운 배수(채집 간격·타격 간격) — <b>게이트 사슬</b>이다.
     *
     * <pre>
     *   재빠름 −4%/등급 · 굼뜸 +4%/등급                       Ⅴ → ×0.80
     *   × 활력 −3%/등급 · 무기력 +3%/등급                     Ⅴ → ×0.85
     *   × 0.85   이동 배율 ≥ 1.10 (활력 게이트 — 재빠름이 연다)
     *   × 0.85   위가 열린 <b>데다</b> 상한 너머(실효 Ⅵ+) 능력 보유
     * </pre>
     *
     * <p>마지막 칸에 활력 게이트를 함께 건 이유: 눈썰미가 아무리 좋아도 <b>손이 느리면</b> 그
     * 표적이 회전으로 바뀌지 않는다. 반경으로 조건을 걸었더니 피공포(+0.5) 하나로 문턱에 닿아
     * 평민이 공짜로 통과했다 — 등급으로 걸어야 유능함 없이는 절대 안 열린다.
     *
     * <p>전부 열리면 0.80×0.85×0.85×0.85 = <b>0.491</b>(행동량 ×2.04).
     */
    public static double actionCooldown(Individual ind) {
        double m = factorDown(ind, Trait.NIMBLE, 0.04, Trait.SLUGGISH, 0.04)
                * factorDown(ind, Trait.VIGOROUS, VITALITY_COOL_PER,
                             Trait.LISTLESS, VITALITY_COOL_PER);
        if (vitalityGateOpen(ind)) {
            m *= GATE_COOL;
            if (Multipliers.hasSuperGrade(ind)) {
                m *= GATE_COOL;
            }
        } else if (lethargyGateOpen(ind)) {
            m *= GATE_COOL_PENALTY;
        }
        return Math.max(0.3, m);
    }

    /** {@link #factor} 의 부호 반전판 — 양(+) 특성이 값을 <b>내리는</b> 축(쿨다운)용. */
    private static double factorDown(Individual ind, Trait down, double downPer,
                                     Trait up, double upPer) {
        int g = grade(ind, down);
        if (g > 0) {
            return 1.0 - downPer * g;
        }
        g = grade(ind, up);
        if (g > 0) {
            return 1.0 + upPer * g;
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
        Trait.FARSIGHTED, Trait.GOOD_SPATIAL, Trait.HARDY, Trait.VIGOROUS,
    };
    // 명석은 여기 넣지 않는다 — 단련은 <b>몸</b>을 벼리는 촉매다. 게다가 단련이 명석 Ⅳ를 Ⅴ로
    // 밀면 배회 노동(노동창 +57%)이 열려, 평민을 안 올린다는 이번 개편의 전제가 깨진다.

    /**
     * <b>신체 등급 — 단련·쇠약을 반영한 실효 등급.</b>
     *
     * <p>안목(깜냥·무딤)이 능력 축에 하는 일을 신체 축에 한다. 종전에는 능력 쪽에만 촉매가
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
