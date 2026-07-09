package com.evosim.mod.entity;

/**
 * 구애 기록 한 건 (GUI 현황판용, 구애 사양서 v2). "누가 누구에게 구애했고, 매력 몇 점·몇 등·몇 %로
 * 수락/거절됐는가"를 담는다.
 *
 * <ul>
 *   <li>{@link Kind#COURTED} — 내가 상대에게 구애함(수락받았는지 표시).</li>
 *   <li>{@link Kind#RECEIVED} — 상대가 나에게 구애함(내가 매력 X점·Y등/pool·P%로 수락/거절).</li>
 * </ul>
 */
public final class CourtRecord {

    public enum Kind { COURTED, RECEIVED }

    public final long dayTime;   // 발생 시각(월드 dayTime)
    public final Kind kind;
    public final int otherId;
    public final String otherName;
    public final boolean accepted;
    public final int charm;      // 상대(구애자)의 내 기준 매력점수
    public final int rank;       // 후보 중 순위(1=최상). RECEIVED에서 의미
    public final int pool;       // 비교 대상 수(후보 n)
    public final int percent;    // 수락 확률 %

    public CourtRecord(long dayTime, Kind kind, int otherId, String otherName, boolean accepted,
                       int charm, int rank, int pool, int percent) {
        this.dayTime = dayTime;
        this.kind = kind;
        this.otherId = otherId;
        this.otherName = otherName;
        this.accepted = accepted;
        this.charm = charm;
        this.rank = rank;
        this.pool = pool;
        this.percent = percent;
    }
}
