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
 * 흙 길 등기부 — <b>깔린 길의 중심선</b> 칸을 월드 단위로 영속한다.
 *
 * <p>여기 담기는 것은 폭 3으로 퍼진 전체가 아니라 <b>중심선</b>이다. 폭은 중심선에서
 * 그때그때 유도한다({@link RoadPlanner#band}) — 저장량이 1/3로 줄고, 나중에 밭이 옆칸을
 * 가져가도 등기를 손댈 필요가 없다.
 *
 * <p>도로망은 <b>나무</b>라서 한 칸만 끊겨도 그 아래가 통째로 떨어진다. 그래서 밭이 길을
 * 먹을 때 {@link #splitBy}로 쪼개짐을 먼저 판정하고, 쪼개졌으면 그 밭을 넓힌 미믹이
 * 우회로를 놓는다(설계 검토에서 실측: 5칸이 잘려 33채 중 12채가 떨어져 나갔다).
 */
public class RoadStore extends SavedData {

    private static final String KEY = "evosim_roads";

    private final Set<Long> cells = new HashSet<>();

    public static RoadStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(RoadStore::load, RoadStore::new, KEY);
    }

    public static RoadStore load(CompoundTag tag) {
        RoadStore s = new RoadStore();
        for (long l : tag.getLongArray("Cells")) {
            s.cells.add(l);
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        long[] arr = new long[cells.size()];
        int i = 0;
        for (long l : cells) {
            arr[i++] = l;
        }
        tag.putLongArray("Cells", arr);
        return tag;
    }

    /** 이 칸이 길 중심선인가 — y 는 무시하고 x/z 열로만 본다(길은 지표에 하나). */
    public boolean has(int x, int z) {
        return cells.contains(key(x, z));
    }

    public void add(int x, int z) {
        if (cells.add(key(x, z))) {
            setDirty();
        }
    }

    public void remove(int x, int z) {
        if (cells.remove(key(x, z))) {
            setDirty();
        }
    }

    public int size() {
        return cells.size();
    }

    /** 전체 중심선 좌표(x,z 쌍) — 보고·검증용. */
    public List<int[]> all() {
        List<int[]> out = new ArrayList<>(cells.size());
        for (long l : cells) {
            out.add(new int[] {(int) (l >> 32), (int) l});
        }
        return out;
    }

    public Set<Long> raw() {
        return cells;
    }

    /** x/z 를 한 long 으로 — BlockPos.asLong 은 y 를 섞으므로 쓰지 않는다. */
    public static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int keyX(long k) {
        return (int) (k >> 32);
    }

    public static int keyZ(long k) {
        return (int) k;
    }

    /**
     * 이 칸들을 빼면 도로망이 <b>쪼개지는가</b> — 쪼개진다면 떨어져 나갈 조각들을 돌려준다.
     *
     * <p>가장 큰 덩어리를 본체로 보고 나머지를 조각으로 친다. 조각이 비어 있으면 안전하게
     * 빼도 되는 칸이다.
     */
    public List<Set<Long>> splitBy(Set<Long> removed) {
        Set<Long> left = new HashSet<>(cells);
        left.removeAll(removed);
        List<Set<Long>> parts = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (long c : left) {
            if (!seen.add(c)) {
                continue;
            }
            Set<Long> g = new HashSet<>();
            List<Long> q = new ArrayList<>();
            g.add(c);
            q.add(c);
            while (!q.isEmpty()) {
                long cur = q.remove(q.size() - 1);
                int x = keyX(cur);
                int z = keyZ(cur);
                for (int[] d : RoadPlanner.D4) {
                    long n = key(x + d[0], z + d[1]);
                    if (left.contains(n) && seen.add(n)) {
                        g.add(n);
                        q.add(n);
                    }
                }
            }
            parts.add(g);
        }
        parts.sort((a, b) -> Integer.compare(b.size(), a.size()));
        return parts.size() <= 1 ? List.of() : parts;
    }

    /** 등기 말소 — 그 열의 길을 지운다(밭이 가져갈 때). */
    public void removeAll(Set<Long> keys) {
        if (cells.removeAll(keys)) {
            setDirty();
        }
    }

    public static BlockPos posOf(long k, int y) {
        return new BlockPos(keyX(k), y, keyZ(k));
    }
}
