package com.evosim.mod.encounter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 대화 내용 레지스트리 — 조우당 주제 1개: priority 오름차순으로 {@code applies} 첫 통과를
 * 선정(동순위는 id 사전순 — 결정론). {@link #GLOBAL}에는 {@link SmallTalk}(항상 적용·최하위)
 * 이 상주해 어떤 조우도 빈손으로 끝나지 않는다. 검증은 전용 인스턴스로 계약을 전수 대조
 * ({@code /evotest encounter}).
 */
public final class InteractionRegistry {

    /** 런타임 전역 — 미래 기능은 여기 register 1줄로 편입된다. */
    public static final InteractionRegistry GLOBAL = new InteractionRegistry();

    static {
        GLOBAL.register(new PlayWithChild());
        GLOBAL.register(new SmallTalk());
    }

    private final List<Interaction> handlers = new ArrayList<>();

    public void register(Interaction i) {
        handlers.add(i);
        handlers.sort(Comparator.comparingInt(Interaction::priority)
                .thenComparing(Interaction::id));
    }

    /** 주제 선정 — 적용 가능한 최우선 1건. 없으면 null(GLOBAL 은 SmallTalk 폴백이라 비-null). */
    public Interaction pick(EncounterContext c) {
        for (Interaction i : handlers) {
            if (i.applies(c)) {
                return i;
            }
        }
        return null;
    }

    /** 등록 수(검증용). */
    public int size() {
        return handlers.size();
    }
}
