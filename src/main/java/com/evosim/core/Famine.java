package com.evosim.core;

/**
 * 기근 → 이주 판정 (이주 설계 §1). 주변 풀을 스캔하지 않고 <b>채집 결과</b>로 판단한다:
 * 채집 가능한 성원 전원이 한동안 아무것도 못 벌었고(인지 범위 안에 먹을 게 없다는 증거),
 * 비축(저장고)마저 바닥이면 → 인지거리 외곽으로 이주.
 *
 * <p>순수 함수 — 표현층은 성공 시각·저장고 숫자만 넘긴다. 오탐 가드:
 * 갓 정착한 가족은 쿨다운 동안 판단 유예, 채집자 없는 가구(육아 과부 등)는 이주 불가(제자리 —
 * 과부 붕괴 허용 경로와 일관). 베리 정원 수확도 "성공"이라 정원이 돌아가는 가족은 자동 제외.
 */
public final class Famine {

    /** 마지막 채집 성공 후 이 틱이 지나면 "주변에 먹을 게 없다"(1게임일). 임시값, 게임 관찰로 확정. */
    public static final int STARVE_WINDOW = 24000;
    /** 정착·이주 직후 이 틱 동안 재이주 금지(콜드스타트·연쇄 이주 오탐 방지, 2게임일). */
    public static final int RESETTLE_COOLDOWN = 48000;
    /** 이주 거리 = 활동반경 × 이 값("인지거리 외곽"). */
    public static final double MIGRATE_DISTANCE_MULT = 2.0;
    /** 독신 짝 후보 0명이 이 틱 지속되면 구혼 여행 출발(족외혼, 1게임일 — 세대 압축 비례 3/35×13). */
    public static final int LONELY_TRAVEL_AFTER = 24000;
    /** 구혼 여행 최대 기간 — 넘기면 귀향(2게임일). */
    public static final int TRAVEL_DURATION = 48000;

    private Famine() {
    }

    /**
     * 이주 판정 — 전부 참이어야 이주:
     * ① 채집자 존재 ② 정착 쿨다운 경과 ③ 채집자 전원 최근 성공 없음 ④ 저장고 < 가족 하루소모.
     *
     * @param foragerLastSuccess 채집 가능(비육아) 성원들의 마지막 채집 성공 시각
     */
    public static boolean shouldMigrate(long now, long settledTick, long[] foragerLastSuccess,
                                        double larder, double familyDailyNeed) {
        if (foragerLastSuccess.length == 0) {
            return false; // 채집자 없음(육아 과부 등) → 이주 불가(제자리)
        }
        if (now - settledTick < RESETTLE_COOLDOWN) {
            return false; // 갓 정착 → 판단 유예
        }
        for (long t : foragerLastSuccess) {
            if (now - t < STARVE_WINDOW) {
                return false; // 누군가는 아직 벌고 있음 → 잔류
            }
        }
        return larder < familyDailyNeed; // 비축도 바닥 → 떠난다
    }

    /** 방위별 풀 표본 중 최다 방위(결정론: 동률은 앞 인덱스). 전부 0이면 -1(호출측 무작위 폴백). */
    public static int bestDirection(int[] grassSamples) {
        int best = -1;
        int bestCount = 0;
        for (int i = 0; i < grassSamples.length; i++) {
            if (grassSamples[i] > bestCount) {
                bestCount = grassSamples[i];
                best = i;
            }
        }
        return best;
    }
}
