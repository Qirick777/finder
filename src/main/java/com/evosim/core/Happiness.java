package com.evosim.core;

/**
 * <b>행복도</b> — 0.0~1.0 의 마음 상태. 굶주림이 곧바로 몸을 깎던 자리를 대신 받아 낸다.
 *
 * <p>종전에는 소지 식량이 바닥나고 유예를 넘기면 그 순간부터 체력이 깎여 죽었다. 즉사에 가까운
 * 계단이라 "굶주리며 버티는 삶"이 표현되지 않았다. 여기서는 굶주림이 먼저 이 값을 깎고,
 * 이 값이 {@link #PAIN} 아래로 내려간 뒤에야 몸이 상한다. 오래 굶으면 마음이 먼저 꺾인다.
 *
 * <p>꺾인 동안은 공짜가 아니다 — {@link #workMultiplier} 로 채집·수확이 둔해져 <b>더</b> 못 먹는다.
 * 가난이 스스로를 먹여 살리는 고리가 그 자리에서 닫힌다. 벗어나는 길은 배불리 먹는 것뿐이라,
 * 지주의 구휼이 실제로 사다리 노릇을 한다.
 *
 * <p>마인크래프트적으로는 배고픔이 체력 재생을 묶는 구조와 같다 — 굶으면 곧장 죽는 게 아니라
 * 회복이 멈추고 일이 안 되다가, 끝까지 가면 죽는다.
 */
public final class Happiness {

    private Happiness() {
    }

    /** 태어날 때의 기본값 — 특별히 좋지도 나쁘지도 않다. */
    public static final double BASE = 0.6;

    /** 이 아래로 내려가면 굶주림이 <b>몸</b>을 깎기 시작한다. 그 전까지는 마음만 깎인다. */
    public static final double PAIN = 0.15;

    /** 이 아래면 일손이 둔해진다(1단). */
    public static final double LOW = 0.30;

    /** 이 위로 올라오면 아이를 밭에서 거두고 학교로 돌려보낸다 — 굴레의 출구. */
    public static final double RECOVERED = 0.50;

    // ── 하루 변화량 ──────────────────────────────────────────────────────────────
    /** 그날 소지 식량이 바닥났다. */
    public static final double D_EMPTY = -0.12;
    /** 그 위에 실제로 굶주림 피해 단계까지 갔다. */
    public static final double D_STARVING = -0.08;
    /** 빚이 하루소모의 3배를 넘는다. */
    public static final double D_DEBT = -0.04;
    /** 예속 상태다. */
    public static final double D_BOUND = -0.02;
    /** 저장고가 가구 하루소모 이상이다. */
    public static final double D_FED = 0.06;
    /** 저장고가 하루소모의 두 배 이상이다(D_FED 를 <b>대신</b>한다). */
    public static final double D_FULL = 0.10;
    /** 집에서 배우자와 함께 잤다. */
    public static final double D_SPOUSE = 0.03;
    /** 쉼터에서 쉬었다. */
    public static final double D_REST = 0.02;

    /** 빚이 이 배수를 넘으면 마음을 누른다. */
    public static final double DEBT_DAYS = 3.0;

    /**
     * 하루치 변화량을 특성으로 조정해 더한 새 행복도.
     *
     * <p>깎는 쪽과 채우는 쪽에 각각 다른 특성이 붙는다. <b>안분지족·무욕</b>은 가진 게 없어도
     * 덜 괴로우니 깎이는 폭이 줄고, <b>욕심·야망가</b>는 채워도 덜 기쁘니 회복 폭이 준다.
     * 둘 다 기존 의미(만족선·축장)와 같은 방향이라 새 성격을 얹는 게 아니다.
     *
     * @param now  현재 행복도
     * @param down 오늘 깎인 양(음수 합)
     * @param up   오늘 채운 양(양수 합)
     * @param ind  본인 — null 이면 특성 보정 없음
     */
    public static double step(double now, double down, double up, Individual ind) {
        double d = down;
        double u = up;
        if (ind != null) {
            if (ExpressionResolver.isExpressed(ind, Trait.CONTENT)
                    || ExpressionResolver.isExpressed(ind, Trait.ASCETIC)) {
                d *= 0.7;
            }
            if (ExpressionResolver.isExpressed(ind, Trait.GREEDY)
                    || ExpressionResolver.isExpressed(ind, Trait.AMBITIOUS)) {
                u *= 0.7;
            }
        }
        return clamp(now + d + u);
    }

    /** 사치는 같은 양으로 덜 채워진다 — 배부름 기준선이 높다. */
    public static double fedNeedMultiplier(Individual ind) {
        return ExpressionResolver.isExpressed(ind, Trait.LUXURIOUS) ? 1.3 : 1.0;
    }

    /**
     * 일손 배율 — 꺾인 만큼 채집·수확이 둔해진다. 굴레의 두 번째 마디다.
     *
     * @return 1.0 (평상) · 0.85 ({@link #LOW} 미만) · 0.7 ({@link #PAIN} 미만)
     */
    public static double workMultiplier(double happiness) {
        if (happiness < PAIN) {
            return 0.7;
        }
        if (happiness < LOW) {
            return 0.85;
        }
        return 1.0;
    }

    /**
     * 출산 문턱 보정 — 꺾인 부모는 재지 않는다. 가진 게 없으니 따질 것도 없다는 자리다.
     *
     * <p>{@link Reproduction#threshold} 가 무책임·현재지향에 주는 것과 같은 방식·같은 크기대라
     * 새 장치가 아니라 같은 축의 연장이다. 부모 각각에 −0.25 씩, 둘 다 꺾였으면 −0.5.
     */
    public static double birthThresholdAdjust(double fatherHappiness, double motherHappiness) {
        double adj = 0.0;
        if (fatherHappiness < LOW) {
            adj -= 0.25;
        }
        if (motherHappiness < LOW) {
            adj -= 0.25;
        }
        return adj;
    }

    public static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
