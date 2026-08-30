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
 * <b>가로 시설물 등기</b> — 가로수와 분수. {@link LampStore} 와 같은 꼴이다.
 *
 * <p>왜 등기가 필요한가: 간격 규칙이 "이미 어디에 서 있나"를 알아야 성립하고, 길 찾기가
 * 그 자리를 장애물로 피해야 하기 때문이다. 가로등이 이미 같은 이유로 등기를 갖고 있다 —
 * 등기가 없으면 나중에 나는 길이 기둥을 관통한다({@code RoadPlanner.Obstacles} 의 가로등 줄).
 *
 * <p>두 종류를 한 등기에 담되 <b>따로 센다</b>. 간격이 서로 다르기 때문이다(나무는 촘촘히,
 * 분수는 아주 드물게). 종류를 섞어 세면 분수 하나가 주변 나무를 전부 막는다.
 */
public class StreetStore extends SavedData {

    private static final String KEY = "evosim_street";

    /** 가로수 밑동(지면 칸). */
    private final Set<Long> trees = new HashSet<>();
    /** 분수 중심(지면 칸). */
    private final Set<Long> fountains = new HashSet<>();

    public static StreetStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(StreetStore::load, StreetStore::new, KEY);
    }

    public static StreetStore load(CompoundTag tag) {
        StreetStore s = new StreetStore();
        for (long l : tag.getLongArray("Trees")) {
            s.trees.add(l);
        }
        for (long l : tag.getLongArray("Fountains")) {
            s.fountains.add(l);
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLongArray("Trees", trees.stream().mapToLong(Long::longValue).toArray());
        tag.putLongArray("Fountains", fountains.stream().mapToLong(Long::longValue).toArray());
        return tag;
    }

    private Set<Long> set(boolean fountain) {
        return fountain ? fountains : trees;
    }

    public void add(BlockPos base, boolean fountain) {
        if (set(fountain).add(base.asLong())) {
            setDirty();
        }
    }

    public boolean has(BlockPos base, boolean fountain) {
        return set(fountain).contains(base.asLong());
    }

    public int size(boolean fountain) {
        return set(fountain).size();
    }

    public List<BlockPos> all(boolean fountain) {
        List<BlockPos> out = new ArrayList<>(set(fountain).size());
        for (long l : set(fountain)) {
            out.add(BlockPos.of(l));
        }
        return out;
    }

    /** 이 좌표에서 가장 가까운 같은 종류까지의 평면 거리 — 없으면 무한대. */
    public double nearest(int x, int z, boolean fountain) {
        double best = Double.MAX_VALUE;
        for (long l : set(fountain)) {
            BlockPos p = BlockPos.of(l);
            double dx = p.getX() - x;
            double dz = p.getZ() - z;
            best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
        }
        return best;
    }
}
