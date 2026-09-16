package com.evosim.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 이정표 등기부 — 세워진(또는 착공된) 이정표의 <b>밑동</b>·회전·<b>길 칸</b>을 영속한다.
 *
 * <p>가로등 등기부와 같은 원칙이다: 등기 시점은 착공이며, 두 사람이 같은 자리를 집지 않게 한다.
 * 길 칸은 기둥이 서 있는 길의 중심선 칸으로, 미믹이 "이정표에 닿았다"고 치는 경유점이다(기둥 칸은
 * 돌이라 밟을 수 없다). 경로표({@link RelayNet})는 파생 자료라 여기 쓰지 않는다.
 */
public class SignpostStore extends SavedData {

    private static final String KEY = "evosim_signposts";

    /** 이정표 하나 — 밑동(석재 벽돌 칸), 회전, 옆 길 칸. */
    public record Post(BlockPos base, Rotation rotation, BlockPos road) {
    }

    private final List<Post> posts = new ArrayList<>();
    private final Set<Long> baseKeys = new HashSet<>();

    public static SignpostStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(SignpostStore::load, SignpostStore::new, KEY);
    }

    public static SignpostStore load(CompoundTag tag) {
        SignpostStore s = new SignpostStore();
        ListTag list = tag.getList("Posts", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            Post p = new Post(BlockPos.of(t.getLong("Base")), Rotation.values()[t.getByte("Rot") & 3],
                    BlockPos.of(t.getLong("Road")));
            s.posts.add(p);
            s.baseKeys.add(p.base().asLong());
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Post p : posts) {
            CompoundTag t = new CompoundTag();
            t.putLong("Base", p.base().asLong());
            t.putByte("Rot", (byte) p.rotation().ordinal());
            t.putLong("Road", p.road().asLong());
            list.add(t);
        }
        tag.put("Posts", list);
        return tag;
    }

    public void add(BlockPos base, Rotation rot, BlockPos road) {
        if (baseKeys.add(base.asLong())) {
            posts.add(new Post(base, rot, road));
            setDirty();
            RelayNet.dirty();
        }
    }

    public boolean has(BlockPos base) {
        return baseKeys.contains(base.asLong());
    }

    public int size() {
        return posts.size();
    }

    /** 등기 순(착공 순) — 경로표의 동률 가르기가 이 순서를 쓴다. */
    public List<Post> all() {
        return posts;
    }

    /** 이 열에서 가장 가까운 이정표(밑동)까지의 평면 거리 — 없으면 {@link Double#MAX_VALUE}. */
    public double nearest(int x, int z) {
        double best = Double.MAX_VALUE;
        for (Post p : posts) {
            double dx = p.base().getX() - x;
            double dz = p.base().getZ() - z;
            best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
        }
        return best;
    }

    /** 기둥 열 — 길이 나중에 이 칸을 지나가지 않도록 장애물에 실린다. */
    public Set<Long> postColumns() {
        Set<Long> out = new HashSet<>();
        for (Post p : posts) {
            out.add(RoadStore.key(p.base().getX(), p.base().getZ()));
        }
        return out;
    }
}
