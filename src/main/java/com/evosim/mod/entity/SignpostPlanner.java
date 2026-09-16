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

import javax.annotation.Nullable;

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
     * 매단 표지판에 글씨를 쓴다 — 팔이 뻗는 쪽 표지판에 그쪽 시설 이름들과 그쪽을 가리키는 화살표.
     * 앞뒤 양면에 쓰되 화살표는 보는 쪽에서 팔 방향이 되도록 면마다 뒤집는다. 이름이 없으면 화살표만.
     *
     * @param names 동쪽 팔(회전 후 {@code arms(rot)[0]}) 표지판 줄들, 서쪽 팔 줄들(각 최대 4줄)
     * @return 글씨를 쓴 표지판 수
     */
    public static int label(ServerLevel sl, BlockPos base, Rotation rot, List<String>[] names) {
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
            List<String> lines = names == null || names[which] == null ? List.of() : names[which];
            sign.setText(text(lines, arrow(front, out)), true);
            sign.setText(text(lines, arrow(front.getOpposite(), out)), false);
            done++;
        }
        return done;
    }

    /** 경로표대로 표지판을 다시 쓴다 — 팔 방향으로 다음 마디가 놓인 시설을 가까운 순으로 최대 4줄. */
    public static int relabel(ServerLevel sl, SignpostStore.Post post) {
        Direction[] arms = arms(post.rotation());
        @SuppressWarnings("unchecked")
        List<String>[] names = new List[] {new ArrayList<String>(), new ArrayList<String>()};
        for (RelayNet.Route r : RelayNet.routes(sl, post)) {
            int dx = r.nextPos().getX() - post.road().getX();
            int dz = r.nextPos().getZ() - post.road().getZ();
            for (int i = 0; i < 2; i++) {
                if (dx * arms[i].getStepX() + dz * arms[i].getStepZ() > 0) {
                    String name = r.facility().kind.group.label;
                    if (!names[i].contains(name) && names[i].size() < 4) {
                        names[i].add(name);
                    }
                }
            }
        }
        return label(sl, post.base(), post.rotation(), names);
    }

    /** 등기된 이정표 전부 다시 쓴다(밤 정산 뒤). 글씨를 쓴 표지판 수를 돌려준다. */
    public static int relabelAll(ServerLevel sl) {
        int n = 0;
        for (SignpostStore.Post p : SignpostStore.get(sl).all()) {
            n += relabel(sl, p);
        }
        return n;
    }

    /** 표지판 면이 {@code face} 를 향할 때, 그 면을 마주 보는 사람에게 {@code out} 쪽은 왼쪽인가 오른쪽인가. */
    private static String arrow(Direction face, Direction out) {
        Direction viewerLeft = face.getOpposite().getCounterClockWise();
        return out == viewerLeft ? "←" : "→";
    }

    private static SignText text(List<String> names, String arrow) {
        SignText t = new SignText();
        if (names.isEmpty()) {
            return t.setMessage(1, Component.literal(arrow));
        }
        for (int i = 0; i < Math.min(4, names.size()); i++) {
            t = t.setMessage(i, Component.literal(arrow.equals("→") ? names.get(i) + " →" : "← " + names.get(i)));
        }
        return t;
    }

    // ── 자리 고르기 ──────────────────────────────────────────────────────────

    /** 이정표 사이 간격(길 따라). 인지반경 96 과 같다 — 한 구간이 사거리 160 안에 든다. */
    public static final int SPACING = 96;
    /** 한 기 값 — 우물(45)보다 훨씬 싸고 가로등(6)보다 조금 비싸다(표지판 둘·랜턴·돌담). */
    public static final double COST = 8.0;
    /** 도로망이 이만큼은 자라야 이정표를 생각한다. */
    private static final int MIN_ROAD = 24;

    /** 고른 자리 — 밑동(지면 칸), 회전, 옆 길 칸(경유점). */
    public record Site(BlockPos base, Rotation rot, BlockPos road) {
    }

    private static final int[] REJ = new int[6];
    private static final String[] REJ_NAME = {"길위", "금지구역", "간격", "지형", "바닥", "겹침"};

    public static String rejectSummary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < REJ.length; i++) {
            if (REJ[i] > 0) {
                sb.append(sb.length() == 0 ? "" : " ").append(REJ_NAME[i]).append(REJ[i]);
            }
        }
        return sb.length() == 0 ? "후보 자체가 없음" : sb.toString();
    }

    /** 마지막 pickSite 가 왜 null 이었나 — 방아쇠 없음 / 후보 없음 / 자리 없음. */
    public static String lastReason = "";

    /**
     * 다음에 세울 자리 — 없으면 null.
     *
     * <p>방아쇠는 설계서 §1 그대로 "어느 마디(시설 문·이정표)에서도 96 넘게 떨어진 추종 가구". 그런 집이 없으면
     * 세우지 않는다. 후보는 중심선 칸 가운데 <b>기존 마디에서 직선 100 안</b>(이어져야 하니까)이고 길을 따라 72칸
     * 이상 떨어진 칸이다. 점수는 ① 그 자리가 새로 덮는 미도달 가구 수, ② 없으면 미도달 가구에 가장 가까운 칸
     * (망이 그쪽으로 자라도록), ③ 96 에 가까운 순. 기둥은 가로등처럼 중심선에서 수직 2칸 바깥이고,
     * 팔은 길과 나란하다(길이 x 로 뻗으면 회전 없음, z 로 뻗으면 90°, 교차로면 좌표 홀짝으로 고정).
     */
    @Nullable
    public static Site pickSite(ServerLevel sl, java.util.Collection<BlockPos> followerHomes) {
        java.util.Arrays.fill(REJ, 0);
        RoadStore roads = RoadStore.get(sl);
        if (roads.size() < MIN_ROAD) {
            lastReason = "길 " + roads.size() + " < " + MIN_ROAD;
            return null;
        }
        List<BlockPos> stations = RelayNet.stationPositions(sl);
        if (stations.isEmpty()) {
            lastReason = "마디 없음(문 있는 시설이 없다)";
            return null;
        }
        double reach2 = RelayNet.STATION_REACH * RelayNet.STATION_REACH;
        List<BlockPos> unserved = new ArrayList<>();
        for (BlockPos h : followerHomes) {
            boolean served = false;
            for (BlockPos st : stations) {
                if (st.distSqr(h) <= reach2) {
                    served = true;
                    break;
                }
            }
            if (!served) {
                unserved.add(h);
            }
        }
        if (unserved.isEmpty()) {
            lastReason = "추종 가구 " + followerHomes.size() + " 모두 마디 96 안";
            return null;
        }
        // 길 따라 마디에서의 걸음 수 — 마디 주변 4칸 안 중심선 칸을 씨앗으로.
        java.util.Map<Long, Integer> walk = new java.util.HashMap<>();
        java.util.ArrayDeque<Long> q = new java.util.ArrayDeque<>();
        java.util.Set<Long> cells = roads.raw();
        for (BlockPos st : stations) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    long k = RoadStore.key(st.getX() + dx, st.getZ() + dz);
                    if (cells.contains(k) && walk.putIfAbsent(k, 0) == null) {
                        q.add(k);
                    }
                }
            }
        }
        while (!q.isEmpty()) {
            long cur = q.poll();
            int nd = walk.get(cur) + 1;
            for (int[] d : RoadPlanner.D4) {
                long n = RoadStore.key(RoadStore.keyX(cur) + d[0], RoadStore.keyZ(cur) + d[1]);
                if (cells.contains(n) && walk.putIfAbsent(n, nd) == null) {
                    q.add(n);
                }
            }
        }
        double link2 = RelayNet.LINK * RelayNet.LINK;
        List<double[]> cand = new ArrayList<>(); // {-덮는 가구, 미도달까지 최단, |d-96|, x, z}
        for (int[] c : roads.all()) {
            int x = c[0];
            int z = c[1];
            Integer w = walk.get(RoadStore.key(x, z));
            if (w == null || w < SPACING * 3 / 4) {
                continue;
            }
            double dmin = Double.MAX_VALUE;
            for (BlockPos st : stations) {
                dmin = Math.min(dmin, st.distSqr(new BlockPos(x, st.getY(), z)));
            }
            if (dmin > link2) {
                continue;
            }
            int covers = 0;
            double nearestUnserved = Double.MAX_VALUE;
            for (BlockPos h : unserved) {
                double dh = (h.getX() - x) * (double) (h.getX() - x) + (h.getZ() - z) * (double) (h.getZ() - z);
                if (dh <= reach2) {
                    covers++;
                }
                nearestUnserved = Math.min(nearestUnserved, dh);
            }
            cand.add(new double[] {-covers, nearestUnserved, Math.abs(Math.sqrt(dmin) - SPACING), x, z});
        }
        if (cand.isEmpty()) {
            lastReason = "후보 없음(마디 100 안·길 72칸 밖 중심선 칸이 없다) · 미도달 " + unserved.size();
            return null;
        }
        cand.sort(java.util.Comparator.<double[]>comparingDouble(a -> a[0]).thenComparingDouble(a -> a[1])
                .thenComparingDouble(a -> a[2]).thenComparingDouble(a -> a[3]).thenComparingDouble(a -> a[4]));
        LampStore lamps = LampStore.get(sl);
        SignpostStore posts = SignpostStore.get(sl);
        RoadPlanner.Obstacles ob = RoadPlanner.Obstacles.of(sl);
        FarmStore farms = FarmStore.get(sl);
        int budget = 200;
        for (double[] c : cand) {
            int x = (int) c[3];
            int z = (int) c[4];
            if (posts.nearest(x, z) < SPACING * 0.75) {
                continue;
            }
            if (--budget < 0) {
                break;
            }
            int cy = RoadPlanner.surfaceY(sl, x, z);
            if (cy == Integer.MIN_VALUE) {
                continue;
            }
            boolean runX = roads.has(x + 1, z) || roads.has(x - 1, z);
            boolean runZ = roads.has(x, z + 1) || roads.has(x, z - 1);
            Rotation rot = runX && !runZ ? Rotation.NONE : (runZ && !runX ? Rotation.CLOCKWISE_90
                    : (((x + z) & 1) == 0 ? Rotation.NONE : Rotation.CLOCKWISE_90));
            for (int[] off : offsets(runX, runZ)) {
                int px = x + off[0];
                int pz = z + off[1];
                if (ok(sl, roads, lamps, posts, ob, farms, px, pz, cy, rot)) {
                    lastReason = "";
                    return new Site(new BlockPos(px, RoadPlanner.surfaceY(sl, px, pz), pz), rot,
                            new BlockPos(x, cy + 1, z));
                }
            }
        }
        lastReason = "자리 없음 — " + rejectSummary() + " · 미도달 " + unserved.size();
        return null;
    }

    private static List<int[]> offsets(boolean runX, boolean runZ) {
        List<int[]> out = new ArrayList<>(4);
        if (runX && !runZ) {
            out.add(new int[] {0, 2});
            out.add(new int[] {0, -2});
        } else if (runZ && !runX) {
            out.add(new int[] {2, 0});
            out.add(new int[] {-2, 0});
        } else {
            out.add(new int[] {0, 2});
            out.add(new int[] {0, -2});
            out.add(new int[] {2, 0});
            out.add(new int[] {-2, 0});
        }
        return out;
    }

    /** 이 열에 기둥을 세워도 되는가 — 가로등과 같은 검사에 이정표·가로등 간격과 도면 자리 비움을 더한다. */
    private static boolean ok(ServerLevel sl, RoadStore roads, LampStore lamps, SignpostStore posts,
                              RoadPlanner.Obstacles ob, FarmStore farms, int px, int pz, int cy, Rotation rot) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (roads.has(px + dx, pz + dz)) {
                    REJ[0]++;
                    return false;
                }
            }
        }
        if (ob.blocked(px, pz) || farms.nearBody(px, pz, 1)) {
            REJ[1]++;
            return false;
        }
        if (lamps.nearest(px, pz) < 4 || posts.nearest(px, pz) < SPACING * 0.75) {
            REJ[2]++;
            return false;
        }
        int gy = RoadPlanner.surfaceY(sl, px, pz);
        if (gy == Integer.MIN_VALUE || Math.abs(gy - cy) > 1) {
            REJ[3]++;
            return false;
        }
        BlockPos ground = new BlockPos(px, gy, pz);
        if (!sl.getBlockState(ground).isFaceSturdy(sl, ground, Direction.UP)) {
            REJ[4]++;
            return false;
        }
        var pl = plan(sl, rot);
        if (pl.isEmpty()) {
            REJ[5]++;
            return false;
        }
        for (HomeTemplate.Placement p : pl.get()) {
            if (p.rel().getY() == 0) {
                continue; // 밑동은 지면 칸을 대신한다
            }
            if (!sl.isEmptyBlock(ground.offset(p.rel()))) {
                REJ[5]++;
                return false;
            }
        }
        return true;
    }

    /** 기둥 열 — 길이 나중에 이 칸을 지나가지 않도록 장애물에 실린다. */
    public static java.util.Set<Long> postColumns(ServerLevel sl) {
        return SignpostStore.get(sl).postColumns();
    }
}
