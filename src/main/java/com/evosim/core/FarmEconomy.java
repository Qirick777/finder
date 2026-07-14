package com.evosim.core;

/**
 * 밭 경제 순수 산식 (봉건 밭 경제 M0, 4단계 계획 문서의 확정 수치). 전부 [미확정] 노브 —
 * 장기 로그로 실측 보정 대상. {@code /evotest farm} 손계산 대조.
 */
public final class FarmEconomy {

    /** 익음 주기(틱) — 심은 뒤 이 시간이 지나면 결정론적으로 익음(랜덤틱은 보너스 하한). */
    public static final long RIPEN_TICKS = 24000L;
    /** 지대(수수료) — 소작 수확의 이 비율이 밭 계정으로. */
    public static final double FEE = 0.3;
    /** 개인 수확 용량 기본(타일/일) — 밭 전담창 2400틱 ÷ 타일 200틱. */
    public static final int C_BASE = 12;
    /** 상시 소작 승격 — 같은 밭 연속 출근 일수(예약석: 이후 슬롯 변동 무관 유지). */
    public static final int PROMOTE_DAYS = 3;
    /** 최소 일감(타일) — 이 미만의 부족분은 게시하지 않음(1타일 일자리 방지). */
    public static final int MIN_JOB = 10;
    /** 확장 비용(food/타일) — 신규 개간과 1:3.3 격차(소작 확장 유인 게이트). */
    public static final double EXPAND_COST = 3.0;
    /** 신규 밭 기본 비용(food) — 소유 밭 수에 ×1.5 체증(축적 폭주 제동). */
    public static final double NEW_FARM_BASE = 30.0;

    /** 무주지 등록 소거까지(틱, 2.5일) — 선점자가 없으면 야생으로 복원(등록만 소거, 베리는 남음). */
    public static final long VACANT_EXPIRE_TICKS = 60000L;
    /** 능력 게이트 경계(타일) — 이 규모 "초과" 확장은 주인의 발현 능력 특성을 요구(T4=첫 고용 규모). */
    public static final int SKILL_GATE_TILES = 35;
    /** 하루 확장 상한(타일) — 개간도 노동이라는 병목 근사(축적 폭주 제동 P1-ⓐ). */
    public static final int EXPAND_PER_DAY = 3;
    /** 확장·신규의 최소 여유 — 비용 지불 후에도 이틀치(6)는 남아야 투자(생계 우선). */
    public static final double INVEST_RESERVE = 6.0;

    private FarmEconomy() {
    }

    /** 대규모 경영 능력 — 채집·저장 계열 발현 능력 특성 1개 이상(성향만으로 대지주 불가). */
    public static boolean canManageLarge(Individual owner) {
        var t = ExpressionResolver.expressedTraits(owner);
        return t.contains(Trait.HERBALIST) || t.contains(Trait.GATHERER)
                || t.contains(Trait.DEXTEROUS) || t.contains(Trait.COOK);
    }

    /** 이 주인이 키울 수 있는 밭 규모 상한 — 능력 보유면 무제한, 아니면 SKILL_GATE_TILES. */
    public static int growthCap(Individual owner) {
        return canManageLarge(owner) ? Integer.MAX_VALUE : SKILL_GATE_TILES;
    }

    /** n번째 신규 밭 비용(이미 owned 개 소유) = 30 × 1.5^owned. */
    public static double newFarmCost(int owned) {
        return NEW_FARM_BASE * Math.pow(1.5, Math.max(0, owned));
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
