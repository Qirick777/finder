package com.evosim.core;

/**
 * <b>여유금 자식 지원</b> — 밤 정산의 <b>맨 마지막</b>에, 모든 지출(확장·착공·시설·봉급·지대)이 끝난
 * 뒤 남은 여유의 일정 비율을 분가한 자식 가구에 보낸다. 노인 배달(ElderVisitGoal)·지참금
 * 비례·착공비 대납을 대신하는 단일 장치.
 *
 * <p>규칙5(신분 분기 금지): 지주 전용이 아니라 <b>범용</b>이다. 다만 여유의 기준선이 그 가구가
 * 이미 쓰는 예비(밭 있으면 확장 예비, 없으면 착공 문턱)라서, 실제로 여유가 남는 쪽은 거의
 * 지주뿐이다 — 평민은 착공 문턱 아래에서 제 밭 돈을 지키고, 문턱 위인데도 착공 안 하는(만족)
 * 가구만 자식에게 흘린다.
 *
 * <p>영원한 적자가 없는 이유: 예비 아래로는 절대 내려가지 않고(여유 ≤ 0 이면 0), 여유의
 * 25% 만 보내며, 받는 쪽도 제 착공 문턱까지만 받는다(그 위는 지원 대상이 아님). 부모의 밭
 * 확장은 이미 그날 밤 먼저 끝났으므로 지원이 확장을 끊지 못한다 — 다음 날 밤 확장은 남은
 * 75% 위에서 다시 시작한다.
 *
 * <p>순수 함수 — /evotest support 로 대조.
 */
public final class ChildSupport {

    /** 여유 중 자식에게 보내는 비율. */
    public static final double SHARE = 0.25;
    /** 자식 가구 탐색 반경(블록) — 노인 방문 배달과 같은 96. */
    public static final double RADIUS = 96.0;

    private ChildSupport() {
    }

    /** 오늘 밤 보낼 수 있는 정수 예산 = floor(max(0, 저장고 − 예비) × {@link #SHARE}). */
    public static int budget(double larder, double reserve) {
        double surplus = Math.max(0.0, larder - reserve);
        return (int) Math.floor(surplus * SHARE);
    }

    /** 한 자식에게 주는 정수 = min(예산, ceil(문턱 − 자식 저장고)); 문턱 이상이면 0. */
    public static int grant(int budget, double childLarder, double childThreshold) {
        if (budget <= 0 || childLarder >= childThreshold) {
            return 0;
        }
        int need = (int) Math.ceil(childThreshold - childLarder);
        return Math.max(0, Math.min(budget, need));
    }
}
