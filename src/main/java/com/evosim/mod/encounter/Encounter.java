package com.evosim.mod.encounter;

import com.evosim.mod.entity.MimicEntity;
import com.evosim.mod.log.SimEvents;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * 조우 세션 — "대화의 순간"의 <b>단일 관문</b>(설계 계획서 v2 §1). 모든 만남 goal(마실·놀이·
 * 추후 순시/장터/알현)은 두 개체를 한 자리에 데려다 놓은 뒤 {@link #begin}을 호출한다.
 * begin 은 주제 선정(레지스트리) → 즉석 수행(perform) → 로그·NBT(lastChat) 반영까지 마치고,
 * goal 은 남은 예산 틱 동안 연출(체류·바라보기·모션)만 담당한다.
 *
 * <p>미래 기능(신분·물물교환·요청·평판)은 전부 이 관문 안쪽(레지스트리 핸들러·Outcome 소비)
 * 에 얹힌다 — goal 층 수정 금지 규약.
 */
public final class Encounter {

    public final EncounterContext ctx;
    public final Interaction topic;
    public final Outcome outcome;

    private Encounter(EncounterContext ctx, Interaction topic, Outcome outcome) {
        this.ctx = ctx;
        this.topic = topic;
        this.outcome = outcome;
    }

    public static Encounter begin(ServerLevel level, MimicEntity initiator,
                                  @Nullable MimicEntity partner, EncounterContext.Place place,
                                  EncounterContext.Occasion occasion, int budgetTicks) {
        long day = level.getGameTime() / 24000L;
        long iid = initiator.getIndividual() != null ? initiator.getIndividual().id()
                : initiator.getId();
        long pid = partner != null && partner.getIndividual() != null
                ? partner.getIndividual().id() : 0L;
        long seed = iid * 31L + pid * 131L + day * 1009L; // 결정론 — 같은 날 같은 짝이면 동일
        EncounterContext ctx = new EncounterContext(level, initiator, partner, place, occasion,
                seed, budgetTicks);
        Interaction topic = InteractionRegistry.GLOBAL.pick(ctx); // SmallTalk 폴백으로 비-null
        Outcome outcome = topic.perform(ctx);
        initiator.noteEncounter(pid, topic.id());
        if (partner != null) {
            partner.noteEncounter(iid, topic.id());
        }
        SimEvents.event(initiator, "대화", String.format("%s%s → %s", outcome.detail(),
                partner != null && partner.getIndividual() != null
                        ? " · 상대 " + partner.getIndividual().shortName() : "",
                outcome.result()));
        return new Encounter(ctx, topic, outcome);
    }
}
