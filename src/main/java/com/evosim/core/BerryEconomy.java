package com.evosim.core;

/**
 * 베리 심기 잉여 배분 (설계: 생존·번식 우선, 베리는 최하위). 밤 정산 잉여에서 <b>하루 예비 + 번식 몫</b>을
 * 뺀 나머지로만 옆 정원에 베리를 심는다 — 넉넉할수록 여러 그루. 상한 C에서 멈춘다.
 *
 * <p>순수 함수: {@code 심을 그루 수 = clamp( (잉여 − 예비 − 번식몫) / 그루당비용, 0, 상한−현재 )}.
 * 굶는 가정은 잉여가 없어 자동으로 0(아사·출산 지장 없음). 실제 심기는 표현층이 이 수만큼 실행.
 */
public final class BerryEconomy {

    /** 베리 한 그루를 심는 데 쓰는 잉여(넉넉할수록 여러 그루 → 이 값으로 조절). */
    public static final double BUSH_COST = 1.0;

    private BerryEconomy() {
    }

    /**
     * @param surplus      정산 후 잉여식량
     * @param reserve      하루 예비로 남길 양(가족 하루 소모량)
     * @param reproReserve 번식에 먼저 떼어둘 몫(부부면 번식 임계, 아니면 0)
     * @param bushCount    현재 거처 베리 그루 수
     * @param cap          거처당 베리 상한
     * @return 이번 밤에 심을 그루 수(0 이상)
     */
    public static int plant(double surplus, double reserve, double reproReserve, int bushCount, int cap) {
        double leftover = surplus - reserve - reproReserve;
        if (leftover <= 0.0) {
            return 0; // 생존·번식 몫을 빼면 남는 게 없음 → 안 심음
        }
        return Math.min(Math.max(0, cap - bushCount), (int) (leftover / BUSH_COST));
    }
}
