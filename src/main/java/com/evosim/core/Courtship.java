package com.evosim.core;

/**
 * 구애 수락 판정 (구애 사양서 v2 §3, 베이지안). 순수 함수 — 구애받은 쪽 R이 구애자 C를 수락할 확률.
 *
 * <p>핵심: R의 후보군에서 C보다 <b>엄격히 매력이 높은</b> 후보 수(better)와 R의 후보 수(n), R의 까다로움
 * 계수(k)로 "C가 별로일 확률" q̂ 을 추정하고, {@code P_accept = 1 - q̂}. 후보가 많고 C가 상위일수록↑,
 * 까다로움 k 가 클수록 표본이 커도 100%에 도달하지 않는다(의도된 동작).
 *
 * <pre>
 *   q̂ = (n + k == 0) ? q0 : (better + k·q0) / (n + k)     // 분모는 항상 (n+k)
 *   P_accept = clamp(1 - q̂, 0, 1)
 * </pre>
 */
public final class Courtship {

    /** 사전 확률 q0 (전 개체 공통 상수). */
    public static final double Q0 = 0.5;

    /**
     * 실전 밸런싱 스케일 — 한쪽 구애의 최종 수락 확률에 곱해 <b>전체적으로 살짝 낮춘다</b>(1.0=원식).
     * 상호구애 자동 성사에는 적용하지 않는다. 값만 바꿔 난이도 조절.
     */
    public static final double ACCEPT_SCALE = 0.8;

    private Courtship() {
    }

    /**
     * 수락 확률.
     *
     * @param better R의 후보 중 C보다 <b>매력이 엄격히 큰(&gt;)</b> 개체 수 (동점은 미포함 = C에게 유리)
     * @param n      R의 현재 후보 수
     * @param k      R의 까다로움 계수 (MateChoiceClass.k)
     */
    public static double acceptProbability(int better, int n, int k) {
        double qHat = (n + k == 0) ? Q0 : (better + k * Q0) / (n + k);
        double p = 1.0 - qHat;
        return Math.max(0.0, Math.min(1.0, p));
    }

    /** 실전 수락 확률 = 공식값 × {@link #ACCEPT_SCALE} (밸런싱 하향). 게임 구애 판정은 이걸 쓴다. */
    public static double acceptChance(int better, int n, int k) {
        return acceptProbability(better, n, k) * ACCEPT_SCALE;
    }

    /** R의 후보 매력 배열에서 C의 매력보다 엄격히 큰 후보 수(better). 동점은 세지 않는다. */
    public static int betterCount(int[] candidateCharms, int suitorCharm) {
        int c = 0;
        for (int a : candidateCharms) {
            if (a > suitorCharm) {
                c++;
            }
        }
        return c;
    }
}
