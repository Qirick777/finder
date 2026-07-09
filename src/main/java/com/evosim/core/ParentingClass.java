package com.evosim.core;

/**
 * 육아 적극성 (5단계 클래스). 자식이 <b>유아기</b>일 때만 작동하는 양육 행동을 가른다.
 * 개체마다 정확히 하나를 가진다(Individual 필드, breed로 유전).
 *
 * <ul>
 *   <li><b>적극</b>: 유아 있으면 거처에서 안 나옴(반경 0) → 항상 곁에서 돌봄.</li>
 *   <li><b>소극</b>: 거처 주변만(좁은 반경) 채집하며 왔다갔다 돌봄.</li>
 *   <li><b>평범</b>: 중간 반경까지 움직임(적극/소극과 무심의 중간, 자동 채움).</li>
 *   <li><b>무심</b>: 거처 주변을 넓게 움직이나 저녁 배회·산책은 안 하고 귀가.</li>
 *   <li><b>무시</b>: 저녁 배회·산책까지 다 한 뒤 복귀(사실상 무제한).</li>
 * </ul>
 *
 * <p>돌봄 반경이 클수록 유아 곁을 자주 비워 → 유아가 굶주려 죽을 확률↑(§7, 육아 선택압).
 */
public enum ParentingClass {
    DEVOTED("적극", 0.0),
    CARING("소극", 6.0),
    MODERATE("평범", 12.0),
    DETACHED("무심", 22.0),
    NEGLECTFUL("무시", Double.MAX_VALUE);

    private final String label;
    private final double careRadius;

    ParentingClass(String label, double careRadius) {
        this.label = label;
        this.careRadius = careRadius;
    }

    public String label() {
        return label;
    }

    /** 유아를 돌보는 동안 거처에서 벗어날 수 있는 최대 반경. */
    public double careRadius() {
        return careRadius;
    }

    /** 저녁 배회·산책을 하는가 (무시만). */
    public boolean eveningWander() {
        return this == NEGLECTFUL;
    }

    public static ParentingClass byOrdinal(int i) {
        ParentingClass[] all = values();
        return all[Math.floorMod(i, all.length)];
    }
}
