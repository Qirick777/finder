package com.evosim.mod.entity;

import com.evosim.mod.EvoSimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 이정표 — {@code signpost.nbt} 도면 해석(회전 포함)과 매단 표지판 글씨.
 *
 * <p>도면은 3×6×3 이다: 석재 벽돌 밑동(지면 칸을 대신한다) → 석재 담 → 울타리 기둥 → 동쪽 팔(+3)과
 * 서쪽 팔(+4) → 랜턴(+5). 팔 아래마다 매단 표지판이 하나씩 달린다. 원점은 <b>석재 벽돌 밑동</b>이며
 * 도면에 하나뿐이라 유일하게 정해진다. 밑동을 지면 칸에 놓으면 랜턴이 지면+5 에 걸려 가로등과
 * 같은 높이로 길을 비춘다.
 *
 * <p>회전은 거처 도면과 같은 식(상태는 {@code rotate}, 좌표는 {@code HomeTemplate.transform})이다.
 * 울타리 팔의 연결 속성과 매단 표지판의 16단 rotation 이 함께 돌므로, 팔이 길과 나란하도록
 * 회전을 고르면 화살표가 길 양쪽을 가리킨다. 표지판 글씨(블록 엔티티)는 도면 로더가 다루지
 * 않으므로 설치 뒤 {@link #label} 로 따로 쓴다.
 */
public final class SignpostPlanner {

    private SignpostPlanner() {
    }

    private static final Map<Rotation, List<HomeTemplate.Placement>> PLAN_CACHE = new EnumMap<>(Rotation.class);

    /** 도면 → 밑동(석재 벽돌) 상대 배치 계획(쌓는 순서). 회전은 상태와 좌표에 함께 적용된다. */
    public static Optional<List<HomeTemplate.Placement>> plan(ServerLevel sl, Rotation rot) {
        List<HomeTemplate.Placement> cached = PLAN_CACHE.get(rot);
        if (cached != null) {
            return Optional.of(cached);
        }
        ResourceLocation rl = new ResourceLocation(EvoSimMod.MODID, "structures/signpost.nbt");
        var res = sl.getServer().getResourceManager().getResource(rl);
        if (res.isEmpty()) {
            return Optional.empty();
        }
        CompoundTag tag;
        try (InputStream in = res.get().open()) {
            tag = NbtIo.readCompressed(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("signpost: 도면을 읽을 수 없다 — " + e.getMessage(), e);
        }
        ListTag paletteTag = tag.contains("palette", Tag.TAG_LIST)
                ? tag.getList("palette", Tag.TAG_COMPOUND)
                : tag.getList("palettes", Tag.TAG_LIST).getCompound(0)
                        .getList("palette", Tag.TAG_COMPOUND);
        BlockState[] states = new BlockState[paletteTag.size()];
        var lookup = BuiltInRegistries.BLOCK.asLookup();
        for (int i = 0; i < paletteTag.size(); i++) {
            states[i] = NbtUtils.readBlockState(lookup, paletteTag.getCompound(i)).rotate(rot);
        }
        ListTag blocksTag = tag.getList("blocks", Tag.TAG_COMPOUND);
        List<HomeTemplate.Placement> raw = new ArrayList<>();
        BlockPos base = null;
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag b = blocksTag.getCompound(i);
            ListTag p = b.getList("pos", Tag.TAG_INT);
            BlockState st = states[b.getInt("state")];
            if (st.isAir() || st.is(Blocks.CAVE_AIR)) {
                continue;
            }
            BlockPos pos = HomeTemplate.transform(p.getInt(0), p.getInt(1), p.getInt(2), rot, Mirror.NONE);
            raw.add(new HomeTemplate.Placement(pos, st));
            if (st.is(Blocks.STONE_BRICKS)) {
                if (base != null) {
                    throw new IllegalStateException("signpost: 석재 벽돌 밑동이 2개 이상 — 원점을 정할 수 없다");
                }
                base = pos;
            }
        }
        if (base == null) {
            throw new IllegalStateException("signpost: 석재 벽돌 밑동이 없다 — 원점을 정할 수 없다");
        }
        final BlockPos origin = base;
        List<HomeTemplate.Placement> out = new ArrayList<>(raw.size());
        for (HomeTemplate.Placement p : raw) {
            out.add(new HomeTemplate.Placement(p.rel().subtract(origin), p.state()));
        }
        // 밑동·담·기둥·팔(아래층부터) → 표지판(팔에 매달리므로 팔 다음) → 랜턴.
        out.sort(Comparator
                .comparingInt((HomeTemplate.Placement p) -> p.state().is(Blocks.LANTERN) ? 2
                        : (p.state().getBlock() instanceof net.minecraft.world.level.block.CeilingHangingSignBlock ? 1 : 0))
                .thenComparingInt(p -> p.rel().getY())
                .thenComparingInt(p -> p.rel().getX())
                .thenComparingInt(p -> p.rel().getZ()));
        List<HomeTemplate.Placement> plan = List.copyOf(out);
        PLAN_CACHE.put(rot, plan);
        return Optional.of(plan);
    }

    /** 팔이 뻗는 방향(도면 원본은 동·서) — 회전을 적용한 값. */
    public static Direction[] arms(Rotation rot) {
        return new Direction[] {rot.rotate(Direction.EAST), rot.rotate(Direction.WEST)};
    }

    /**
     * 매단 표지판에 글씨를 쓴다 — 팔이 뻗는 쪽 표지판에 {@code names[i]} 와 그쪽을 가리키는 화살표.
     * 앞뒤 양면에 쓰되 화살표는 보는 쪽에서 팔 방향이 되도록 면마다 뒤집는다.
     *
     * @param names 동쪽 팔(회전 후 {@code arms(rot)[0]}) 표지판, 서쪽 팔 표지판 순. null 이면 화살표만.
     * @return 글씨를 쓴 표지판 수
     */
    public static int label(ServerLevel sl, BlockPos base, Rotation rot, String[] names) {
        var pl = plan(sl, rot);
        if (pl.isEmpty()) {
            return 0;
        }
        Direction[] arms = arms(rot);
        int done = 0;
        for (HomeTemplate.Placement p : pl.get()) {
            if (!(p.state().getBlock() instanceof net.minecraft.world.level.block.CeilingHangingSignBlock)) {
                continue;
            }
            // 어느 팔의 표지판인가 — 원점에서 본 수평 방향.
            int which = Math.signum(p.rel().getX()) == Math.signum(arms[0].getStepX())
                    && Math.signum(p.rel().getZ()) == Math.signum(arms[0].getStepZ()) ? 0 : 1;
            Direction out = arms[which];
            BlockPos at = base.offset(p.rel());
            if (!(sl.getBlockEntity(at) instanceof SignBlockEntity sign)) {
                continue;
            }
            // 매단 표지판의 정면 방향 — rotation(16단)을 4방으로 접는다(0=남, 4=서, 8=북, 12=동).
            int r = p.state().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.ROTATION_16);
            Direction front = Direction.from2DDataValue(((r + 2) / 4) & 3);
            String name = names == null || names[which] == null ? "" : names[which];
            sign.setText(text(name, arrow(front, out)), true);
            sign.setText(text(name, arrow(front.getOpposite(), out)), false);
            done++;
        }
        return done;
    }

    /** 표지판 면이 {@code face} 를 향할 때, 그 면을 마주 보는 사람에게 {@code out} 쪽은 왼쪽인가 오른쪽인가. */
    private static String arrow(Direction face, Direction out) {
        Direction viewerLeft = face.getOpposite().getCounterClockWise();
        return out == viewerLeft ? "←" : "→";
    }

    private static SignText text(String name, String arrow) {
        SignText t = new SignText();
        t = t.setMessage(1, Component.literal(name.isEmpty() ? arrow : name));
        if (!name.isEmpty()) {
            t = t.setMessage(2, Component.literal(arrow));
        }
        return t;
    }
}
