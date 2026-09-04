package com.evosim.core;

/**
 * 특성 축 (설계서 §2, §14 특성 마스터 목록).
 *
 * <p>각 축은 한 카테고리에 속하고, 대개 대립하는 특성 A/B를 묶는다. 축이 비어 있으면 = 중립.
 *
 * <p><b>반발(exclusive)</b>: 같은 축의 특성은 동시에 못 가진다(강함↔약함 등).
 * 성향·신체는 전부 반발한다. 선호는 "반발 없는 독립 가점"이라 반발하지 않는다 —
 * 단 하나의 예외가 익숙함선호↔다양성선호(FAMILIARITY, 유일한 배타 선호쌍, 설계서 §14).
 */
public enum Axis {
    // ── 성향 (DISPOSITION) — 전부 반발 ──
    DILIGENCE(Category.DISPOSITION, true),            // 근면: 게으름 / 부지런
    COURAGE(Category.DISPOSITION, true),              // 용기(진입): 겁쟁이 / 용감함
    RETREAT(Category.DISPOSITION, true),              // 퇴각: 무모 / 신중
    DETECTION(Category.DISPOSITION, true),            // 발각(전투): 조심성 / 대담함
    CHILD_PREFERENCE(Category.DISPOSITION, true),     // 육아선호: 아이불호 / 아이선호
    PREPARATION(Category.DISPOSITION, true),          // 준비: 즉흥적 / 준비성
    SHARING(Category.DISPOSITION, true),              // 나눔: 이기 / 이타
    SHARING_RANGE(Category.DISPOSITION, true),        // 나눔범위: 인색 / 관대
    SETTLEMENT(Category.DISPOSITION, true),           // 정착: 애향심 / 이주자
    RESOURCE_COMPETITION(Category.DISPOSITION, true), // 자원경쟁: 평화 / 경쟁
    GREGARIOUSNESS(Category.DISPOSITION, true),       // 군집: 고독 / 군집
    TIME_ORIENTATION(Category.DISPOSITION, true),     // 미래관: 현재지향 / 미래지향
    MARRIAGE_TIMING(Category.DISPOSITION, true),      // 혼기: 조혼 / 만혼
    PARENTING_SPEED(Category.DISPOSITION, true),      // 육아속도: 느린육아 / 빠른육아
    INVESTMENT(Category.DISPOSITION, true),           // 투자: 장기투자 / 신속투자
    EDUCATION(Category.DISPOSITION, true),            // 교육: 전문교육 / 기본교육
    REPRODUCTION_PREF(Category.DISPOSITION, true),    // 번식성향: 번식불호 / 번식선호
    FERTILITY(Category.DISPOSITION, true),            // 다산성: 난임 / 다산
    RESPONSIBILITY(Category.DISPOSITION, true),       // 책임(남): 무책임 / 과한책임 (남성발현)
    MATERNAL(Category.DISPOSITION, true),             // 모성(여): 모성애없음 / 강한모성애 (여성발현)
    MATE_CHOICE(Category.DISPOSITION, true),          // 짝고르기: 엄격 / 완전개방 (성별기본 여=신중·남=널널)
    AMBITION(Category.DISPOSITION, true),             // 야망: 야망가 / 안분지족 (만족 기준 — 밭 자산/조기)
    GREED(Category.DISPOSITION, true),                // 축장: 욕심 / 무욕 (만족 불가 / 확장 안 함)
    SPENDING(Category.DISPOSITION, true),             // 소비: 사치 / 검소 (소모 ±, 사치는 과시 매력)

    // ── 신체 (PHYSICAL) — 전부 반발, 전부 V~I 등급 스탯(배율 특성은 능력으로 이전) ──
    STRENGTH(Category.PHYSICAL, true),                // 힘: 힘센 / 약함
    TOUGHNESS(Category.PHYSICAL, true),               // 튼튼함: 튼튼 / 빈약
    AGILITY(Category.PHYSICAL, true),                 // 민첩: 재빠름 / 굼뜸
    VISION(Category.PHYSICAL, true),                  // 시야: 천리안 / 근시안
    SPATIAL(Category.PHYSICAL, true),                 // 공간지각: 공간지각 / 길치
    RECOVERY(Category.PHYSICAL, true),                // 회복력: 강건 / 병약
    /**
     * 활력: 활력적 / 무기력 — 일찍 깨고 늦게 자며 손놀림이 빠르다. <b>단독은 적자</b>다:
     * 노동창 +10.7% 를 얻는 대신 소모가 +15% 라 순증이 음수다. 재빠름이 이동 문턱을 넘겨 줄
     * 때 비로소 쿨다운 감소가 열려 흑자로 넘어간다.
     */
    VITALITY(Category.PHYSICAL, true),
    /**
     * 지능: 멍청 / 명석 — <b>성향에서 신체로 옮겼다</b>(등급화 Ⅰ~Ⅴ).
     *
     * <p>대가가 있다: {@link Genetics#randomFirstGen} 은 카테고리마다 풀을 섞어 3개를 뽑으므로
     * 축이 34개인 성향에서 8.8% 이던 명석이 축 8개인 신체에서 <b>37.5%</b> 가 된다. 명석은
     * 배회 노동(노동창 +57%)을 켜는 스위치라, 그대로 두면 야생 미믹 셋 중 하나가 노동 시간을
     * 공짜로 얻는다 — 엘리트를 세우려다 평민을 통째로 올리는 꼴이다. 그래서 배회 노동을
     * <b>실효 Ⅴ 전용</b>으로 좁혔다({@link Multipliers#brightDriven}): 발현율 37.5%×20% = 7.5%
     * 중 등급 조건까지 걸려 실제로는 종전 4.4% 아래로 내려간다.
     */
    INTELLIGENCE(Category.PHYSICAL, true),

    // ── 능력 (ABILITY, 배율 특성) — 반발. 성향과 같은 슬롯 공유 → Category.DISPOSITION 로 편입 ──
    GATHER_SKILL(Category.DISPOSITION, true),         // 획득(채집): 약초학자 / 식물혼동
    HUNT_SKILL(Category.DISPOSITION, true),           // 획득(사냥): 도축업자 / 피공포
    ACQUISITION(Category.DISPOSITION, true),          // 획득: 사냥꾼 / 채집꾼
    /**
     * 재능: 유능함 / 서투름 — <b>단독으로는 별볼일 없다</b>(채집 +0.03/등급, Ⅴ 라야 +0.15).
     * 값어치는 조건부에 있다: 다른 능력 특성의 등급 상한 Ⅴ 를 풀어 {@code +등급/2} 만큼 위로
     * 밀어 올린다({@link Multipliers#effectiveAbilityGrade}). 그 승격이 반경 게이트와 깜냥
     * 게이트를 <b>동시에</b> 통과시키므로, 유능함이 빠지면 사슬이 중간에서 끊긴다.
     */
    COMPETENCE(Category.DISPOSITION, true),
    DEXTERITY(Category.DISPOSITION, true),            // 손재주 / 곰손
    DIET(Category.DISPOSITION, true),                 // 채식 / 육식
    COOKING(Category.DISPOSITION, true),              // 요리: 요리사 / 날로먹기
    ELOQUENCE(Category.DISPOSITION, true),            // 언변: 달변가 / 눌변가 (기본 매력 ±1)
    /**
     * 집중: 몰입 / 산만 — 산만은 채집·사냥을 못 하는 대신 <b>넓게 돌고 멀리 본다</b>.
     * 혼자서는 먹고살기 어려워 구걸·고용으로 밀리지만, 경계가 값어치라 초병으로 뽑힌다.
     */
    FOCUS(Category.DISPOSITION, true),
    /**
     * 조야: 섬세 / 단순무식 — 단순무식은 벌이가 나쁜 대신 힘이 세다(전위).
     * 섬세는 그 반대 — 손은 좋고 몸은 약하다.
     */
    CRUDENESS(Category.DISPOSITION, true),

    // ── 선호 (PREFERENCE) — 반발 없음(exclusive=false), FAMILIARITY만 예외 ──
    STRENGTH_PREF(Category.PREFERENCE, false),        // 강함선호 / 효율선호
    ABILITY_PREF(Category.PREFERENCE, false),         // 능력선호 / 소박선호
    VITALITY_PREF(Category.PREFERENCE, false),        // 활력선호 / 정주선호
    FECUNDITY_PREF(Category.PREFERENCE, false),       // 다산선호 / 소산선호
    STABILITY_PREF(Category.PREFERENCE, false),       // 안정선호 / 모험선호
    SPEED_PREF(Category.PREFERENCE, false),           // 신속선호 / 지연선호
    DEVOTION_PREF(Category.PREFERENCE, false),        // 헌신선호 (단일)
    FAMILIARITY_PREF(Category.PREFERENCE, true),      // 익숙함선호 / 다양성선호 (유일한 배타 선호쌍)
    INTELLIGENCE_PREF(Category.PREFERENCE, false),    // 똑똑함선호 / 단순함선호
    WASTE_PREF(Category.PREFERENCE, false),           // 낭비선호 (단일, 핸디캡 원리)
    PEACE_PREF(Category.PREFERENCE, false),           // 평화선호 / 개척선호
    PROVISION_PREF(Category.PREFERENCE, false),       // 부유선호 / 생산력선호 (자산 vs 벌이)

    // ── 등급 선호 (PREFERENCE, 단일·등급 보유) — 신체 등급 매칭용(예: 튼튼II선호) ──
    TOUGHNESS_PREF(Category.PREFERENCE, false),       // 튼튼선호 (등급)
    AGILITY_PREF(Category.PREFERENCE, false),         // 재빠름선호 (등급)
    VISION_PREF(Category.PREFERENCE, false),          // 천리안선호 (등급)
    RECOVERY_PREF(Category.PREFERENCE, false),        // 강건선호 (등급)

    // ── 보조 (AUXILIARY) — 전부 반발. 단독 효과 ≈ 0, 조건이 갖춰질 때만 발동 ──
    /** 만족선: 자수성가 / 안분 — 만족 계수 σ 를 올리거나 내린다. 가난한 가구는 애초에
     *  만족선 근처에 못 가므로 <b>단독 효과가 0</b>이고, 능력으로 자금을 모은 가구에서만
     *  "만족의 덫"을 풀거나 조인다(착공 병목의 실제 지점 — 런 실측으로 특정). */
    ASPIRATION(Category.AUXILIARY, true),
    /** 안목: 깜냥 / 무딤 — 관리 능력 실효 등급 ±1. <b>능력이 하나라도 있을 때만</b> 적용되어
     *  무능력자에겐 효과가 정확히 0(명석의 best&gt;0 가드와 동일 구조). 착공 시기를 앞당기는
     *  촉매이자, 관리용량(8+g³) 상승으로 대영지 수확 붕괴까지 함께 완화한다. */
    PERCEPTION(Category.AUXILIARY, true),
    /** 단련: 단련 / 쇠약 — <b>보유한 신체 특성 중 최고 등급</b>만 ±1. 안목(PERCEPTION)이
     *  능력 축에 하는 일을 신체 축에 한다 — 그쪽만 촉매가 있고 이쪽은 비어 있었다.
     *  신체 특성이 하나도 없으면 올릴 등급이 없어 효과가 정확히 0(best&gt;0 가드). */
    CONDITIONING(Category.AUXILIARY, true),
    /** 보완: 보완 / 악화 — <b>감소형 특성의 계수</b>만 완화하거나 심화한다. 감소형을 하나도
     *  안 가졌으면 깎을 것이 없어 효과 0. 안목이 <b>등급</b>을 밀고 이쪽은 <b>계수</b>를
     *  미는 것이라 축이 겹치지 않는다. */
    COMPENSATION(Category.AUXILIARY, true),
    /** 근성: 끈기 / 변덕 — <b>연속으로 세는 날수</b>의 요구치를 ∓1. 상시소작 승격(연속 출근),
     *  군인 봉급 미납 인내가 그 대상이다. 일자리·직위가 없으면 셀 것이 없어 효과 0. */
    PERSISTENCE(Category.AUXILIARY, true),
    /** 붙임성: 넉살 / 서먹 — <b>신세(예속·호의) 적립</b> 배율. 같은 도움을 받아도 넉살은 더
     *  빨리 마음이 기울고 서먹은 더디다. 관계가 없으면 적립 자체가 없어 효과 0. */
    RAPPORT(Category.AUXILIARY, true),
    /** 위엄: 위엄 / 물렁 — <b>사람이 따르는 문턱</b>을 내리거나 올린다(patronOf 의 gate).
     *  거느릴 사람이 없으면 효과 0. 봉건 축과 직접 맞물리는 촉매다. */
    COMMAND(Category.AUXILIARY, true);

    private final Category category;
    private final boolean exclusive;

    Axis(Category category, boolean exclusive) {
        this.category = category;
        this.exclusive = exclusive;
    }

    public Category category() {
        return category;
    }

    /** 같은 축의 특성이 동시에 발현 불가한가(반발). */
    public boolean exclusive() {
        return exclusive;
    }
}
