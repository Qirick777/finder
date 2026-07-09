package com.evosim.core;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 발현 판정 (설계서 §2 "발현 판정 순서"). 발현은 저장하지 않고 참조할 때마다 성별로 재판정한다.
 *
 * <p>Phase 1 ①: 성별발현 판정만. 반발 카드 무효화(판정 2단계)는 Phase 1 ②에서 이 리졸버에 얹는다.
 * 마크에 안 얽힌 순수 함수(§18) → 배율·매력·검증이 모두 이걸 거친다.
 */
public final class ExpressionResolver {

    private ExpressionResolver() {
    }

    /** 개체의 성별에서 실제로 발동 중인 특성 목록(흔적 제외). */
    public static List<TraitInstance> expressed(Individual ind) {
        List<TraitInstance> out = new ArrayList<>();
        for (TraitInstance ti : ind.allTraits()) {
            if (ti.expressedFor(ind.sex())) {
                out.add(ti);
            }
        }
        return out;
    }

    /** 발동 중인 특성을 enum 집합으로 (배율·매력 계산용). */
    public static Set<Trait> expressedTraits(Individual ind) {
        EnumSet<Trait> out = EnumSet.noneOf(Trait.class);
        for (TraitInstance ti : ind.allTraits()) {
            if (ti.expressedFor(ind.sex())) {
                out.add(ti.trait());
            }
        }
        return out;
    }

    /** 이 개체 기준으로 해당 특성이 발동 중인가. */
    public static boolean isExpressed(Individual ind, Trait trait) {
        for (TraitInstance ti : ind.allTraits()) {
            if (ti.trait() == trait && ti.expressedFor(ind.sex())) {
                return true;
            }
        }
        return false;
    }
}
