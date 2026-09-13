package com.evosim.core;

/**
 * <b>교회 고도화</b>(사용자 승인, 지식인 체계 P5) — 순수 함수만.
 *
 * <p>큰교회에 <b>목사</b>(전업 — 예배 시간 교회 상주)와 <b>선교사</b>(평소 제 일을 하다가 배회
 * 시간에 사슬 밖 가구를 찾아가 신세를 심는다)가 붙는다. 교회 계정은 헌금이고, 급여를 그 계정에서
 * 내며 모자라면 주인(군주)이 보전하고 남으면 주인이 갖는다 — "미믹들이 방문해서 내는 식량으로
 * 돌고, 적자는 군주가 보전한다".
 *
 * <p>왜 필요한가: 추종이 생기는 길이 소작·긴급고용·구휼뿐이라 밭 경제 밖 평민(런 28 d7 "주인
 * 없음" 14명)은 누구도 못 끌어들였다. 종교가 밭 없이도 추종을 넓혀 인두세·병사 정원(지킬 가구
 * ÷ 4)·밭 상한의 기반을 키운다.
 */
public final class Church {

    private Church() {
    }

    /** 목사 하루 급여 — 전업이라 소작(3.3)보다 낮되 성직자(0.5)보다 훨씬 높다. */
    public static final double PASTOR_WAGE = 2.0;
    /** 선교사 하루 급여 — 평소 제 일을 하므로 부업 값. */
    public static final double MISSIONARY_WAGE = 1.5;
    /** 목사가 있는 큰교회의 하루 예배 정원(없으면 12). */
    public static final int PASTOR_VISIT_CAP = 16;
    /** 목사가 있는 교회의 헌금(없으면 0.25). */
    public static final double PASTOR_TITHE = 0.4;
    /** 목사가 있는 교회의 방문 신세 배수. */
    public static final double PASTOR_BOND_MULT = 1.5;
    /** 선교 반경(교회 기준) — 통근 한계(96)보다 넓다: 마을 밖 변두리까지 간다. */
    public static final double MISSION_RANGE = 128.0;
    /** 선교사가 하루에 찾아가는 가구 수 — 배회 시간 하나에 둘이면 걷는 시간이 남는다. */
    public static final int MISSION_PER_DAY = 2;
    /** 선교 방문 1회의 신세 — 추종 문턱(4)까지 네 번. 소작(0.6/일)·봉토 신세보다 작아 이미 매인
     *  가구는 안 넘어오고, 아무도 안 따르는 가구만 넘어온다. */
    public static final double MISSION_BOND = 1.0;
    /** 선교사 최소 학력 — 초급. 글을 읽어야 전한다. 학위자는 우대. */
    public static final int MISSIONARY_MIN_SCHOOL = 1;

    /** 하루 예배 정원 — 큰교회에 목사가 있으면 16, 큰교회 12, 작은교회 4. */
    public static int visitCap(boolean bigChurch, boolean hasPastor, int bigCap, int smallCap) {
        if (!bigChurch) {
            return smallCap;
        }
        return hasPastor ? PASTOR_VISIT_CAP : bigCap;
    }

    /** 방문 1회 헌금 — 목사가 있으면 0.4, 없으면 기본값. */
    public static double tithe(boolean hasPastor, double baseTithe) {
        return hasPastor ? PASTOR_TITHE : baseTithe;
    }

    /** 방문 신세 배수 — 목사가 있으면 1.5. */
    public static double bondMult(boolean hasPastor) {
        return hasPastor ? PASTOR_BOND_MULT : 1.0;
    }

    /**
     * 밤 정산 — 헌금 수입에서 급여를 낸다. [0] 주인이 보전하는 적자(≥0), [1] 주인이 가져가는
     * 흑자(≥0). 둘 중 하나는 0 이다.
     */
    public static double[] settle(double income, double wages) {
        double net = Math.max(0.0, income) - Math.max(0.0, wages);
        return net >= 0.0 ? new double[] {0.0, net} : new double[] {-net, 0.0};
    }

    /** 선교사 자격 — 학력 초급 이상이거나 학위자. */
    public static boolean missionaryEligible(int schoolLevel, int degree) {
        return degree > 0 || schoolLevel >= MISSIONARY_MIN_SCHOOL;
    }

    /** 성직 후보 순위 점수 — 학위 우선, 다음 학력. 같으면 호출부가 id 로 가른다. */
    public static int clergyScore(int degree, int schoolLevel) {
        return degree * 10 + Math.max(0, schoolLevel);
    }
}
