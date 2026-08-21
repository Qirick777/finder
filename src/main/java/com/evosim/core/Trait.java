package com.evosim.core;

/**
 * 특성 카탈로그 (설계서 §14 특성 마스터 목록).
 *
 * <p>각 특성은 하나의 축에 속한다. 같은 축의 반발 특성은 한 개체가 동시에 못 가진다.
 * 안 가진 축은 자동으로 중립(설계서 §2).
 *
 * <p>등급(힘 V~I 등)은 Phase 3에서 도입 — Phase 0/1은 특성 토큰의 유전·발현만 다룬다.
 */
public enum Trait {
    // ── 성향 ──
    LAZY(Axis.DILIGENCE, "게으름"),
    DILIGENT(Axis.DILIGENCE, "부지런"),
    COWARD(Axis.COURAGE, "겁쟁이"),
    BRAVE(Axis.COURAGE, "용감함"),
    RECKLESS(Axis.RETREAT, "무모"),
    PRUDENT(Axis.RETREAT, "신중"),
    CAUTIOUS(Axis.DETECTION, "조심성"),
    BOLD(Axis.DETECTION, "대담함"),
    CHILD_AVERSE(Axis.CHILD_PREFERENCE, "아이불호"),
    CHILD_LOVING(Axis.CHILD_PREFERENCE, "아이선호"),
    IMPULSIVE(Axis.PREPARATION, "즉흥적"),
    PREPARED(Axis.PREPARATION, "준비성"),
    SELFISH(Axis.SHARING, "이기"),
    ALTRUISTIC(Axis.SHARING, "이타"),
    STINGY(Axis.SHARING_RANGE, "인색"),
    GENEROUS(Axis.SHARING_RANGE, "관대"),
    HOMEBOUND(Axis.SETTLEMENT, "애향심"),
    MIGRATORY(Axis.SETTLEMENT, "이주자"),
    PEACEFUL(Axis.RESOURCE_COMPETITION, "평화"),
    COMPETITIVE(Axis.RESOURCE_COMPETITION, "경쟁"),
    SOLITARY(Axis.GREGARIOUSNESS, "고독"),
    GREGARIOUS(Axis.GREGARIOUSNESS, "군집"),
    PRESENT_ORIENTED(Axis.TIME_ORIENTATION, "현재지향"),
    FUTURE_ORIENTED(Axis.TIME_ORIENTATION, "미래지향"),
    EARLY_MARRIAGE(Axis.MARRIAGE_TIMING, "조혼"),
    LATE_MARRIAGE(Axis.MARRIAGE_TIMING, "만혼"),
    SLOW_PARENTING(Axis.PARENTING_SPEED, "느린육아"),
    FAST_PARENTING(Axis.PARENTING_SPEED, "빠른육아"),
    LONG_INVESTMENT(Axis.INVESTMENT, "장기투자"),
    QUICK_INVESTMENT(Axis.INVESTMENT, "신속투자"),
    SPECIALIST_EDUCATION(Axis.EDUCATION, "전문교육"),
    BASIC_EDUCATION(Axis.EDUCATION, "기본교육"),
    REPRODUCTION_AVERSE(Axis.REPRODUCTION_PREF, "번식불호"),
    REPRODUCTION_EAGER(Axis.REPRODUCTION_PREF, "번식선호"),
    INFERTILE(Axis.FERTILITY, "난임"),
    PROLIFIC(Axis.FERTILITY, "다산"),
    DULL(Axis.INTELLIGENCE, "멍청"),
    BRIGHT(Axis.INTELLIGENCE, "명석"),
    IRRESPONSIBLE(Axis.RESPONSIBILITY, "무책임"),
    OVER_RESPONSIBLE(Axis.RESPONSIBILITY, "과한책임"),
    NO_MATERNAL(Axis.MATERNAL, "모성애없음"),
    STRONG_MATERNAL(Axis.MATERNAL, "강한모성애"),
    STRICT_MATE(Axis.MATE_CHOICE, "엄격"),
    OPEN_MATE(Axis.MATE_CHOICE, "완전개방"),
    AMBITIOUS(Axis.AMBITION, "야망가"),       // 만족 기준이 밭 자산(49타일) — 대지주 동기(능력 필요)
    CONTENT(Axis.AMBITION, "안분지족"),        // 일찍 만족(σ 절반) — 소농 수렴
    GREEDY(Axis.GREED, "욕심"),               // 어떤 부에도 만족 불가 — 무한 축장(영원한 노동이 대가)
    ASCETIC(Axis.GREED, "무욕"),              // 일찍 만족 + 밭 확장 안 함
    LUXURIOUS(Axis.SPENDING, "사치"),         // 소모 +30%(만족 기준 자동 상승) ↔ 과시 매력 +1
    FRUGAL(Axis.SPENDING, "검소"),            // 소모 −10% — 일찍 만족

    // ── 신체 ──
    STRONG(Axis.STRENGTH, "힘센"),
    WEAK(Axis.STRENGTH, "약함"),
    TOUGH(Axis.TOUGHNESS, "튼튼"),
    FRAIL(Axis.TOUGHNESS, "빈약"),
    NIMBLE(Axis.AGILITY, "재빠름"),
    SLUGGISH(Axis.AGILITY, "굼뜸"),
    FARSIGHTED(Axis.VISION, "천리안"),
    NEARSIGHTED(Axis.VISION, "근시안"),
    GOOD_SPATIAL(Axis.SPATIAL, "공간지각"),
    POOR_SPATIAL(Axis.SPATIAL, "길치"),
    HARDY(Axis.RECOVERY, "강건"),
    SICKLY(Axis.RECOVERY, "병약"),

    // ── 능력 (배율 특성, 성향 슬롯 공유) ──
    HERBALIST(Axis.GATHER_SKILL, "약초학자"),
    PLANT_CONFUSED(Axis.GATHER_SKILL, "식물혼동"),
    BUTCHER(Axis.HUNT_SKILL, "도축업자"),
    BLOOD_FEARFUL(Axis.HUNT_SKILL, "피공포"),
    HUNTER(Axis.ACQUISITION, "사냥꾼"),
    GATHERER(Axis.ACQUISITION, "채집꾼"),
    DEXTEROUS(Axis.DEXTERITY, "손재주"),
    CLUMSY(Axis.DEXTERITY, "곰손"),
    HERBIVORE(Axis.DIET, "채식"),
    CARNIVORE(Axis.DIET, "육식"),
    COOK(Axis.COOKING, "요리사"),
    RAW_EATER(Axis.COOKING, "날로먹기"), // 구명 BAD_COOK(요리치) — NBT 레거시 별칭은 IndividualNbt
    ELOQUENT(Axis.ELOQUENCE, "달변가"),
    INARTICULATE(Axis.ELOQUENCE, "눌변가"),

    // ── 선호 ──
    PREF_STRENGTH(Axis.STRENGTH_PREF, "강함선호"),
    PREF_EFFICIENCY(Axis.STRENGTH_PREF, "효율선호"),
    PREF_ABILITY(Axis.ABILITY_PREF, "능력선호"),
    PREF_SIMPLE(Axis.ABILITY_PREF, "소박선호"),
    PREF_VITALITY(Axis.VITALITY_PREF, "활력선호"),
    PREF_SEDENTARY(Axis.VITALITY_PREF, "정주선호"),
    PREF_FECUNDITY(Axis.FECUNDITY_PREF, "다산선호"),
    PREF_FEW_CHILDREN(Axis.FECUNDITY_PREF, "소산선호"),
    PREF_STABILITY(Axis.STABILITY_PREF, "안정선호"),
    PREF_ADVENTURE(Axis.STABILITY_PREF, "모험선호"),
    PREF_SWIFT(Axis.SPEED_PREF, "신속선호"),
    PREF_SLOW(Axis.SPEED_PREF, "지연선호"),
    PREF_DEVOTION(Axis.DEVOTION_PREF, "헌신선호"),
    PREF_FAMILIARITY(Axis.FAMILIARITY_PREF, "익숙함선호"),
    PREF_DIVERSITY(Axis.FAMILIARITY_PREF, "다양성선호"),
    PREF_SMART(Axis.INTELLIGENCE_PREF, "똑똑함선호"),
    PREF_PLAIN(Axis.INTELLIGENCE_PREF, "단순함선호"),
    PREF_WASTE(Axis.WASTE_PREF, "낭비선호"),
    PREF_PEACE(Axis.PEACE_PREF, "평화선호"),
    PREF_PIONEER(Axis.PEACE_PREF, "개척선호"),
    PREF_WEALTH(Axis.PROVISION_PREF, "부유선호"),     // 잉여(저장고+밭계정) 보유량 → 매력
    PREF_YIELD(Axis.PROVISION_PREF, "생산력선호"),    // 벌이(성별×채집배율) → 매력

    // ── 등급 선호 (신체 등급 매칭) — 선호 자신도 목표 등급을 가진다(예: 튼튼II선호) ──
    PREF_TOUGHNESS(Axis.TOUGHNESS_PREF, "튼튼선호"),
    PREF_AGILITY(Axis.AGILITY_PREF, "재빠름선호"),
    PREF_VISION(Axis.VISION_PREF, "천리안선호"),
    PREF_RECOVERY(Axis.RECOVERY_PREF, "강건선호"),

    // ── 보조 (AUXILIARY) ──
    /** 자수성가 — 만족선 σ 상향(2.0 → 3.5). 부유해져도 쉽게 멈추지 않는다. 가난하면 만족선
     *  근처도 못 가므로 <b>단독 효과 0</b>. 능력과 맞물릴 때만 "만족의 덫"을 풀어 착공을 연다. */
    SELF_MADE(Axis.ASPIRATION, "자수성가"),
    /** 안분 — 만족선 σ 하향(2.0 → 1.4). 일찍 멈춰 축적이 착공 임계에 닿지 않는다. */
    MODEST(Axis.ASPIRATION, "안분");

    private final Axis axis;
    private final String koreanName;

    Trait(Axis axis, String koreanName) {
        this.axis = axis;
        this.koreanName = koreanName;
    }

    // 등급(I~V)을 갖는 축 — 신체 스탯 전부 + 그 등급 선호. 나머지는 무등급(grade 0).
    private static final java.util.EnumSet<Axis> GRADED_AXES = java.util.EnumSet.of(
            Axis.STRENGTH, Axis.TOUGHNESS, Axis.AGILITY, Axis.VISION, Axis.SPATIAL, Axis.RECOVERY,
            Axis.TOUGHNESS_PREF, Axis.AGILITY_PREF, Axis.VISION_PREF, Axis.RECOVERY_PREF);

    // 능력 특성 축(배율 특성) — 성향과 슬롯을 공유하되 '능력'으로 구분(표시·판정용).
    private static final java.util.EnumSet<Axis> ABILITY_AXES = java.util.EnumSet.of(
            Axis.GATHER_SKILL, Axis.HUNT_SKILL, Axis.ACQUISITION,
            Axis.DEXTERITY, Axis.DIET, Axis.COOKING, Axis.ELOQUENCE);

    public Axis axis() {
        return axis;
    }

    /** 이 특성이 강도 등급(I~V)을 갖는가 — 신체 스탯·그 등급 선호 + 능력 특성(밴드 산출 문서 ④). */
    public boolean isGraded() {
        return GRADED_AXES.contains(axis) || ABILITY_AXES.contains(axis);
    }

    /** 능력 특성인가 — 성향 슬롯을 공유하는 배율 특성(획득 계열·언변). */
    public boolean isAbility() {
        return ABILITY_AXES.contains(axis);
    }

    public Category category() {
        return axis.category();
    }

    public String koreanName() {
        return koreanName;
    }

    /**
     * 두 특성이 반발하는가 (한 개체에 동시 발현 불가).
     * 같은 반발 축(exclusive)에 속하면 반발. 같은 특성끼리는 중복일 뿐 반발 아님.
     */
    public boolean conflictsWith(Trait other) {
        return this != other && this.axis == other.axis && this.axis.exclusive();
    }
}
