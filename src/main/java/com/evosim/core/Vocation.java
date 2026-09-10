package com.evosim.core;

/**
 * <b>직업 성향</b> — 특성이 어느 자리로 사람을 미는가. 두 자리는 재는 방식이 다르다.
 *
 * <p><b>경비대는 점수가 아니라 값으로 뽑는다.</b> 한때 "멍청Ⅱ 이상이면 입소" 같은 이진
 * 스위치였고, 그다음엔 손으로 매긴 희망도 점수였다. 둘 다 목록을 손으로 들고 있어야 해서,
 * 채집을 깎는 특성(식물혼동·육식·곰손·산만·단순무식·무모)을 넣으려면 그 목록을 늘려야 했고
 * 그러면 {@link Multipliers#gather} 와 두 벌이 되어 반드시 어긋난다.
 *
 * <p>그래서 경비대 채용은 <b>봉급 협상</b>이 정한다({@code FarmTicker.guardAskWage}):
 * 요구 봉급을 바깥벌이(채집 하루치 × {@code gather})에서 뽑으므로, 채집을 깎는 특성은
 * 무엇이든 자동으로 요구를 낮춰 경비대 쪽으로 민다. 규칙5(하드코딩된 신분 분기 금지)가
 * 목록이 아니라 <b>산식</b>으로 지켜진다. 여기 남는 것은 능력에 안 잡히는 성향뿐이다.
 *
 * <p><b>군인은 아직 점수다.</b> 그쪽 채용은 추종·거리로 뽑고 있어 붙일 자리가 없다 —
 * 개편이 올 때 같은 틀로 옮긴다.
 */
public final class Vocation {

    /** 성향(등급 없는) 특성 하나가 군인 희망도에 얹는 몫. 등급Ⅴ 하나(1.0)의 절반이다. */
    private static final double FLAG_WEIGHT = 0.5;

    /** 등급 특성의 만점 — Ⅴ 하나면 이 값을 다 채운다. */
    private static final double GRADE_FULL = 5.0;

    private Vocation() {
    }

    /**
     * <b>요구 봉급에 곱하는 성향계수</b> — 같은 벌이라도 누구는 싸게 응하고 누구는 버틴다.
     *
     * <p>봉급 협상의 밑값은 <b>바깥벌이</b>(채집 하루치 × {@link Multipliers#gather})다. 그것은
     * 능력만 재므로, 능력에 안 잡히는 성향을 여기서 얹는다:
     *
     * <ul>
     *   <li><b>게으름</b> — 편한 자리를 싸게 받아들인다(밤에 서 있는 것이 밭일보다 낫다).</li>
     *   <li><b>비관</b> — 나아질 것이라 보지 않으니 눈앞의 자리를 잡는다.</li>
     *   <li><b>낙관</b> — 더 받겠다고 버틴다. 그래서 늦게, 혹은 영영 안 온다.</li>
     * </ul>
     *
     * <p><b>채집을 깎는 특성은 여기 없다.</b> 식물혼동·육식·곰손·산만·단순무식·무모·멍청·무능은
     * 이미 {@code gather} 가 다 반영한다 — 목록을 두 벌로 두면 반드시 어긋난다. 새 특성이
     * 채집을 깎게 되면 요구 봉급도 자동으로 따라 내려간다.
     */
    public static double guardWageFactor(Individual ind) {
        if (ind == null) {
            return 1.0;
        }
        double f = 1.0;
        if (ExpressionResolver.isExpressed(ind, Trait.LAZY)) {
            f -= WAGE_TILT;
        }
        if (ExpressionResolver.isExpressed(ind, Trait.PESSIMIST)) {
            f -= WAGE_TILT;
        }
        if (ExpressionResolver.isExpressed(ind, Trait.OPTIMIST)) {
            f += WAGE_TILT;
        }
        return Math.max(0.1, f);
    }

    /** 성향 하나가 요구 봉급을 기울이는 비율 — 셋이 겹쳐도 부호가 뒤집히지 않는 크기. */
    private static final double WAGE_TILT = 0.2;

    /**
     * <b>군인 희망도</b> — 싸우는 자리를 원하는 정도.
     *
     * <p>경비대와 정반대 축이다. 지시 사양: "용맹, 야망가, 욕심 등 적절한 특성이 있으면 군인
     * 희망도를 높이고, 사냥 쪽 특성도 살짝 높인다".
     *
     * <p><b>아직 아무 데서도 쓰지 않는다.</b> 군인 채용은 지금도 추종·거리로 뽑으므로, 이
     * 함수는 그 개편이 올 때 붙일 자리를 미리 같은 틀로 잡아 둔 것이다(지시: "추후 군인
     * 업데이트시 사용할 수 있도록 우선 만들어보자"). 값이 실제 행동을 가르기 전까지는
     * 검증할 대상이 아니다 — 쓰는 곳이 생길 때 그 자리에서 실측한다.
     */
    public static double soldier(Individual ind) {
        if (ind == null) {
            return 0.0;
        }
        double score = 0.0;
        if (ExpressionResolver.isExpressed(ind, Trait.BRAVE)) {
            score += FLAG_WEIGHT;
        }
        if (ExpressionResolver.isExpressed(ind, Trait.AMBITIOUS)) {
            score += FLAG_WEIGHT;
        }
        if (ExpressionResolver.isExpressed(ind, Trait.GREEDY)) {
            score += FLAG_WEIGHT;
        }
        // 사냥 계열은 <b>살짝</b>만 — 반 몫의 반이다. 사냥꾼이 곧 군인은 아니다.
        score += Multipliers.abilityGrade(ind, Trait.BUTCHER) / GRADE_FULL * (FLAG_WEIGHT / 2.0);
        if (ExpressionResolver.isExpressed(ind, Trait.DEPENDENT)) {
            score += FLAG_WEIGHT; // 의탁 — 지주 밑의 창이 편한 자(중소지주 축의 짝)
        }
        if (ExpressionResolver.isExpressed(ind, Trait.COWARD)) {
            score -= FLAG_WEIGHT;
        }
        return clamp(score);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
