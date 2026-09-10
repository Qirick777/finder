package com.evosim.core;

/**
 * <b>영지 수지와 왕국 성립</b>(왕국 세수안, 사용자 승인) — 순수 함수만.
 *
 * <p>지배는 두 흐름으로 읽힌다. 세수(인두세·재산세·보호세·상납)와 통치 지출(군인·경비 봉급·
 * 구휼·자식 지원). 초중반은 세수가 0 이거나 얇아 지배자가 <b>사비로 봉사</b>하고, 군대가 서서
 * 무력이 닿는 가구가 늘면 세수가 지출을 넘는다. 그 전환이 며칠 이어지고 신민이 충분하면
 * "왕국"이라 부른다 — 칭호는 숫자에서 나온다(규칙5).
 *
 * <p>징세의 조건은 <b>무력 도달</b>이다: 추종 관계만으로는 신세 상환(25%)만 오고, 그 지배자의
 * 막사 통근 반경({@code Facilities.COMMUTE_RANGE} 96) 안 가구만 인두세·재산세를 낸다. 순찰이
 * 안 닿는 집은 굴복하지 않는다는 현실적 사유이며, 군대가 세를 만들고 세가 군대를 키운다.
 */
public final class Realm {

    /** 세수 ≥ 통치 지출이 이 일수 연속이면 왕국 성립. */
    public static final int KINGDOM_DAYS = 3;
    /** 왕국이라 부를 최소 신민(무력 도달) 가구 수 — 병사 2명 몫(4가구/명). */
    public static final int KINGDOM_MIN_SUBJECTS = 8;

    private Realm() {
    }

    /** 오늘 수지 = 세수 − 지출. */
    public static double balance(double taxIn, double ruleOut) {
        return taxIn - ruleOut;
    }

    /** 사비 보전 = 지출이 세수를 넘는 몫(음수면 0) — "지배자가 제 곳간에서 댄 돈". */
    public static double outOfPocket(double taxIn, double ruleOut) {
        return Math.max(0.0, ruleOut - taxIn);
    }

    /**
     * 흑자 연속 일수 갱신 — 오늘 세수 ≥ 지출이고 신민이 {@link #KINGDOM_MIN_SUBJECTS} 이상이면
     * +1, 아니면 0 으로 되돌린다. 세수·지출이 둘 다 0 인 날(아무 일도 없던 날)은 흑자로 세지 않는다.
     */
    public static int streak(int prev, double taxIn, double ruleOut, int reachedSubjects) {
        boolean surplus = taxIn > 0.0 && taxIn >= ruleOut;
        return surplus && reachedSubjects >= KINGDOM_MIN_SUBJECTS ? prev + 1 : 0;
    }

    /** 왕국 성립 판정 — 연속 흑자가 {@link #KINGDOM_DAYS} 에 닿은 날. */
    public static boolean kingdomFounded(int streak) {
        return streak >= KINGDOM_DAYS;
    }
}
