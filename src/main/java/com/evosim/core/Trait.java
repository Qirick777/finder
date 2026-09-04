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
    /** 활력적 — 일찍 깨고 늦게 자며 손이 빠르다. 대가는 소모 +3%/등급이라 <b>단독은 적자</b>.
     *  이동 배율이 문턱(1.10)을 넘을 때만 쿨다운 감소가 열린다 — 재빠름이 그 열쇠다. */
    VIGOROUS(Axis.VITALITY, "활력적"),
    /** 무기력 — 늦게 깨고 일찍 자며 손이 굼뜨다. 대신 소모가 준다. */
    LISTLESS(Axis.VITALITY, "무기력"),
    /** 멍청 — 능력 감폭(등급당 −5%)·인지거리 감소. 신체 축으로 이전(등급 Ⅰ~Ⅴ). */
    DULL(Axis.INTELLIGENCE, "멍청"),
    /** 명석 — 능력 증폭(등급당 +5%)·인지거리 증가. 능력이 없으면 증폭할 항이 없어 거의 무효.
     *  배회 노동은 <b>실효 Ⅴ 에서만</b> 열린다({@link Multipliers#brightDriven}). */
    BRIGHT(Axis.INTELLIGENCE, "명석"),

    // ── 능력 (배율 특성, 성향 슬롯 공유) ──
    /** 눈썰미 — 채집 계수 +0.10/등급. 구명 "약초학자". <b>enum 이름은 바꾸지 않는다</b>:
     *  특성은 NBT 에 {@code name()} 으로 저장되므로(IndividualNbt) 개명하면 기존 세이브에서
     *  이 특성이 통째로 사라진다. 실효 Ⅵ 이상이면 표적 선별이 열려 인지거리 ×1.25 — 그 등급은
     *  유능함 없이는 닿을 수 없다. */
    HERBALIST(Axis.GATHER_SKILL, "눈썰미"),
    PLANT_CONFUSED(Axis.GATHER_SKILL, "식물혼동"),
    BUTCHER(Axis.HUNT_SKILL, "도축업자"),
    BLOOD_FEARFUL(Axis.HUNT_SKILL, "피공포"),
    HUNTER(Axis.ACQUISITION, "사냥꾼"),
    GATHERER(Axis.ACQUISITION, "채집꾼"),
    /** 유능함 — 채집·사냥 +0.03/등급(Ⅴ 라야 +0.15, 단독으론 잡동사니다). 진짜 값어치는
     *  <b>다른 능력의 등급 상한 Ⅴ 를 푸는 것</b>: {@code +등급/2} 만큼 위로 민다. 자기 자신은
     *  승격 대상이 아니다(상호 승격 폭주 차단). */
    COMPETENT(Axis.COMPETENCE, "유능함"),
    /** 서투름 — 채집·사냥 −0.03/등급. 상한을 풀기는커녕 아무것도 열지 않는다. */
    INEPT(Axis.COMPETENCE, "서투름"),
    DEXTEROUS(Axis.DEXTERITY, "손재주"),
    CLUMSY(Axis.DEXTERITY, "곰손"),
    HERBIVORE(Axis.DIET, "채식"),
    CARNIVORE(Axis.DIET, "육식"),
    COOK(Axis.COOKING, "요리사"),
    RAW_EATER(Axis.COOKING, "날로먹기"), // 구명 BAD_COOK(요리치) — NBT 레거시 별칭은 IndividualNbt
    ELOQUENT(Axis.ELOQUENCE, "달변가"),
    INARTICULATE(Axis.ELOQUENCE, "눌변가"),
    /** 몰입 — 한 가지에 붙어 있어 벌이가 좋다. 대가로 활동반경이 좁다. */
    FOCUSED(Axis.FOCUS, "몰입"),
    /** 산만 — 채집·사냥을 못 한다. 대신 <b>넓게 돌고 멀리 본다</b>(반경 ×1.5 · 인지 +4).
     *  혼자서는 못 먹고살아 구걸로 밀리지만, 경계가 값어치라 초병으로 뽑힌다. */
    SCATTERED(Axis.FOCUS, "산만"),
    /** 섬세 — 손이 좋아 벌이가 낫다. 대가로 몸이 약하다. */
    REFINED(Axis.CRUDENESS, "섬세"),
    /** 단순무식 — 벌이가 나쁜 대신 힘이 세다. 소모는 힘센(4%/등급)의 <b>절반</b>만 오른다 —
     *  같은 비율이면 Ⅴ등급에서 하루 소모가 시혜 1유닛에 붙어 굶어 죽는다. */
    BRUTISH(Axis.CRUDENESS, "단순무식"),

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
    MODEST(Axis.ASPIRATION, "안분"),
    /** 깜냥 — 관리 능력이 <b>하나라도 있을 때만</b> 실효 등급 +1(상한 Ⅴ). 무능력자에겐
     *  정확히 0(명석과 같은 best>0 가드). 촉매형 보조의 표준형. */
    // <b>"눈썰미" 에서 바꿨다.</b> 효과가 눈이 아니라 <b>경영</b>이다(관리 실효 등급 ±1 →
    // 정원 수확·관리 용량·착공 시기). 정작 시야·감지는 신체의 천리안/근시안이 맡고 있어,
    // 개체 정보창에 둘이 나란히 뜨면 이름과 효과가 어긋나 보인다.
    //
    // 깜냥은 "제 재주를 알아보고 굴리는 그릇"이라 촉매 성질(능력이 없으면 효과 0)까지
    // 이름이 설명한다. 숙련은 쓰지 않았다 — 이것은 <b>유전</b> 형질이라 살면서 쌓는 말과
    // 어긋나고(후천 숙련은 학교 교육이 맡는다), 대립쌍 "타고난 미숙"도 성립하지 않는다.
    KEEN_EYE(Axis.PERCEPTION, "깜냥"),
    /** 무딤 — 같은 조건에서 실효 등급 −1(하한 0). 능력자만 손해, 무능력자 무영향. */
    DULL_EYE(Axis.PERCEPTION, "무딤"),
    /** 단련 — 보유한 신체 특성 중 <b>최고 등급 하나만</b> +1(상한 Ⅴ). 신체 특성이 없으면
     *  정확히 0. 안목이 능력 축에 하는 일을 신체 축에 한다. */
    CONDITIONED(Axis.CONDITIONING, "단련"),
    /** 쇠약 — 같은 조건에서 최고 등급 −1(하한 0). 신체 특성 보유자만 손해. */
    DECONDITIONED(Axis.CONDITIONING, "쇠약"),
    /** 보완 — 감소형 특성의 계수를 완화한다. 감소형이 없으면 정확히 0. */
    COMPENSATOR(Axis.COMPENSATION, "보완"),
    /**
     * 야성 — 감소형 계수를 <b>미량</b> 심화하되, 그렇게 깎인 <b>결손만큼 힘을 돌려받는다</b>.
     *
     * <p>순수 하위 특성이던 "악화"를 거래로 바꾼 것이다. 핵심 성질은 <b>결손이 없으면 효과도
     * 없다</b>는 것 — 능력이 멀쩡한 자가 이걸 들면 증폭할 감소형이 없어 힘도 안 붙는다.
     * 이미 망가진 자에게만 보상이 가므로, 신분으로 분기하지 않고 산술로만 갈린다.
     *
     * <p>enum 이름은 {@code AGGRAVATOR} 그대로다 — 특성은 NBT 에 {@code name()} 으로 저장되므로
     * (IndividualNbt) 이름을 바꾸면 기존 세이브에서 이 특성이 통째로 사라진다.
     */
    AGGRAVATOR(Axis.COMPENSATION, "야성"),
    /** 끈기 — 연속 일수 요구치 −1(하한 1). 셀 일자리가 없으면 정확히 0. */
    TENACIOUS(Axis.PERSISTENCE, "끈기"),
    /** 변덕 — 연속 일수 요구치 +1. */
    FICKLE(Axis.PERSISTENCE, "변덕"),
    /** 넉살 — 신세 적립 배율 상향. 관계가 없으면 정확히 0. */
    AFFABLE(Axis.RAPPORT, "넉살"),
    /** 서먹 — 신세 적립 배율 하향. */
    STANDOFFISH(Axis.RAPPORT, "서먹"),
    /** 위엄 — 사람이 따르는 문턱 하향(더 잘 따른다). 거느릴 사람이 없으면 정확히 0. */
    COMMANDING(Axis.COMMAND, "위엄"),
    /** 물렁 — 사람이 따르는 문턱 상향(잘 안 따른다). */
    MEEK(Axis.COMMAND, "물렁");

    private final Axis axis;
    private final String koreanName;

    Trait(Axis axis, String koreanName) {
        this.axis = axis;
        this.koreanName = koreanName;
    }

    // 등급(I~V)을 갖는 축 — 신체 스탯 전부 + 그 등급 선호. 나머지는 무등급(grade 0).
    private static final java.util.EnumSet<Axis> GRADED_AXES = java.util.EnumSet.of(
            Axis.STRENGTH, Axis.TOUGHNESS, Axis.AGILITY, Axis.VISION, Axis.SPATIAL, Axis.RECOVERY,
            Axis.VITALITY, Axis.INTELLIGENCE,
            Axis.TOUGHNESS_PREF, Axis.AGILITY_PREF, Axis.VISION_PREF, Axis.RECOVERY_PREF);

    // 능력 특성 축(배율 특성) — 성향과 슬롯을 공유하되 '능력'으로 구분(표시·판정용).
    private static final java.util.EnumSet<Axis> ABILITY_AXES = java.util.EnumSet.of(
            Axis.GATHER_SKILL, Axis.HUNT_SKILL, Axis.ACQUISITION, Axis.COMPETENCE,
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
