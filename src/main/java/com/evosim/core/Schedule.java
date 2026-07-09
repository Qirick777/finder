package com.evosim.core;

/**
 * 하루 리듬 시간대 (설계서 §16). 시간대 구조는 공통 고정, 특성은 기상/취침/노동경계 <b>오프셋만</b> 민다.
 *
 * <p>마크 월드 시각(0~24000틱)을 읽어 "지금 내 어느 구간인가?"만 판정하는 순수 함수(§18).
 * 새 시스템이 아니라 시각 비교 + 개체별 오프셋 몇 개. 값은 밸런싱 1차 제안.
 */
public final class Schedule {

    /** 마크 하루 길이(틱). */
    public static final int DAY = 24000;

    // 기본 경계 (틱). wake < workEnd < DUSK < sleep 순서 불변.
    private static final int BASE_WAKE = 1000;      // 기상
    private static final int BASE_WORK_END = 8000;  // 일→배회 경계
    private static final int DUSK = 12000;          // 배회→밤(귀가) 경계 = 마크 밤 시작
    private static final int BASE_SLEEP = 14000;    // 취침

    /** 하루 구간. */
    public enum Phase {
        SLEEP,   // 취침 (허기 소모 0)
        WORK,    // 채집/사냥 (카운트 누적)
        WANDER,  // 산책/구애/짝찾기
        NIGHT    // 귀가 → 정산 → 번식
    }

    private Schedule() {
    }

    /** 개체 오프셋 없는 전역 하루 구간 (시계·로그 표시용). */
    public static Phase globalPhase(long worldTick) {
        int t = (int) (((worldTick % DAY) + DAY) % DAY);
        if (t < BASE_WAKE || t >= BASE_SLEEP) {
            return Phase.SLEEP;
        }
        if (t < BASE_WORK_END) {
            return Phase.WORK;
        }
        if (t < DUSK) {
            return Phase.WANDER;
        }
        return Phase.NIGHT;
    }

    /** 개체의 그 세계 시각에서의 하루 구간. */
    public static Phase phaseAt(Individual ind, long worldTick) {
        int t = (int) (((worldTick % DAY) + DAY) % DAY);
        int wake = BASE_WAKE + wakeOffset(ind);
        int workEnd = BASE_WORK_END + workEndOffset(ind);
        int sleep = BASE_SLEEP + sleepOffset(ind);

        if (t < wake || t >= sleep) {
            return Phase.SLEEP;
        }
        if (t < workEnd) {
            return Phase.WORK;
        }
        if (t < DUSK) {
            return Phase.WANDER;
        }
        return Phase.NIGHT;
    }

    // 근면(부지런): 일찍 기상 + 노동 연장. 게으름: 늦잠 + 일찍 취침 + 노동 단축 (설계서 §16).
    private static int wakeOffset(Individual ind) {
        if (ExpressionResolver.isExpressed(ind, Trait.DILIGENT)) {
            return -1000;
        }
        if (ExpressionResolver.isExpressed(ind, Trait.LAZY)) {
            return +2000;
        }
        return 0;
    }

    private static int workEndOffset(Individual ind) {
        if (ExpressionResolver.isExpressed(ind, Trait.DILIGENT)) {
            return +2000;
        }
        if (ExpressionResolver.isExpressed(ind, Trait.LAZY)) {
            return -2000;
        }
        return 0;
    }

    private static int sleepOffset(Individual ind) {
        if (ExpressionResolver.isExpressed(ind, Trait.LAZY)) {
            return -1500;
        }
        return 0;
    }
}
