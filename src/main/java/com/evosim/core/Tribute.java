package com.evosim.core;

/**
 * <b>세금과 상납의 산수</b> — 순수 함수만. 상태도 월드도 모른다.
 *
 * <p>추종이 성립하면 <b>매일</b> 주인에게 낸다. 이것이 지배의 실체다 — 그 전까지 추종은
 * 장부에만 있는 관계였고, 주인이 얻는 것이 하나도 없었다. 세금이 붙어야 "지배자는 손해가
 * 아니라 이익을 본다"(목표 4)가 수치로 성립한다.
 *
 * <p><b>신분으로 분기하지 않는다</b>(규칙5). 세액은 누구에게나 같은 식이고, 갈리는 것은
 * <b>낼 수 있는가</b> 하나뿐이다. 여유가 없으면 못 낸 몫이 빚(상환분)으로 남고, 그 빚이
 * 쌓이면 천민이 된다 — 넘지 못할 숫자가 신분을 만든다.
 */
public final class Tribute {

    private Tribute() {
    }

    /**
     * 추종자 하나가 하루에 내는 세액.
     *
     * <p>1.0 은 성년 하루소모(3~4)의 약 4분의 1이다. 한 사람을 거느려 하루 1 을 얻고, 아홉을
     * 거느리면 9 를 얻는다 — 밭 한 칸 확장이 1({@link FarmEconomy#EXPAND_COST})이므로,
     * 세력의 크기가 그대로 밭의 성장 속도가 된다. 거느린 자만 크게 키운다는 밭 상한
     * ({@code plotTileCap})과 같은 방향이라 두 장치가 서로를 밀어준다.
     *
     * <p><b>측정 뒤에 확정할 값</b>이다. 너무 크면 추종자가 말라 죽고, 너무 작으면 지배가
     * 이름뿐이 된다. 판정 지표는 지배자 수지(흑자여야 한다)와 미납률(0 도 폭주도 아니어야).
     */
    public static final double TAX_PER_FOLLOWER_DAY = 1.0;

    /**
     * 세금을 걷기 전에 남겨 두는 가구 예비 — 가구 하루소모의 배수.
     *
     * <p>세금 때문에 굶어 죽으면 지배자도 손해다(값싼 노동을 잃는다). 이틀치를 남기고 그
     * 위에서만 걷는다. 이 예비 때문에 <b>가난한 가구는 구조적으로 못 낸다</b> — 미납은
     * 사고가 아니라 설계된 출구이고, 그 출구가 천민으로 이어진다.
     */
    public static final double TAX_RESERVE_DAYS = 2.0;

    /**
     * 걷은 세금 중 <b>자기 주인에게 올리는</b> 몫. 상납 사슬이 이 값으로 위로 흐른다.
     *
     * <p>0.5 면 2단 사슬에서 왕이 말단 세금의 절반을 가져간다. 중간 지배자에게도 절반이
     * 남으므로 사슬에 끼는 것이 손해가 아니다 — 그래야 사슬이 유지된다.
     */
    public static final double TRIBUTE_SHARE = 0.5;

    /**
     * 세금을 다 내고도 예비 위에 남은 여유 중 <b>빚 갚기에 쓰는</b> 몫.
     *
     * <p>"빚은 전부 갚을 게 아니라 천천히 뜯겨도 좋다." 0.25 는 여유의 4분의 1이라, 형편이
     * 나아지면 빚이 줄고 나빠지면 이자가 앞선다. 갚아 벗어나는 길이 <b>있되 좁다.</b>
     */
    public static final double REPAY_SHARE = 0.25;

    /** 이 가구가 오늘 세금·상환에 쓸 수 있는 몫 — 예비를 뺀 나머지(음수면 0). */
    public static double payable(double larder, double familyDailyNeed) {
        return Math.max(0.0, larder - familyDailyNeed * TAX_RESERVE_DAYS);
    }

    /** 이 개체가 오늘 내야 할 세액 — 추종 중이면 정액, 아니면 0. */
    public static double due(boolean follows) {
        return follows ? TAX_PER_FOLLOWER_DAY : 0.0;
    }

    /** 걷은 총액 중 위로 올릴 몫. */
    public static double tributeUp(double collected) {
        return collected * TRIBUTE_SHARE;
    }

    /** 남은 여유로 갚을 수 있는 몫 — 빚보다 많이 갚지는 않는다. */
    public static double repayment(double spare, double owed) {
        return Math.min(owed, spare * REPAY_SHARE);
    }
}
