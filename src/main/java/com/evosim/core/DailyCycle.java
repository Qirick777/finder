package com.evosim.core;

/**
 * 하루 사이클 정산 (설계서 §4 밤 정산 + §6 번식). 낮에 쌓인 수확을 밤에 가족 단위로 정산하고,
 * 남은 잉여가 번식 임계 이상이면 <b>번식을 해금</b>한다 — "식량이 확보되어야 번식"의 단일 판정점.
 *
 * <p>순수 함수(§18): 창고 분배(굶주림·사망)와 잉여→번식 게이트를 한 함수로 모은다. 마크 표현층은
 * 이 결과를 받아 먹이기·사망 처리·출산만 실행한다. 헤드리스로 완전 검증됨(/evotest cycle).
 */
public final class DailyCycle {

    private DailyCycle() {
    }

    /** 하루 정산 결과 — 창고 분배 결과 + 잉여 + 번식 해금 여부. */
    public static final class DayResult {
        public final Feeding.Result feeding;
        public final double surplus;          // 정산 후 창고 잔량(= 번식 판정 대상)
        public final boolean reproductionUnlocked;
        public final double reproThreshold;   // 이 가족의 번식 임계(무한대 = 부부 아님)

        DayResult(Feeding.Result feeding, double surplus, boolean repro, double thr) {
            this.feeding = feeding;
            this.surplus = surplus;
            this.reproductionUnlocked = repro;
            this.reproThreshold = thr;
        }
    }

    /**
     * 한 가정의 하루를 정산한다 (설계서 §4 §6).
     *
     * <p>① {@link Feeding#settle}로 창고를 우선순위 분배(남편→자식→아내) → 굶주림/사망 산정.
     * <br>② 남은 잉여가 임계 이상이고 <b>성년 부부(남편+아내 생존)</b>가 있으면 번식 해금.
     */
    public static DayResult settleFamily(Feeding.Household h) {
        Feeding.Result r = Feeding.settle(h);
        double surplus = r.storageLeft;

        double thr = Double.POSITIVE_INFINITY;
        boolean repro = false;
        Feeding.Member wife = firstLiveWife(h);
        if (h.father != null && !h.father.dead && h.father.stage == LifeStage.ADULT && wife != null) {
            thr = Reproduction.threshold(h.father.ind, wife.ind);
            repro = Reproduction.canReproduce(surplus, thr);
        }
        return new DayResult(r, surplus, repro, thr);
    }

    private static Feeding.Member firstLiveWife(Feeding.Household h) {
        for (Feeding.Member w : h.wives) {
            if (!w.dead && w.stage == LifeStage.ADULT) {
                return w;
            }
        }
        return null;
    }
}
