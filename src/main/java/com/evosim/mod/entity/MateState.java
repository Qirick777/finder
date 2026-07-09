package com.evosim.mod.entity;

/**
 * 구애 상태 (구애 사양서 v2 §2). 방랑자 성년만 참여한다.
 *
 * <ul>
 *   <li>{@code IDLE} — 미참여(미성년·정착 등).</li>
 *   <li>{@code SEARCHING} — 인식 범위의 이성을 후보로 모으는 중(노동/배회).</li>
 *   <li>{@code COURTING} — 후보를 매력 순으로 시도하는 중(배회 = 구애 시간).</li>
 *   <li>{@code PAIRED} — 짝 성사(거처 형성). 모든 구애를 자동 거절.</li>
 * </ul>
 */
public enum MateState {
    IDLE,
    SEARCHING,
    COURTING,
    PAIRED
}
