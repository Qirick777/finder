package com.evosim.mod.stage;

import com.evosim.mod.EvoSimMod;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

/**
 * 실시간 수치 판별기 (§17). 명령이 연출한 "결과 직전" 상황의 <b>변수 변화를 2초마다 채팅에 중계</b>하고,
 * 성공 조건(수치 기준) 충족 시 ✅, 제한 시간 초과 시 ❌를 자동 판정한다 — 육안 조회가 어려운 검증
 * (입금/인출/나눔 등 이동이 걸리는 행동)을 숫자로 확인시킨다.
 */
@Mod.EventBusSubscriber(modid = EvoSimMod.MODID)
public final class LiveCheck {

    private static final int PROGRESS_INTERVAL = 40; // 2초마다 수치 중계

    private static final List<Entry> ACTIVE = new ArrayList<>();

    private LiveCheck() {
    }

    /** 판별 시작 — progress는 현재 수치 한 줄, pass는 수치 기준 성공 판정. timeout 초과 시 실패. */
    public static void watch(CommandSourceStack src, String name, int timeoutTicks,
                             Supplier<String> progress, Supplier<Boolean> pass) {
        watch(src, name, timeoutTicks, progress, pass, null);
    }

    /** cleanup 포함판 — 판별 종료(성공/실패/중단) 시 무대 개체를 정리해 재실행 중첩·세계 오염을 막는다. */
    public static void watch(CommandSourceStack src, String name, int timeoutTicks,
                             Supplier<String> progress, Supplier<Boolean> pass, Runnable cleanup) {
        Entry e = new Entry();
        e.src = src;
        e.name = name;
        e.timeout = timeoutTicks;
        e.progress = progress;
        e.pass = pass;
        e.cleanup = cleanup;
        ACTIVE.add(e);
        send(e, "판별 시작 — 시작값: " + safe(progress), ChatFormatting.GOLD);
    }

    /** 새 무대가 이전 판별을 인수 — 좌표를 공유하는 단독 명령이 진행 중 판별의 개체를 지워
     *  스퓨리어스 ❌가 나던 것을, 명시적 중단(정리 포함)으로 대체한다. */
    public static void cancelAll() {
        Iterator<Entry> it = ACTIVE.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            send(e, "⏹ 중단 — 새 무대 시작으로 판별 종료", ChatFormatting.YELLOW);
            it.remove();
            finish(e);
        }
    }

    private static void finish(Entry e) {
        if (e.cleanup != null) {
            try {
                e.cleanup.run();
            } catch (Exception ignored) {
                // 정리 실패는 판별 결과에 영향 없음(개체가 이미 소실된 경우 등)
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) {
            return;
        }
        Iterator<Entry> it = ACTIVE.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            e.ticks++;
            boolean ok;
            try {
                ok = Boolean.TRUE.equals(e.pass.get());
            } catch (Exception ex) {
                send(e, "판별 중단(개체 소실 등): " + ex, ChatFormatting.RED);
                it.remove();
                finish(e);
                continue;
            }
            if (ok) {
                send(e, "✅ 성공 (" + (e.ticks / 20) + "초) — 결과값: " + safe(e.progress),
                        ChatFormatting.GREEN);
                it.remove();
                finish(e);
            } else if (e.ticks >= e.timeout) {
                send(e, "❌ 실패 (제한 " + (e.timeout / 20) + "초 초과) — 최종값: " + safe(e.progress),
                        ChatFormatting.RED);
                it.remove();
                finish(e);
            } else if (e.ticks % PROGRESS_INTERVAL == 0) {
                send(e, (e.ticks / 20) + "초 — " + safe(e.progress), ChatFormatting.GRAY);
            }
        }
    }

    private static String safe(Supplier<String> s) {
        try {
            return s.get();
        } catch (Exception ex) {
            return "(수치 조회 불가)";
        }
    }

    private static void send(Entry e, String msg, ChatFormatting color) {
        try {
            e.src.sendSuccess(() -> Component.literal("[" + e.name + "] " + msg).withStyle(color), false);
        } catch (Exception ignored) {
            // 명령 주체 로그아웃 등 — 전송 실패가 서버 틱으로 전파되지 않게(판별 자체는 계속)
        }
    }

    private static final class Entry {
        CommandSourceStack src;
        String name;
        int timeout;
        int ticks;
        Supplier<String> progress;
        Supplier<Boolean> pass;
        Runnable cleanup;
    }
}
