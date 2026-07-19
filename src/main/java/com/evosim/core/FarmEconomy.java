package com.evosim.core;

/**
 * 밭 경제 순수 산식 (봉건 밭 경제 M0, 4단계 계획 문서의 확정 수치). 전부 [미확정] 노브 —
 * 장기 로그로 실측 보정 대상. {@code /evotest farm} 손계산 대조.
 */
public final class FarmEconomy {

    /** 익음 주기(틱) — 심은 뒤 이 시간이 지나면 결정론적으로 익음(랜덤틱은 보너스 하한). */
    public static final long RIPEN_TICKS = 24000L;
    /** 지대(수수료) — 소작 수확의 이 비율이 밭 계정으로. 0.6→0.45(계층 분화 v2): 소작 임금
     *  8×0.75×0.55=3.3 → 소작가구(정원5.0+임금)가 자식 2명대 소모(8.4)에 닿는다(E2 출산 재개).
     *  독립 차단은 임금이 아니라 "만족의 덫"(개간 임계 30 > 만족선 — 아래 INVEST_RESERVE 참조). */
    public static final double FEE = 0.45;
    /** 개인 수확 용량 기본(타일/일). 12→8(소작 루프 v2): "적은 노동의 지주" 수치화 —
     *  고용 문턱을 8+최소일감으로 낮추고, 큰 밭일수록 소작 의존이 커진다. */
    public static final int C_BASE = 8;
    /** 상시 소작 승격 — 같은 밭 연속 출근 일수(예약석: 이후 슬롯 변동 무관 유지).
     *  3→2(2배속 압축 — 1.5의 올림, 반나절 관대해지는 왜곡은 정직 표기). */
    public static final int PROMOTE_DAYS = 2;
    /** 최소 일감(타일). 10→2(소작 루프 v2): 첫 고용 밭 크기 = C_BASE 8 + 2 = 10타일 —
     *  착공 9타일에서 하루 확장이면 게시(40~50분 첫 고용 역산). */
    public static final int MIN_JOB = 2;
    /** 확장 비용(food/타일). 3.0→2.0: 신규(18÷9타일=2.0/타일)와 동률 — 소작 비례 확장이 주 성장 경로. */
    public static final double EXPAND_COST = 2.0;
    /** 신규 밭 기본 비용(food). 30→18: 엘리트 초기 저축률(관측 10~12/일)로 착공 d2(40분) 역산.
     *  체증 ×1.5 유지(2호 27·3호 40.5 — 축적 폭주 제동). */
    public static final double NEW_FARM_BASE = 18.0;

    /** 무주지 등록 소거까지(틱, 1.25일) — 선점자가 없으면 야생으로 복원(등록만 소거, 베리는 남음).
     *  60000→30000(2배속 — 대기 반감). */
    public static final long VACANT_EXPIRE_TICKS = 30000L;
    /** 1인 하루 확장 기본(타일) — 개간도 노동이라는 병목 근사(축적 폭주 제동 P1-ⓐ).
     *  3→6(2배속 압축: 하루당 진행률 2배 — 소득·소모율은 불변이라 자금이 자연 병목). */
    public static final int EXPAND_PER_DAY = 6;
    /** 구획 하루 확장 상한 — 소작 비례 확장 6×(1+상시소작 수)의 캡. 30→60(2배속 비례 유지). */
    public static final int EXPAND_DAY_MAX = 60;
    /** 확장·신규의 최소 여유. 6→12(만족의 덫): 개간 임계 = 18+12 = <b>30</b> — 빈둥지 부부
     *  만족선(6.0×2×σ2=24)·1자녀 가구 만족선(27.6)보다 위. 평민은 "궁핍해서 불만족 ⟹ 자금<30,
     *  자금≥30 ⟹ 이미 만족 ⟹ 개간 동기 없음"의 모순에 잠긴다(규칙 아닌 수치 잠금 — G 하드게이트
     *  대체). 탈출구 = 동기특성(야망·욕심·부지런·경쟁: 만족 무시) × 능력 소득. 예비는 소모되지
     *  않으므로(착공 후 저장고에 12 잔존) 엘리트 초기 밭 경영의 안전판을 겸한다. */
    public static final double INVEST_RESERVE = 12.0;

    private FarmEconomy() {
    }

    /**
     * 관리용량(타일) = 8 + g³ (관리등급 세제곱 — 정원 배율과 같은 설계 언어). <b>총소유타일</b>
     * 기준: 무능력 8 · Ⅱ 16 · Ⅲ 35(소지주 총량 — 구 밴드 35 연속) · Ⅳ 72 · Ⅴ 133(다밭 대지주).
     * 천장 규칙이 아니라 초과분의 수확 손실(E)로 자연 정체 — 준엘리트도 다밭은 가능하되 총량이
     * 묶이고, 엘리트만 5~6밭·소작 15+ 규모(100명 의존권)에 닿는다.
     */
    public static int manageCapacity(Individual owner) {
        int g = Multipliers.manageAbilityGrade(owner);
        return 8 + g * g * g;
    }

    /**
     * 관리 효율 E = min(1, 용량/<b>총소유타일</b>)² — 용량 내 손실 0, 초과분 제곱 감쇠. 소유 전
     * 밭의 수확(자영·소작)에 곱해져 지대가 마르며 확장·신밭이 <b>스스로</b> 멈춘다(강제 규칙
     * 없음). E(무능력,14)=0.33 · E(Ⅲ,24)=1 · E(Ⅲ,50)=0.49 · E(Ⅴ,133)=1 · E(Ⅴ,266)=0.25.
     */
    public static double manageEfficiency(Individual owner, int tiles) {
        if (tiles <= 0) {
            return 1.0;
        }
        double r = Math.min(1.0, (double) manageCapacity(owner) / tiles);
        return r * r;
    }

    /** 타일당 수확 수율 G = 0.5 × 채집수확배율(성별×gather) — 소득 격차·개간 로그 병기의 입력.
     *  (구 하드게이트 canFound(G≥0.95)는 삭제 — 독립 잠금은 만족의 덫이 담당, INVEST_RESERVE 참조.) */
    public static double tileYield(Individual ind) {
        return 0.5 * FoodEconomy.forageYieldMult(ind);
    }

    /** 밭 성숙 판정 — 최신(직영) 밭이 이 타일 수 이상이면 다음 밭 개간 자격(소작 2명 붙는 규모). */
    public static final int MATURE_TILES = 24;

    /** n번째 신규 밭 비용(이미 owned 개 소유) = 18 × 1.5^owned. */
    public static double newFarmCost(int owned) {
        return NEW_FARM_BASE * Math.pow(1.5, Math.max(0, owned));
    }

    /**
     * 신규 개간의 저장고 예비 — max(12, 2×가구 하루소모): 만족의 덫 부등식(만족선 < 개간 임계)을
     * <b>모든 가구 규모</b>에서 보편화. 종전 고정 12는 다자녀 가구(만족선 33.6 > 임계 30)에 구멍 —
     * 소작 임금으로 30만 모은 저능력 가구가 개간 러시(런5~7 실측: 지주 11명 중 저능력 6+).
     * 검산: 빈둥지 18+12=30>24 · 1자녀 18+13.8=31.8>27.6 · 2자녀 18+16.8=34.8>33.6 —
     * 무동기 가구는 어떤 규모든 만족이 먼저 와 봉인, 동기특성×능력 소득만 돌파(원안 그대로).
     */
    public static double foundReserve(double familyDailyNeed) {
        return Math.max(INVEST_RESERVE, 2.0 * familyDailyNeed);
    }

    /** 성숙 구획의 재투자 확장에 쓸 수 있는 계정 비율 — 잔여 절반은 밤 정산 때 지주 저장고로
     *  이체되어 다음 밭 종잣돈이 된다("성숙한 밭의 지대 절반은 다음 밭 몫" — 배분이지 금지 아님).
     *  런5 실측: 지대 53이 전액 확장에 흡수돼 2호 자금 39가 영구 미달(저장고 22 정체) →
     *  절반 배분 시 저축 +13/일, 2호 도달 1~2일 역산. */
    public static final double MATURE_REINVEST_SHARE = 0.5;

    /**
     * 확장 시 저장고 예비 — 다음 밭 자격(성숙)에 도달한 지주는 다음 신규 밭 자금(비용+예비)을
     * 저장고에 보존하고, 확장은 지대(밭 계정)로만 굴린다: "다음 밭을 위한 저축"의 산술화.
     * 2배속 압축에서 확장 6/일 지출이 저장고를 상시 소진해 2호 밭 저축이 영구 불가하던 병목의
     * 수치 해소(런3 실측: 지주 저장고 3일째 15 정체 vs 필요 39). 성숙 전엔 기존 예비 12 —
     * 초기 성장(9→24)은 지대가 얇아 저장고 보충이 필수라 종전 그대로.
     */
    public static double expandReserve(boolean nextFarmEligible, int ownedPlots) {
        return nextFarmEligible ? newFarmCost(ownedPlots) + INVEST_RESERVE : INVEST_RESERVE;
    }

    /** 개인 수확 용량 — 부지런 ×1.2 / 게으름 ×0.8 / 노년 ×0.5 (내림). */
    public static int capacity(Individual ind, LifeStage stage) {
        double c = C_BASE;
        if (ExpressionResolver.isExpressed(ind, Trait.DILIGENT)) {
            c *= 1.2;
        } else if (ExpressionResolver.isExpressed(ind, Trait.LAZY)) {
            c *= 0.8;
        }
        if (stage == LifeStage.ELDER) {
            c *= 0.5;
        }
        return (int) Math.floor(c);
    }

    /** 부족 타일 = T − 가구 용량. 최소 일감 미만이면 0(게시 안 함 — 잔여는 익은 채 이월). */
    public static int shortfall(int tiles, int ownCapacity) {
        int s = tiles - ownCapacity;
        return s >= MIN_JOB ? s : 0;
    }

    /**
     * 가구 케어 예산 배분 — 주인 가구의 노동 용량(budget)을 <b>가까운 구획부터</b> 소진해 구획별
     * 자가 케어 몫을 정한다. 실수확 행동(가까운 타일 우선)과 슬롯 장부의 대칭: 케어가 닿지 않는
     * 원거리 구획은 몫 0 → 부족분이 전량 게시되어 100% 소작으로 굴러간다(다구획 중복 차감 제거).
     *
     * @param tilesNearestFirst 주인 거처에서 가까운 순으로 정렬된 구획별 타일 수
     * @return 같은 순서의 구획별 자가 케어 용량(합 ≤ budget)
     */
    public static int[] allocateCare(int[] tilesNearestFirst, int budget) {
        int[] out = new int[tilesNearestFirst.length];
        int left = Math.max(0, budget);
        for (int i = 0; i < tilesNearestFirst.length; i++) {
            out[i] = Math.min(left, tilesNearestFirst[i]);
            left -= out[i];
        }
        return out;
    }

    /**
     * 지대 재투자(R1) — 소작 구획의 확장 자금은 <b>밭 계정</b>: 계정 잔액으로 감당 가능한 타일 수.
     * 생계 예비 불필요(계정은 누구의 식량도 아님 — 정산 전 미이체분). 노동·게이트 상한은 호출부.
     */
    public static int reinvestTiles(double account) {
        return (int) Math.floor(Math.max(0.0, account) / EXPAND_COST);
    }

    /** 소작 몫 = 수확 × (1−FEE). 나머지는 밭 계정(밤 정산 때 정수 유닛만 주인 저장고 — L 정수성). */
    public static double tenantShare(double yield) {
        return yield * (1.0 - FEE);
    }

    /** 밭 계정 몫(지대). tenantShare 와 합이 정확히 yield — 회계 항등식. */
    public static double ownerShare(double yield) {
        return yield * FEE;
    }
}
