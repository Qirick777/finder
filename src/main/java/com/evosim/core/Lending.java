package com.evosim.core;

/**
 * <b>대부와 봉신</b>(중간층 형성, 사용자 승인) — 순수 함수만.
 *
 * <p>왜 중간층이 없었나(런 9~11 실측): 밭 상한 = 12 + 12×추종자인데 추종자는 땅·곳간이 있어야
 * 생기므로 무산자는 12타일에서 영구 동결(니컬러스: 착공 뒤 닷새 정지)이고, 착공 문턱 30 은
 * 자식지원 아니면 못 넘었다. 중간층(마름·군인)은 전부 지주 급여로 산다.
 *
 * <p>장치: 야망·욕심·경쟁 발현자가 착공 문턱에 <b>돈만</b> 모자랄 때, 종자 반은 제 것이고
 * ({@link #EQUITY_SHARE}) 아는 사람(상시소작 근속·신세)이면 주인(없으면 반경 안 지주)이 여유의
 * 절반까지 꾸어 준다. 빚은 기존 신세 원장에 얹혀 자동으로 그 지주의 추종자가 되고, 갚을 때까지
 * 곳간에 닿는 지대의 {@link #RENT_TRIBUTE} 를 상납한다(이자 대신 봉토의 조세). 봉신의 밭 상한은
 * 주인 추종자의 {@link #VASSAL_SHARE} 를 제 몫으로 얹는다 — 봉신은 주인 밑에서 큰다.
 *
 * <p>은행 건물은 대부의 전제가 아니다. 고리대는 은행보다 먼저 있었고, 마인크래프트적으로도
 * "종자 베리를 꾸어 주고 수확에서 받는다"가 자연스럽다. 순수 함수 — /evotest lending.
 */
public final class Lending {

    /** 자기 자본 — 착공 문턱의 이 비율은 제 저축이어야 빌려준다. 종자 반은 제 것. */
    public static final double EQUITY_SHARE = 0.5;
    /** 대부자가 하루에 내주는 상한 — 여유(저장고 − 확장 예비)의 이 비율. */
    public static final double LENDER_SHARE = 0.5;
    /** 봉신 상한 — 주인 추종자 중 제 몫으로 얹는 비율(⌊주인 추종자 × 이 값⌋). */
    public static final double VASSAL_SHARE = 0.25;
    /** 빚이 있는 동안 곳간 도착 지대에서 주인에게 올리는 비율(반올림 정수). */
    public static final double RENT_TRIBUTE = 0.2;
    /** 신용 — 상시소작 근속 일수(그 밭 주인이 아는 사람). */
    public static final int TENANT_DAYS = 2;

    private Lending() {
    }

    /** 자기 자본 충족 — 저축 ≥ 문턱 × {@link #EQUITY_SHARE}. */
    public static boolean equityOk(double larder, double threshold) {
        return larder >= threshold * EQUITY_SHARE;
    }

    /** 빌릴 액수 = ceil(문턱 − 저축), 0 이하면 0. 자기 자본 조건 아래에서 문턱의 절반 이하. */
    public static double loanNeeded(double larder, double threshold) {
        return Math.max(0.0, Math.ceil(threshold - larder));
    }

    /** 대부자가 오늘 내줄 수 있는 몫 = max(0, 저장고 − 예비) × {@link #LENDER_SHARE}. */
    public static double lenderRoom(double lenderLarder, double reserve) {
        return Math.max(0.0, lenderLarder - reserve) * LENDER_SHARE;
    }

    /** 봉신 몫 = ⌊주인 추종자 × {@link #VASSAL_SHARE}⌋. */
    public static int vassalBonus(int liegeFollowers) {
        return (int) Math.floor(Math.max(0, liegeFollowers) * VASSAL_SHARE);
    }

    /** 밭 타일 상한 = 12 + 12 × (자기 추종자 + 봉신 몫). 주인이 없으면 종전 식 그대로. */
    public static int vassalCap(int ownFollowers, int liegeFollowers) {
        return FarmEconomy.plotTileCap(ownFollowers + vassalBonus(liegeFollowers));
    }

    /** 오늘 지대 도착분 중 상납 정수 = round(units × {@link #RENT_TRIBUTE}), 빚 없으면 0, 빚 이하. */
    public static int rentTribute(int units, double owed) {
        if (owed <= 0.0 || units <= 0) {
            return 0;
        }
        int send = (int) Math.round(units * RENT_TRIBUTE);
        return (int) Math.min(send, Math.ceil(owed));
    }
}
