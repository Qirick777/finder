package com.evosim.mod.stage;

/**
 * 무대 검증 관찰자 (설계서 §17 "무대 세팅식 자동 검증"). 엔티티가 주요 행동을 여기에 기록하면,
 * 진행 중인 무대({@link StageRun})가 자기 감시 대상의 것만 로그로 수집한다.
 *
 * <p>평상시엔 활성 무대가 없어 기록이 무시된다(오버헤드 0). 서버 틱은 단일 스레드라 정적 상태로 충분.
 */
public final class StageObserver {

    private static StageRun active;

    private StageObserver() {
    }

    static void setActive(StageRun run) {
        active = run;
    }

    /** 무대 검증이 진행 중인가 (평상시 부가 관측을 생략해 오버헤드 0으로 만들기 위함). */
    public static boolean isActive() {
        return active != null;
    }

    /** 엔티티가 행동을 보고 — 예: {@code record(getId(), "combat:engage")}. */
    public static void record(int entityId, String tag) {
        StageRun run = active;
        if (run != null) {
            run.observe(entityId, tag);
        }
    }
}
