package com.evosim.core;

/**
 * 생애단계 (설계서 §7). 유아기 → 소년기 → 성년기.
 *
 * <p>{@code modelScale}은 마크 렌더 배율(외형 구분용) — 유아는 작게+머리 큰 아기 비율(렌더에서 young),
 * 소년은 키 작게, 성년은 기본. 실제 성장 전환(마크 기본 성장 재활용)은 Phase 3.
 */
public enum LifeStage {
    INFANT(0.5f),  // 유아: 자가 섭취 불가, 몬스터 노출
    BOY(0.72f),    // 소년: 섭취 O·채취 X, 미아사 위험
    ADULT(1.0f);   // 성년: 채집·사냥·번식·전투

    private final float modelScale;

    LifeStage(float modelScale) {
        this.modelScale = modelScale;
    }

    public float modelScale() {
        return modelScale;
    }
}
