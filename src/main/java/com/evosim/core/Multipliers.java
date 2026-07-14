package com.evosim.core;

import java.util.Set;

/**
 * 배율·매력 함수 (설계서 §15 배율 특성 참조표, §14 선호). "흩어질 것을 함수 하나로 모은다"(§18).
 *
 * <p><b>합연산</b>: 각 배율 = 1.0 + (발동 중인 특성 보너스 합). 특성 추가 = 함수 안에 한 줄.
 * 오직 <b>발동(발현) 중인 특성</b>만 반영된다 → 흔적은 배율에 안 잡힘(발현과 자동 연동).
 *
 * <p>수치는 설계서 "밸런싱 초기값"의 1차 제안 — {@code /evobalance} 로 실측 보정 대상.
 * 등급(힘 V~I 등)은 Phase 3 도입 예정이라, 지금은 있음/없음 토큰 기준.
 */
public final class Multipliers {

    private Multipliers() {
    }

    /** 채집 배율 (설계서 §15). */
    public static double gather(Individual ind) {
        Set<Trait> t = ExpressionResolver.expressedTraits(ind);
        double m = 1.0;
        if (t.contains(Trait.HERBALIST)) m += 0.5;        // 약초학자 ×1.5
        if (t.contains(Trait.PLANT_CONFUSED)) m -= 0.5;   // 식물혼동 ×0.5
        if (t.contains(Trait.DEXTEROUS)) m += 0.2;        // 손재주(전체)
        if (t.contains(Trait.CLUMSY)) m -= 0.2;           // 곰손(전체)
        if (t.contains(Trait.HERBIVORE)) m += 0.2;        // 채식 채집↑
        if (t.contains(Trait.CARNIVORE)) m -= 0.3;        // 육식 채집↓
        if (t.contains(Trait.BRIGHT)) m += 0.2;           // 명석 자원↑
        if (t.contains(Trait.DULL)) m -= 0.2;             // 멍청 자원↓
        if (t.contains(Trait.PRUDENT)) m += 0.1;          // 신중 자원×1.1
        if (t.contains(Trait.RECKLESS)) m -= 0.1;         // 무모 자원×0.9
        if (t.contains(Trait.GATHERER)) m += 0.3;         // 채집꾼 채집사거리↑
        if (t.contains(Trait.HUNTER)) m -= 0.1;           // 사냥꾼 채집딜레이
        if (t.contains(Trait.BASIC_EDUCATION)) m += 0.1;  // 기본교육 — 제너럴리스트(채집·사냥 둘 다)
        if (t.contains(Trait.INARTICULATE)) m += 0.1;     // 눌변가 — 말 대신 손(매력 −1의 반대급부)
        return Math.max(0.0, m);
    }

    /** 사냥 배율 (설계서 §15). */
    public static double hunt(Individual ind) {
        Set<Trait> t = ExpressionResolver.expressedTraits(ind);
        double m = 1.0;
        if (t.contains(Trait.BUTCHER)) m += 0.5;          // 도축업자 ×1.5
        if (t.contains(Trait.BLOOD_FEARFUL)) m -= 0.5;    // 피공포 ×0.5
        if (t.contains(Trait.DEXTEROUS)) m += 0.2;        // 손재주(전체)
        if (t.contains(Trait.CLUMSY)) m -= 0.2;           // 곰손(전체)
        if (t.contains(Trait.CARNIVORE)) m += 0.2;        // 육식 사냥↑
        if (t.contains(Trait.HERBIVORE)) m -= 0.3;        // 채식 사냥↓
        if (t.contains(Trait.BRIGHT)) m += 0.2;           // 명석 자원↑
        if (t.contains(Trait.DULL)) m -= 0.2;             // 멍청 자원↓
        if (t.contains(Trait.PRUDENT)) m += 0.1;          // 신중 자원×1.1
        if (t.contains(Trait.RECKLESS)) m -= 0.1;         // 무모 자원×0.9
        if (t.contains(Trait.HUNTER)) m += 0.3;           // 사냥꾼 동물데미지↑
        if (t.contains(Trait.GATHERER)) m -= 0.3;         // 채집꾼 데미지↓
        if (t.contains(Trait.COMPETITIVE)) m += 0.2;      // 경쟁 — 실리(사냥↑), 온화의 매력 가산과 대칭
        if (t.contains(Trait.BASIC_EDUCATION)) m += 0.1;  // 기본교육 — 제너럴리스트(채집·사냥 둘 다)
        if (t.contains(Trait.INARTICULATE)) m += 0.1;     // 눌변가 — 말 대신 손(매력 −1의 반대급부)
        return Math.max(0.0, m);
    }

    /** 동물 탐지거리 배율 — 식물혼동은 식물 대신 동물에 눈이 감(+50%, 채집 ×0.5의 반대급부). */
    public static double huntRange(Individual ind) {
        return ExpressionResolver.isExpressed(ind, Trait.PLANT_CONFUSED) ? 1.5 : 1.0;
    }

    /**
     * 식물 탐지거리 배율 — 피공포 +50%(피를 피해 식물에 눈이 감), 공간지각 +25%(먹을거리 위치 기억),
     * 공간지각+식물혼동 <b>동시 발현이면 +50%</b>(시너지 — 눈으로는 혼동해도 공간 기억으로 보완,
     * 식물 한정. 채집 ×0.5 페널티 자체는 유지). 가산이라 피공포와 중첩 가능(최대 2.0).
     */
    public static double forageRange(Individual ind) {
        Set<Trait> t = ExpressionResolver.expressedTraits(ind);
        double m = 1.0;
        if (t.contains(Trait.BLOOD_FEARFUL)) m += 0.5;
        if (t.contains(Trait.GOOD_SPATIAL)) {
            m += t.contains(Trait.PLANT_CONFUSED) ? 0.5 : 0.25;
        }
        return m;
    }

    /**
     * 저장(가족 창고 유입) 배율 (설계서 §15 요리사/날로먹기). v2 정산에서는 L 정수성을 지키기 위해
     * "저장고 1유닛에 드는 H = 1/배율"로 적용된다({@link FoodEconomy#settleHome}).
     */
    public static double storage(Individual ind) {
        Set<Trait> t = ExpressionResolver.expressedTraits(ind);
        double m = 1.0;
        if (t.contains(Trait.COOK)) m += 0.2;             // 요리사 ×1.2
        if (t.contains(Trait.RAW_EATER)) m -= 0.2;        // 날로먹기 ×0.8 — 가공 없이 보관하면 상함
        if (t.contains(Trait.SPECIALIST_EDUCATION)) m += 0.15; // 전문교육 — 전문 기술(가공·저장)
        return Math.max(0.0, m);
    }

    /**
     * 매력점수 (구애 사양서 v2, 상대평가). 평가자의 발동 중인 <b>선호</b>로 상대의 발동 특성을 읽어 합산.
     *
     * <ul>
     *   <li><b>특정선호</b>(지정 특성 하나): 상대가 그 특성을 보유하면 <b>+2</b>.</li>
     *   <li><b>포괄선호</b>(능력·활력·헌신 등 개념군): 상대가 그 개념군 특성을 보유한 <b>개수마다 +1</b>.</li>
     * </ul>
     *
     * <p>오직 발동(발현) 중인 특성만 반영 → 흔적은 매력에 안 잡힌다. 값이 클수록 매력적.
     */
    /**
     * 부유선호 가점(표현층에서 합산) — 상대의 잉여(거처 저장고+소유 밭 계정, 월드 수치라 순수
     * 인자로 받음)를 생존일수로 환산해 3/9/27일 로그 문턱으로 등급화. [미확정] 문턱.
     */
    public static int wealthCharm(double wealth, double dailyNeed) {
        if (dailyNeed <= 0.0) {
            return 0;
        }
        double days = wealth / dailyNeed;
        return days >= 27.0 ? 3 : days >= 9.0 ? 2 : days >= 3.0 ? 1 : 0;
    }

    public static int charmScore(Individual evaluator, Individual target) {
        Set<Trait> pref = ExpressionResolver.expressedTraits(evaluator);
        Set<Trait> tt = ExpressionResolver.expressedTraits(target);
        int score = 0;

        // ── 특정선호 (+2): 지정한 특성 하나를 상대가 보유 시 ──
        score += spec(pref, Trait.PREF_STRENGTH, tt, Trait.STRONG);
        score += spec(pref, Trait.PREF_EFFICIENCY, tt, Trait.WEAK);   // 저비용
        score += spec(pref, Trait.PREF_FECUNDITY, tt, Trait.PROLIFIC);
        score += spec(pref, Trait.PREF_FEW_CHILDREN, tt, Trait.INFERTILE);
        score += spec(pref, Trait.PREF_STABILITY, tt, Trait.PREPARED);
        score += spec(pref, Trait.PREF_ADVENTURE, tt, Trait.IMPULSIVE);
        score += spec(pref, Trait.PREF_SWIFT, tt, Trait.FAST_PARENTING);
        score += spec(pref, Trait.PREF_SLOW, tt, Trait.SLOW_PARENTING);
        score += spec(pref, Trait.PREF_SMART, tt, Trait.BRIGHT);
        score += spec(pref, Trait.PREF_PLAIN, tt, Trait.DULL);
        score += spec(pref, Trait.PREF_WASTE, tt, Trait.STRONG);      // 핸디캡: 많이 먹는데 생존
        score += spec(pref, Trait.PREF_PEACE, tt, Trait.PEACEFUL);
        score += spec(pref, Trait.PREF_PIONEER, tt, Trait.MIGRATORY);

        // ── 포괄선호 (+1/매칭): 개념군 특성을 보유한 개수만큼 ──
        if (pref.contains(Trait.PREF_ABILITY)) {
            score += count(tt, Trait.BRIGHT, Trait.DEXTEROUS, Trait.HERBALIST,
                    Trait.BUTCHER, Trait.HUNTER, Trait.GATHERER, Trait.COOK);
        }
        if (pref.contains(Trait.PREF_SIMPLE)) {
            score += count(tt, Trait.DULL, Trait.CLUMSY);
        }
        if (pref.contains(Trait.PREF_VITALITY)) {
            score += count(tt, Trait.NIMBLE, Trait.FARSIGHTED, Trait.GOOD_SPATIAL);
        }
        if (pref.contains(Trait.PREF_SEDENTARY)) {
            score += count(tt, Trait.SLUGGISH, Trait.NEARSIGHTED, Trait.POOR_SPATIAL);
        }
        if (pref.contains(Trait.PREF_DEVOTION)) {
            score += count(tt, Trait.CHILD_LOVING, Trait.OVER_RESPONSIBLE,
                    Trait.STRONG_MATERNAL, Trait.ALTRUISTIC);
        }
        if (pref.contains(Trait.PREF_YIELD)) {
            // 생산력선호 — 상대의 벌이(성별×채집배율, 순수)를 등급 가점으로. [미확정] 문턱.
            double y = FoodEconomy.forageYieldMult(target);
            score += y >= 2.25 ? 3 : y >= 1.95 ? 2 : y >= 1.5 ? 1 : 0;
        }

        // 익숙함↔다양성 — 나와 겹치는/안 겹치는 발동 특성 수 (+1/매칭, 설계서 §14).
        if (pref.contains(Trait.PREF_FAMILIARITY)) {
            for (Trait x : tt) {
                if (pref.contains(x)) score++;
            }
        }
        if (pref.contains(Trait.PREF_DIVERSITY)) {
            for (Trait x : tt) {
                if (!pref.contains(x)) score++;
            }
        }

        // ── 언변(능력): 상대의 기본 매력 가감 — 달변가 +1 / 눌변가 −1 ──
        if (tt.contains(Trait.ELOQUENT)) {
            score += 1;
        }
        if (tt.contains(Trait.INARTICULATE)) {
            score -= 1;
        }
        if (tt.contains(Trait.LUXURIOUS)) {
            score += 1; // 사치 — 과시 소비의 매력(소모 +30%의 반대급부)
        }

        // ── 등급 선호 (신체 등급): 지정 목표 등급과 상대 보유 등급의 근접도 ──
        //    완전 일치 +3, 한 칸 차이마다 −1(예: 강건III선호 → I·V=+1, II·IV=+2, III=+3).
        score += gradedMatch(evaluator, target, Trait.PREF_TOUGHNESS, Trait.TOUGH);
        score += gradedMatch(evaluator, target, Trait.PREF_AGILITY, Trait.NIMBLE);
        score += gradedMatch(evaluator, target, Trait.PREF_VISION, Trait.FARSIGHTED);
        score += gradedMatch(evaluator, target, Trait.PREF_RECOVERY, Trait.HARDY);
        return score;
    }

    /**
     * 등급 선호 매칭 — 평가자가 등급 선호(prefTrait)를 발동 중이고 상대가 목표 특성(wanted)을 보유하면
     * {@code max(0, 3 − |선호등급 − 보유등급|)} 가점(완전 일치 3점, 한 칸 멀어질수록 1점씩 감소).
     */
    private static int gradedMatch(Individual evaluator, Individual target, Trait prefTrait, Trait wanted) {
        int pg = ExpressionResolver.expressedGrade(evaluator, prefTrait);
        if (pg <= 0) {
            return 0; // 선호 미발동
        }
        int tg = ExpressionResolver.expressedGrade(target, wanted);
        if (tg <= 0) {
            return 0; // 상대가 그 특성 없음
        }
        return Math.max(0, 3 - Math.abs(pg - tg));
    }

    /** 특정선호: 평가자가 prefTrait 선호를 발동 중이고 상대가 wanted 특성 보유 시 +2. */
    private static int spec(Set<Trait> pref, Trait prefTrait, Set<Trait> tt, Trait wanted) {
        return (pref.contains(prefTrait) && tt.contains(wanted)) ? 2 : 0;
    }

    /** 포괄선호: 상대가 보유한 개념군 특성 개수 (개수마다 +1). */
    @SafeVarargs
    private static int count(Set<Trait> set, Trait... traits) {
        int c = 0;
        for (Trait t : traits) {
            if (set.contains(t)) {
                c++;
            }
        }
        return c;
    }
}
