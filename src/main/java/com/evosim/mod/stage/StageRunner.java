package com.evosim.mod.stage;

import com.evosim.mod.EvoSimMod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 무대 실행기 — 큐에 쌓인 {@link StageRun}을 서버 틱마다 하나씩 진행(순차, 겹침 방지).
 *
 * <p>명령어가 즉시 블로킹하지 않고, 여러 틱에 걸쳐 소환·관찰·판정이 진행되게 한다.
 */
@Mod.EventBusSubscriber(modid = EvoSimMod.MODID)
public final class StageRunner {

    private static final Deque<StageRun> QUEUE = new ArrayDeque<>();

    private StageRunner() {
    }

    public static void enqueue(StageRun run) {
        QUEUE.addLast(run);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        StageRun current = QUEUE.peekFirst();
        if (current == null) {
            return;
        }
        if (current.tick()) {
            QUEUE.pollFirst();
        }
    }
}
