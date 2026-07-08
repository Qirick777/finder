package com.evosim.core;

/**
 * breed() 내부 이벤트 누적기 (설계서 §17 검증용).
 *
 * <p>검증 명령어는 실제 기능이 쓰는 <b>같은 breed()</b>를 호출하되, 확률적 이벤트(우성 유지·돌연변이)를
 * 밖에서 셀 수 없으므로 breed()가 이 누적기에 카운트를 남긴다. 게임 실행 시에는 null을 넘겨 무시.
 */
public final class BreedStats {
    /** 부모에게서 우성으로 물려받을 후보가 실제로 자식에 채택된 횟수. */
    public long dominantInherited = 0;
    /** 그중 자식이 우성 태그를 유지한 횟수 (기대 ≈ 75%). */
    public long dominantRetained = 0;

    /** 돌연변이 주사위를 굴린 횟수 (카테고리 단위). */
    public long mutationRolls = 0;
    /** 실제 돌연변이가 일어난 횟수 (기대 ≈ 2%). */
    public long mutations = 0;

    public double dominantRetentionRate() {
        return dominantInherited == 0 ? 0.0 : (double) dominantRetained / dominantInherited;
    }

    public double mutationRate() {
        return mutationRolls == 0 ? 0.0 : (double) mutations / mutationRolls;
    }
}
