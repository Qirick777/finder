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
    /** 최소 일감(타일) — 이 미만의 부족분은 게시하지 않음(1타일 일자리 방지). */
    public static final int MIN_JOB = 10;
    /** 확장 비용(food/타일) — 신규 개간과 1:3.3 격차(소작 확장 유인 게이트). */
    public static final double EXPAND_COST = 3.0;
    /** 신규 밭 기본 비용(food) — 소유 밭 수에 ×1.5 체증(축적 폭주 제동). */
    public static final double NEW_FARM_BASE = 30.0;

    private FarmEconomy() {
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

    /** 소작 몫 = 수확 × (1−FEE). 나머지는 밭 계정(밤 정산 때 정수 유닛만 주인 저장고 — L 정수성). */
    public static double tenantShare(double yield) {
        return yield * (1.0 - FEE);
    }

    /** 밭 계정 몫(지대). tenantShare 와 합이 정확히 yield — 회계 항등식. */
    public static double ownerShare(double yield) {
        return yield * FEE;
    }
}
