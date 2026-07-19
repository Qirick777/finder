package com.evosim.core;

import java.util.List;
import java.util.Set;

/**
 * 식량 경제 v2 (연속 저장고 + 개인 보유). 기존 "밤 18000틱 1회 정산"을 대체하는 계산의 심장 — 전부 순수.
 *
 * <p><b>H(holding)는 배부름과 소지 식량의 통합 추상이다.</b> 따라서 '위급'은 배고픔이 아니라
 * <b>소지 식량 고갈</b>이며, 채집 성공 즉시 아사 클럭이 풀린다. 거처 저장고 L은 <b>정수 단위로만</b>
 * 입출금하고(비정수 잔여는 개인이 소지), 소모는 소수로 연속 차감한다.
 *
 * <p>규칙 요약(R1~R6): 소모(R1) → 채집×성별배율(R2) → 집 정산: 여분 정수 입금 + 남편→자식→아내
 * 순 급식(R3) → 저장고 부족 시 가족 채집 합류(R4) → 출산 비용 차감형 번식 판정(R5) → 위급 시
 * 수면 무시 생존 행동(R6). 표현층은 이 함수들의 결과에 이동·연출만 입힌다(§18).
 */
public final class FoodEconomy {

    // ── H 밴드·임계 ──
    /** 급식 발동 트리거 — 이 미만이면 저장고에서 채운다. */
    public static final double FILL_TRIGGER = 1.0;
    /** 급식 종료 목표 — 여기 이상이 될 때까지 정수 단위로 채움. 임시값, food/생존시뮬로 확정. */
    public static final double FILL_TARGET = 1.5;
    /** 이 이상의 정수 여분은 거처 저장고에 입금. */
    public static final double BAND_HIGH = 2.0;
    /** 수확 세션 운반 상한(소작 루프 v2) — 노동 중 내 밭·정원에 익은 것이 남아 있으면 입금 귀가를
     *  이 값까지 미룬다: 일일 수확 8타일×0.75 = 6.0 → 귀가 왕복 6~9회가 1~2회로. 평시는 BAND_HIGH. */
    public static final double WORK_CARRY_CAP = 6.0;
    /** 위급 — 소지 식량 고갈 임박(R6 발동·굶주림 판정). */
    public static final double CRITICAL = 0.3;
    /** 귀가 goal 발동 임계(급식 직후 재귀가 핑퐁 방지, 히스테리시스). 임시값. */
    public static final double RETURN_LOW = 0.8;

    // ── 채집 ──
    /** 1트립당 기본 수확. 임시값, food/생존시뮬로 확정.
     *  손계산: 남성 2.0×1.5×3트립=9.0/일 vs 가족(성인2+유아1) 소모 6.9/일 → 잉여 +2.1.
     *  여성 2.0×0.5×3트립=3.0/일 = 본인 기준 소모와 동률(자급 경계선). */
    public static final double BASE_YIELD = 2.0;
    /** 성별 채집 배율 — 남성 혼자 3인 부양 가능하게(외벌이 설계). */
    public static final double MALE_FORAGE = 1.5;
    public static final double FEMALE_FORAGE = 0.5;

    // ── 번식 ──
    /** 출산 시 저장고에서 차감하는 비용(연쇄 출산 제동). 임시값, food/출산비용으로 검증. */
    public static final double BIRTH_COST = 3.0;

    // ── 표현층 타이밍 상수(단일 출처 유지를 위해 여기 정의) ──
    /** 아사 유예(틱) — H=0 지속이 이 틱을 넘으면 굶주림 피해 시작. 임시값, 게임 관찰로 확정. */
    public static final int GRACE_TICKS = 1200;
    /** 가족 정산 주기(틱, 스태거). 유아 소모 0.9/일=1200틱당 0.045라 충분히 촘촘. 임시값. */
    public static final int FAMILY_TICK_INTERVAL = 1200;

    private FoodEconomy() {
    }

    /** 하루 기준 소모(단계). 활동일 ~3회 귀가 트립 템포를 만드는 값. */
    static double baseConsumption(LifeStage s) {
        return switch (s) {
            case ADULT -> 3.0;
            case ELDER -> Elder.CONSUMPTION; // 노년 2.0 — 적게 먹음
            case BOY -> 1.5;
            case INFANT -> 0.9;
        };
    }

    /**
     * 소모율(식량/일) = 기준(단계) × 활동배율 × 특성배율 (+ 부상 회복 가산 0.5).
     * 표현층이 Δt/24000 을 곱해 틱마다 H에서 차감한다.
     */
    public static double consumptionPerDay(LifeStage stage, Activity act, Individual ind, boolean healing) {
        double c = baseConsumption(stage) * act.mult * traitMult(ind);
        if (healing) {
            c += 0.5;
        }
        return Math.max(0.0, c);
    }

    /**
     * 채집 수확 배율 = 성별(남 1.5 / 여 0.5) × 기존 채집배율({@link Multipliers#gather} —
     * 약초학자·손재주 등 보정특성 포함). 보정특성이 잉여↑ → 자식↑로 이어진다.
     */
    public static double forageYieldMult(Individual ind) {
        double sexM = ind.sex() == Sex.MALE ? MALE_FORAGE : FEMALE_FORAGE;
        return sexM * Multipliers.gather(ind);
    }

    /** 1트립 기대 수확 = 기본 수확 × 채집 수확 배율. */
    public static double tripYield(Individual ind) {
        return BASE_YIELD * forageYieldMult(ind);
    }

    /**
     * 집 정산(R3) — <b>집에 있는(home)</b> 구성원만 대상으로, 우선순위 리스트 순서대로:
     * ① H≥{@link #BAND_HIGH}인 여분을 정수 단위로 저장고에 입금.
     * ② 원래 H<{@link #FILL_TRIGGER}였던 구성원만, H≥{@link #FILL_TARGET}이 될 때까지 정수 인출
     * (히스테리시스 — 밴드 중간값은 건드리지 않아 진동을 막는다).
     *
     * @param familyInPriority 남편 → 자식(태어난 순) → 아내 순으로 정렬된 전체 가족
     * @return 갱신된 저장고 (H는 Eater에 in-place 반영). L은 항상 정수 유닛으로만 입출금 —
     *         요리 축 배율은 H 쪽(유닛당 소요/회복량)에만 붙어 L의 정수성이 보존된다.
     */
    public static double settleHome(double larder, List<Eater> familyInPriority) {
        for (Eater e : familyInPriority) { // ① 입금: 여분 정수 유닛만 — 무책임은 문턱 3.0(가족 몫 기여↓)
            double depositAt = depositThreshold(e.ind);
            // 저장 배율(요리 축)은 L 정수성을 지키기 위해 "1유닛에 드는 H"로 적용 —
            // 요리사 0.83H/유닛(가공 이득), 날로먹기 1.25H/유닛(보관하면 상함). 무특성 1.0(종전과 동일).
            double hPerUnit = 1.0 / Multipliers.storage(e.ind);
            while (e.home && e.holding >= depositAt && e.holding >= hPerUnit) {
                larder += 1.0;
                e.holding -= hPerUnit;
            }
        }
        for (Eater e : familyInPriority) { // ② 분배: 트리거 미만이었던 구성원만, 목표까지
            if (!e.home || e.holding >= FILL_TRIGGER) {
                continue;
            }
            while (e.holding < FILL_TARGET && larder >= 1.0) {
                larder -= 1.0;
                e.holding += intakeMult(e.ind); // 날로먹기 1.2 — 같은 1유닛으로 더 회복
            }
        }
        return larder;
    }

    /** 번식 게이트의 소모 비축 일수. 1→2(회차 13 — 압축 재캘리브레이션): 쿨다운 1일 체제에서
     *  계수 1은 지참금 14로 d0부터 열려 초반 출산이 가구당 ~1/일(목표 '초반 1'의 4배)로 폭주 —
     *  전 가구 소모 조기 폭증이 엘리트 착공 저축 경주를 붕괴시켰다(런9~11 3회 실측).
     *  검산: 부부 문턱 18(>지참금 14 — 첫 출산 d2, 설계 주석 정합) · 자녀1 20.8(무밭 정체
     *  저장고로 둘째 억제 = 초반 1) · 소작 임금 가구는 저장고 상승으로 2~3명(2.3 재개). */
    public static final double REPRO_NEED_DAYS = 2.0;

    /**
     * 번식 판정(R5) — 출산 비용을 <b>선차감한 뒤에도</b> (가족 하루소모×{@link #REPRO_NEED_DAYS}
     * + 성년수+1 ± 특성)의 여유가 남을 때만 참. 비용 없는 스냅샷 판정의 연쇄 출산을
     * {@link #BIRTH_COST}가 제동한다. 굶주림 판정은 {@link #anyStarvingHome}을 쓸 것.
     */
    public static boolean canReproduce(double larder, double familyDailyNeed,
                                       int adultCount, double reproTraitAdj, boolean anyStarving) {
        if (anyStarving) {
            return false;
        }
        return (larder - BIRTH_COST - familyDailyNeed * REPRO_NEED_DAYS)
                >= (adultCount + 1) + reproTraitAdj;
    }

    /**
     * 굶주림 판정(B-1) — <b>settleHome 이후에도</b> H<{@link #CRITICAL}인 <b>home=true</b> 구성원만.
     * 밖에 있는 구성원은 급식받을 수 없으므로 제외(사냥 중 일시 위급이 번식을 노이즈성 차단 방지).
     */
    public static boolean anyStarvingHome(List<Eater> family) {
        for (Eater e : family) {
            if (e.home && e.holding < CRITICAL) {
                return true;
            }
        }
        return false;
    }

    /** 신혼 지참금 가산(정수) = 정원 전액(8그루 비용) — 정착 즉시 정원 8/8 완성이 설계 기준선.
     *  식수 후 잔여 6 < 출산 게이트 12라 d0 출산은 없고, 완성 정원 잉여(+1.9/일)로 d2에 첫 출산. */
    public static final double INITIAL_LARDER_BONUS = 8.0;

    /** 저장고 시작값 = ceil(하루소모) + 지참금 — 정수 유지(정수 입출금 불변식). */
    public static double initialLarder(double familyDailyNeed) {
        return Math.ceil(familyDailyNeed) + INITIAL_LARDER_BONUS;
    }

    /** 가족 명목 하루소모 합(이동 기준·부상 제외) — 번식 예비·저장고 넉넉 판정의 기준값. */
    public static double nominalDailyNeed(List<Eater> family) {
        double sum = 0.0;
        for (Eater e : family) {
            sum += consumptionPerDay(e.stage, Activity.MOVE, e.ind, false);
        }
        return sum;
    }

    private static double traitMult(Individual ind) {
        Set<Trait> t = ExpressionResolver.expressedTraits(ind);
        double m = Physique.appetite(ind); // 힘/약 등급 비례 소모(±4%/등급, V = 종전 ×1.2/×0.8)
        if (t.contains(Trait.DILIGENT)) m *= 1.1;
        if (t.contains(Trait.LAZY)) m *= 0.9;
        if (t.contains(Trait.NO_MATERNAL)) m *= 0.9; // 모성애없음 — 돌봄에 에너지 안 씀(본인 소모↓)
        // 페널티 특성의 반대급부(에너지 절약) — 각 축의 페널티(출산상한−·임계+·체력−·재생−)는 유지
        if (t.contains(Trait.CHILD_AVERSE)) m *= 0.95;        // 아이불호 — 육아에 에너지 안 씀
        if (t.contains(Trait.REPRODUCTION_AVERSE)) m *= 0.95; // 번식불호 — 번식에 에너지 안 씀
        if (t.contains(Trait.FRAIL)) m *= 0.95;               // 빈약 — 작은 몸
        if (t.contains(Trait.SICKLY)) m *= 0.9;               // 병약 — 낮은 대사(페널티 최대라 보상도 최대)
        if (t.contains(Trait.LUXURIOUS)) m *= 1.3;            // 사치 — 낭비(만족 기준 자동 상승 ↔ 과시 매력)
        if (t.contains(Trait.FRUGAL)) m *= 0.95;              // 검소 — 아껴 씀. 0.9→0.95(산출 ⑦):
        // 0.9면 검소 부부 잉여 0.905/일 → 자식 4명(능력 없이 능력 밴드 도달). 0.95 = 부부 소모
        // 3.04 → 잉여 0.745 → 3명(보정값 계층)으로 정렬.
        return m;
    }

    /** 섭취 효율(요리 축) — 날로먹기는 같은 식량 1유닛으로 H를 더 회복(저장 손실 ×0.8의 반대급부). */
    public static double intakeMult(Individual ind) {
        return ExpressionResolver.isExpressed(ind, Trait.RAW_EATER) ? 1.2 : 1.0;
    }

    // ── 특성 효과 노브(전부 순수·evotest 대조) ──

    /** 나눔 자격 문턱(이기/이타 축) — 이타는 여유 없어도 나눔, 이기는 나눔 안 함. */
    public static double shareThreshold(Individual ind) {
        Set<Trait> t = ExpressionResolver.expressedTraits(ind);
        if (t.contains(Trait.SELFISH)) return Double.POSITIVE_INFINITY;
        if (t.contains(Trait.ALTRUISTIC)) return 1.0;
        return FILL_TARGET;
    }

    /** 나눔 1회 전달량(관대/인색 축). */
    public static double shareAmount(Individual ind) {
        Set<Trait> t = ExpressionResolver.expressedTraits(ind);
        if (t.contains(Trait.GENEROUS)) return 0.75;
        if (t.contains(Trait.STINGY)) return 0.25;
        return 0.5;
    }

    /** 입금 문턱(책임감 축) — 무책임은 3까지 들고 다니며 가족 저장고 기여가 줄어든다. */
    public static double depositThreshold(Individual ind) {
        return ExpressionResolver.isExpressed(ind, Trait.IRRESPONSIBLE) ? 3.0 : BAND_HIGH;
    }

    /** 저장고 "넉넉" 기준 일수(시간지향 축) — 미래지향은 더 모아야 쉬고, 현재지향은 일찍 쉰다(R4). */
    public static double comfortDays(Individual ind) {
        Set<Trait> t = ExpressionResolver.expressedTraits(ind);
        if (t.contains(Trait.FUTURE_ORIENTED)) return 3.0;
        if (t.contains(Trait.PRESENT_ORIENTED)) return 1.0;
        return 2.0;
    }

    /**
     * 모성애 축 — <b>어미의 특성</b>이 자식(유아·소년)의 허기 효율을 바꾼다.
     * 강함(+1): 자식 소모 ×0.7(적은 식량으로 적정 허기) / 없음(−1): ×1.3(돌봄 부실) / 중립·성년 1.0.
     */
    public static double maternalHungerMult(LifeStage stage, int maternalCare) {
        if ((stage != LifeStage.INFANT && stage != LifeStage.BOY) || maternalCare == 0) {
            return 1.0; // 자식 단계에만 적용 — 노년이 자식 취급되는 누출 방지
        }
        return maternalCare > 0 ? 0.7 : 1.3;
    }

    /** 정산 단위 — 개체 + 단계 + 보유 H + 집에있음. H만 가변(은닉 상태 없음 → 결정론). */
    public static final class Eater {
        public final Individual ind;
        public final LifeStage stage;
        public double holding;
        public boolean home;

        public Eater(Individual ind, LifeStage stage, double holding, boolean home) {
            this.ind = ind;
            this.stage = stage;
            this.holding = holding;
            this.home = home;
        }
    }
}
