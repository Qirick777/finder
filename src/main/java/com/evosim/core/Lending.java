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
    public static final double EQUITY_SHARE = 1.0 / 3.0; // ½ → ⅓(사용자 승인, 런 15·16): 후보 저축 10~13 이 15 문턱에 며칠씩 걸렸다
    /** 대부자가 하루에 내주는 상한 — 여유(저장고 − 확장 예비)의 이 비율. */
    public static final double LENDER_SHARE = 0.5;
    /** 봉신 상한 — 주인 추종자 중 제 몫으로 얹는 비율(⌊주인 추종자 × 이 값⌋). */
    public static final double VASSAL_SHARE = 0.25;
    /** 빚이 있는 동안 곳간 도착 지대에서 주인에게 올리는 비율(반올림 정수). */
    public static final double RENT_TRIBUTE = 0.2;
    /** 신용 — 상시소작 근속 일수(그 밭 주인이 아는 사람). */
    public static final int TENANT_DAYS = 1; // 2 → 1(사용자 승인): 출근 하루면 아는 사람

    /** 텃밭꾼의 자기 자본 비율 — 종자를 아껴 두는 자라 ⅓ 이면 된다. */
    public static final double EQUITY_SHARE_SMALLHOLDER = 0.25; // 전원 ⅓ 로 내리면서 텃밭꾼은 ¼ 로 한 단 더
    /** 의탁·품팔이의 착공 문턱 배율 — 제 밭에 관심이 없는 자는 웬만해선 안 연다. */
    public static final double FOUND_GATE_MULT_DEPENDENT = 1.5;
    /** 대부자의 다음 대출 조건 — 직전 대출 밭이 이 타일(소작이 붙는 크기 = 부부 용량 16 + 최소 일감 2)에 닿아야. */
    public static final int PREV_LOAN_PLOT_TILES = FarmEconomy.C_BASE * 2 + FarmEconomy.MIN_JOB;

    /** 봉신 한 명에게 묶이는 미상환 잔액 상한 — 36타일 총비용(착공 12 + 확장 22) + 여유. */
    public static final double LOAN_CAP = 40.0;
    /** 분할(반반) 대출 — 봉신 밭 36 미만의 확장비 중 주인이 대는 몫. 나머지는 봉신 곳간(예비 위). */
    public static final double TRANCHE_SHARE = 0.5;

    // ── 봉토 수여(사용자 승인, 런 19·20 실측 뒤) ──────────────────────────────────────
    //
    // 대부는 곳간으로 들어가 집·식비로 샜다(런 20 길버트: 대출 43 중 이틀 만에 23 이 이사·부양).
    // 저축 문턱(30~66)과 잔액 상한 40 은 "먼저 모아야 빌린다"를 강제해 봉신이 늦고 큰 가구일수록
    // 못 받았다. 봉토세는 소작 지대에만 걸려 봉신이 제 손으로 따는 15/일은 비과세였다.
    //
    // 봉건 그대로 간다 — 주군이 봉토를 떼어 주고, 봉신은 산출을 바친다. 착공비와 확장비는 주군
    // 곳간에서 <b>밭으로 직접</b> 들어가고(봉신 곳간 경유 없음), 봉신은 밭 산출(자영 + 지대 +
    // 축장 = 주인 몫)의 2할을 밤마다 올린다. 중견 축(야망가·욕심·경쟁·자수성가)은 36칸까지,
    // 소지주 축(미래지향·개척선호·자립심·텃밭꾼)은 24칸까지 받는다.

    /** 중견 봉토 — 대지주 축이 받는 밭 크기(둘째 밭은 제 힘으로). */
    public static final int FIEF_TILES_GRAND = 36;
    /** 소지주 봉토 — 중소지주 축이 받는 밭 크기(밭 하나로 산다). */
    public static final int FIEF_TILES_SMALL = 24;
    /** 봉토세 — 봉신 밭 산출(주인 몫) 중 주군에게 올리는 비율. */
    public static final double FIEF_OUTPUT_TAX = 0.2;

    /** 봉토 크기 — 대지주 축 36, 그 외(중소지주 축) 24. 축이 겹치면 큰 쪽. */
    public static int fiefTiles(Individual ind) {
        java.util.Set<Trait> t = ExpressionResolver.expressedTraits(ind);
        return grandAxis(t) ? FIEF_TILES_GRAND : FIEF_TILES_SMALL;
    }

    /** 이 밤 주군이 대는 확장비 = min(봉토 잔여 칸 비용, 주군 여유). 봉토가 다 찼으면 0. */
    public static double fiefTranche(int plotTiles, int fiefTiles, double costPerTile, double lenderRoom) {
        int left = Math.max(0, fiefTiles - plotTiles);
        return Math.max(0.0, Math.min(left * costPerTile, lenderRoom));
    }

    /** 봉신 밭 타일 상한 — 봉토 크기와 추종 상한 중 큰 쪽(봉토 크기는 주군이 정한다). */
    public static int fiefCap(int ownFollowers, int liegeFollowers, int fiefTiles) {
        return Math.max(vassalCap(ownFollowers, liegeFollowers), fiefTiles);
    }

    /**
     * 봉토세 청구액 — (마지막 과세 뒤 늘어난 주인 몫 산출) × 2할 + 이월. 곳간에서 정수 유닛만
     * 이체하고 나머지는 이월한다(L 정수성). 이체 가능액은 호출부가 곳간으로 자른다.
     */
    public static double fiefDue(double ownerTakeSinceTax, double carry) {
        return Math.max(0.0, ownerTakeSinceTax) * FIEF_OUTPUT_TAX + Math.max(0.0, carry);
    }

    private Lending() {
    }

    /** 봉토세 — 봉신(주인 있는 밭 소유자)의 곳간 도착 지대 중 주인에게 올리는 정수(빚과 무관). */
    public static int fiefTax(int units) {
        return units <= 0 ? 0 : (int) Math.round(units * RENT_TRIBUTE);
    }

    /** 분할 대출의 이 밤 주인 몫 = min(봉신이 제 곳간에서 낼 수 있는 몫, 주인 여유, 상한 − 잔액). */
    public static double trancheRoom(double vassalBudget, double lenderRoom, double owed) {
        return Math.max(0.0, Math.min(Math.min(vassalBudget, lenderRoom), LOAN_CAP - owed));
    }

    /** 대지주 축 — 지주와 경쟁하는 자: 야망가·욕심·경쟁·자수성가. */
    public static boolean grandAxis(java.util.Set<Trait> t) {
        return t.contains(Trait.AMBITIOUS) || t.contains(Trait.GREEDY)
                || t.contains(Trait.COMPETITIVE) || t.contains(Trait.SELF_MADE);
    }

    /** 대부를 청하는가 — 두 축 중 하나 발현, 의탁·품팔이는 절대 아님. */
    public static boolean wantsLoan(Individual ind) {
        java.util.Set<Trait> t = ExpressionResolver.expressedTraits(ind);
        if (t.contains(Trait.DEPENDENT) || t.contains(Trait.HIRELING)) {
            return false;
        }
        return grandAxis(t) || Satisfaction.smallholderAxis(t);
    }

    /** 자기 자본 비율 — 텃밭꾼 ⅓, 그 외 ½. */
    public static double equityShare(Individual ind) {
        return ExpressionResolver.isExpressed(ind, Trait.SMALLHOLDER)
                ? EQUITY_SHARE_SMALLHOLDER : EQUITY_SHARE;
    }

    /** 착공 문턱 배율 — 의탁·품팔이 ×1.5, 그 외 1. */
    public static double foundGateMult(Individual ind) {
        return ExpressionResolver.isExpressed(ind, Trait.DEPENDENT)
                || ExpressionResolver.isExpressed(ind, Trait.HIRELING)
                ? FOUND_GATE_MULT_DEPENDENT : 1.0;
    }

    /** 자기 자본 충족 — 저축 ≥ 문턱 × {@link #EQUITY_SHARE}. */
    public static boolean equityOk(double larder, double threshold) {
        return larder >= threshold * EQUITY_SHARE;
    }

    /** 자기 자본 충족(특성 반영) — 저축 ≥ 문턱 × equityShare(ind). */
    public static boolean equityOk(double larder, double threshold, Individual ind) {
        return larder >= threshold * equityShare(ind);
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
