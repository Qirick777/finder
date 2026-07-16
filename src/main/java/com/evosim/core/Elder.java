package com.evosim.core;

import java.util.Set;

/**
 * 노년기 규칙 (생애 확장). 청년(성년) {@link #ADULT_DAYS}일 — 최상 배율 부부가 번식 슬롯 10개를
 * 쿨다운(3일)으로 소진하는 30일 + 여유 — 뒤 노년 진입, {@link #elderDays}일 뒤 자연사.
 *
 * <p>노년은 <b>쿼터 노동</b>: 하루 필요량({@link #dailyQuota})만 벌고 쉰다. 책임 축이 노년에 추가
 * 발동 — 책임은 잉여 목표까지 더 벌어 <b>자식 집에 방문 배달</b>, 무책임은 자기 생존분만(공유 없음),
 * 기본은 남으면 주고 안 남으면 만다. 순수 함수 — /evotest elder 로 전 수치 대조.
 */
public final class Elder {

    /** 청년(성년) 기간(일) — 번식 슬롯 최대 10개 × 쿨다운 3일 + 정착·여유. */
    public static final int ADULT_DAYS = 35;
    /** 노년 기본 기간(일) — 강건 +2 / 병약 −2. */
    public static final int ELDER_BASE_DAYS = 8;
    /** 노년 채집·사냥 수확 배율(노쇠). */
    public static final double FORAGE_MULT = 0.5;
    /** 노년 하루 기준 소모(성인 3.0보다 적게 먹음). */
    public static final double CONSUMPTION = 2.0;
    /** 노년 이동속도 배율. */
    public static final double SPEED_MULT = 0.8;
    /** 노년 노동 마감 시각(성인 8000) — 이후는 쉼/방문. */
    public static final int WORK_END = 6000;
    /**
     * 노년 쿼터 배율 — 명목 필요량(이동 1.0 기준 2.0/일)이 실효 소모(활동가중 ≈0.68/일)의 2.9배라
     * 노년이 성년급 잉여를 계속 쏟아붓던 누수의 원인. ×0.5 = 쿼터 1.0/일 = 실효 소모의 1.4~1.5배
     * (자급 + 우천 버퍼) — 노년 확장 산출 ㉳. 특성 소모곱은 ownNeed 에 이미 포함돼 자동 정합.
     */
    public static final double QUOTA_MULT = 0.5;
    /** 책임 노인의 잉여 목표 — 2.0→0.75(산출 ㉴): 생애 배달 총량 0.75×8일 = 6.0 = 자식 2가구 ×
     *  밴드 반 계단(0.09/일×32일) — 조부모 보조는 '보정' 계층을 넘지 않는다. */
    public static final double SHARE_EXTRA = 0.75;
    /** 가드② — 자기 저장고가 부부 하루소모 × 이 일수 이상일 때만 배달(배우자 완충). */
    public static final double HOME_RESERVE_DAYS = 1.0;

    private Elder() {
    }

    /**
     * 노년 일일 채집 쿼터 — 기본 = 자기 하루소모(명목) × {@link #QUOTA_MULT} / 책임 =
     * +{@link #SHARE_EXTRA}(나눔 몫) / 무책임 = 기본과 같음(대신 공유 안 함). 부지런 ×1.2 / 게으름 ×0.8.
     */
    public static double dailyQuota(Individual ind, double ownDailyNeed) {
        Set<Trait> t = ExpressionResolver.expressedTraits(ind);
        double q = ownDailyNeed * QUOTA_MULT;
        if (t.contains(Trait.OVER_RESPONSIBLE)) {
            q += SHARE_EXTRA;
        }
        if (t.contains(Trait.DILIGENT)) {
            q *= 1.2;
        } else if (t.contains(Trait.LAZY)) {
            q *= 0.8;
        }
        return q;
    }

    /** 잉여 공유 자격 — 무책임만 안 나눔(책임=목표부터 잉여, 기본=남으면). */
    public static boolean sharesLeftover(Individual ind) {
        return !ExpressionResolver.isExpressed(ind, Trait.IRRESPONSIBLE);
    }

    /** 노년 기간(일) — 강건 +2 / 병약 −2. */
    public static int elderDays(Individual ind) {
        Set<Trait> t = ExpressionResolver.expressedTraits(ind);
        int d = ELDER_BASE_DAYS;
        if (t.contains(Trait.HARDY)) {
            d += 2;
        }
        if (t.contains(Trait.SICKLY)) {
            d -= 2;
        }
        return d;
    }
}
