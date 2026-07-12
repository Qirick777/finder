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
        Entry e = new Entry();
        e.src = src;
        e.name = name;
        e.timeout = timeoutTicks;
        e.progress = progress;
        e.pass = pass;
        ACTIVE.add(e);
        send(e, "판별 시작 — 시작값: " + safe(progress), ChatFormatting.GOLD);
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
                continue;
            }
            if (ok) {
                send(e, "✅ 성공 (" + (e.ticks / 20) + "초) — 결과값: " + safe(e.progress),
                        ChatFormatting.GREEN);
                it.remove();
            } else if (e.ticks >= e.timeout) {
                send(e, "❌ 실패 (제한 " + (e.timeout / 20) + "초 초과) — 최종값: " + safe(e.progress),
                        ChatFormatting.RED);
                it.remove();
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
        e.src.sendSuccess(() -> Component.literal("[" + e.name + "] " + msg).withStyle(color), false);
    }

    private static final class Entry {
        CommandSourceStack src;
        String name;
        int timeout;
        int ticks;
        Supplier<String> progress;
        Supplier<Boolean> pass;
    }
}
