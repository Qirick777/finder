package com.evosim.mod.stage;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 무대 시나리오 (설계서 §17). "격리된 무대 자동 세팅 → 강제 발동 → 기대 vs 실제 대조".
 *
 * <p>{@link #setup}에서 개체를 소환하고 감시 대상으로 등록({@link StageRun#watch}), 상황을 강제한다.
 * 진행 중 {@link #expected} 태그가 전부 관측되면 성공, 예산 틱 안에 못 채우면 실패.
 */
public interface Stage {

    String name();

    /** 한 줄 설명(리포트용). */
    String description();

    /** 관측되어야 할 행동 태그들(전부 관측 = 성공). */
    List<String> expected();

    /** 최대 대기 틱(초과 시 실패 판정). */
    default int tickBudget() {
        return 160;
    }

    /** 무대 세팅 — 개체 소환 + 감시 등록 + 상황 강제. */
    void setup(ServerLevel level, Vec3 anchor, StageRun run);

    /** 매 틱 추가 구동(선택). 대개 엔티티 AI가 알아서 하므로 비워둠. */
    default void tick(ServerLevel level, StageRun run, int tick) {
    }
}
