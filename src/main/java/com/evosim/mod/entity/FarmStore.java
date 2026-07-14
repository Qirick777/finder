package com.evosim.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * 밭 원장 (봉건 밭 경제 M0). 구획 = {앵커, 타일 목록(심은 시각 포함), 소유자 개체 id, 밭 계정}.
 * 배치 기하는 순수 {@link com.evosim.core.FarmLayout} — 여기는 영속만. 무대 개체 밭 금지는 생성부에서.
 */
public class FarmStore extends SavedData {

    private static final String KEY = "evosim_farms";

    /** 밭 한 구획. tiles[i]=BlockPos.asLong, planted[i]=심은 gameTime(-1=미설치). */
    public static final class Plot {
        public final long id;
        public final BlockPos anchor;
        public final long ownerId;
        public long[] tiles = new long[0];
        public long[] planted = new long[0];
        public double account = 0.0;

        Plot(long id, BlockPos anchor, long ownerId) {
            this.id = id;
            this.anchor = anchor;
            this.ownerId = ownerId;
        }
    }

    private final Map<Long, Plot> plots = new HashMap<>();
    private long nextId = 1;

    public static FarmStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FarmStore::load, FarmStore::new, KEY);
    }

    public Plot create(BlockPos anchor, long ownerId) {
        Plot p = new Plot(nextId++, anchor, ownerId);
        plots.put(p.id, p);
        setDirty();
        return p;
    }

    public Plot get(long id) {
        return plots.get(id);
    }

    public Map<Long, Plot> all() {
        return plots;
    }

    public void addTile(Plot p, BlockPos pos, long gameTime) {
        long[] t = new long[p.tiles.length + 1];
        long[] g = new long[p.planted.length + 1];
        System.arraycopy(p.tiles, 0, t, 0, p.tiles.length);
        System.arraycopy(p.planted, 0, g, 0, p.planted.length);
        t[t.length - 1] = pos.asLong();
        g[g.length - 1] = gameTime;
        p.tiles = t;
        p.planted = g;
        setDirty();
    }

    /** 검증 전용 정리 — 무대 밭 회수(규칙 7). 멱등. */
    public void debugRemove(long id) {
        if (plots.remove(id) != null) {
            setDirty();
        }
    }

    public static FarmStore load(CompoundTag tag) {
        FarmStore s = new FarmStore();
        s.nextId = Math.max(1, tag.getLong("Next"));
        ListTag list = tag.getList("Plots", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            Plot p = new Plot(c.getLong("Id"), BlockPos.of(c.getLong("Anchor")), c.getLong("Owner"));
            p.tiles = c.getLongArray("Tiles");
            p.planted = c.getLongArray("Planted");
            p.account = c.getDouble("Acct");
            s.plots.put(p.id, p);
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong("Next", nextId);
        ListTag list = new ListTag();
        for (Plot p : plots.values()) {
            CompoundTag c = new CompoundTag();
            c.putLong("Id", p.id);
            c.putLong("Anchor", p.anchor.asLong());
            c.putLong("Owner", p.ownerId);
            c.putLongArray("Tiles", p.tiles);
            c.putLongArray("Planted", p.planted);
            c.putDouble("Acct", p.account);
            list.add(c);
        }
        tag.put("Plots", list);
        return tag;
    }
}
