package com.evosim.mod.entity;

/**
 * <b>시설의 수치</b> — 학교·교회의 문턱과 값. 한 곳에 모아 둔다.
 *
 * <p>전부 <b>측정 뒤에 확정할 값</b>이다. 판정 지표는 시설 수지(흑자여야 한다)와 시설 수
 * (0 도 난립도 아니어야 한다)이며, 0 이 나오면 사건 로그의 "보류/자리 없음" 줄이 <b>왜</b>
 * 0 인지 말해야 한다.
 */
public final class Facilities {

    private Facilities() {
    }

    /**
     * 학교 건축비 — 저장고에서 즉시 빠진다(인부를 산 값).
     *
     * <p>60 은 저택({@code MANSION.buildCost} 70) 바로 아래다. 학교는 1,336칸으로 저택
     * (1,027칸 중 비공기)보다 크지만, 저택은 <b>사는 집</b>이라 그 값 자체가 과시의 상한 노릇을
     * 한다. 학교를 그보다 비싸게 두면 저택을 지을 수 있는 자만 학교를 지을 수 있어 두 문턱이
     * 겹쳐 버린다 — 서로 다른 선택지가 되게 조금 아래에 둔다.
     */
    public static final double SCHOOL_COST = 60.0;

    /**
     * 학교를 세울 자격 — 거느린 자가 이만큼 있어야 한다.
     *
     * <p>계획서 1.5: "이용자가 많을수록 이익이므로 세력이 클수록 남는 장사이고, 작은 세력은
     * 적자라 시설을 못 짓는다." 그 문장을 그대로 수로 옮긴 것이 이 상수다. 6 은 실측(P4 D19)
     * 에서 주인 3명의 세력이 28·25·3 이었던 분포를 보고 잡았다 — 상위 둘만 자격이 되고
     * 말단 지주는 안 되는 자리다. <b>신분으로 분기하지 않는다</b>: 누구든 여섯을 거느리면 된다.
     */
    public static final int SCHOOL_MIN_FOLLOWERS = 6;

    /** 부지 탐색 — 세운 자의 집에서 이 거리부터 이 거리까지 고리를 넓히며 찾는다. */
    public static final int MIN_RADIUS = 12;
    public static final int SEARCH_RADIUS = 64;

    /** 거처와의 여유 — 시설 반경 + 이웃 거처 반경에 이만큼 더 띄운다(사람이 지나갈 길). */
    public static final int HOME_MARGIN = 4;

    /** 부지 지형 낙차 상한 — 이보다 울퉁불퉁하면 메움·파냄이 지형을 헤집는다. */
    public static final int MAX_SPREAD = 4;
}
