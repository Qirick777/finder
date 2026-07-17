package com.evosim.mod.encounter;

/** 잡담 — 항상 적용되는 최하위 폴백. 상태 변화 없음(연출은 goal 몫). 조우 성립 보증 장치. */
public final class SmallTalk implements Interaction {

    public static final String ID = "smalltalk";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return 1000;
    }

    @Override
    public boolean applies(EncounterContext c) {
        return true;
    }

    @Override
    public Outcome perform(EncounterContext c) {
        return Outcome.done(ID, c.partner == null ? "불쬐기" : "잡담");
    }
}
