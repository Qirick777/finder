package com.evosim.core;

import java.util.Set;

/**
 * 노년기 규칙 (생애 확장 — 2배속 압축판). 청년(성년) {@link #ADULT_DAYS}일 — 출산은 상한 10
 * (사실상 무제한)에 쿨다운 1일: 물리 최대 ~7명, 실제는 경제 게이트가 계층별로 가른다 —
 * 뒤 노년 진입, {@link #elderDays}일 뒤 자연사.
 * 수명 설계(현행): 유아 1.75 + 소년 3 + 성년 15 + 노년 2 = 21.75일.
 *
 * <p>노년은 <b>은퇴</b>다(인구 제동 1단계): 채집·밭일·배달·고용·구애 goal 이 전부 닫히고 귀가·
 * 취침·리시만 남는다 — 연산량을 아끼고, 자식 지원은 성년 부모의 정산({@link ChildSupport})이
 * 맡는다. 아래 쿼터·공유 상수는 그 이전 '쿼터 노동' 설계의 순수 함수로, 검사(/evotest elder)와
 * 노년 소모(2.0/일)에만 남아 있다.
 */
public final class Elder {

    /** 청년(성년) 기간(일). 9→11→15(판정 기준 d14 정합 — 사용자 승인): 왕조 완성 창이
     *  d10→d14(창 d12~15)로 이동하며 "판정일 < 창업 세대 노년 진입" 불변식을 유지하는
     *  최소 명목치(판정 14 < 노년 15 — 종전 판정 10 < 노년 11과 동형). d0 스폰 창업
     *  엘리트가 판정 창 내내 성년 노동력·관리력을 유지한다. 수명 2+15+3=20. */
    public static final int ADULT_DAYS = 15;
    /** 노년 기본 기간(일) — 강건 +1 / 병약 −1. 6→3(2배속)→<b>2</b>(인구 제동 1단계, 사용자 승인):
     *  노년의 하루를 유아기로 옮겼다(유아 0.75→1.75일 — 병듦에 노출되는 창). 수명 합은
     *  1.75+3+15+2 = 21.75일로 종전(0.75+3+15+3)과 같다. 노년은 이제 <b>은퇴</b>다 — 귀가·취침·
     *  리시만 남고 노동·배달·고용·구애는 없다(MimicEntity.growthTick 의 은퇴 정리 참조). */
    public static final int ELDER_BASE_DAYS = 2;
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
