package com.evosim.core;

/**
 * 활동 강도 — 식량 소모율에 곱해지는 배율 (식량 경제 v2). 순수.
 *
 * <p>표현층이 개체의 현재 상태(취침/대기/이동/사냥/전투)를 이 enum으로 요약해
 * {@link FoodEconomy#consumptionPerDay}에 넘긴다. 취침은 소모 0(§4 "허기 소모 0" 계승).
 */
public enum Activity {
    SLEEP(0.0), IDLE(0.4), MOVE(1.0), HUNT(1.5), COMBAT(2.0);

    public final double mult;

    Activity(double m) {
        this.mult = m;
    }
}
