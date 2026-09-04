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
        double amp = abilityAmp(ind, t);
        double m = 1.0;
        m += amp * scaled(ind, t, Trait.HERBALIST, 0.50); // 눈썰미 0.65→0.50(단독 하향 — 조합으로 되찾는다)
        m += amp * scaled(ind, t, Trait.COMPETENT, 0.15); // 유능함 — 단독은 잡동사니, 상한을 푸는 값이 본체
        m += scaled(ind, t, Trait.INEPT, -0.15);          // 서투름
        m += scaled(ind, t, Trait.PLANT_CONFUSED, -0.5);  // 식물혼동 Ⅴ=×0.5
        m += amp * scaled(ind, t, Trait.DEXTEROUS, 0.2);  // 손재주(전체)
        m += scaled(ind, t, Trait.CLUMSY, -0.2);          // 곰손(전체)
        m += amp * scaled(ind, t, Trait.HERBIVORE, 0.2);  // 채식 채집↑
        m += scaled(ind, t, Trait.CARNIVORE, -0.3);       // 육식 채집↓
        m += scaled(ind, t, Trait.BRIGHT, 0.1);           // 명석 기본(증폭기 겸)
        m += scaled(ind, t, Trait.DULL, -0.1);            // 멍청 기본(감폭기 겸)
        m += scaled(ind, t, Trait.PRUDENT, 0.1);          // 신중 자원×1.1
        m += scaled(ind, t, Trait.RECKLESS, -0.1);        // 무모 자원×0.9
        m += amp * scaled(ind, t, Trait.GATHERER, 0.3);   // 채집꾼 0.4→0.3(눈썰미와 함께 하향)
        m += scaled(ind, t, Trait.HUNTER, -0.1);          // 사냥꾼 채집딜레이
        m += scaled(ind, t, Trait.SCATTERED, -0.2);       // 산만 — 한자리에 못 붙어 있다
        m += amp * scaled(ind, t, Trait.FOCUSED, 0.15);   // 몰입 — 붙어 있는 만큼 번다
        m += scaled(ind, t, Trait.BRUTISH, -0.25);        // 단순무식 — 손이 굵다
        m += amp * scaled(ind, t, Trait.REFINED, 0.15);   // 섬세 — 손이 곱다
        m += scaled(ind, t, Trait.BASIC_EDUCATION, 0.1);  // 기본교육 — 제너럴리스트(채집·사냥 둘 다)
        m += amp * scaled(ind, t, Trait.INARTICULATE, 0.1); // 눌변가 — 말 대신 손(매력 −1의 반대급부)
        // 획득 교육 — 유전 기본교육(+0.1)의 아래에 둔다. 증폭기를 탄다(명석이 더 크게 쓴다).
        m += amp * Schooling.PER_LEVEL * Schooling.level(schoolLevel);
        if (t.contains(Trait.GATHERER) && t.contains(Trait.DEXTEROUS)) {
            m += 0.1; // 시너지: 숙련 채집조(채집꾼×손재주 동시 발현)
        }
        m += ambitionDrive(ind, t);
        return Math.max(0.0, m);
    }

    /** 야망 몰입이 붙는 채집 계열 능력 — 야망은 <b>재능을 몰아붙이는</b> 것이라 재능이 없으면 0. */
    private static final Trait[] AMBITION_TARGETS = {
        Trait.HERBALIST, Trait.GATHERER, Trait.DEXTEROUS, Trait.COMPETENT,
    };

    /**
     * 야망 몰입 — 채집 계열 재능이 있을 때만 붙고, 그 재능이 <b>Ⅴ 에 닿았으면</b> 두 배가 된다.
     * 무능력 야망은 종전대로 정확히 0 이다.
     */
    private static double ambitionDrive(Individual ind, Set<Trait> t) {
        if (!t.contains(Trait.AMBITIOUS)) {
            return 0.0;
        }
        int best = 0;
        for (Trait a : AMBITION_TARGETS) {
            best = Math.max(best, effectiveAbilityGrade(ind, a));
        }
        return best >= 5 ? 0.30 : best > 0 ? 0.15 : 0.0;
    }

    /** 명석·멍청 등급당 증폭 폭 — Ⅴ 에서 ×1.25 / ×0.75. */
    public static final double BRIGHT_AMP_PER = 0.05;
    /** 명석 게이트 ① — 굴릴 능력이 이만큼 있어야 열린다. */
    private static final int BRIGHT_GATE_ABILITIES = 2;
    /** 명석 게이트 ① 의 덤. */
    public static final double MASTERY_AMP = 0.15;
    /** 깜냥 게이트의 덤 — 상한 너머(실효 Ⅵ+) 능력을 굴릴 때만. */
    public static final double KEEN_EYE_AMP = 0.20;

    private static double abilityAmp(Set<Trait> t) {
        return abilityAmp(null, t);
    }

    /**
     * 능력 증폭기 — <b>혼자서는 거의 아무것도 아니고, 조건이 갖춰질 때만 커진다</b>.
     *
     * <pre>
     *   기본   1 + 0.05×명석등급   (Ⅴ → 1.25 · 멍청은 −0.05×등급)
     *   ①     + 0.15   양(+) 능력 특성 2종 이상 <b>이면서 명석 보유</b>
     *   ②     + 0.20   깜냥 + 상한 너머(실효 Ⅵ+) 능력 보유
     * </pre>
     *
     * <p>①에 명석 조건을 단 것은 <b>증폭기가 없는 자에게 증폭을 주지 않기 위해서</b>다. 능력
     * 둘 가진 평민은 종전에도 amp 1.0 이었고 지금도 1.0 이다 — 명석이 있어야 "굴릴 재료가
     * 둘"이라는 말이 성립한다.
     *
     * <p>②의 열쇠는 {@link #SUPER_GRADE} 이고 그 등급은 유능함 없이는 닿을 수 없다. 그래서
     * 깜냥은 <b>명석과 유능함이 둘 다 있을 때만</b> 켜진다 — 셋이 모여야 amp 가 1.60 이 된다.
     * 종전의 "깜냥 + Ⅴ 능력 둘"(MASTERY_AMP)은 유능함 없이도 열려 사슬이 한 칸 짧았다.
     *
     * <p>{@code ind == null}(집합만 아는 호출부)에서는 등급을 못 읽으므로 중앙 Ⅲ 로 본다.
     */
    private static double abilityAmp(Individual ind, Set<Trait> t) {
        if (ind == null) {
            return t.contains(Trait.BRIGHT) ? 1.0 + BRIGHT_AMP_PER * 3
                    : t.contains(Trait.DULL) ? 1.0 - BRIGHT_AMP_PER * 3 : 1.0;
        }
        int bright = brightGrade(ind);
        double base = 1.0;
        if (bright > 0) {
            base += BRIGHT_AMP_PER * bright;
        } else {
            base -= BRIGHT_AMP_PER * dullGrade(ind);
        }
        if (bright > 0 && abilityCount(ind) >= BRIGHT_GATE_ABILITIES) {
            base += MASTERY_AMP;
        }
        if (t.contains(Trait.KEEN_EYE) && hasSuperGrade(ind)) {
            base += KEEN_EYE_AMP;
        }
        return base;
    }

    /** 사냥 배율 (설계서 §15). 능력 축 보너스는 등급 비례(×g/5, Ⅴ=만액 — 밴드 산출 문서 ⑤).
     *  명석 증폭기는 채집과 동일 규칙(양의 능력 축만 ×1.25/×0.8, 기본 ±0.1). */
    public static double hunt(Individual ind) {
        return hunt(ind, 0);
    }

    /** 사냥 배율 + <b>획득 교육</b> — 채집과 같은 규칙({@link #gather(Individual, int)} 참조). */
    public static double hunt(Individual ind, int schoolLevel) {
        Set<Trait> t = ExpressionResolver.expressedTraits(ind);
        double amp = abilityAmp(ind, t);
        double m = 1.0;
        m += amp * scaled(ind, t, Trait.BUTCHER, 0.5);    // 도축업자 Ⅴ=×1.5(증폭 전)
        m += amp * scaled(ind, t, Trait.COMPETENT, 0.15); // 유능함 — 채집과 같은 눈금
        m += scaled(ind, t, Trait.INEPT, -0.15);          // 서투름
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
        m += scaled(ind, t, Trait.SCATTERED, -0.2);       // 산만 — 추적을 못 이어간다
        m += amp * scaled(ind, t, Trait.FOCUSED, 0.15);   // 몰입
        m += scaled(ind, t, Trait.BRUTISH, -0.25);        // 단순무식 — 몰이만 할 줄 안다
        m += amp * scaled(ind, t, Trait.REFINED, 0.15);   // 섬세
        m += scaled(ind, t, Trait.COMPETITIVE, 0.2);      // 경쟁 — 실리(사냥↑), 온화의 매력 가산과 대칭
        m += scaled(ind, t, Trait.BASIC_EDUCATION, 0.1);  // 기본교육 — 제너럴리스트(채집·사냥 둘 다)
        m += amp * scaled(ind, t, Trait.INARTICULATE, 0.1); // 눌변가 — 말 대신 손(매력 −1의 반대급부)
        // 획득 교육 — 채집과 같은 눈금·같은 증폭기.
        m += amp * Schooling.PER_LEVEL * Schooling.level(schoolLevel);
        return Math.max(0.0, m);
    }

    /** 특성 보너스 한 항 — 능력 축이면 등급 비례(×g/5), 무등급 축(성향·지능 등)이면 만액 그대로. */
    /**
     * 보완이 감소형 계수를 깎는 비율 / 야성이 키우는 비율.
     *
     * <p>0.35 → 0.15. 야성이 <b>순수 손해</b>이던 시절에는 0.35 가 그 특성의 전부였지만, 이제는
     * 키운 결손만큼 힘으로 돌려받는 <b>거래</b>다({@link #feralStrength}). 증폭이 크면 거래가
     * 아니라 도박이 되고, 채집·사냥이 둘 다 반토막 난 개체가 구걸에 닿기 전에 굶어 죽는다.
     * "미량 증폭"이 설계 의도다.
     */
    public static final double COMPENSATION_RELIEF = 0.15;

    /** 결손 1.0(채집·사냥이 완전히 0)당 힘 가산. 결손 0.5 면 힘 ×1.25. */
    public static final double FERAL_STRENGTH = 0.5;

    /**
     * <b>능력 결손</b> — 중립(1.0) 대비 채집·사냥 평균이 얼마나 깎였는가. 0(멀쩡) ~ 1(전무).
     *
     * <p>{@link #gather}·{@link #hunt} 를 그대로 읽는다. 감소형 항 목록을 여기 다시 적으면
     * 두 벌이 되어 언젠가 어긋나므로, <b>단일 출처를 다시 부르는</b> 쪽을 택했다. 두 함수는
     * 힘에 의존하지 않으므로 순환이 없다.
     */
    public static double deficit(Individual ind) {
        return Math.max(0.0, 1.0 - (gather(ind) + hunt(ind)) / 2.0);
    }

    /**
     * <b>야성의 힘 배수</b> — 결손에 비례해 공격력만 올린다(소모에는 얹지 않는다).
     *
     * <p>결손이 0 이면 정확히 1.0 이다. 능력이 멀쩡한 자가 야성을 들어도 <b>아무 일도 일어나지
     * 않는다</b> — 이미 망가진 자에게만 보상이 가는 것이 이 특성의 전부다.
     *
     * <p>소모를 안 올리는 이유: 대가는 이미 위 증폭된 결손으로 치렀다. 거기에 식욕까지 얹으면
     * "못 번다 × 많이 먹는다 × 전투 보상은 미지급"의 삼중고가 되어 구걸에 닿기 전에 죽는다.
     */
    public static double feralStrength(Individual ind) {
        if (!ExpressionResolver.isExpressed(ind, Trait.AGGRAVATOR)) {
            return 1.0;
        }
        return 1.0 + FERAL_STRENGTH * deficit(ind);
    }

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
        double v;
        if (trait.isAbility()) {
            // 양(+)에만 승격을 먹인다 — 유능함이 식물혼동·곰손을 <b>더 나쁘게</b> 만들면 안 된다.
            int g = atV > 0 ? effectiveAbilityGrade(ind, trait) : abilityGrade(ind, trait);
            v = atV * g / 5.0;
        } else if (trait.axis() == Axis.INTELLIGENCE) {
            // 명석·멍청은 신체 축으로 옮기며 등급화됐다 — 기본 가산도 등급 비례가 된다.
            v = atV * physGrade(ind, trait) / 5.0;
        } else {
            v = atV;
        }
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

    /** 신체 등급 특성(명석·활력 등)의 실효 등급 — 단련·쇠약 반영 + 무등급은 중앙 Ⅲ. */
    private static int physGrade(Individual ind, Trait trait) {
        int g = Physique.grade(ind, trait);
        if (g == 0 && ExpressionResolver.isExpressed(ind, trait)) {
            g = 3;
        }
        return g;
    }

    /** 명석의 실효 등급(0~5). 신체 축으로 옮기며 등급화됐다. */
    public static int brightGrade(Individual ind) {
        return physGrade(ind, Trait.BRIGHT);
    }

    /** 멍청의 실효 등급(0~5). */
    public static int dullGrade(Individual ind) {
        return physGrade(ind, Trait.DULL);
    }

    /**
     * 유능함이 상한을 푸는 대상 — <b>양(+)의 능력 특성</b>만. 음(−)까지 밀면 유능함이
     * 식물혼동·곰손을 더 나쁘게 만드는 꼴이 된다.
     */
    private static final Trait[] ABILITY_UP = {
        Trait.HERBALIST, Trait.BUTCHER, Trait.HUNTER, Trait.GATHERER,
        Trait.DEXTEROUS, Trait.HERBIVORE, Trait.CARNIVORE, Trait.COOK, Trait.ELOQUENT,
    };

    /** 유능함이 능력 등급을 미는 폭 — {@code +등급/2}(Ⅴ → +2). */
    private static final int COMPETENCE_LIFT_DIV = 2;

    /** 이 등급 이상이면 "상한 너머" — 반경 게이트·깜냥 게이트·회전 게이트의 공통 열쇠. */
    public static final int SUPER_GRADE = 6;

    /**
     * <b>유능함이 Ⅴ 상한을 푼다</b> — 능력 특성의 실효 등급을 {@code +유능함등급/2} 만큼 위로 민다.
     *
     * <p>유능함 단독은 채집 +0.03/등급짜리 잡동사니다. 값어치는 전부 여기 있다: 눈썰미Ⅴ 옆에
     * 유능함Ⅴ 가 오면 눈썰미가 <b>Ⅶ</b> 로 읽혀 계수가 0.50 → 0.70 이 되고, 동시에
     * {@link #SUPER_GRADE} 문턱을 넘겨 반경 게이트와 깜냥 게이트를 <b>둘 다</b> 통과시킨다.
     * 유능함이 빠지면 그 세 개가 한꺼번에 닫힌다 — 사슬이 중간에서 끊기게 하는 지점이다.
     *
     * <p>자기 자신은 승격 대상이 아니다(상호 승격 폭주 차단). 음(−) 능력도 아니다.
     */
    public static int effectiveAbilityGrade(Individual ind, Trait a) {
        int g = abilityGrade(ind, a);
        if (g <= 0) {
            return g;
        }
        boolean up = false;
        for (Trait t : ABILITY_UP) {
            if (t == a) {
                up = true;
                break;
            }
        }
        if (!up) {
            return g;
        }
        int c = abilityGrade(ind, Trait.COMPETENT);
        return c <= 0 ? g : g + c / COMPETENCE_LIFT_DIV;
    }

    /** 상한 너머(실효 Ⅵ+) 능력을 하나라도 가졌는가 — 유능함 없이는 도달 불가. */
    public static boolean hasSuperGrade(Individual ind) {
        for (Trait t : ABILITY_UP) {
            if (effectiveAbilityGrade(ind, t) >= SUPER_GRADE) {
                return true;
            }
        }
        return false;
    }

    /**
     * 보유한 양(+) 능력 특성의 수 — 명석이 "굴릴 재료"가 얼마나 되는지.
     *
     * <p>유능함도 <b>센다</b>. {@link #ABILITY_UP} 은 "유능함이 밀어 올릴 대상" 목록이라 자기
     * 자신이 빠져 있는데, 그것을 그대로 세면 눈썰미Ⅴ+유능함Ⅴ 를 능력 1종으로 읽어 명석
     * 게이트가 안 열린다(엘리트 채집이 2.76 대신 2.63 으로 나왔다).
     */
    private static int abilityCount(Individual ind) {
        int n = abilityGrade(ind, Trait.COMPETENT) > 0 ? 1 : 0;
        for (Trait t : ABILITY_UP) {
            if (abilityGrade(ind, t) > 0) {
                n++;
            }
        }
        return n;
    }

    /** 관리 등급 상한 — Ⅴ 에서 Ⅵ 로 열었다. 정원 M(6)=6.70 · 용량 8+6³=224. */
    public static final int MANAGE_GRADE_MAX = 6;
    /** 자산 점수를 등급으로 나누는 눈금 — 능력 Ⅴ 하나(raw 5)가 g1 이 되게 하는 값. */
    private static final int MANAGE_RAW_PER_GRADE = 3;

    /**
     * 관리 능력 등급 — <b>최고 하나(max)에서 합산(sum)으로 바꿨다</b>.
     *
     * <p>종전 식은 {@code max(약초,채집꾼,손재주,요리사)} 뒤에 명석 +1·깜냥 +1 을 상한 Ⅴ 로
     * 잘랐다. 그래서 <b>눈썰미Ⅳ 하나뿐인 평민 · 눈썰미Ⅴ 하나뿐인 평민 · 능력 Ⅴ 둘에 명석과
     * 깜냥까지 붙은 엘리트가 전부 g5(정원 4.30 · 용량 133)로 같았다</b> — 두 번째 Ⅴ 와 명석·깜냥의
     * +1 셋이 통째로 상한에서 버려졌다. 엘리트가 엘리트가 아니던 근본 원인이다.
     *
     * <pre>
     *   raw  = Σ(관리 4종 등급) + 유능함 등급          ← 승격 전 원등급으로 센다
     *   +3   명석 보유 &amp;&amp; raw ≥ 6                     ← 굴릴 자산이 있어야 경영이 붙는다
     *   +4   깜냥 &amp;&amp; 상한 너머(실효 Ⅵ+) 능력 보유
     *   −3   무딤 (raw ≥ 6 일 때만 — 무능력자는 어차피 0)
     *   +3   활력 게이트 열림 &amp;&amp; 상한 너머 능력 보유    ← 돌아다니며 챙기는 양
     *   g    = clamp(0, 6, raw / 3)
     * </pre>
     *
     * <p><b>승격 전 원등급</b>으로 합산하는 이유: 실효등급으로 세면 눈썰미Ⅴ+유능함Ⅴ 두 장만으로
     * raw 12 → g4(정원 2.69)가 되어 "둘만 모여도 사기"가 된다. 원등급이면 raw 10 → g3(1.71)이고,
     * 명석·깜냥·활력 게이트가 다 열려야 raw 20 → g6 에 닿는다.
     *
     * <p>{@code raw == 0} 가드는 그대로다 — 능력이 하나도 없으면 명석·깜냥이 있어도 정확히 0이라
     * 평민 정원 경제(M=1.0 · 적자)는 전혀 건드리지 않는다.
     */
    public static int manageAbilityGrade(Individual ind) {
        int raw = abilityGrade(ind, Trait.HERBALIST)
                + abilityGrade(ind, Trait.GATHERER)
                + abilityGrade(ind, Trait.DEXTEROUS)
                + abilityGrade(ind, Trait.COOK)
                + abilityGrade(ind, Trait.COMPETENT);
        if (raw <= 0) {
            return 0; // 지능만으로는 경영 불가 — 능력 경사 유지
        }
        boolean superGrade = hasSuperGrade(ind);
        if (raw >= 6) {
            if (brightGrade(ind) > 0) {
                raw += 3;
            } else if (dullGrade(ind) > 0) {
                raw -= 3;
            }
        }
        if (superGrade && ExpressionResolver.isExpressed(ind, Trait.KEEN_EYE)) {
            raw += 4;
        }
        if (ExpressionResolver.isExpressed(ind, Trait.DULL_EYE)) {
            raw -= 3;
        }
        if (superGrade && Physique.vitalityGateOpen(ind)) {
            raw += 3;
        }
        return Math.max(0, Math.min(MANAGE_GRADE_MAX, raw / MANAGE_RAW_PER_GRADE));
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
     * m = 3.3. Ⅰ 1.026 / Ⅱ 1.211 / Ⅲ 1.713 / Ⅳ 2.690 / Ⅴ 4.3 / <b>Ⅵ 6.70</b>.
     *
     * <p>상한이 Ⅵ 로 열렸다({@link #MANAGE_GRADE_MAX}) — 식은 그대로 두고 도달 조건만 늘렸다.
     * Ⅵ 는 사슬(능력 Ⅴ 둘 + 명석 + 깜냥 + 활력 게이트)이 전부 닫혀야 나오는 값이라, 그 아래
     * 구간의 값은 한 톨도 바뀌지 않는다.
     */
    public static double gardenAbility(Individual ind) {
        double r = manageAbilityGrade(ind) / 5.0;
        return 1.0 + 3.3 * r * r * r;
    }

    /**
     * <b>배회 시간에도 일하는가</b> — {@code MimicForageGoal} 의 여가 컷 문지기와 계측이
     * 공유하는 단 하나의 술어.
     *
     * <p>이 판정이 노동창을 7000틱(WORK)에서 11000틱(WORK+WANDER)으로 <b>+57%</b> 늘리므로,
     * 소득 계측이 이것을 따로 베껴 두면 언젠가 한쪽만 고쳐져 보고가 조용히 거짓이 된다
     * ({@code Satisfaction.bar} 와 같은 이유로 함수 하나에 모은다).
     *
     * <p><b>실효 Ⅴ 전용</b>이다. 명석이 성향(축 34개)에서 신체(축 8개)로 옮겨 오며 보유율이
     * 8.8%→37.5% 로 네 배가 됐는데, 종전의 "명석이면 배회에도 노동"을 그대로 두면 야생 미믹
     * 셋 중 하나가 노동 시간 +57% 를 공짜로 얻는다 — 엘리트를 세우려다 평민을 통째로 올리는
     * 꼴이다. Ⅴ 로 좁히면 발현율이 종전(4.4%) 아래로 내려간다.
     */
    public static boolean brightDriven(Individual ind) {
        return brightGrade(ind) >= 5;
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
        m += 0.05 * brightGrade(ind);             // 명석 인지거리 — 등급화(Ⅴ 에서 종전 +0.25 와 동일)
        m -= 0.03 * dullGrade(ind);               // 멍청 인지거리 감소
        // 눈썰미 게이트 — 실효 Ⅵ 이상이면 표적 선별이 열린다. 그 등급은 유능함 없이는 못 닿는다.
        // 손이 아무리 빨라도 볼 표적이 없으면 대기로 새므로, 이 반경이 회전율을 소득으로 바꾼다.
        if (effectiveAbilityGrade(ind, Trait.HERBALIST) >= SUPER_GRADE) {
            m *= 1.25;
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
                    Trait.BUTCHER, Trait.HUNTER, Trait.GATHERER, Trait.COOK, Trait.COMPETENT);
        }
        if (pref.contains(Trait.PREF_SIMPLE)) {
            score += count(tt, Trait.DULL, Trait.CLUMSY, Trait.INEPT);
        }
        if (pref.contains(Trait.PREF_VITALITY)) {
            score += count(tt, Trait.NIMBLE, Trait.FARSIGHTED, Trait.GOOD_SPATIAL, Trait.VIGOROUS);
        }
        if (pref.contains(Trait.PREF_SEDENTARY)) {
            score += count(tt, Trait.SLUGGISH, Trait.NEARSIGHTED, Trait.POOR_SPATIAL, Trait.LISTLESS);
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
