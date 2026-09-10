package com.evosim.core;

/**
 * <b>유아 병듦</b> — 인구 제동의 첫 장치. 마을이 빽빽해질수록 유아가 병들어 죽고, 부모의 교육이
 * 그것을 얼마간 막는다. 사유는 마인크래프트적이자 현실적이다: 집이 다닥다닥 붙은 마을은
 * 우물·모닥불·오물이 한 마당을 쓰고(중세 도시의 영아 사망), 배운 부모는 아이를 씻기고 끓인
 * 물을 먹인다.
 *
 * <p>설계 의도(사용자 승인): <b>처음부터 끝까지 도는 하나의 장치</b>가 인구가 늘수록 제동을
 * 건다. 수표(하루 발병 p · 유아기 1.75일 누적 사망률, 무학 부모):
 * <pre>
 *   이웃  4채  p 0.006 · 누적  1%      이웃 16채  p 0.100 · 누적 17%
 *   이웃  8채  p 0.025 · 누적  4%      이웃 24채  p 0.225 · 누적 36%
 *   이웃 12채  p 0.056 · 누적 10%      이웃 28채+ p 0.300(상한) · 누적 46%
 * </pre>
 * 초반(반경 32 안 이웃 2~6채)은 거의 0 이라 성장이 빠르고, 집 간격 10·기본 16 의 고리가 세
 * 겹 차면(16채+) 여섯에 하나가 죽는다. 상급 부모(−60%)는 같은 밀집에서 p 0.04 — 이 수를
 * 낮추는 길은 학교뿐이다. 병원은 다음 단계.
 *
 * <p>순수 함수 — /evotest illness 로 전 수치 대조. 발병 = 사망(현 단계). 회복·병원은 뒤.
 */
public final class InfantIllness {

    /** 이웃을 세는 반경(블록) — 집 사이 최소 간격 10·기본 16 에서 반경 32 는 두 겹 고리쯤. */
    public static final double NEIGHBOR_RADIUS = 32.0;
    /** 상한이 닿는 이웃 수 — 반경 32 원에 집 16채면 "빽빽하다". */
    public static final int DENSE_HOMES = 16;
    /** 이웃 {@link #DENSE_HOMES}채에서의 하루 발병 확률(캡 적용 전). */
    public static final double BASE_RATE = 0.1;
    /** 하루 발병 확률 상한 — 아무리 빽빽해도 하루 30% 를 넘지 않는다. */
    public static final double CAP = 0.3;
    /** 부모 교육 1등급당 감면(무학 0 · 초급 1 · 중급 2 · 상급 3 → 최대 −60%). */
    public static final double EDUCATION_RELIEF = 0.2;

    private InfantIllness() {
    }

    /** 밀집 항 — 이웃 수의 제곱에 비례(적을 땐 거의 0, 많을 땐 급등), {@link #CAP} 으로 자른다. */
    public static double densityRate(int neighborHomes) {
        double x = Math.max(0, neighborHomes) / (double) DENSE_HOMES;
        return Math.min(CAP, BASE_RATE * x * x);
    }

    /** 교육 항 — 부모 중 높은 쪽의 교육수준(0~{@link Schooling#MAX_LEVEL})만큼 감면. */
    public static double educationFactor(int parentLevel) {
        int lv = Schooling.level(parentLevel);
        return 1.0 - EDUCATION_RELIEF * lv;
    }

    /** 하루 발병 확률 = 밀집 항 × 교육 항. */
    public static double dailyOnset(int neighborHomes, int parentLevel) {
        return densityRate(neighborHomes) * educationFactor(parentLevel);
    }
}
