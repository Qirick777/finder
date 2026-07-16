package com.evosim.core;

/**
 * 베리 심기 잉여 배분 (설계: 생존·번식 우선, 베리는 최하위). 밤 정산 잉여에서 <b>하루 예비 + 번식 몫</b>을
 * 뺀 나머지로만 옆 정원에 베리를 심는다 — 넉넉할수록 여러 그루. 상한 C에서 멈춘다.
 *
 * <p>순수 함수: {@code 심을 그루 수 = clamp( (잉여 − 예비 − 번식몫) / 그루당비용, 0, 상한−현재 )}.
 * 굶는 가정은 잉여가 없어 자동으로 0(아사·출산 지장 없음). 실제 심기는 표현층이 이 수만큼 실행.
 */
public final class BerryEconomy {

    /** 베리 한 그루를 심는 데 쓰는 잉여(넉넉할수록 여러 그루 → 이 값으로 조절). */
    public static final double BUSH_COST = 1.0;

    private BerryEconomy() {
    }

    /**
     * @param surplus      정산 후 잉여식량
     * @param reserve      하루 예비로 남길 양(가족 하루 소모량)
     * @param reproReserve 번식에 먼저 떼어둘 몫(부부면 번식 임계, 아니면 0)
     * @param bushCount    현재 거처 베리 그루 수
     * @param cap          거처당 베리 상한
     * @return 이번 밤에 심을 그루 수(0 이상)
     */
    public static int plant(double surplus, double reserve, double reproReserve, int bushCount, int cap) {
        return plant(surplus, reserve, reproReserve, bushCount, cap, 1.0);
    }

    /** 부트스트랩 그루 수 — 이 수까지는 번식예비 면제(생존몫만 확보되면 심음). 들풀-단독 지형에서
     *  "정원을 만들 잉여"와 "번식 예비"가 같은 문턱(≈12)이라 성장 사다리가 순환 잠금되던 것을,
     *  '생산 자본(정원) 먼저 → 번식 나중' 순서로 교정한다. 생존몫(reserve)은 부트스트랩도 침범 불가. */
    public static final int BOOTSTRAP_BUSHES = 2;

    /** 투자 축 반영판 — costMult(장기투자 0.5 / 신속투자 2.0)로 정원 조성 속도가 갈린다. */
    public static int plant(double surplus, double reserve, double reproReserve, int bushCount, int cap,
                            double costMult) {
        double cost = BUSH_COST * costMult;
        double leftover = surplus - reserve - reproReserve;
        int normal = leftover > 0.0 ? (int) (leftover / cost) : 0;
        int boot = 0;
        if (bushCount < BOOTSTRAP_BUSHES) { // 첫 1~2그루: 번식예비 면제(생존몫은 유지)
            double bootLeft = surplus - reserve;
            boot = bootLeft > 0.0
                    ? Math.min(BOOTSTRAP_BUSHES - bushCount, (int) (bootLeft / cost)) : 0;
        }
        return Math.min(Math.max(0, cap - bushCount), Math.max(normal, boot));
    }

    /** 투자 성향의 그루당 비용 배율 — 장기투자는 정원을 2배 속도로, 신속투자는 절반 속도로 조성. */
    public static double costMult(Individual ind) {
        if (ExpressionResolver.isExpressed(ind, Trait.LONG_INVESTMENT)) {
            return 0.5;
        }
        if (ExpressionResolver.isExpressed(ind, Trait.QUICK_INVESTMENT)) {
            return 2.0;
        }
        return 1.0;
    }
}
