package com.evosim.mod.encounter;

/**
 * 대화 내용 1종 — <b>무상태 싱글턴</b> 플러그인(설계 계획서 v2 §2). 상태는 기존 스토어
 * (LarderStore/FarmStore)나 전용 SavedData 에만 둔다.
 *
 * <p>확장 규약: 신분 예법·물물교환·요청 등 미래 기능은 이 인터페이스 구현 + 레지스트리 등록
 * 1줄로 모든 조우 지점(마실·놀이·추후 순시/장터)에 자동 편입된다 — goal/조우 층 수정 금지.
 * 상대 동의가 필요한 주제는 {@link #applies}에서 양측 상태를 검사하고, {@link #perform}에서
 * {@link Outcome.Result#REFUSED}를 정상 결과로 반환할 수 있다(합의 모델).
 *
 * <p>{@code perform} 은 <b>즉석 상태 변화만</b> 담당한다(예: 교환이면 저장고 이전) — 체류·
 * 바라보기·모션 등 연출은 조우를 연 goal 이 {@code budgetTicks} 동안 수행한다.
 */
public interface Interaction {

    String id();

    /** 낮을수록 먼저 검토 — 신분(예법) < 교환 < 요청 < 잡담(폴백 1000) 순 설계. */
    int priority();

    boolean applies(EncounterContext c);

    Outcome perform(EncounterContext c);
}
