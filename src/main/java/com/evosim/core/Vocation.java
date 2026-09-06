package com.evosim.core;

/**
 * <b>직업 희망도</b> — 특성이 그 자리를 얼마나 원하게(또는 그 자리로 밀리게) 하는가.
 * 0.0 = 아무 이유 없음, 1.0 = 그 자리 말고는 없음.
 *
 * <p><b>왜 점수인가.</b> 종전에는 "멍청Ⅱ 이상이면 즉시 입소"처럼 <b>이진 스위치</b>였다.
 * 스위치는 두 가지가 나쁘다: 등급 Ⅰ 과 Ⅴ 가 같은 대우를 받아 경사가 사라지고, 자리가 모자랄
 * 때 <b>누구를 먼저</b> 앉힐지 말해 주지 못한다. 점수로 두면 문턱과 순서를 같은 수 하나가
 * 정하고, 규칙5(하드코딩된 신분 분기 금지)와도 맞는다 — 신분이 특성 값에서 나온다.
 *
 * <p><b>왜 한 클래스에 모으는가.</b> 경비대와 군인은 뽑는 기준이 정반대지만(못하는 자 /
 * 잘하는 자) 쓰는 방식이 같다 — 문턱을 낮추고 자리를 다툴 때 순서를 매긴다. 축을 따로 두면
 * 두 곳의 산식이 갈라져 나중에 한쪽만 고치게 된다.
 *
 * <p>등급은 전부 0~5 눈금이고({@link Multipliers}), 발현 여부는 등급이 없는 성향 특성에 쓴다.
 */
public final class Vocation {

    /** 성향(등급 없는) 특성 하나가 희망도에 얹는 몫. 등급Ⅴ 하나(1.0)의 절반이다. */
    private static final double FLAG_WEIGHT = 0.5;

    /** 등급 특성의 만점 — Ⅴ 하나면 이 값을 다 채운다. */
    private static final double GRADE_FULL = 5.0;

    private Vocation() {
    }

    /**
     * <b>경비대 희망도</b> — 밭일로 먹고살 수 없는 정도.
     *
     * <p>원하는 자리가 아니라 <b>밀려나는</b> 자리다. 그래서 능력이 아니라 무능이 점수가 된다:
     * 멍청·무능은 등급으로, 게으름·비관은 발현으로 센다. 육아 구속은 특성이 아니라 상태라
     * 여기 넣지 않는다 — 부르는 쪽에서 따로 본다.
     *
     * <p>낙관은 <b>깎는다</b>. 나아질 것이라 보므로 남의 밥을 먹는 자리로 늦게 간다. 종전에
     * 문턱 일수 +1 로 주던 효과를 같은 뜻으로 여기에 합친다.
     */
    public static double guard(Individual ind) {
        if (ind == null) {
            return 0.0;
        }
        double dull = Multipliers.dullGrade(ind) / GRADE_FULL;
        double inept = Multipliers.abilityGrade(ind, Trait.INEPT) / GRADE_FULL;
        double score = Math.max(dull, inept); // 둘 다면 더 나쁜 쪽 하나로 — 합치면 쉽게 만점이 된다
        if (ExpressionResolver.isExpressed(ind, Trait.LAZY)) {
            score += FLAG_WEIGHT;
        }
        if (ExpressionResolver.isExpressed(ind, Trait.PESSIMIST)) {
            score += FLAG_WEIGHT;
        }
        if (ExpressionResolver.isExpressed(ind, Trait.OPTIMIST)) {
            score -= FLAG_WEIGHT;
        }
        return clamp(score);
    }

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
        if (ExpressionResolver.isExpressed(ind, Trait.COWARD)) {
            score -= FLAG_WEIGHT;
        }
        return clamp(score);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
