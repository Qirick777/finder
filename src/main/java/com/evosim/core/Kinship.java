package com.evosim.core;

/**
 * 근친 회피 (설계서 §13-E). 형제(부모 공유)와 <b>직계 존비속 전체</b>(부모-자식·조부모-손주·증조…)를
 * 회피, 사촌·삼촌 등 방계는 허용(격리 집단 번식 보전).
 *
 * <p>직계는 "가족 링크 겹침"을 사전 계산한 조상 명단으로 판정: breed 가 자식에게 부모+양가 조상
 * 명단을 병합 저장({@link #combineAncestors}) → 상대가 내 조상 명단에 있으면 금지. 중간 세대(부모)가
 * 죽어 사라져도 명단은 남아 판정이 유지된다. 부모 ID 0 = 미상(1세대)이라 매칭 제외.
 */
public final class Kinship {

    /** 조상 명단 상한 — BFS 순이라 ≈4세대(2+4+8+16). 수명(청년35+노년8일)상 그 이상 공존 불가. */
    public static final int ANCESTOR_CAP = 30;

    private Kinship() {
    }

    /** 짝으로 부적합한 근친인가 (형제 또는 직계 존비속 — 부모·조부모·증조…). */
    public static boolean isRelated(Individual a, Individual b) {
        return sharesParent(a, b) || isParentChild(a, b) || isLineal(a, b);
    }

    /** 한쪽이 다른 쪽의 직계 조상인가(조상 명단 대조 — 조부모-손주 이상 차단). */
    private static boolean isLineal(Individual a, Individual b) {
        return contains(b.ancestorIds(), a.id()) || contains(a.ancestorIds(), b.id());
    }

    private static boolean contains(long[] ids, long id) {
        if (id == 0) {
            return false;
        }
        for (long x : ids) {
            if (x == id) {
                return true;
            }
        }
        return false;
    }

    /**
     * 자식의 직계 조상 명단 계산 — [부모A, 부모B] + 양가 명단 교차 병합(BFS 세대순 유지),
     * 0(미상)·중복(사촌혼 시 공유 조상) 제거, {@link #ANCESTOR_CAP} 상한.
     */
    public static long[] combineAncestors(Individual a, Individual b) {
        long[] la = a.ancestorIds();
        long[] lb = b.ancestorIds();
        long[] out = new long[Math.min(ANCESTOR_CAP, 2 + la.length + lb.length)];
        int n = 0;
        n = addUnique(out, n, a.id());
        n = addUnique(out, n, b.id());
        for (int i = 0; i < Math.max(la.length, lb.length) && n < out.length; i++) {
            if (i < la.length) {
                n = addUnique(out, n, la[i]);
            }
            if (i < lb.length && n < out.length) {
                n = addUnique(out, n, lb[i]);
            }
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    private static int addUnique(long[] out, int n, long id) {
        if (id == 0 || n >= out.length) {
            return n;
        }
        for (int i = 0; i < n; i++) {
            if (out[i] == id) {
                return n;
            }
        }
        out[n] = id;
        return n + 1;
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
