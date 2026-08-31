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
    /**
     * 개인 수확 용량 기본(타일/일) — 고용 문턱은 이 값 + {@link #MIN_JOB}.
     *
     * <p>8 → 12 로 올렸다가 <b>되돌린다</b>. 12 로 두면 고용 문턱이 14 가 되는데 평민 밭 상한이
     * 12({@link #PLOT_TILE_BASE})라, 평민 밭은 <b>절대 소작을 못 쓴다</b>. 소작 임금이 평민의
     * 주 수입원이므로 노동 시장이 통째로 사라졌다 — 실측(wildpair 런 D11): 마을 전체 소작 1명,
     * 평민 저장고 10~11 로 지참금 14 에도 못 미쳐 <b>출산이 d2~d8 내내 0</b>이었다.
     *
     * <p>두 손잡이를 분리한다. 밭 <b>크기</b>는 plotTileCap 이 가른다(평민 12칸 = 2단계).
     * <b>고용 규모</b>는 그 크기에서 따라 나온다 — 평민 밭 12칸은 소작 1명, 엘리트 밭 36~60칸은
     * 여럿. 엘리트가 갈리는 것은 "소작을 쓰느냐"가 아니라 "몇을 거느리느냐"다.
     */
    public static final int C_BASE = 8;
    /** 상시 소작 승격 — 같은 밭 연속 출근 일수(예약석: 이후 슬롯 변동 무관 유지).
     *  3→2(2배속 압축 — 1.5의 올림, 반나절 관대해지는 왜곡은 정직 표기). */
    public static final int PROMOTE_DAYS = 2;
    /** 최소 일감(타일). 10→2(소작 루프 v2): 첫 고용 밭 크기 = C_BASE 8 + 2 = 10타일 —
     *  착공 9타일에서 하루 확장이면 게시(40~50분 첫 고용 역산). */
    public static final int MIN_JOB = 2;
    /** 확장 비용(food/타일). 3.0→2.0→1.0: 기존 구획 증축은 이미 개간된 인프라 위라 신규
     *  단가(18÷9=2.0)의 절반 — 런14 실측: 2.0에서는 확장 복리율 +11%/일로 왕조 타일이
     *  d15에 63(목표 180~220의 1/3) 정체. 1.0이면 +22%/일 → 180 도달 d20±1. 전 지주
     *  공통 산식(자금 능력 순)·노동 캡 불변 — 능력 경사 유지. */
    public static final double EXPAND_COST = 1.0;

    /** 단계마다 개간이 비싸지는 비율 — 타일당 비용 = EXPAND_COST × STAGE_COST_MULT^(단계−1). */
    public static final double STAGE_COST_MULT = 1.5;

    /**
     * <b>이 단계의 타일당 개간 비용</b> — 단계가 오를수록 가파르게 비싸진다.
     *
     * <pre>1단계 1.00 · 2단계 1.50 · 3단계 2.25 · 4단계 3.38 · 5단계 5.06</pre>
     *
     * <p>한 단계를 여는 총액으로 보면 6 · 9 · 27 · 40 · 91 이다. 빈둥지 부부 만족선이 24,
     * 1자녀 가구가 27.6 이므로 <b>3단계(27)가 정확히 그 사이에 걸린다</b> — 거기서부터는
     * 만족의 덫을 뚫는 자, 즉 야망가만 자금을 쌓는다. 새 잠금이 아니라 기존 잠금(Satisfaction)을
     * 단계 경계에 맞춘 것이다.
     */
    public static double expandCost(int stage) {
        return EXPAND_COST * Math.pow(STAGE_COST_MULT, Math.max(0, stage - 1));
    }
    /** 신규 밭 기본 비용(food). 30→18: 엘리트 초기 저축률(관측 10~12/일)로 착공 d2(40분) 역산.
     *  체증 ×1.5 유지(2호 27·3호 40.5 — 축적 폭주 제동).
     *  18→12(P1): 밭 유무가 더는 권력이 아니게 되므로 여는 문턱을 낮춰 <b>밭을 흔하게</b> 한다.
     *  권력은 이제 크기(plotTileCap)와 추종자 수가 정한다. 체증 ×1.5 는 그대로 둔다 —
     *  여러 밭을 쥐는 것은 여전히 비싸야 한다. */
    public static final double NEW_FARM_BASE = 12.0;

    /**
     * <b>구획 하나가 자랄 수 있는 타일 상한</b> = 기본 + 추종자 수 × 계수.
     *
     * <p>이 한 줄이 세 가지를 동시에 푼다. 밭을 <b>여는 것</b>은 싸져서(NEW_FARM_BASE 18→12)
     * 흔해지고, <b>키우는 것</b>은 추종자를 거느려야 가능해져 엘리트만 크게 키운다. 일반민은
     * 밭을 열 수 있으나 키울 수 없다 — "시도하나 능력이 안 됨".
     *
     * <p>40/15 → <b>12/12</b>(덩어리 도면 정합). 상한을 단계 경계에 얹는다:
     *
     * <pre>추종자 0 → 12(2단계) · 1 → 24(3단계) · 2 → 36(4단계) · 4 → 60(5단계 54 가능)</pre>
     *
     * <p>그래서 <b>평범한 미믹은 1~2단계에서 멈춘다</b> — 혼자 거둘 수 있는 만큼(C_BASE 12)이
     * 곧 상한이다. 소작을 부릴 규모(3단계 24칸)로 가려면 사람을 거느려야 하고, 사람을 거느리는
     * 것은 신세를 베풀 여유가 있는 자만 한다. 신분 분기가 아니라 추종자 수가 가른다.
     */
    public static final int PLOT_TILE_BASE = 12;

    public static final int PLOT_TILE_PER_FOLLOWER = 12;

    public static int plotTileCap(int followers) {
        return PLOT_TILE_BASE + Math.max(0, followers) * PLOT_TILE_PER_FOLLOWER;
    }

    /** 무주지 등록 소거까지(틱, 1.25일) — 선점자가 없으면 야생으로 복원(등록만 소거, 베리는 남음).
     *  60000→30000(2배속 — 대기 반감). */
    public static final long VACANT_EXPIRE_TICKS = 30000L;
    /** 1인 하루 확장 기본(타일) — 개간도 노동이라는 병목 근사(축적 폭주 제동 P1-ⓐ).
     *  3→6→12(2배속 압축 정합): 시뮬 1일=설계 2일이므로 일당 진행률은 설계율 6의 2배가 정합값.
     *  6은 압축 미반영 잔재로, 밭 소득>12/일(≈34타일) 국면에서 노동 캡이 자금보다 먼저 결박돼
     *  왕조 타일 총량이 d10 목표(~180~220)의 40%에서 정체(런12 실측: 확장 4~5/일 = 자금 병목,
     *  캡 6 임박). 자금 제약(소득×재투자몫÷EXPAND_COST)은 불변 — 실효 확장은 여전히 능력 소득
     *  순이며 부유 지주만 빨라진다(강제 아님). */
    public static final int EXPAND_PER_DAY = 12;
    /** 구획 하루 확장 상한 — 소작 비례 확장 6×(1+상시소작 수)의 캡. 30→60(2배속 비례 유지). */
    public static final int EXPAND_DAY_MAX = 60;
    /** 확장·신규의 최소 여유. 6→12(만족의 덫): 개간 임계 = 18+12 = <b>30</b> — 빈둥지 부부
     *  만족선(6.0×2×σ2=24)·1자녀 가구 만족선(27.6)보다 위. 평민은 "궁핍해서 불만족 ⟹ 자금<30,
     *  자금≥30 ⟹ 이미 만족 ⟹ 개간 동기 없음"의 모순에 잠긴다(규칙 아닌 수치 잠금 — G 하드게이트
     *  대체). 탈출구 = 동기특성(야망·욕심·부지런·경쟁: 만족 무시) × 능력 소득. 예비는 소모되지
     *  않으므로(착공 후 저장고에 12 잔존) 엘리트 초기 밭 경영의 안전판을 겸한다. */
    /**
     * 확장·신규의 최소 여유 — 6 → <b>12</b>(설계 의도 복원).
     *
     * <p>착공 임계 = 18 + 이 값. 12 면 <b>30</b> 이 되어 빈둥지 부부 만족선(24)·1자녀 가구
     * (27.6)보다 위다. 그러면 평민은 "궁핍해서 불만족 ⟹ 자금&lt;30, 자금≥30 ⟹ 이미 만족 ⟹
     * 개간 동기 없음"의 모순에 잠기고, <b>만족을 무시하는 야망가만</b> 밭을 연다.
     *
     * <p>한때 12→6 으로 내렸던 이유는 "임계 30 이 부풀어 g5만 통과하고 g4는 d14에 25에서
     * 멈췄다" 였다. 그것은 <b>수확이 적었을 때</b>의 이야기다. tileYield 를 0.5→0.8 로 올리면
     * 그 제약이 사라지므로 함께 되돌린다 — 두 손잡이는 같이 움직여야 한다.
     */
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
    /**
     * 타일당 수확 계수 — 0.5 → 0.8.
     *
     * <p>"밭 만들기는 어렵되 수확은 많게"의 절반이다(나머지 절반은 {@link #INVEST_RESERVE}).
     * 수확이 오르면 밭 계정이 커져 확장이 빨라지고, 밭이 커지니 고용이 활발해지고, 소작 수취
     * (55%)가 올라 소작농이 만족선을 넘는다 — 그 지점에서 만족의 덫이 대다수를 소작에 묶고
     * 야망가만 착공 임계를 넘어 자영으로 독립한다. 소작 다수·자영 일부·마름 소수·지배자 하나.
     *
     * <p>실측 근거: 소작 1인 하루 수취 1.85 · 신혼 가구 지참금 14 · 출산 문턱 16~20. 신혼이
     * <b>시작부터 문턱 아래</b>라 2세대 부부가 네 스냅샷 연속 자녀 0 이었다(w4 D7~D11).
     * 0.8 이면 수취가 약 3.0 이 되어 며칠이면 문턱을 넘는다.
     *
     * <p><b>0.8 → 1.0</b>(w9 실측 뒤). 0.8 로는 무토지 가구의 순저축이 하루 0.14 밖에 안 됐다
     * (19일간 13쌍이 자녀 9명, 살림은 11 에서 평탄 — 버는 족족 출산에 쓰이고 남는 것이 없다).
     * 그래서 두 가지가 함께 실패했다: 자영농 0명(19일간 자력 착공은 엘리트 3건이 전부, 나머지
     * 지주는 전부 상속인)과 소작 출산 0.78(목표 1.5~2). <b>둘은 같은 원인의 두 얼굴</b>이다.
     *
     * <p>총수입 약 3.0 이 +25% 되면 순저축이 약 0.9/일 — 살림 11 에서 착공 임계 30 까지 약 21일로,
     * 자영농이 나올 수 있는 속도가 된다. 1.1(+37.5%)이 아니라 1.0 을 택한 것은 순저축이 8배
     * 뛰면 출산이 목표를 넘어 "능력 없이도 4"라는 결함 쪽으로 갈 위험이 있어서다 — 한 번에 크게
     * 올려 두 목표를 다 놓치는 것보다 한 단계씩 올리며 잰다.
     */
    public static final double TILE_YIELD_MULT = 1.0;

    public static double tileYield(Individual ind) {
        return TILE_YIELD_MULT * FoodEconomy.forageYieldMult(ind);
    }

    /** 밭 성숙 판정 — 최신 밭이 이 타일 수 이상이면 다음 밭 개간 자격. 3단계(24칸) = 소작이 붙는 규모. */
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
    /**
     * 착공 예비의 <b>바닥</b> — 18. {@link #INVEST_RESERVE}(확장용 12)와 따로 둔다.
     *
     * <p>착공 임계 = 비용 12 + 이 값 = <b>30</b> 이고, 가족이 커져도 famNeed 가 18 을 넘기 전까지
     * (부부+자녀 넷 남짓) <b>움직이지 않는다</b>. 평평해야 하는 이유는 실측이다 — 계수를 3.0 으로
     * 두었더니 자녀 하나마다 임계가 +2.7 씩 도망갔고(w8 실측 D3→D5: 무능 임계 31→34, 저축 +1),
     * 착공이 D5 까지 <b>한 건도</b> 일어나지 않았다(w6 는 같은 조건에서 D2 에 엘리트 착공). 이는
     * 코드베이스에 이미 기록돼 있던 "착공 임계가 저축을 앞서 도주하는 러닝머신"(런15)의 재현이다.
     *
     * <p>그래서 계층 분리는 임계 쪽이 아니라 <b>만족선 쪽</b>에서 낸다
     * ({@link Satisfaction#aspiration}). 임계는 평평하게, 눈높이는 능력에 따라.
     */
    public static final double FOUND_RESERVE_MIN = 18.0;

    /**
     * 계수는 <b>1.0</b> — 가족 규모에 비례하되 바닥({@link #FOUND_RESERVE_MIN})이 웬만한 가구를
     * 덮으므로 실질적으로 평평하다.
     *
     * <p>실측으로 확인한 고장: {@code NEW_FARM_BASE} 를 18→12 로 내리면서 착공 임계가 30→24 로
     * 떨어졌는데 예비는 바닥값 12 에 걸려 가족이 커져도 오르지 않았다. 그래서 부등식이
     * <b>자녀 수에 따라 뒤집혔다</b> — 만족선은 가구 소모에 비례해 오르는데 임계는 고정이라:
     *
     * <pre>
     *   빈둥지 만족선 24.0 · 임계 24.0 → 동률(우연)
     *   1자녀  만족선 27.6 · 임계 24.0 → <b>3.6 열림</b>
     *   2자녀  만족선 31.2 · 임계 24.0 → <b>7.2 열림</b>
     * </pre>
     *
     * 즉 "자녀가 있고 24 를 모으면 누구나 착공"이었다. 신분을 가른 것은 능력이 아니라
     * <b>가족 규모</b>였다.
     *
     * <p>계수를 3.0 으로 두면 임계 = 12 + 3n 이 만족선 4n 위에 선다(n &lt; 12, 즉 부부+자녀 4명
     * 까지). 그리고 능력 문턱이 가족 규모와 거의 무관한 좁은 띠에 모인다 — 빈둥지 1.25 ·
     * 1자녀 1.19 · 2자녀 1.14. 사다리를 가르는 것이 가족 규모가 아니라 능력이라는 뜻이다
     * ({@link Satisfaction#aspiration} 이 나머지 절반).
     *
     * <p>{@code NEW_FARM_BASE} 를 18 로 되돌려도 임계 30 은 나오지만, 그러면 "밭을 흔하게"라는
     * 결정(18→12)이 뒤집힌다. 여는 값은 싸게 두고 <b>쿠션</b>을 요구하는 쪽을 택했다.
     */
    public static double foundReserve(double familyDailyNeed) {
        // 계수 2.0→1.0(실측 재캘리브레이션): familyDailyNeed 는 MOVE 명목치(부부 6.0)인데
        // 실제 소모는 부부 1.64/일이다. 따라서 종전 "2일치 예비"(=12)는 실제로 <b>7일치</b>였고,
        // 정원을 명목 6.0과 비교해 적자로 착각했던 것과 <b>동일한 착오</b>가 착공 예비에 박혀
        // 있었다. 그 결과 착공 임계가 30으로 부풀어 g5만 통과하고 g4는 d14에 25에서 멈췄다
        // (실측: 야생 최고 저장고 k1 24·k2 20·aux1 21 — 계산과 일치, 야생 착공률 0/4).
        // 1.0×명목 = 실측 기준 약 3.7일치로, 여전히 보수적이면서 임계를 24로 되돌린다.
        // 가족 규모 비례(자녀 늘면 예비 증가)는 그대로 유지 — 만족의 덫 보편화 취지 불변.
        return Math.max(FOUND_RESERVE_MIN, 1.0 * familyDailyNeed);
    }

    /** 성숙 구획의 재투자 확장에 쓸 수 있는 계정 비율. 0.5→0.3(부익부 가시화 — 사용자 의도):
     *  잔여 70%는 밤 정산 때 지주 저장고로 이체 — "이름만 지주"가 아니라 현금이 쌓이는 지주.
     *  가속 패키지로 확장이 과속(+33/일 실측)된 밸런스 회복을 겸하며, 미성숙 구획은 전액
     *  재투자 유지라 착공→첫 소작 초기 사슬은 불변. 누진 지대(fee)와 합산해 지주-소작
     *  소지금 격차 8~15배 예측(런5 실측 각주: 전액 흡수 시 2호 자금 영구 미달 — 배분 원리 유지). */
    public static final double MATURE_REINVEST_SHARE = 0.3;

    /**
     * 확장 시 저장고 예비 — 다음 밭 자격(성숙)에 도달한 지주는 다음 신규 밭 자금(비용+예비)을
     * 저장고에 보존하고, 확장은 지대(밭 계정)로만 굴린다: "다음 밭을 위한 저축"의 산술화.
     * 2배속 압축에서 확장 6/일 지출이 저장고를 상시 소진해 2호 밭 저축이 영구 불가하던 병목의
     * 수치 해소(런3 실측: 지주 저장고 3일째 15 정체 vs 필요 39). 성숙 전엔 기존 예비 12 —
     * 초기 성장(9→24)은 지대가 얇아 저장고 보충이 필수라 종전 그대로.
     */
    public static double expandReserve(boolean nextFarmEligible, int ownedPlots,
                                       double familyDailyNeed) {
        // 회차 25: 예비를 착공 임계와 동일 산식(비용+foundReserve)으로 — 종전 고정 +12는
        // 자녀 있는 지주의 착공 임계(비용+2×가구소모)보다 낮아, 확장이 그 위를 전부 흡수해
        // 3호 자금에 영원히 못 닿는 교착(런15 니컬러스·런18 티모시 실측). 예비 = 임계이면
        // 저축이 임계 도달하는 밤 착공이 정확히 발화한다.
        return nextFarmEligible
                ? newFarmCost(ownedPlots) + foundReserve(familyDailyNeed) : INVEST_RESERVE;
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
        return reinvestTiles(account, 1);
    }

    /** 단계 비용을 반영한 재투자 가능 타일 수 — 호출부가 구획의 단계를 안다. */
    public static int reinvestTiles(double account, int stage) {
        return (int) Math.floor(Math.max(0.0, account) / expandCost(stage));
    }

    // ── 마름(클래스 시스템 v1.3) — 임명·수당 산식 ──

    /** 마름 최초 임명 문턱 — 무마름 밭의 상시 소작 수. 이력 구획(stewarded)은 1(즉시 충원 — 칭호 무붕괴). */
    public static final int STEWARD_APPOINT_TENANTS = 2;

    /**
     * 수당 기본 계수 — 구획 소작 1인 평균 일수취의 배수(겸업 전제: 본업 소득 위에 얹는 수당).
     *
     * <p>0.5→0.8. 실측(런 A 24일): 수당 지급 33건 중 <b>32건이 정확히 +1</b>이었다. 계수가
     * 0.56~0.91 로 상한(1.0) 근처도 못 갔고, {@code 일수취 1.28 × 계수 0.91 = 1.16} 이
     * 버림으로 1 이 된 것이다. 그래서 상한을 올려도 아무 일도 안 일어난다 — 묶고 있던 것은
     * 기본 계수였다. 0.8 이면 계수가 0.9~1.2 로 올라 수당이 하루 1~2 가 된다.
     *
     * <p>마름은 무토지라 신분상 천민으로 묶이고 하루 순수지가 마이너스였다(실측 −0.95).
     * 계층이 굳으려면 고용된 자가 제 저장고를 가질 수 있어야 한다.
     */
    public static final double STEWARD_WAGE_BASE = 0.8;
    /** 수당 관리등급 가산(등급당). */
    public static final double STEWARD_WAGE_PER_GRADE = 0.05;
    /** 수당 근속 가산(재직일당) — 황금 수갑: 이탈(사임) 시 소멸하는 누진분. */
    public static final double STEWARD_WAGE_PER_DAY = 0.02;
    /** 수당 계수 상한 — 소작 평균의 1.0배: 마름 총소득(본업+수당)이 소작을 넘되 영주는 못 넘는 결박. */
    public static final double STEWARD_WAGE_CAP = 1.0;
    /** 재직 마름의 착공 예비 배율(이탈 방지 ④ — 마찰이지 금지 아님). */
    public static final double STEWARD_FOUND_RESERVE_MULT = 3.0;

    /** 수당 계수 = min(상한, 0.5 + 0.05×관리등급 + 0.02×근속일). 임금 = 소작 1인 평균 일수취 × 계수. */
    public static double stewardWageMult(int grade, long tenureDays) {
        return Math.min(STEWARD_WAGE_CAP, STEWARD_WAGE_BASE
                + STEWARD_WAGE_PER_GRADE * Math.max(0, grade)
                + STEWARD_WAGE_PER_DAY * Math.max(0L, tenureDays));
    }

    /** 소작 몫 = 수확 × (1−FEE). 나머지는 밭 계정(밤 정산 때 정수 유닛만 주인 저장고 — L 정수성). */
    public static double tenantShare(double yield) {
        return yield * (1.0 - FEE);
    }

    /** 밭 계정 몫(지대). tenantShare 와 합이 정확히 yield — 회계 항등식. */
    public static double ownerShare(double yield) {
        return yield * FEE;
    }

    /** 규모 반비례 분배(쌍곡선) — 지주 몫 fee(T) = 0.70 − 0.25×45/(45+T), 소작 몫 = 1−fee.
     *  선형 누진(0.45→0.55 캡)을 대체(사용자 확정): 소작 <b>전체</b> 수취는 영지가 클수록 늘되
     *  증가량이 로그형으로 체감하고, 지주 몫은 준선형 폭증 — 재산 격차 극대화(목표 20~30배).
     *  소농 fee(0)=0.45 불변(초기 사슬·소작 유인 무손상), 상한 0.70/소작 하한 0.30(임금
     *  1.8/일 = 아사 방지선: 배우자 정원·구휼 합산 생존). 대영지 소작은 2자녀 밴드로 계층화
     *  (소농 밭 소작 3자녀 유지) — 부익부의 자연 귀결. */
    /** 자산 누진 지대의 하한 — 무일푼 소작이 내는 몫. 낮을수록 빈곤 탈출이 빠르다. */
    public static final double FEE_MIN = 0.15;
    /** 자산 누진 지대의 상한 — 부유한 소작이 내는 몫. 1.0에 가까울수록 축적 제동이 단단하다. */
    public static final double FEE_MAX = 0.95;
    /** 누진 곡선의 급격함. 클수록 기준선 부근에서 급제동. */
    public static final double FEE_CURVE_K = 1.0;
    /** 기준선 = <b>성인</b> 명목소모 × 이 일수. 자녀를 빼는 것이 핵심(아래 progressiveFee 참조). */
    public static final double WEALTH_CAP_DAYS = 3.5;

    /**
     * 자산 누진 지대 — 소작 가구가 <b>부유할수록 수취 비율이 줄어든다</b>.
     *
     * <pre>
     *   r   = 가구 저장고 ÷ (성인 명목소모 × WEALTH_CAP_DAYS)
     *   fee = FEE_MIN + (FEE_MAX − FEE_MIN) × (1 − e^(−k·r))
     * </pre>
     *
     * <p>지수 감쇠라 소작 <b>수취</b>가 r 에 따라 지수로 줄고, 따라서 누적은 로그처럼 완만해진다.
     * 가난하면 거의 다 가져가 빠르게 회복하고(애는 빨리 낳게), 기준선에 닿으면 수취가 말라
     * 더 쌓지 못한다(축적은 못하게).
     *
     * <p><b>기준선에서 자녀를 빼는 것</b>이 자기제한의 핵심이다. 출산 게이트는 자녀마다 1.8씩
     * 오르는데(BIRTH_COST + 가구소모×REPRO_NEED_DAYS + 성인수+1) 기준선은 그대로이므로,
     * 자녀가 늘수록 게이트가 기준선 위로 올라가 출산이 스스로 막힌다. 자녀가 성년이 되어
     * 독립하면 게이트가 내려와 다시 열린다 — 평생 출산을 막지 않고 <b>속도만</b> 제한한다.
     * 기준선을 출산 게이트로 정규화하면 정반대가 된다(낳을수록 상한이 따라 올라 영영 안 막힘).
     *
     * <p>성인 수에는 비례하므로 일부다처 가구는 기준선이 높아 더 낳을 수 있고, 홀로 남은 가구는
     * 기준선이 게이트 아래로 떨어져 출산이 멎는다 — 별도 조항 없이 가구 형태가 반영된다.
     *
     * @param larder    소작 <b>가구</b>의 저장고
     * @param adultNeed 그 가구 <b>성인</b>의 명목 하루소모 합(자녀 제외)
     */
    public static double progressiveFee(double larder, double adultNeed) {
        double cap = Math.max(1.0E-6, adultNeed * WEALTH_CAP_DAYS);
        double r = Math.max(0.0, larder) / cap;
        return FEE_MIN + (FEE_MAX - FEE_MIN) * (1.0 - Math.exp(-FEE_CURVE_K * r));
    }

    /** 소작 몫 — 자산 누진. 아래 두 지주 몫과 합이 정확히 yield(회계 항등식). */
    public static double tenantShare(double yield, double larder, double adultNeed) {
        return yield * (1.0 - progressiveFee(larder, adultNeed));
    }

    /**
     * 지주 몫의 <b>기본분</b> — 밭 계정(확장 재원)으로. 누진 요율이 FEE 아래면 그 요율이 전부다
     * (가난한 소작에게서는 확장 재원조차 적게 걷힌다). FEE 를 넘는 부분은 아래 초과분이 가져간다.
     */
    public static double baseOwnerShare(double yield, double larder, double adultNeed) {
        return yield * Math.min(progressiveFee(larder, adultNeed), FEE);
    }

    /**
     * 지주 몫의 <b>초과분</b> — 지주 저장고 직행·확장 재원 아님(E11 축장 경로). 부유한 소작에게서
     * 더 걷힌 만큼이 그대로 지주의 자산 격차가 된다. 규모 누진이 폐지되며 휴면 상태였던 이 경로를
     * 자산 누진이 되살린다.
     */
    public static double excessOwnerShare(double yield, double larder, double adultNeed) {
        return yield * Math.max(0.0, progressiveFee(larder, adultNeed) - FEE);
    }

    public static double fee(int ownerTiles) {
        // 규모 누진 폐지 → 고정 FEE(0.45). 5규칙 정합: 규칙4·5(밭 무한 성장·자산 무한 누적)와
        // 규칙3(소작 출산 2~3)이 동시에 서려면 <b>소작 몫이 밭 크기와 무관</b>해야 한다. 누진은
        // 대영지 소작 몫을 0.30까지 눌러, 규칙4가 성공할수록 규칙3이 깨지는 구조였다.
        // 검산(base 4.0/일): 소작 임금 4.0×0.55 = 2.20 → 가구 수입 정원1.2+풀0.4+2.2 = 3.80
        //   자녀2 소모 3.36 → +0.44 / 자녀3 3.84 → −0.04(한계) / 자녀4 4.32 → −0.52 → 2~3명.
        // 격차(E11 목표 8~13배)는 요율이 아니라 <b>규모</b>가 만든다: 소작 20인 지주
        //   20×4.0×0.45 = 36/일 vs 소작 2.2/일 = 16배이고 소작 수에 따라 상한 없이 커진다.
        // 부수: excessOwnerShare = max(0, fee−FEE) 가 항상 0이 되어 E11 축장 경로는 자연 휴면.
        return FEE;
    }

    /** 소작 몫(규모 누진 반영) — ownerShare(y,t) 와 합이 정확히 yield(회계 항등식). */
    public static double tenantShare(double yield, int ownerTiles) {
        return yield * (1.0 - fee(ownerTiles));
    }

    /** 밭 계정 몫(규모 누진 반영). */
    public static double ownerShare(double yield, int ownerTiles) {
        return yield * fee(ownerTiles);
    }

    // ── fee 분할(E11 저장고 격차) — 지주 몫을 두 갈래로 쪼갠다. 소작 몫은 손대지 않아 회계 항등식
    //    tenantShare + baseOwnerShare + excessOwnerShare == yield 유지. 목적: 확장 재원(계정)과
    //    잠금 축장(저장고 직행)을 분리해 부익부가 확장에 갉아먹히지 않게 — 격차의 생전 지속. ──

    /** 지주 몫의 <b>기본분</b>(고정 0.45) — 밭 계정으로: 평상시 확장 재원 + 잔여 정산. fee(0)과 동일. */
    public static double baseOwnerShare(double yield) {
        return yield * FEE;
    }

    /** 지주 몫의 <b>초과분</b>(누진분 fee(T)−0.45 = 0.25×T/(45+T)) — 지주 저장고 직행·잠금(확장
     *  재원 아님). T가 클수록(대영지) 커져 격차를 누진적으로 벌린다. T=0에서 정확히 0(소농 무영향). */
    public static double excessOwnerShare(double yield, int ownerTiles) {
        return yield * Math.max(0.0, fee(ownerTiles) - FEE);
    }
}
