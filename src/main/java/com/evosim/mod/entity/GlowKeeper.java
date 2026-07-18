package com.evosim.mod.entity;

import com.evosim.mod.EvoSimMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 렌즈 고정 발광 관리 (UX-D). 클라 하트비트(20틱)가 만료를 연장하고, 하트비트가 끊기면
 * 유예({@link #GRACE_TICKS}) 뒤 자동 소등 — 발광 태그는 NBT 에 저장되므로 명시 해제 없이
 * 클라가 사라져도(크래시·로그아웃) 발광이 세이브에 영구 잔존하지 않게 하는 안전핀.
 */
@Mod.EventBusSubscriber(modid = EvoSimMod.MODID)
public final class GlowKeeper {

    /** 하트비트(20틱) 3회 유실까지 허용. */
    public static final long GRACE_TICKS = 60;
    private static final int SWEEP_INTERVAL = 20;

    private record Entry(ServerLevel level, long expireGameTime) {
    }

    private static final Map<Integer, Entry> ACTIVE = new ConcurrentHashMap<>();

    private GlowKeeper() {
    }

    /** 켜기/하트비트(on=true) 또는 즉시 소등(on=false). 미믹이 아니면 무시(악용 방지). */
    public static void heartbeat(ServerLevel level, int entityId, boolean on) {
        Entity e = level.getEntity(entityId);
        if (!(e instanceof MimicEntity)) {
            return;
        }
        if (on) {
            e.setGlowingTag(true);
            ACTIVE.put(entityId, new Entry(level, com.evosim.mod.entity.SimTime.tick(level) + GRACE_TICKS));
        } else {
            e.setGlowingTag(false);
            ACTIVE.remove(entityId);
        }
    }

    /** 검증용 — 현재 발광 유지 중인 개체 수. */
    public static int activeCount() {
        return ACTIVE.size();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Integer, Entry>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Entry> en = it.next();
            ServerLevel level = en.getValue().level();
            if (com.evosim.mod.entity.SimTime.tick(level) % SWEEP_INTERVAL != 0) {
                continue;
            }
            if (com.evosim.mod.entity.SimTime.tick(level) >= en.getValue().expireGameTime()) {
                Entity e = level.getEntity(en.getKey());
                if (e != null) {
                    e.setGlowingTag(false);
                }
                it.remove();
            }
        }
    }
}
