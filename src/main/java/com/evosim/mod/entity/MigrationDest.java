package com.evosim.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 마을 이주 합의 (이주 설계 §3-A). 첫 이주 가족이 목적지를 등록하면, 유효기간 안에 이주하는
 * 근처 가족들은 <b>같은 목적지</b>로 따라간다 — 캐러밴이 자연 발생해 짝 후보 풀이 통째로 보존된다.
 *
 * <p>SavedData 영속(리로드에도 합의 유지). 선착 등록·유효기간 만료 후 새 등록.
 */
public class MigrationDest extends SavedData {

    private static final String KEY = "evosim_migration";
    /** 합의 유효기간(5게임일) — 늦게 떠나는 가족도 같은 곳으로. */
    public static final long VALID_TICKS = 5 * 24000L;
    /** 출발지가 등록지에서 이 거리 안이어야 같은 마을로 간주(합의 적용). */
    public static final double NEAR = 256.0;

    private boolean present = false;
    private long destPos = 0L;
    private long originPos = 0L; // 등록한 가족의 출발지(같은 마을 판정 기준)
    private long registeredTick = 0L;

    public static MigrationDest get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(MigrationDest::load, MigrationDest::new, KEY);
    }

    public static MigrationDest load(CompoundTag tag) {
        MigrationDest d = new MigrationDest();
        d.present = tag.getBoolean("Present");
        d.destPos = tag.getLong("Dest");
        d.originPos = tag.getLong("Origin");
        d.registeredTick = tag.getLong("Tick");
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("Present", present);
        tag.putLong("Dest", destPos);
        tag.putLong("Origin", originPos);
        tag.putLong("Tick", registeredTick);
        return tag;
    }

    /** 유효한 합의가 있고 출발지가 같은 마을권이면 목적지 반환, 아니면 null(직접 정찰). */
    public BlockPos resolve(BlockPos from, long now) {
        if (!present || now - registeredTick > VALID_TICKS) {
            return null;
        }
        BlockPos origin = BlockPos.of(originPos);
        if (from.distSqr(origin) > NEAR * NEAR) {
            return null; // 다른 마을 — 각자 정찰
        }
        BlockPos dest = BlockPos.of(destPos);
        // 출발지가 origin 보다 dest 에 더 가깝다 = 이미 목적지권에 정착한 가족(연쇄 이주)이
        // 낡은 합의를 따라 굶는 자리로 "제자리 이주"하려는 것 — 기각(각자 정찰). 상수 문턱은
        // 캐러밴 정착 산포(8~58)와 최소 이주거리(24)를 동시에 만족 못 해 비교 판정으로 대체.
        if (from.distSqr(dest) < from.distSqr(origin)) {
            return null;
        }
        return dest;
    }

    /** 새 합의 등록(선착) — 이후 이주 가족들이 따라온다. */
    public void register(BlockPos from, BlockPos dest, long now) {
        this.present = true;
        this.originPos = from.asLong();
        this.destPos = dest.asLong();
        this.registeredTick = now;
        setDirty();
    }
}
