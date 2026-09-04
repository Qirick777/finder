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
        // 부지런(−1000)과 활력Ⅴ(−750)가 겹치면 기상이 −750 이 된다. 음수면 t < wake 가 영영
        // 거짓이라 취침 구간이 통째로 사라지므로 0 에서 막는다.
        int wake = Math.max(0, BASE_WAKE + wakeOffset(ind));
        int workEnd = BASE_WORK_END + workEndOffset(ind);
        int sleep = Math.min(DAY, BASE_SLEEP + sleepOffset(ind));

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

    /** 이 개체의 기상 시각(틱) — 0 아래로는 안 내려간다. */
    public static int wakeTick(Individual ind) {
        return Math.max(0, BASE_WAKE + wakeOffset(ind));
    }

    /** 이 개체의 노동 종료 시각(틱). */
    public static int workEndTick(Individual ind) {
        return BASE_WORK_END + workEndOffset(ind);
    }

    /** 이 개체의 취침 시각(틱). */
    public static int sleepTick(Individual ind) {
        return Math.min(DAY, BASE_SLEEP + sleepOffset(ind));
    }

    /**
     * 하루의 WORK 구간 길이(틱) — 하루를 훑지 않고 경계에서 바로 낸다.
     *
     * <p>계측(TraitAudit)이 개체마다 24000틱을 돌면 인구 2만 대조에 몇 분이 걸린다. 경계를
     * 그대로 쓰되, {@link #phaseAt} 와 어긋나면 보고가 조용히 거짓이 되므로 순수 검증이 두
     * 값을 대조한다({@code evotest simulate} 의 일과 항).
     */
    public static int workTicks(Individual ind) {
        return Math.max(0, Math.min(workEndTick(ind), sleepTick(ind)) - wakeTick(ind));
    }

    /** 하루의 WANDER 구간 길이(틱) — 명석 실효Ⅴ 는 이 시간에도 노동한다. */
    public static int wanderTicks(Individual ind) {
        return Math.max(0, Math.min(DUSK, sleepTick(ind))
                - Math.max(wakeTick(ind), workEndTick(ind)));
    }

    /** 활력/무기력 등급당 기상 ∓ — Ⅴ 에서 750틱 일찍 깬다(노동창 +10.7%). */
    private static final int VITALITY_WAKE_PER = 150;
    /** 활력/무기력 등급당 취침 ± — Ⅴ 에서 1000틱 늦게 잔다.
     *  <b>노동이 아니라 밤(귀가·정산·구애) 이 길어진다</b> — WORK 는 workEnd 에서 끝나기 때문이다.
     *  소득이 아니라 "먼 채집에서 늦게 돌아와도 정산을 놓치지 않는" 여유가 값어치다. */
    private static final int VITALITY_SLEEP_PER = 200;

    // 근면(부지런): 일찍 기상 + 노동 연장. 게으름: 늦잠 + 일찍 취침 + 노동 단축 (설계서 §16).
    // 활력/무기력(신체): 기상·취침만 민다 — 노동경계는 안 건드려 근면 축과 겹치지 않는다.
    private static int wakeOffset(Individual ind) {
        int off = -VITALITY_WAKE_PER * Physique.grade(ind, Trait.VIGOROUS)
                + VITALITY_WAKE_PER * Physique.grade(ind, Trait.LISTLESS);
        if (ExpressionResolver.isExpressed(ind, Trait.DILIGENT)) {
            return off - 1000;
        }
        if (ExpressionResolver.isExpressed(ind, Trait.LAZY)) {
            return off + 2000;
        }
        return off;
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
        int off = VITALITY_SLEEP_PER * Physique.grade(ind, Trait.VIGOROUS)
                - VITALITY_SLEEP_PER * Physique.grade(ind, Trait.LISTLESS);
        if (ExpressionResolver.isExpressed(ind, Trait.LAZY)) {
            return off - 1500;
        }
        return off;
    }
}
