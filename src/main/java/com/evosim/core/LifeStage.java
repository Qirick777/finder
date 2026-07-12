package com.evosim.core;

/**
 * 생애단계 (설계서 §7). 유아기 → 소년기 → 청년(성년) → 노년 → 자연사.
 *
 * <p>{@code modelScale}은 전체 렌더 배율(키·몸집), {@code headScale}은 머리 파츠 추가 배율(동안 느낌).
 * 유아·소년은 머리를 살짝 크게 해 "어림"을 표현. (값은 외형 튜닝용 1차값 — 조정 자유.)
 */
public enum LifeStage {
    INFANT(0.5f, 1.30f),  // 유아: 작게 + 머리 살짝 큼. 자가 섭취 불가, 몬스터 노출
    BOY(0.72f, 1.15f),    // 소년: 키 작게 + 머리 살짝 큼. 섭취 O·채취 X, 미아사 위험
    ADULT(1.0f, 1.0f),    // 청년: 기본. 채집·사냥·번식·전투 — 35일 뒤 노년
    ELDER(1.0f, 1.0f);    // 노년: 번식·구애 불가, 수확 0.5×·소모 2.0·속도 0.8 — 쿼터 노동·방문 공유

    private final float modelScale;
    private final float headScale;

    LifeStage(float modelScale, float headScale) {
        this.modelScale = modelScale;
        this.headScale = headScale;
    }

    public float modelScale() {
        return modelScale;
    }

    /** 머리 파츠 추가 배율 (동안 표현). */
    public float headScale() {
        return headScale;
    }
}
