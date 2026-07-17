package com.evosim.core;

/**
 * 지정 돌봄자 판정 (육아 개편 — 돌봄 충분성). 한 유아의 구속 후보(육아 '무시'가 아닌 친부모)가
 * 둘 다 실재하면 <b>정확히 한 명만</b> 구속(지정 돌봄자)하고 나머지는 해제(무시처럼 자유):
 *
 * <ol>
 *   <li>육아 성향이 강한 쪽이 잔류 — 적극 &gt; 소극 &gt; 평범 &gt; 무심 (적극+무심이면 무심은 자유).</li>
 *   <li>동급이면 채집효율(forageYieldMult) <b>낮은</b> 쪽이 잔류 — 높은 쪽을 노동에 내보내는 것이
 *       가구 최적(남성 1.5×라 대부분 남성이 해제). 양쪽 다 적극인 가구의 아사 차단이 목적.</li>
 *   <li>효율까지 동률이면 여성 잔류, 그래도 동률이면 id 작은 쪽 잔류(결정론 꼬리).</li>
 * </ol>
 *
 * <p>대칭 순수 함수 — 두 부모가 각자 계산해도 정확히 한 명만 참이 되어야 한다(구속 공백·이중
 * 구속 금지). {@code /evotest caregiving} 이 전수 대칭성을 대조한다.
 */
public final class Caregiving {

    private Caregiving() {
    }

    /** 내가 지정 돌봄자로 잔류(구속)하는가. 상대(theirs…)도 구속 후보(비무시 친부모)일 때만 호출. */
    public static boolean staysBound(ParentingClass mine, double myYield, Sex mySex, long myId,
                                     ParentingClass theirs, double theirYield, Sex theirSex,
                                     long theirId) {
        if (mine.ordinal() != theirs.ordinal()) {
            return mine.ordinal() < theirs.ordinal(); // enum 선언 = 강한 순(적극이 0) — 강한 쪽 잔류
        }
        if (myYield != theirYield) {
            return myYield < theirYield; // 효율 낮은 쪽 잔류 — 높은 쪽이 벌러 나간다
        }
        if (mySex != theirSex) {
            return mySex == Sex.FEMALE;
        }
        return myId < theirId;
    }
}
