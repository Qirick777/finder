package com.evosim.mod.encounter;

/** 놀아주기 — 놀이 계기(PLAY) 전용 주제. 상태 변화 없음: 실효(방치 래치 충족)는 곁에 머무는
 *  체류 자체가 만든다(급식 출석 = 성년 근접 — 기하 판정). */
public final class PlayWithChild implements Interaction {

    public static final String ID = "play";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return 500;
    }

    @Override
    public boolean applies(EncounterContext c) {
        return c.occasion == EncounterContext.Occasion.PLAY && c.partner != null;
    }

    @Override
    public Outcome perform(EncounterContext c) {
        return Outcome.done(ID, "놀이");
    }
}
