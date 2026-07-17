package com.evosim.mod.encounter;

/**
 * 조우 수행 결과 — 로그·렌즈·검증·(미래)평판의 공용 어휘.
 *
 * <ul>
 *   <li>{@code DONE} — 완결(잡담·놀이 등 단방향 주제).</li>
 *   <li>{@code ACCEPTED}/{@code REFUSED} — 합의 주제(물물교환 등)의 성사/거절. 거절도 정상
 *       결과다(추후 평판 입력).</li>
 *   <li>{@code DEFERRED} — 즉석 완결 불가(요청·청원). 이행 장부(PledgeStore — 미구현, 설계
 *       계획서 v2 §2)가 생기면 이 결과가 기입 트리거가 된다.</li>
 * </ul>
 */
public record Outcome(String topicId, Result result, String detail) {

    public enum Result { DONE, ACCEPTED, REFUSED, DEFERRED }

    public static Outcome done(String topicId, String detail) {
        return new Outcome(topicId, Result.DONE, detail);
    }
}
