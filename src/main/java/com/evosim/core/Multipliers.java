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

    /** 채집 배율 (설계서 §15). 능력 축 보너스는 등급 비례(×g/5, Ⅴ=만액 — 밴드 산출 문서 ⑤).
     *  명석 재설계(성장 가속 패키지): 명석 = <b>능력 증폭기</b> — 양(+)의 능력 축 보너스에
     *  ×1.25(멍청 ×0.8), 기본 가산은 ±0.2→±0.1 하향. "같은 재능도 명석한 자가 더 크게 쓴다" —
     *  단독 명석은 미미, 능력자와 결합 시 발화(4종 콤보 아키타입). 음의 능력·성향 항은 비증폭. */
    public static double gather(Individual ind) {
        return gather(ind, 0);
    }

    /**
     * 채집 배율 + <b>획득 교육</b>({@link Schooling}) — 교육수준을 아는 호출부가 쓴다.
     *
     * <p>교육수준을 {@link Individual} 이 아니라 <b>인자로</b> 받는 이유: 등교 일수는 유전되면
     * 안 되는 획득값이라 엔티티에만 둔다({@code MimicEntity.schoolDays}). Individual 에 실으면
     * 언젠가 상속 코드가 함께 옮겨 "획득"이 "세습"이 된다. 기존 {@link #gather(Individual)} 는
     * 교육 0 으로 위임하므로 종전 호출부·테스트의 값은 바뀌지 않는다.
     */
    public static double gather(Individual ind, int schoolLevel) {
        Set<Trait> t = ExpressionResolver.expressedTraits(ind);
        double amp = abilityAmp(t);
        double m = 1.0;
        m += amp * scaled(ind, t, Trait.HERBALIST, 0.65); // 약초학자 Ⅴ=×1.65(증폭 전)
        m += scaled(ind, t, Trait.PLANT_CONFUSED, -0.5);  // 식물혼동 Ⅴ=×0.5
        m += amp * scaled(ind, t, Trait.DEXTEROUS, 0.2);  // 손재주(전체)
        m += scaled(ind, t, Trait.CLUMSY, -0.2);          // 곰손(전체)
        m += amp * scaled(ind, t, Trait.HERBIVORE, 0.2);  // 채식 채집↑
        m += scaled(ind, t, Trait.CARNIVORE, -0.3);       // 육식 채집↓
        m += scaled(ind, t, Trait.BRIGHT, 0.1);           // 명석 기본(증폭기 겸)
        m += scaled(ind, t, Trait.DULL, -0.1);            // 멍청 기본(감폭기 겸)
        m += scaled(ind, t, Trait.PRUDENT, 0.1);          // 신중 자원×1.1
        m += scaled(ind, t, Trait.RECKLESS, -0.1);        // 무모 자원×0.9
        m += amp * scaled(ind, t, Trait.GATHERER, 0.4);   // 채집꾼 0.3→0.4 — tileYield G 직결(성장 가속)
        m += scaled(ind, t, Trait.HUNTER, -0.1);          // 사냥꾼 채집딜레이
        m += scaled(ind, t, Trait.BASIC_EDUCATION, 0.1);  // 기본교육 — 제너럴리스트(채집·사냥 둘 다)
        m += amp * scaled(ind, t, Trait.INARTICULATE, 0.1); // 눌변가 — 말 대신 손(매력 −1의 반대급부)
        // 획득 교육 — 유전 기본교육(+0.1)의 아래에 둔다. 증폭기를 탄다(명석이 더 크게 쓴다).
        m += amp * Schooling.PER_LEVEL * Schooling.level(schoolLevel);
        if (t.contains(Trait.GATHERER) && t.contains(Trait.DEXTEROUS)) {
            m += 0.1; // 시너지: 숙련 채집조(채집꾼×손재주 동시 발현)
        }
        if (t.contains(Trait.AMBITIOUS) && (t.contains(Trait.HERBALIST)
                || t.contains(Trait.GATHERER) || t.contains(Trait.DEXTEROUS))) {
            m += 0.15; // 야망 몰입 — 야망이 재능을 몰아붙인다(채집 계열 능력 보유 시만, 무능력 야망은 무효)
        }
        return Math.max(0.0, m);
    }

    /**
     * 명석 = 능력 증폭 ×1.4 / 멍청 = ×0.8 — 양(+)의 능력 축 항에만 곱해진다(재설계 A안).
     *
     * <p>1.25→1.4 (약초학자 0.5→0.65 와 함께): 엘리트의 식량을 올리되 <b>바닥은 올리지 않는다</b>.
     * 증폭기는 능력 축의 양수 항에만 붙으므로 무능력자의 값은 그대로고, 감폭(0.8)도 그대로다.
     * 실측 엘리트(야망가+약초Ⅴ+명석)는 1.875 → 2.160 (+15%), 평범한 자는 1.0 불변.
     *
     * <p>{@code tileYield = TILE_YIELD_MULT × forageYieldMult} 이므로 <b>밭 산출도 함께 오른다</b> — 의도한
     * 것이다. 엘리트 지주는 채집과 밭 양쪽에서 벌어, 격차가 능력에서 나온다는 축이 굵어진다.
     */
    private static double abilityAmp(Set<Trait> t) {
        if (t.contains(Trait.BRIGHT)) {
            return 1.4;
        }
        if (t.contains(Trait.DULL)) {
            return 0.8;
        }
        return 1.0;
    }

    /** 사냥 배율 (설계서 §15). 능력 축 보너스는 등급 비례(×g/5, Ⅴ=만액 — 밴드 산출 문서 ⑤).
     *  명석 증폭기는 채집과 동일 규칙(양의 능력 축만 ×1.25/×0.8, 기본 ±0.1). */
    public static double hunt(Individual ind) {
        return hunt(ind, 0);
    }

    /** 사냥 배율 + <b>획득 교육</b> — 채집과 같은 규칙({@link #gather(Individual, int)} 참조). */
    public static double hunt(Individual ind, int schoolLevel) {
        Set<Trait> t = ExpressionResolver.expressedTraits(ind);
        double amp = abilityAmp(t);
        double m = 1.0;
        m += amp * scaled(ind, t, Trait.BUTCHER, 0.5);    // 도축업자 Ⅴ=×1.5(증폭 전)
        m += scaled(ind, t, Trait.BLOOD_FEARFUL, -0.5);   // 피공포 Ⅴ=×0.5
        m += amp * scaled(ind, t, Trait.DEXTEROUS, 0.2);  // 손재주(전체)
        m += scaled(ind, t, Trait.CLUMSY, -0.2);          // 곰손(전체)
        m += amp * scaled(ind, t, Trait.CARNIVORE, 0.2);  // 육식 사냥↑
        m += scaled(ind, t, Trait.HERBIVORE, -0.3);       // 채식 사냥↓
        m += scaled(ind, t, Trait.BRIGHT, 0.1);           // 명석 기본(증폭기 겸)
        m += scaled(ind, t, Trait.DULL, -0.1);            // 멍청 기본(감폭기 겸)
        m += scaled(ind, t, Trait.PRUDENT, 0.1);          // 신중 자원×1.1
        m += scaled(ind, t, Trait.RECKLESS, -0.1);        // 무모 자원×0.9
        m += amp * scaled(ind, t, Trait.HUNTER, 0.3);     // 사냥꾼 동물데미지↑
        m += scaled(ind, t, Trait.GATHERER, -0.3);        // 채집꾼 데미지↓
        m += scaled(ind, t, Trait.COMPETITIVE, 0.2);      // 경쟁 — 실리(사냥↑), 온화의 매력 가산과 대칭
        m += scaled(ind, t, Trait.BASIC_EDUCATION, 0.1);  // 기본교육 — 제너럴리스트(채집·사냥 둘 다)
        m += amp * scaled(ind, t, Trait.INARTICULATE, 0.1); // 눌변가 — 말 대신 손(매력 −1의 반대급부)
        // 획득 교육 — 채집과 같은 눈금·같은 증폭기.
        m += amp * Schooling.PER_LEVEL * Schooling.level(schoolLevel);
        return Math.max(0.0, m);
    }

    /** 특성 보너스 한 항 — 능력 축이면 등급 비례(×g/5), 무등급 축(성향·지능 등)이면 만액 그대로. */
    /**
     * 보완이 감소형 계수를 깎는 비율 / 악화가 더하는 비율 — 0.35.
     *
     * <p>패널티를 <b>없애지는 않는다</b>. 식물혼동Ⅴ(−0.5)를 −0.325 로 만드는 정도라, 감소형을
     * 가진 자가 안 가진 자를 앞지르지 못한다 — 촉매는 순위를 뒤집지 않는다는 원칙.
     */
    public static final double COMPENSATION_RELIEF = 0.35;

    /**
     * 특성 계수 — 능력 축이면 등급 비례(×g/5), 아니면 만액.
     *
     * <p><b>감소형(atV &lt; 0)에만</b> 보완·악화가 붙는다. 양(+)에 붙이면 그것은 촉매가 아니라
     * 또 하나의 능력이 되어, 안목(등급을 미는 축)과도 겹친다. 감소형을 하나도 안 가진 개체에겐
     * 곱할 대상이 없어 효과가 정확히 0이다.
     */
    private static double scaled(Individual ind, Set<Trait> t, Trait trait, double atV) {
        if (!t.contains(trait)) {
            return 0.0;
        }
        double v = trait.isAbility() ? atV * abilityGrade(ind, trait) / 5.0 : atV;
        if (v < 0.0) {
            if (t.contains(Trait.COMPENSATOR)) {
                v *= 1.0 - COMPENSATION_RELIEF;
            } else if (t.contains(Trait.AGGRAVATOR)) {
                v *= 1.0 + COMPENSATION_RELIEF;
            }
        }
        return v;
    }

    /** 능력 특성의 실효 등급(1~5) — 무등급 인스턴스(구 세이브·수동 생성)는 중앙 Ⅲ 취급. 미발현 0. */
    public static int abilityGrade(Individual ind, Trait trait) {
        int g = ExpressionResolver.expressedGrade(ind, trait);
        if (g == 0 && ExpressionResolver.isExpressed(ind, trait)) {
            g = 3;
        }
        return g;
    }

    /** 관리 능력 4종(약초학자·채집꾼·손재주·요리사 — canManageLarge 와 동일 집합)의 최고 실효 등급.
     *  명석 재설계 B안(경영 지능): 능력이 하나라도 있으면(best>0) 실효 +1등급(상한 Ⅴ) — 무능
     *  상속인 정체(런15 미리엄 실측)를 명석한 상속인이 완충하는 세대 리스크 축. 능력 0이면 그대로
     *  0(지능만으로는 경영 불가 — 능력 경사 유지). */
    public static int manageAbilityGrade(Individual ind) {
        int best = 0;
        best = Math.max(best, abilityGrade(ind, Trait.HERBALIST));
        best = Math.max(best, abilityGrade(ind, Trait.GATHERER));
        best = Math.max(best, abilityGrade(ind, Trait.DEXTEROUS));
        best = Math.max(best, abilityGrade(ind, Trait.COOK));
        if (best > 0 && ExpressionResolver.isExpressed(ind, Trait.BRIGHT)) {
            best = Math.min(5, best + 1);
        }
        // 보조 축(안목) — 명석과 같은 best>0 가드. 능력이 없으면 눈썰미가 있어도 정확히 0이라
        // 평민 경제(정원 M=1.0 · 적자)는 전혀 건드리지 않는다. 능력자에게만 등급이 ±1 되어
        // 정원(M(g))·관리용량(8+g³)·착공 시기가 동시에 움직인다 — 촉매형 보조의 표준형.
        // 도입 근거: 야생 착공이 d5~d18로 늦어 소작 전환이 제때 열리지 않으면, 무밭 출산(≈1)이
        // 인구 유지선(2.1) 아래라 1세대가 노령으로 빠질 때 개체군이 붕괴한다(t2 실측: d18
        // 착공 → 소작 0 → 인구 42→11). 착공 시기는 곧 개체군 존속 조건이다.
        if (best > 0 && ExpressionResolver.isExpressed(ind, Trait.KEEN_EYE)) {
            best = Math.min(5, best + 1);
        }
        if (best > 0 && ExpressionResolver.isExpressed(ind, Trait.DULL_EYE)) {
            best = Math.max(0, best - 1);
        }
        return best;
    }

    /**
     * 정원(옆 베리) 수확 배율 M(g) = 1 + 3.3×(g/5)³ — 관리 능력 등급 기준(무능력 g=0 → 1.0).
     * 1.6→3.3(A안 후속 — 격차 증폭): A1+A3로 평민 정원을 적자(−0.80/일)로 되돌린 뒤, 엘리트가
     * 정원+풀만으로 착공 자금 30에 닿지 못하고 최대 24에서 정체하는 것이 실측됐다(모델 예측
     * 23.96과 일치). 필요 G(5) = (30−14)/3 − 2.6 + 2.4 = 5.13 → M(5) ≥ 4.28 → 계수 ≥ 3.3.
     * <b>세제곱이라 저등급에는 사실상 무영향</b>(Ⅰ 1.013→1.026)이고 고등급만 오르므로, 평민
     * 적자(조건: 정원 < 소모)를 건드리지 않고 엘리트만 문턱을 넘게 한다 — 착공 임계 자체를
     * 낮추는 대안(B1)은 평민 최대치(21)와 마진이 1뿐이라 철회하고 이 경로를 택했다(마진 10.7).
     * 자급선 C(0)=2.40 기준 <b>g4부터 흑자</b> — "엘리트만 전진"이 수치로 성립한다.
     * <b>성중립·채집특성 무관</b>: 성별 곱(±3배 분산)이 밴드 간격을 압도해 정원에서는 제거.
     * m = 3.3. Ⅰ 1.026 / Ⅱ 1.211 / Ⅲ 1.713 / Ⅳ 2.690 / Ⅴ 4.3.
     */
    public static double gardenAbility(Individual ind) {
        double r = manageAbilityGrade(ind) / 5.0;
        return 1.0 + 3.3 * r * r * r;
    }

    /** 동물 탐지거리 배율 — 식물혼동은 식물 대신 동물에 눈이 감(+50%, 채집 ×0.5의 반대급부).
     *  명석 인지는 <b>식물 한정</b>(forageRange) — 회차 23: 동물 탐지에도 걸면 전원 명석 체계에서
     *  사냥(저효율 추격)이 풀 러시를 잠식해 d1 총소득이 1/3로 붕괴(런17 실측: hunt 10.7 첫 등장,
     *  grass 130→24). "계획적 채집 동선"은 채집 표적에만. */
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
        if (t.contains(Trait.BRIGHT)) m += 0.25;  // 명석 인지거리(재설계 — huntRange 와 대칭)
        if (t.contains(Trait.DULL)) m -= 0.15;    // 멍청 인지거리 감소
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
        if (t.contains(Trait.GATHERER) && t.contains(Trait.COOK)) {
            m += 0.1; // 시너지: 수확→가공 파이프라인(채집꾼×요리사 동시 발현)
        }
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
