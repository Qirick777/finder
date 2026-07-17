package com.evosim.mod.encounter;

import com.evosim.mod.entity.MimicEntity;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * 조우 문맥 — 내용 핸들러({@link Interaction})가 필요한 것을 꺼내 쓰는 <b>단일 창구</b>.
 * 필드 추가는 하위 호환(기존 핸들러 무영향)이라, 신분·물물교환·요청 등 확장 기능이 요구하는
 * 입력은 여기에만 얹으면 된다. 순수 검증(evotest encounter)에서는 level·개체가 null 인 채로
 * 레지스트리 계약만 대조한다.
 */
public final class EncounterContext {

    /** 조우 장소 — 값 추가 개방(추후: MARKET 등). */
    public enum Place { HEARTH, HOME, FARM, WILD }

    /** 조우 계기 — 어느 goal 이 만남을 성립시켰나(추후: PATROL/MARKET/AUDIENCE). */
    public enum Occasion { VISIT, PLAY }

    @Nullable public final ServerLevel level;     // 순수 검증에선 null
    @Nullable public final MimicEntity initiator; // 방문자·부모
    @Nullable public final MimicEntity partner;   // 집주인·자식·동석자. null = 독백(불쬐기)
    public final Place place;
    public final Occasion occasion;
    /** 결정론 시드 — (주체 id, 상대 id, 게임일) 해시. 핸들러 내 무작위는 반드시 이걸 쓴다. */
    public final long seed;
    /** 이 조우가 쓸 수 있는 시간(틱) — 연출은 goal 이 이 예산으로 수행. */
    public final int budgetTicks;

    public EncounterContext(@Nullable ServerLevel level, @Nullable MimicEntity initiator,
                            @Nullable MimicEntity partner, Place place, Occasion occasion,
                            long seed, int budgetTicks) {
        this.level = level;
        this.initiator = initiator;
        this.partner = partner;
        this.place = place;
        this.occasion = occasion;
        this.seed = seed;
        this.budgetTicks = budgetTicks;
    }
}
