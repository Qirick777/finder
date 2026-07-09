package com.evosim.mod.stage;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 무대 한 판의 진행 상태 — 서버 틱마다 {@link StageRunner}가 tick한다.
 *
 * <p>첫 틱에 세팅(소환)하고, 이후 감시 대상의 행동 태그를 모아 기대와 대조. 기대를 다 채우면 즉시 성공,
 * 예산 초과면 실패로 리포트하고 소환 개체를 정리한다.
 */
public final class StageRun {

    private final Stage stage;
    private final ServerLevel level;
    private final CommandSourceStack source;
    private final Vec3 anchor;

    private final Set<Integer> watched = new LinkedHashSet<>();
    private final List<Integer> tracked = new ArrayList<>(); // 정리 대상(감시+헬퍼)
    private final Set<String> expected;
    private final Set<String> seen = new LinkedHashSet<>();
    private final List<String> log = new ArrayList<>();
    private final List<String> details = new ArrayList<>();

    private int ticks = 0;
    private boolean started = false;

    public StageRun(Stage stage, ServerLevel level, CommandSourceStack source, Vec3 anchor) {
        this.stage = stage;
        this.level = level;
        this.source = source;
        this.anchor = anchor;
        this.expected = new LinkedHashSet<>(stage.expected());
    }

    public String name() {
        return stage.name();
    }

    /** 감시 대상(행동을 기대와 대조)으로 등록 + 정리 대상. */
    public void watch(Entity e) {
        watched.add(e.getId());
        tracked.add(e.getId());
    }

    /** 감시하지 않는 보조 개체(예: 몬스터)도 정리 대상으로만 등록. */
    public void track(Entity e) {
        tracked.add(e.getId());
    }

    void observe(int entityId, String tag) {
        if (!watched.contains(entityId)) {
            return;
        }
        log.add("t" + ticks + " #" + entityId + " " + tag);
        if (expected.contains(tag)) {
            seen.add(tag);
        }
    }

    /** 스테이지가 직접 만족시키는 기대(엔티티 관측이 아닌 스테이지 측정 — 예: 이동거리). */
    public void mark(String tag) {
        log.add("t" + ticks + " [stage] " + tag);
        if (expected.contains(tag)) {
            seen.add(tag);
        }
    }

    public int ticks() {
        return ticks;
    }

    /** 스테이지가 리포트에 넣을 수치/요약 라인(예: 감사 통계). */
    public void detail(String line) {
        details.add(line);
    }

    /** @return 이번 판이 끝났으면 true. */
    boolean tick() {
        if (!started) {
            started = true;
            StageObserver.setActive(this);
            stage.setup(level, anchor, this);
        }
        stage.tick(level, this, ticks);
        ticks++;

        boolean allSeen = seen.containsAll(expected);
        if (allSeen || ticks >= stage.tickBudget()) {
            finish(allSeen);
            return true;
        }
        return false;
    }

    private void finish(boolean success) {
        StageObserver.setActive(null);

        Component header = Component.literal(
                        (success ? "[성공] " : "[실패] ") + "evostage " + stage.name()
                                + " — " + stage.description())
                .withStyle(success ? ChatFormatting.GREEN : ChatFormatting.RED);
        source.sendSuccess(() -> header, false);

        source.sendSuccess(() -> Component.literal(
                        "기대 " + expected + " · 관측 " + seen + " (" + ticks + "틱)")
                .withStyle(ChatFormatting.GRAY), false);

        for (String d : details) {
            final String line = d;
            source.sendSuccess(() -> Component.literal("  " + line).withStyle(ChatFormatting.YELLOW), false);
        }

        if (!success) {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(seen);
            source.sendSuccess(() -> Component.literal("누락: " + missing)
                    .withStyle(ChatFormatting.RED), false);
        }
        // 행동 로그(최근 것 위주로 최대 12줄).
        int from = Math.max(0, log.size() - 12);
        for (int i = from; i < log.size(); i++) {
            final String line = log.get(i);
            source.sendSuccess(() -> Component.literal("  " + line).withStyle(ChatFormatting.DARK_GRAY), false);
        }

        // 소환 개체 정리.
        for (int id : tracked) {
            Entity e = level.getEntity(id);
            if (e != null) {
                e.discard();
            }
        }
    }
}
