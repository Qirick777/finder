package com.evosim.core;

/**
 * 행동 결정 = 우선순위 목록 (설계서 §18: 위에서부터 조건 맞는 첫 행동, if 중첩 X).
 *
 * <p>순수 함수 — 마크 엔티티는 매 결정틱마다 이걸 호출해 나온 {@link Action}을 실행만 한다.
 * 공간·주변 개체가 필요한 세부(짝 스캔·실제 길찾기)는 마크 표현층 몫. 여기선 시간대 + 특성으로 결정.
 */
public final class BehaviorDecision {

    /** 개체가 지금 할 행동. */
    public enum Action {
        SLEEP,         // 잠
        GATHER,        // 채집(잔디)
        HUNT,          // 사냥(동물)
        WANDER_COURT,  // 배회·구애·짝찾기
        RETURN_HOME    // 귀가(밤 정산·번식 준비)
    }

    private BehaviorDecision() {
    }

    /** 세계 시각에서 개체가 취할 행동. */
    public static Action decide(Individual ind, long worldTick) {
        Schedule.Phase phase = Schedule.phaseAt(ind, worldTick);
        return switch (phase) {
            case SLEEP -> Action.SLEEP;
            case WORK -> gatherOrHunt(ind);
            case WANDER -> Action.WANDER_COURT;
            case NIGHT -> Action.RETURN_HOME;
        };
    }

    /** 노동 구간: 사냥 기대치가 채집보다 높으면 사냥, 아니면 채집(기본). */
    private static Action gatherOrHunt(Individual ind) {
        return Multipliers.hunt(ind) > Multipliers.gather(ind) ? Action.HUNT : Action.GATHER;
    }
}
