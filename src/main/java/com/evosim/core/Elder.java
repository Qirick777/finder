package com.evosim.core;

import java.util.Set;

/**
 * 노년기 규칙 (생애 확장 — 2배속 압축판). 청년(성년) {@link #ADULT_DAYS}일 — 기본 출산상한
 * 5명을 쿨다운(1일)으로 소진하는 5일 + 정착·여유 — 뒤 노년 진입, {@link #elderDays}일 뒤 자연사.
 * 수명 설계: 성장2 + 성년7 + 노년3 = 12일 &lt; 첫 증손 출생(≈12.3일) — "증손을 보기 직전
 * 노환사"의 세대 시계(강건 +1만 예외적으로 증손을 본다). 대기 상수만 반감 — 소득·소모율 불변.
 *
 * <p>노년은 <b>쿼터 노동</b>: 하루 필요량({@link #dailyQuota})만 벌고 쉰다. 책임 축이 노년에 추가
 * 발동 — 책임은 잉여 목표까지 더 벌어 <b>자식 집에 방문 배달</b>, 무책임은 자기 생존분만(공유 없음),
 * 기본은 남으면 주고 안 남으면 만다. 순수 함수 — /evotest elder 로 전 수치 대조.
 */
public final class Elder {

    /** 청년(성년) 기간(일). 13→7(2배속 압축): 5명째 출산 = 첫 출산 ~1.1일 + 4×쿨다운 1일
     *  = 5.1일 ≤ 가용 창 6일(+정착 1일). */
    public static final int ADULT_DAYS = 7;
    /** 노년 기본 기간(일) — 강건 +1 / 병약 −1. 6→3(2배속): 사망 나이 2+7+3=12.0 < 증손 12.3. */
    public static final int ELDER_BASE_DAYS = 3;
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

    /** 노년 기간(일) — 강건 +1 / 병약 −1 (2배속: 기본 3과 비례 유지 — 구 ±2/기본 6). */
    public static int elderDays(Individual ind) {
        Set<Trait> t = ExpressionResolver.expressedTraits(ind);
        int d = ELDER_BASE_DAYS;
        if (t.contains(Trait.HARDY)) {
            d += 1;
        }
        if (t.contains(Trait.SICKLY)) {
            d -= 1;
        }
        return d;
    }
}
