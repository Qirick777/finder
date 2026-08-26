package com.evosim.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 가로등 등기부 — 세워진(또는 착공된) 가로등의 <b>기둥 밑동</b> 좌표를 영속한다.
 *
 * <p>등기 시점은 <b>착공</b>이다. 완성이 아니다. 두 미믹이 같은 날 같은 자리를 집어 겹쳐 세우는
 * 것을 막아야 하는데, 완성 시점에 등기하면 시공 중(14블록 × 6틱 ≈ 84틱) 그 자리가 비어 보인다.
 * 대신 시공자가 죽으면 반쯤 선 등이 등기된 채 남는다 — 그래서 보고({@code /evosim lamps})는
 * <b>등기 수와 실제 랜턴 수를 따로</b> 센다. 등기 수만 세면 허수가 섞인다.
 */
public class LampStore extends SavedData {

    private static final String KEY = "evosim_lamps";

    private final Set<Long> posts = new HashSet<>();

    public static LampStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(LampStore::load, LampStore::new, KEY);
    }

    public static LampStore load(CompoundTag tag) {
        LampStore s = new LampStore();
        for (long l : tag.getLongArray("Posts")) {
            s.posts.add(l);
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        long[] arr = new long[posts.size()];
        int i = 0;
        for (long l : posts) {
            arr[i++] = l;
        }
        tag.putLongArray("Posts", arr);
        return tag;
    }

    public void add(BlockPos base) {
        if (posts.add(base.asLong())) {
            setDirty();
        }
    }

    public void remove(BlockPos base) {
        if (posts.remove(base.asLong())) {
            setDirty();
        }
    }

    public boolean has(BlockPos base) {
        return posts.contains(base.asLong());
    }

    public int size() {
        return posts.size();
    }

    public List<BlockPos> all() {
        List<BlockPos> out = new ArrayList<>(posts.size());
        for (long l : posts) {
            out.add(BlockPos.of(l));
        }
        return out;
    }

    /** 이 열에서 가장 가까운 등까지의 평면 거리 — 없으면 {@link Double#MAX_VALUE}. */
    public double nearest(int x, int z) {
        double best = Double.MAX_VALUE;
        for (long l : posts) {
            BlockPos p = BlockPos.of(l);
            double dx = p.getX() - x;
            double dz = p.getZ() - z;
            best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
        }
        return best;
    }
}
