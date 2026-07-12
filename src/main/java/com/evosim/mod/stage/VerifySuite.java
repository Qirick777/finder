package com.evosim.mod.stage;

import com.evosim.mod.EvoSimMod;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 원트랙 순차 검증 러너 (§17). 미검증 기능들을 <b>한 명령으로 차례차례</b> 돌린다 — 각 단계는:
 * ① setup이 "발동 직전" 조건을 즉각 조성 → ② 별도의 감지(pass)가 <b>결과값의 변화만</b> 보고 판정
 * (호출 여부·로그가 아니라 수치·상태 실측 → 실행 안 됐는데 성공 처리되는 오류 없음) → ③ cleanup 이
 * 무대 개체를 치우고 다음 단계로. passOnTimeout 단계는 "금지 결과가 제한 시간 내 없었음"을 성공으로
 * 본다(예: 질투 아내 → 중혼이 일어나면 안 됨). 끝나면 ✅/❌ 요약을 출력.
 */
@Mod.EventBusSubscriber(modid = EvoSimMod.MODID)
public final class VerifySuite {

    private static final int PROGRESS_INTERVAL = 60; // 3초마다 수치 중계
    private static final int STEP_GAP = 20;          // 단계 사이 정리 틈(틱)

    /** 검증 한 단계 — setup(직전 조성) / progress(수치 한 줄) / pass(결과값 판정) / cleanup(개체 정리). */
    public static final class Step {
        final String name;
        final int timeout;
        final boolean passOnTimeout;
        final Runnable setup;
        final Supplier<String> progress;
        final Supplier<Boolean> pass;
        final Runnable cleanup;

        public Step(String name, int timeout, boolean passOnTimeout, Runnable setup,
                    Supplier<String> progress, Supplier<Boolean> pass, Runnable cleanup) {
            this.name = name;
            this.timeout = timeout;
            this.passOnTimeout = passOnTimeout;
            this.setup = setup;
            this.progress = progress;
            this.pass = pass;
            this.cleanup = cleanup;
        }
    }

    private static CommandSourceStack src;
    private static List<Step> steps;
    private static final List<String> RESULTS = new ArrayList<>();
    private static int idx;
    private static int ticks;
    private static int gap;
    private static boolean stepStarted;

    private VerifySuite() {
    }

    public static boolean isRunning() {
        return steps != null;
    }

    /** 검증 시작 — 이미 돌고 있으면 거절(원트랙 보장). */
    public static boolean start(CommandSourceStack source, List<Step> list) {
        if (steps != null) {
            return false;
        }
        src = source;
        steps = list;
        RESULTS.clear();
        idx = 0;
        ticks = 0;
        gap = 0;
        stepStarted = false;
        say("═══ 원트랙 검증 시작 — 총 " + list.size() + "단계 ═══", ChatFormatting.GOLD);
        return true;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || steps == null) {
            return;
        }
        if (gap > 0) { // 단계 사이 정리 틈
            gap--;
            return;
        }
        if (idx >= steps.size()) {
            summarize();
            return;
        }
        Step cur = steps.get(idx);
        if (!stepStarted) {
            say(String.format("▶ [%d/%d] %s — 직전 조건 조성", idx + 1, steps.size(), cur.name),
                    ChatFormatting.GOLD);
            try {
                cur.setup.run();
            } catch (Exception ex) {
                finishStep(cur, false, "조성 실패: " + ex);
                return;
            }
            stepStarted = true;
            ticks = 0;
            return;
        }
        ticks++;
        boolean hit;
        try {
            hit = Boolean.TRUE.equals(cur.pass.get());
        } catch (Exception ex) {
            finishStep(cur, false, "판정 중단(개체 소실): " + ex);
            return;
        }
        if (cur.passOnTimeout) { // "금지 결과" 감시: 발생 → 실패, 무사 경과 → 성공
            if (hit) {
                finishStep(cur, false, "금지 결과 발생 — " + safe(cur.progress));
            } else if (ticks >= cur.timeout) {
                finishStep(cur, true, "금지 결과 없음(" + (cur.timeout / 20) + "초) — " + safe(cur.progress));
            } else if (ticks % PROGRESS_INTERVAL == 0) {
                say("   " + (ticks / 20) + "초 — " + safe(cur.progress), ChatFormatting.GRAY);
            }
        } else {
            if (hit) {
                finishStep(cur, true, (ticks / 20) + "초 — " + safe(cur.progress));
            } else if (ticks >= cur.timeout) {
                finishStep(cur, false, "제한 " + (cur.timeout / 20) + "초 초과 — " + safe(cur.progress));
            } else if (ticks % PROGRESS_INTERVAL == 0) {
                say("   " + (ticks / 20) + "초 — " + safe(cur.progress), ChatFormatting.GRAY);
            }
        }
    }

    private static void finishStep(Step cur, boolean ok, String detail) {
        String line = (ok ? "✅ " : "❌ ") + cur.name + " — " + detail;
        RESULTS.add(line);
        say(line, ok ? ChatFormatting.GREEN : ChatFormatting.RED);
        try {
            cur.cleanup.run();
        } catch (Exception ignored) {
            // 정리 실패는 판정에 영향 없음
        }
        idx++;
        stepStarted = false;
        gap = STEP_GAP;
    }

    private static void summarize() {
        long pass = RESULTS.stream().filter(r -> r.startsWith("✅")).count();
        say("═══ 검증 완료 — ✅ " + pass + " / ❌ " + (RESULTS.size() - pass) + " ═══",
                pass == RESULTS.size() ? ChatFormatting.GREEN : ChatFormatting.RED);
        for (String r : RESULTS) {
            say(r, r.startsWith("✅") ? ChatFormatting.DARK_GREEN : ChatFormatting.RED);
        }
        steps = null;
        src = null;
    }

    private static String safe(Supplier<String> s) {
        try {
            return s.get();
        } catch (Exception ex) {
            return "(수치 조회 불가)";
        }
    }

    private static void say(String msg, ChatFormatting color) {
        if (src != null) {
            src.sendSuccess(() -> Component.literal(msg).withStyle(color), false);
        }
    }
}
