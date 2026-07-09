package com.evosim.core;

/**
 * 근친 회피 (설계서 §13-E). 형제(부모 공유)와 부모-자식만 회피, 사촌은 허용(격리 집단 번식 보전).
 *
 * <p>저장 중인 parentA/B ID 비교만으로 처리 — 추가 저장 0. 부모 ID 0 = 미상(1세대)이라 매칭 제외.
 */
public final class Kinship {

    private Kinship() {
    }

    /** 짝으로 부적합한 근친인가 (형제 또는 부모-자식). */
    public static boolean isRelated(Individual a, Individual b) {
        return sharesParent(a, b) || isParentChild(a, b);
    }

    private static boolean sharesParent(Individual a, Individual b) {
        return sharedWith(a.parentAId(), b) || sharedWith(a.parentBId(), b);
    }

    private static boolean sharedWith(long parentId, Individual b) {
        return parentId != 0 && (parentId == b.parentAId() || parentId == b.parentBId());
    }

    private static boolean isParentChild(Individual a, Individual b) {
        return (a.id() != 0 && (a.id() == b.parentAId() || a.id() == b.parentBId()))
                || (b.id() != 0 && (b.id() == a.parentAId() || b.id() == a.parentBId()));
    }
}
