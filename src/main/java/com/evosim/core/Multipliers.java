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
     * 매력점수 (설계서 §10, §14). 평가자의 발동 중인 <b>선호</b> 특성을 훑어 상대의 발동 특성을 읽어 합산.
     *
     * <p>Phase 1: 등급 미도입이라 있음/없음 프록시로 근사. Phase 3 등급 도입 시 "높을수록" 비례로 교체.
     */
    public static int charmScore(Individual evaluator, Individual target) {
        Set<Trait> pref = ExpressionResolver.expressedTraits(evaluator);
        Set<Trait> tt = ExpressionResolver.expressedTraits(target);
        int score = 0;

        if (pref.contains(Trait.PREF_STRENGTH) && tt.contains(Trait.STRONG)) score++;
        if (pref.contains(Trait.PREF_EFFICIENCY) && tt.contains(Trait.WEAK)) score++;   // 저비용
        if (pref.contains(Trait.PREF_ABILITY) && anyOf(tt,
                Trait.BRIGHT, Trait.DEXTEROUS, Trait.HERBALIST, Trait.BUTCHER,
                Trait.HUNTER, Trait.GATHERER, Trait.COOK)) score++;
        if (pref.contains(Trait.PREF_SIMPLE) && anyOf(tt, Trait.DULL, Trait.CLUMSY)) score++;
        if (pref.contains(Trait.PREF_VITALITY)
                && anyOf(tt, Trait.NIMBLE, Trait.FARSIGHTED, Trait.GOOD_SPATIAL)) score++;
        if (pref.contains(Trait.PREF_SEDENTARY)
                && anyOf(tt, Trait.SLUGGISH, Trait.NEARSIGHTED, Trait.POOR_SPATIAL)) score++;
        if (pref.contains(Trait.PREF_FECUNDITY) && tt.contains(Trait.PROLIFIC)) score++;
        if (pref.contains(Trait.PREF_FEW_CHILDREN) && tt.contains(Trait.INFERTILE)) score++;
        if (pref.contains(Trait.PREF_STABILITY) && tt.contains(Trait.PREPARED)) score++;
        if (pref.contains(Trait.PREF_ADVENTURE) && tt.contains(Trait.IMPULSIVE)) score++;
        if (pref.contains(Trait.PREF_SWIFT) && tt.contains(Trait.FAST_PARENTING)) score++;
        if (pref.contains(Trait.PREF_SLOW) && tt.contains(Trait.SLOW_PARENTING)) score++;
        if (pref.contains(Trait.PREF_DEVOTION) && anyOf(tt,
                Trait.CHILD_LOVING, Trait.OVER_RESPONSIBLE, Trait.STRONG_MATERNAL, Trait.ALTRUISTIC)) score++;
        if (pref.contains(Trait.PREF_SMART) && tt.contains(Trait.BRIGHT)) score++;
        if (pref.contains(Trait.PREF_PLAIN) && tt.contains(Trait.DULL)) score++;
        if (pref.contains(Trait.PREF_WASTE) && tt.contains(Trait.STRONG)) score++;   // 핸디캡: 많이 먹는데 생존
        if (pref.contains(Trait.PREF_PEACE) && tt.contains(Trait.PEACEFUL)) score++;
        if (pref.contains(Trait.PREF_PIONEER) && tt.contains(Trait.MIGRATORY)) score++;

        // 익숙함↔다양성 — 유일한 배타 선호쌍: 나와 겹치는/안 겹치는 특성 수(설계서 §14).
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

    @SafeVarargs
    private static boolean anyOf(Set<Trait> set, Trait... traits) {
        for (Trait t : traits) {
            if (set.contains(t)) {
                return true;
            }
        }
        return false;
    }
}
