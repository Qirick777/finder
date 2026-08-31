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
    /** 자수성가(보조)의 계수 — 부유해져도 늦게 만족. 2.0→3.5: 무동기 부부의 만족선을
     *  12~17에서 21~30으로 올려 착공 임계(30) 위로 보낸다. 런 실측에서 저장고 39를 쌓고도
     *  만족 상태라 착공이 봉쇄된 가구가 관측됐고(야생 착공률 0/3), 그 병목을 푸는 값이다.
     *  <b>가난한 가구는 만족선 근처에 못 가므로 이 값의 영향을 전혀 받지 않는다</b> — 보조
     *  특성의 요건(단독 효과 ≈ 0, 조합 시에만 발동)을 만족한다. */
    public static final double SIGMA_SELF_MADE = 3.5;
    /** 안분(보조)의 계수 — 기본보다 이르게 만족(축적이 착공 임계에 못 닿는다). */
    public static final double SIGMA_MODEST = 1.4;
    /** 재개 히스테리시스 — 만족 중엔 기준 × 이 값 미만으로 떨어져야 재개. */
    public static final double RESUME_FACTOR = 0.8;
    /** 야망가의 만족 기준 — 소유 밭 타일 합이 이 값 이상이어야(대지주 규모 T5). */
    public static final int AMBITION_TILE_GOAL = 54; // 49→54: 5단계 경계(덩어리 도면)

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
        double bar = bar(ind, dailyNeed);
        return wealth > (wasSatisfied ? bar * RESUME_FACTOR : bar);
    }

    /**
     * 만족선 그 자체 — 판정과 <b>같은 식</b>을 계측이 읽게 한다. 계측이 σ 선택을 따로 베껴
     * 두면 언젠가 한쪽만 고쳐져 보고가 조용히 거짓이 된다.
     *
     * <p>σ 우선순위: 안분지족·무욕(기존 성향) &gt; 보조 축(자수성가/안분) &gt; 기본. 기존 성향을
     * 앞세우는 것은 보조가 <b>주(主)를 뒤집지 않는다</b>는 원칙 — 보조는 아무 성향도 없을 때의
     * 기본값만 좌우한다.
     */
    public static double bar(Individual ind, double dailyNeed) {
        var t = ExpressionResolver.expressedTraits(ind);
        double sigma;
        if (t.contains(Trait.CONTENT) || t.contains(Trait.ASCETIC)) {
            sigma = SIGMA_CONTENT;
        } else if (t.contains(Trait.SELF_MADE)) {
            sigma = SIGMA_SELF_MADE;
        } else if (t.contains(Trait.MODEST)) {
            sigma = SIGMA_MODEST;
        } else {
            sigma = SIGMA_BASE;
        }
        return dailyNeed * FoodEconomy.comfortDays(ind) * sigma * aspiration(ind);
    }

    /**
     * <b>능력이 만족선을 끌어올린다</b> — 같은 곳간을 보고도 잘 버는 자에게는 며칠치가 안 된다.
     *
     * <p>이것이 신분 사다리의 능력 축이다. 종전에는 만족을 뚫는 길이 <b>특성뿐</b>이었다
     * (욕심·부지런·경쟁·야망). 그래서 능력이 아무리 좋아도 야망이 없으면 영원히 소작이고,
     * 능력이 없어도 욕심만 있으면 착공했다 — 능력이 신분에 <b>한 톨도</b> 관여하지 않았다.
     * 기준을 벌이에 비례시키면 사다리가 수치에서 저절로 선다(1자녀 가구·착공 임계 32.7 기준):
     *
     * <pre>
     *   0.8 멍청·곰손 → 만족선 27.6(바닥 1.0) → 임계 30 아래, 게다가 소득이 낮아 매인다  농노
     *   1.0 평범      → 27.6                  → 임계 아래                              소작
     *   1.26 약초Ⅱ   → 42.0                  → <b>임계 돌파</b>(밭 12칸 상한)           자영농
     *   2.16 엘리트   → 91.6 · 야망은 만족 자체를 무시                                  지배자
     * </pre>
     * (눈높이 = 1 + {@link #ASPIRATION_GAIN}×(능력−1) 이므로 1.26 → 1.52, 2.16 → 3.32.)
     *
     * <p>엘리트 2.16 = 1 + 1.4(명석 증폭)×0.65(약초Ⅴ) + 0.1(명석 기본) + 0.15(야망 몰입).
     * 실측 대조: 개간 로그의 {@code G2.59} = 0.8(수확계수) × 1.5(남성) × 2.16 — 일치.
     *
     * <p><b>성별을 뺀</b> {@link Multipliers#gather} 를 쓴다. {@code forageYieldMult} 는 남 1.5 /
     * 여 0.5 를 품고 있어, 그걸 쓰면 사다리가 능력제가 아니라 <b>성별 카스트</b>가 된다.
     *
     * <p><b>1.0 아래로는 내리지 않는다</b>(위로만 곱한다). 무능력자의 만족선까지 끌어내리면
     * "소작이 임금 며칠에 만족 진입 → 노동 정지 → 지대 고갈"이 되살아난다 — 런3·5·6 에서
     * <b>세 번</b> 실측된 실패다. 사다리의 분리는 착공 임계 쪽({@link FarmEconomy#foundReserve}
     * 계수 3.0)이 만들고, 능력은 그 올라간 임계를 <b>넘는 데에만</b> 쓴다. 바닥층(농노)은 이
     * 기준이 아니라 소득으로 갈린다 — 못 버는 자는 신세를 져 매이고, 매인 무토지가 곧 천민이다
     * ({@code SocialRank}).
     */
    public static double aspiration(Individual ind) {
        return Math.max(1.0, 1.0 + ASPIRATION_GAIN * (Multipliers.gather(ind) - 1.0));
    }

    /**
     * 능력이 만족선을 미는 <b>세기</b> — 2.0.
     *
     * <p>1.0(능력을 그대로 곱함)으로는 부족하다. 능력의 실효 폭은 평민 구간에서 1.0~1.3 인데,
     * 같은 식에 든 σ 는 1.0~3.5, comfortDays 는 1.0~3.0 이라 <b>능력 신호가 특성 신호에 묻힌다</b>
     * — w8 실측에서 무능 구간의 만족선(19~21)이 평범 구간(30~31)보다 낮게 나온 것이 그 증거다
     * (특성이 만든 차이). 2.0 이면 능력 1.26 이 눈높이 1.52 가 되어 특성과 견줄 수 있다.
     *
     * <p>검산(1자녀 가구·착공 임계 30): 평범 27.6 → 봉인 · 유능(1.26) 42.0 → 돌파 ·
     * 엘리트(2.16) 91.6 → 돌파. 무능은 바닥 1.0 에 걸려 평범과 같은 27.6 이고, 바닥층은
     * 이 기준이 아니라 소득으로 갈린다.
     */
    public static final double ASPIRATION_GAIN = 2.0;

    /** 밭 확장·개간을 아예 안 하는가 — 무욕(잉여는 나눔·생계로만). */
    public static boolean neverExpands(Individual ind) {
        return ExpressionResolver.isExpressed(ind, Trait.ASCETIC);
    }
}
