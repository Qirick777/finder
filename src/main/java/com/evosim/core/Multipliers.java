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
        return Math.max(0.0, m);
    }

    /** 저장(가족 창고 유입) 배율 (설계서 §15 요리사/요리치). */
    public static double storage(Individual ind) {
        Set<Trait> t = ExpressionResolver.expressedTraits(ind);
        double m = 1.0;
        if (t.contains(Trait.COOK)) m += 0.2;             // 요리사 ×1.2
        if (t.contains(Trait.BAD_COOK)) m -= 0.2;         // 요리치 ×0.8
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
        return score;
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
