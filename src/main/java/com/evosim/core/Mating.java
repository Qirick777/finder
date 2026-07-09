package com.evosim.core;

/**
 * 짝짓기 조우 판정 (설계서 §10). 매력점수 + 기준선으로 짝 성립을 결정. 순수 함수.
 *
 * <p>핵심: <b>거절당할 때만 눈을 낮춘다</b>(기준선 −1). 척박한 마을은 다들 낮아져 결국 맺어짐 → 멸종 방지.
 * 성별 기본값: 여성 = 신중(높은 기준선), 남성 = 널널(낮은 기준선). 짝고르기 특성이 밀거나 당김.
 */
public final class Mating {

    // 기준선(높을수록 까다로움): 완전개방0 < 널널1 < 보통2 < 신중3 < 엄격4 (설계서 §10).
    public static final int OPEN = 0;
    public static final int LOOSE = 1;
    public static final int NORMAL = 2;
    public static final int PRUDENT = 3;
    public static final int STRICT = 4;

    private Mating() {
    }

    /** 조우 결과 — self 관점. */
    public enum Outcome {
        PAIR,      // 짝 성립(상호 수락)
        REJECTED,  // 상대가 나를 거절 → 내 기준선 −1
        CUT        // 상대는 나를 받아줬으나 내가 컷(기준선 유지)
    }

    /** 시작 기준선 — 짝고르기 특성 우선, 없으면 성별 기본(여=신중, 남=널널). */
    public static int startingBaseline(Individual ind) {
        if (ExpressionResolver.isExpressed(ind, Trait.STRICT_MATE)) {
            return STRICT;
        }
        if (ExpressionResolver.isExpressed(ind, Trait.OPEN_MATE)) {
            return OPEN;
        }
        return ind.sex() == Sex.FEMALE ? PRUDENT : LOOSE;
    }

    /**
     * self가 other를 조우 (설계서 §10):
     * <pre>
     * 내매력 &gt;= 상대기준선 ?          // 상대가 나를 받아주나
     *   아니오 → 거절당함(내기준선 −1)
     *   예 → 상대매력 &gt;= 내기준선 ?    // 나도 상대가 맘에 드나
     *          예 → 짝 성립 / 아니오 → 내가 컷
     * </pre>
     */
    public static Outcome encounter(Individual self, int selfBaseline, Individual other, int otherBaseline) {
        int myCharm = Multipliers.charmScore(other, self);    // 상대가 본 내 매력
        int otherCharm = Multipliers.charmScore(self, other); // 내가 본 상대 매력
        if (myCharm < otherBaseline) {
            return Outcome.REJECTED;
        }
        return otherCharm >= selfBaseline ? Outcome.PAIR : Outcome.CUT;
    }

    /** 거절당한 뒤 낮아진 기준선(최저 0). */
    public static int lowerBaseline(int baseline) {
        return Math.max(OPEN, baseline - 1);
    }

    /** 사별녀 하향 — 매력 기준 대폭 하향으로 빠른 재혼(설계서 §10). */
    public static int widowedBaseline(int baseline) {
        return OPEN; // 시작 기준선 −50%↓ → 사실상 완전개방(빠른 재혼)
    }
}
