package com.evosim.core;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 발현 판정 (설계서 §2 "발현 판정 순서"). 발현은 저장하지 않고 참조할 때마다 성별로 재판정한다.
 *
 * <p>판정 순서 (고정):
 * <ol>
 *   <li><b>성별발현</b>: 성별발현 태그로 누가 켜지나 (남녀발현 동시 = 상쇄 → 항상 발동).</li>
 *   <li><b>반발</b>: 켜진 것 중 반발 카드의 무력화 대상이 있으면 그 특성을 흔적으로 끈다.
 *       반발은 "지금 발현 중인 대상"만 끄고, 이미 흔적인 건 무시. 반발 카드 자신도 켜져 있어야 작동.</li>
 * </ol>
 *
 * <p>마크에 안 얽힌 순수 함수(§18) → 배율·매력·검증이 모두 이걸 거친다.
 */
public final class ExpressionResolver {

    private ExpressionResolver() {
    }

    /** 개체의 성별에서 실제로 발동 중인 특성 목록(흔적·무력화된 것 제외). */
    public static List<TraitInstance> expressed(Individual ind) {
        Sex sex = ind.sex();
        List<TraitInstance> all = ind.allTraits();

        // 1) 성별발현 판정 — 켜진 일반 특성.
        List<TraitInstance> active = new ArrayList<>();
        for (TraitInstance ti : all) {
            if (!ti.isAnti() && ti.expressedFor(sex)) {
                active.add(ti);
            }
        }
        // 2) 반발 판정 — 켜진 반발 카드가 발현 중인 대상을 흔적으로 끔.
        for (TraitInstance card : all) {
            if (card.isAnti() && card.expressedFor(sex)) {
                active.removeIf(ti -> ti.trait() == card.trait());
            }
        }
        return active;
    }

    /** 발동 중인 특성을 enum 집합으로 (배율·매력 계산용). */
    public static Set<Trait> expressedTraits(Individual ind) {
        EnumSet<Trait> out = EnumSet.noneOf(Trait.class);
        for (TraitInstance ti : expressed(ind)) {
            out.add(ti.trait());
        }
        return out;
    }

    /** 이 개체 기준으로 해당 특성이 발동 중인가. */
    public static boolean isExpressed(Individual ind, Trait trait) {
        for (TraitInstance ti : expressed(ind)) {
            if (ti.trait() == trait) {
                return true;
            }
        }
        return false;
    }

    /**
     * 발동 중인 해당 특성의 강도 등급(1~5). 발동 안 하면 0. 같은 특성 인스턴스가 여럿이면 가장 높은 등급.
     * (신체 스칼라·등급 선호 매칭용.)
     */
    public static int expressedGrade(Individual ind, Trait trait) {
        int best = 0;
        for (TraitInstance ti : expressed(ind)) {
            if (ti.trait() == trait && ti.grade() > best) {
                best = ti.grade();
            }
        }
        return best;
    }
}
