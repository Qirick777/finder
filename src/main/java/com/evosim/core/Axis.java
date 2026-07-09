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
    INTELLIGENCE(Category.DISPOSITION, true),         // 지능: 멍청 / 명석
    RESPONSIBILITY(Category.DISPOSITION, true),       // 책임(남): 무책임 / 과한책임 (남성발현)
    MATERNAL(Category.DISPOSITION, true),             // 모성(여): 모성애없음 / 강한모성애 (여성발현)
    MATE_CHOICE(Category.DISPOSITION, true),          // 짝고르기: 엄격 / 완전개방 (성별기본 여=신중·남=널널)

    // ── 신체 (PHYSICAL) — 전부 반발 ──
    STRENGTH(Category.PHYSICAL, true),                // 힘: 힘센 / 약함
    TOUGHNESS(Category.PHYSICAL, true),               // 튼튼함: 튼튼 / 빈약
    AGILITY(Category.PHYSICAL, true),                 // 민첩: 재빠름 / 굼뜸
    VISION(Category.PHYSICAL, true),                  // 시야: 천리안 / 근시안
    SPATIAL(Category.PHYSICAL, true),                 // 공간지각: 공간지각 / 길치
    RECOVERY(Category.PHYSICAL, true),                // 회복력: 강건 / 병약
    GATHER_SKILL(Category.PHYSICAL, true),            // 획득(채집): 약초학자 / 식물혼동
    HUNT_SKILL(Category.PHYSICAL, true),              // 획득(사냥): 도축업자 / 피공포
    ACQUISITION(Category.PHYSICAL, true),             // 획득: 사냥꾼 / 채집꾼
    DEXTERITY(Category.PHYSICAL, true),               // 손재주 / 곰손
    DIET(Category.PHYSICAL, true),                    // 채식 / 육식
    COOKING(Category.PHYSICAL, true),                 // 요리사 / 요리치

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
    PEACE_PREF(Category.PREFERENCE, false);           // 평화선호 / 개척선호

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
