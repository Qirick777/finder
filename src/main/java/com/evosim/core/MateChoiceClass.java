package com.evosim.core;

/**
 * 짝 선정 까다로움 (5단계 클래스, 구애 사양서 v2 §1 특성 테이블). 육아 클래스와 동일하게 한 개체가
 * 남/여 슬롯을 모두 지니며 <b>성별에 맞는 쪽</b>이 발현되고, 슬롯 독립 유전 + 변이한다(중립값 존재).
 *
 * <ul>
 *   <li>{@code k} = 회의심 계수(클수록 까다로움). 수락 판정 공식({@link Courtship})에 들어간다.</li>
 *   <li>{@code searchSeconds} = 탐색 시간 T(초). 이 시간만큼 후보를 모은 뒤 구애를 시작한다(밸런싱 대상).</li>
 * </ul>
 */
public enum MateChoiceClass {
    OPEN("완전개방", 0, 4.0),
    LOOSE("널널", 1, 6.0),
    MODERATE("보통", 2, 9.0),   // 중립값
    PRUDENT("신중", 4, 13.0),
    STRICT("엄격", 8, 18.0);

    /** 중립(기본) 값 — 유전 슬롯 기본값. */
    public static final MateChoiceClass NEUTRAL = MODERATE;

    private final String label;
    private final int k;
    private final double searchSeconds;

    MateChoiceClass(String label, int k, double searchSeconds) {
        this.label = label;
        this.k = k;
        this.searchSeconds = searchSeconds;
    }

    public String label() {
        return label;
    }

    /** 회의심 계수 k (수락 판정용). */
    public int k() {
        return k;
    }

    /** 탐색 시간 T (초). */
    public double searchSeconds() {
        return searchSeconds;
    }

    /** 탐색 시간 T (틱, 20tps). */
    public int searchTicks() {
        return (int) Math.round(searchSeconds * 20.0);
    }

    public static MateChoiceClass byOrdinal(int i) {
        MateChoiceClass[] all = values();
        return all[Math.floorMod(i, all.length)];
    }
}
