package com.evosim.mod.entity;

import com.evosim.mod.EvoSimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.data.worldgen.features.TreeFeatures;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * <b>가로수와 분수</b> — 길가에 서는 장식물. {@link LampPlanner} 와 같은 틀이다.
 *
 * <p>가로등이 이미 이 문제를 다 풀어 놨다: 길 옆 자리를 고르고, 포장된 띠를 피하고, 간격을
 * 지키고, <b>등기해서 길 찾기가 비껴가게</b> 한다. 나무와 분수도 통행을 막는 물체이므로 같은
 * 대접이 필요하다 — 등기가 없으면 나중에 나는 길이 나무를 관통한다.
 *
 * <p><b>왜 둘을 한 클래스에</b>: 자리를 고르는 규칙(길 옆·포장 회피·금지구역·간격)이 같고
 * 간격과 값만 다르다. 갈라 두면 같은 규칙이 두 벌이 되어 한쪽만 고치는 일이 생긴다.
 */
public final class StreetPlanner {

    /**
     * 가로수 간격(블록) — 이보다 가까이 두 그루가 서지 않는다.
     *
     * <p>"8칸보다 살짝 크게, 그냥 적당히 생기는 정도" 라는 요구를 10 으로 옮겼다. 가로등
     * ({@link LampPlanner#SPACING} 14)보다는 촘촘하지만 참나무 수관이 약 5칸이라 그루 사이에
     * 5칸의 틈이 남는다 — 가로수길로 읽히되 숲은 아니다. 규칙 14 의 "측정 후 결정할 상수" 라
     * 공중 사진을 보고 확정한다.
     */
    public static final int TREE_SPACING = 10;

    /**
     * 길 중심선에서 나무까지의 <b>최소</b> 거리.
     *
     * <p>가로등은 2칸 물러나 선다. 나무는 수관이 사방 2칸쯤 퍼지므로 2칸이면 잎이 길 위를
     * 덮는다 — "길에 너무 바짝 붙이지 않고" 라는 요구대로 3칸으로 둔다. 그러면 수관 끝이
     * 포장 띠(중심선 ±1) 바로 바깥에서 멈춘다.
     */
    private static final int TREE_OFFSET = 3;

    /**
     * 분수 간격(블록) — <b>아주 크다</b>.
     *
     * <p>가로등의 네 배가 넘는다. 분수는 광장의 중심이지 가로 시설물이 아니어서, 마을에 한둘
     * 있어야 뜻이 산다("상당히 큰 간격"). 학교·교회의 간격({@code 96})과 같은 눈금에 둔다.
     */
    public static final int FOUNTAIN_SPACING = 96;

    /** 분수 중심에서 길 중심선까지의 최소 거리 — 몸통이 5×5 라 반폭 2 + 포장 띠 1 + 여유 1. */
    private static final int FOUNTAIN_OFFSET = 4;

    /**
     * 값 — 가로수는 싸고, 분수는 <b>과시</b>다.
     *
     * <p>가로등이 6.0 이다. 나무는 그보다 싸야 "적당히 생기는" 밀도가 되고, 분수는 아무 기능도
     * 없는 순수 장식이라 <b>그것을 세울 수 있다는 것 자체가 세력의 표시</b>가 되어야 한다.
     * 저택(70)보다 싸고 큰 교회(50)와 나란한 자리에 둔다 — 어느 쪽에 쓸지 고르게 된다.
     */
    public static final double TREE_COST = 2.0;
    public static final double FOUNTAIN_COST = 45.0;

    /** 도로망이 이만큼은 자라야 꾸밈을 생각한다 — 가로등과 같은 문턱. */
    private static final int MIN_ROAD_TREE = 24;
    /** 분수는 광장이 있어야 뜻이 있다 — 훨씬 큰 마을에서만. */
    private static final int MIN_ROAD_FOUNTAIN = 200;

    private StreetPlanner() {
    }

    // ── 분수 도면 ────────────────────────────────────────────────────────────

    private static List<HomeTemplate.Placement> fountainCache;

    /**
     * {@code fountain.nbt} → <b>중심 상대</b> 배치 계획.
     *
     * <p>이 도면에도 앵커 표지가 없다(종도 금블록도 없다 — 시설도 거처도 아니다). 5×5 의
     * <b>기하 중심</b>을 원점으로 삼는다: 도면이 정확히 홀수 폭이라 중심 칸이 유일하게 정해지고,
     * 실제로 그 열에 물기둥이 서 있어 눈으로도 중심이다.
     */
    public static Optional<List<HomeTemplate.Placement>> fountainPlan(ServerLevel sl) {
        if (fountainCache != null) {
            return Optional.of(fountainCache);
        }
        ResourceLocation rl = new ResourceLocation(EvoSimMod.MODID, "structures/fountain.nbt");
        var res = sl.getServer().getResourceManager().getResource(rl);
        if (res.isEmpty()) {
            return Optional.empty();
        }
        CompoundTag tag;
        try (InputStream in = res.get().open()) {
            tag = NbtIo.readCompressed(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("fountain: 도면을 읽을 수 없다 — " + e.getMessage(), e);
        }
        ListTag paletteTag = tag.contains("palette", Tag.TAG_LIST)
                ? tag.getList("palette", Tag.TAG_COMPOUND)
                : tag.getList("palettes", Tag.TAG_LIST).getCompound(0)
                        .getList("palette", Tag.TAG_COMPOUND);
        BlockState[] states = new BlockState[paletteTag.size()];
        var lookup = BuiltInRegistries.BLOCK.asLookup();
        for (int i = 0; i < paletteTag.size(); i++) {
            states[i] = NbtUtils.readBlockState(lookup, paletteTag.getCompound(i));
        }
        ListTag blocksTag = tag.getList("blocks", Tag.TAG_COMPOUND);
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        List<int[]> raw = new ArrayList<>();
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag b = blocksTag.getCompound(i);
            ListTag p = b.getList("pos", Tag.TAG_INT);
            int x = p.getInt(0);
            int y = p.getInt(1);
            int z = p.getInt(2);
            if (states[b.getInt("state")].isAir()) {
                continue; // 공기는 놓지 않는다 — 기존 지형을 파내지 않게
            }
            raw.add(new int[] {x, y, z, b.getInt("state")});
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxZ = Math.max(maxZ, z);
        }
        int cx = (minX + maxX) / 2;
        int cz = (minZ + maxZ) / 2;
        List<HomeTemplate.Placement> out = new ArrayList<>(raw.size());
        for (int[] r : raw) {
            out.add(new HomeTemplate.Placement(
                    new BlockPos(r[0] - cx, r[1] - minY, r[2] - cz), states[r[3]]));
        }
        // 아래에서 위로 — 물이 받침보다 먼저 놓이면 흘러내린다.
        out.sort(Comparator.comparingInt(q -> q.rel().getY()));
        fountainCache = List.copyOf(out);
        return Optional.of(fountainCache);
    }

    // ── 자리 고르기 ──────────────────────────────────────────────────────────

    /** 못 찾았을 때 <b>어디서 걸렀는지</b> — [길위, 금지구역, 간격, 지형, 물가]. */
    private static final int[] REJ = new int[5];
    private static final String[] REJ_NAME = {"길위", "금지구역", "간격", "지형", "물가"};

    public static String rejectSummary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < REJ.length; i++) {
            sb.append(i == 0 ? "" : " ").append(REJ_NAME[i]).append(REJ[i]);
        }
        return sb.toString();
    }

    /**
     * 길가의 빈 자리 하나 — 없으면 null.
     *
     * <p>가로등과 같은 순서로 거른다: 포장된 띠(중심선 ±1) 위는 안 되고, 집·밭·시설의 금지
     * 구역도 안 되고, 같은 종류끼리 간격을 지켜야 한다. 다른 점은 <b>자리값을 매기지 않는</b>
     * 것이다 — 가로등은 "가장 어두운 곳"이라는 목적이 있지만 꾸밈은 고르게 퍼지면 되므로,
     * 간격 규칙만으로 분포가 정해진다.
     */
    @Nullable
    public static BlockPos pickSite(ServerLevel sl, boolean fountain) {
        java.util.Arrays.fill(REJ, 0);
        RoadStore roads = RoadStore.get(sl);
        if (roads.size() < (fountain ? MIN_ROAD_FOUNTAIN : MIN_ROAD_TREE)) {
            return null;
        }
        StreetStore store = StreetStore.get(sl);
        RoadPlanner.Obstacles ob = RoadPlanner.Obstacles.of(sl);
        FarmStore farms = FarmStore.get(sl);
        int spacing = fountain ? FOUNTAIN_SPACING : TREE_SPACING;
        int off = fountain ? FOUNTAIN_OFFSET : TREE_OFFSET;
        int half = fountain ? 2 : 0; // 분수는 5×5 — 몸통 전체를 검사한다

        List<int[]> cells = new ArrayList<>();
        for (int[] c : roads.all()) {
            cells.add(c);
        }
        // 결정론 — 좌표순으로 정렬하되 <b>시작점을 날마다 돌린다</b>.
        //
        // 좌표순으로 고정해 놓고 예산에서 끊으면, 도로망이 예산보다 길 때 <b>늘 같은 서쪽 끝
        // 칸들만</b> 보게 된다. 거기가 막혀 있으면 마을 나머지가 아무리 비어 있어도 영영 자리를
        // 못 찾는다(실측: 도로망이 자란 마을에서 가로수가 0그루였다). 가로등은 어둠 점수로
        // 정렬해 자연히 흩어지지만, 꾸밈은 자리값이 없어 순서가 곧 전부다.
        //
        // 날짜로 시작점을 돌리면 며칠에 걸쳐 도로망 전체를 훑는다 — 무작위를 새로 들이지 않고
        // (결정론이 깨지지 않게) 같은 효과를 낸다. 마실 목적지가 (id+날) 로 도는 것과 같은 수법이다.
        cells.sort(Comparator.<int[]>comparingInt(a -> a[0]).thenComparingInt(a -> a[1]));
        int budget = 400;
        int start = cells.isEmpty() ? 0
                : (int) Math.floorMod(SimTime.tick(sl) / 24000L, cells.size());
        for (int i = 0; i < cells.size(); i++) {
            int[] c = cells.get((start + i) % cells.size());
            int x = c[0];
            int z = c[1];
            if (store.nearest(x, z, fountain) < spacing) {
                continue; // 이 근방은 이미 찼다
            }
            if (--budget < 0) {
                return null;
            }
            for (int[] d : RoadPlanner.D4) {
                int px = x + d[0] * off;
                int pz = z + d[1] * off;
                if (ok(sl, roads, store, ob, farms, px, pz, spacing, half, fountain)) {
                    int y = RoadPlanner.surfaceY(sl, px, pz);
                    return new BlockPos(px, y + 1, pz);
                }
            }
        }
        return null;
    }

    private static boolean ok(ServerLevel sl, RoadStore roads, StreetStore store,
                              RoadPlanner.Obstacles ob, FarmStore farms, int px, int pz,
                              int spacing, int half, boolean fountain) {
        for (int dx = -half - 1; dx <= half + 1; dx++) {
            for (int dz = -half - 1; dz <= half + 1; dz++) {
                if (roads.has(px + dx, pz + dz)) {
                    REJ[0]++;
                    return false; // 포장 띠 위 — 길을 막는다
                }
            }
        }
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                if (ob.blocked(px + dx, pz + dz) || farms.nearBody(px + dx, pz + dz, 1)) {
                    REJ[1]++;
                    return false;
                }
            }
        }
        if (store.nearest(px, pz, fountain) < spacing) {
            REJ[2]++;
            return false;
        }
        // 지면이 고른가 — 분수는 5×5 라 한 칸이라도 어긋나면 물이 샌다.
        int base = RoadPlanner.surfaceY(sl, px, pz);
        if (base == Integer.MIN_VALUE) {
            REJ[3]++;
            return false;
        }
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                int y = RoadPlanner.surfaceY(sl, px + dx, pz + dz);
                if (y == Integer.MIN_VALUE || y != base) {
                    REJ[3]++;
                    return false;
                }
                var st = sl.getBlockState(new BlockPos(px + dx, y, pz + dz));
                if (!st.getFluidState().isEmpty()) {
                    REJ[4]++;
                    return false; // 물 위에는 안 세운다
                }
            }
        }
        return true;
    }

    // ── 세우기 ──────────────────────────────────────────────────────────────

    /**
     * 참나무 한 그루 — 바닐라 지형 생성의 참나무 피처를 그대로 쓴다.
     *
     * <p>묘목을 심어 자라기를 기다리지 않는다: 자라려면 빛·공간·시간이 필요하고 실패하는데,
     * 길가는 마침 가로등이 밝히는 곳이라 실패가 잦다. 다 자란 나무를 바로 놓으면 가로등이
     * 도면을 즉시 세우는 것과 같은 방식이 된다.
     *
     * @return 실제로 섰으면 true
     */
    public static boolean raiseTree(ServerLevel sl, BlockPos base) {
        var reg = sl.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
        var holder = reg.getHolder(TreeFeatures.OAK);
        if (holder.isEmpty()) {
            return false;
        }
        return holder.get().value().place(sl, sl.getChunkSource().getGenerator(),
                sl.getRandom(), base);
    }

    /** 분수 — 도면을 중심 상대로 놓는다. */
    public static boolean raiseFountain(ServerLevel sl, BlockPos centre) {
        var plan = fountainPlan(sl);
        if (plan.isEmpty()) {
            return false;
        }
        for (HomeTemplate.Placement p : plan.get()) {
            sl.setBlock(centre.offset(p.rel()), p.state(), 2);
        }
        return true;
    }
}
