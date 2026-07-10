package com.evosim.core;

/**
 * 베리 농장 잉여 배분 (설계안: 선 식량안보, 후 확장). 밤 정산 잉여를 <b>예비식량 → 베리 농장(상한까지) →
 * 번식</b> 순으로 배분한다. 상한 C가 "베리↔번식" 전환 스위치라, 무한 심기로 번식이 막히지 않고, 동시에
 * 그루 수 자체가 제한되어 도배도 억제된다.
 *
 * <p>순수 함수 — 실제 심기·출산은 표현층이 이 결과대로 실행한다.
 */
public final class BerryEconomy {

    /** 베리 한 그루를 심는 데 드는 잉여식량. */
    public static final double BUSH_COST = 1.0;

    private BerryEconomy() {
    }

    /** 배분 결과 — 이번 밤에 심을 그루 수 + 번식 여부. */
    public record Plan(int plant, boolean reproduce) {
    }

    /**
     * @param surplus        정산 후 잉여식량
     * @param reserve        하루 예비로 남길 양(가족 하루 소모량)
     * @param bushCount      현재 거처 귀속 베리 그루 수
     * @param cap            거처당 베리 상한
     * @param reproThreshold 번식에 필요한 잉여(부부 특성 반영값)
     */
    public static Plan plan(double surplus, double reserve, int bushCount, int cap, double reproThreshold) {
        double avail = surplus - reserve;
        if (avail <= 0.0) {
            return new Plan(0, false); // 예비도 빠듯 → 심기·번식 없음
        }
        // 번식 우선(잉여가 베리 심기로 번식을 굶기지 않게) → 남는 잉여로 상한까지 베리.
        boolean reproduce = avail >= reproThreshold;
        double left = reproduce ? avail - reproThreshold : avail;
        int plant = Math.min(Math.max(0, cap - bushCount), (int) (left / BUSH_COST));
        return new Plan(plant, reproduce);
    }
}
