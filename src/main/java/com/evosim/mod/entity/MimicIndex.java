package com.evosim.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>미믹 위치 격자</b> — 틱당 한 번 미믹 전원을 16블록 칸에 넣어 두고, 반경 검색은 그 칸에서 꺼낸다.
 *
 * <p>종전에는 미믹마다 {@code getEntitiesOfClass(반경)} 을 불러 세계 엔티티를 훑었다(가구 96·마실·나눔·
 * 전투·채집). 200명이 각자 훑으면 틱마다 수만 번 거리 비교다. 격자는 "틱당 한 번 만들고 모두가 쓰는"
 * 구조라 개체 번호 색인(FarmTicker.byIndividual)과 같은 뜻이다. 후보는 실제 거리로 다시 거르므로
 * 결과는 종전과 같고 후보 수만 준다. 격자는 틱 시작 위치라 그 틱 안의 이동은 최대 한 틱 늦다.
 */
public final class MimicIndex {
    private MimicIndex() {
    }

    private static long builtTick = Long.MIN_VALUE;
    private static ServerLevel builtLevel;
    private static final Map<Long, List<MimicEntity>> CELLS = new HashMap<>();
    private static final List<MimicEntity> ALL = new ArrayList<>();

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    private static void ensure(ServerLevel level) {
        long now = level.getGameTime();
        if (now == builtTick && builtLevel == level) {
            return;
        }
        builtTick = now;
        builtLevel = level;
        CELLS.clear();
        ALL.clear();
        for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(), e -> e.isAlive())) {
            ALL.add(m);
            CELLS.computeIfAbsent(key(m.blockPosition().getX() >> 4, m.blockPosition().getZ() >> 4), k -> new ArrayList<>()).add(m);
        }
    }

    /** 살아 있는 미믹 전원(틱당 한 번 모음). */
    public static List<MimicEntity> all(ServerLevel level) {
        ensure(level);
        return ALL;
    }

    /**
     * 상자와 겹치는 미믹 — 종전 {@code getEntitiesOfClass(MimicEntity.class, box)} 와 같은 뜻(바운딩박스 교차).
     * 칸 범위를 사방 1칸 더 잡아, 틱 시작 이후 칸 경계를 넘은 미믹도 놓치지 않는다.
     * 서버 레벨이 아니면(클라이언트) 종전 방식으로 그대로 훑는다. 죽은(제거된) 미믹은 담지 않는다.
     */
    public static List<MimicEntity> near(Level level, AABB box) {
        if (!(level instanceof ServerLevel sl)) {
            return level.getEntitiesOfClass(MimicEntity.class, box);
        }
        ensure(sl);
        List<MimicEntity> out = new ArrayList<>();
        int c0x = (((int) Math.floor(box.minX)) >> 4) - 1;
        int c1x = (((int) Math.floor(box.maxX)) >> 4) + 1;
        int c0z = (((int) Math.floor(box.minZ)) >> 4) - 1;
        int c1z = (((int) Math.floor(box.maxZ)) >> 4) + 1;
        for (int cx = c0x; cx <= c1x; cx++) {
            for (int cz = c0z; cz <= c1z; cz++) {
                List<MimicEntity> cell = CELLS.get(key(cx, cz));
                if (cell == null) {
                    continue;
                }
                for (MimicEntity m : cell) {
                    if (m.isAlive() && box.intersects(m.getBoundingBox())) {
                        out.add(m);
                    }
                }
            }
        }
        return out;
    }

    /** 중심에서 수평·수직 모두 {@code radius} 안(바운딩박스 교차 기준, 종전 AABB.inflate 와 같은 뜻)의 미믹. */
    public static List<MimicEntity> near(ServerLevel level, double x, double y, double z, double radius) {
        return near(level, new AABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius));
    }

    public static List<MimicEntity> near(ServerLevel level, BlockPos p, double radius) {
        return near(level, new AABB(p).inflate(radius));
    }
}
