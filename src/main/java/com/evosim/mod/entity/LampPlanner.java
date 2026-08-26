package com.evosim.mod.entity;

import com.evosim.mod.EvoSimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * 가로등 — 도면 해석과 <b>세울 자리 고르기</b>.
 *
 * <h3>왜 길 위가 아니라 길 <b>가장자리 바깥</b>인가</h3>
 * 기둥이 {@code oak_fence} 라 통행을 막는다. 폭 3 길 한복판에 세우면 가운데 차선이 끊긴다.
 * 그래서 중심선에서 <b>수직으로 2칸</b> 물러난 칸(=폭 3 띠 바로 바깥)에 세운다. 랜턴은
 * 밑동+4, 즉 지면+5 라 미믹 머리(지면+2) 위로 지나가므로 길 위로 뻗어도 걸리지 않는다.
 *
 * <h3>간격 {@link #SPACING} = 14 의 근거(빛 계산)</h3>
 * 랜턴 광원 15, 한 칸당 −1(맨해튼 거리). 지면+5 에 달리므로 바로 아래 지면은 15−5 = 10.
 * 두 등 사이 한복판은 수평 7 + 수직 5 = 12칸 떨어져 <b>3</b>이 남는다. 몹 생성 조건이 밝기 0
 * (1.18+)이므로 길 위는 등간 한복판까지 전부 생성 불가로 덮인다 — 더 촘촘히 박을 이유가 없다.
 *
 * <h3>누가 세우는가 — 규칙5</h3>
 * 밭을 가진 가구가 <b>여유가 넘칠 때만</b> 제 돈으로 세운다({@link #COST}). 하드코딩된
 * "마을 가로등 수"가 없다. 가난한 마을은 어둡고, 부유해지면 밝아진다 — 밀도가 저절로 정해진다.
 */
public final class LampPlanner {

    /** 등 사이 최소 평면 거리(빛 계산 근거는 클래스 주석). */
    public static final int SPACING = 14;
    /** 한 기 세우는 값 — 저장고에서 즉시 차감한다. */
    public static final double COST = 6.0;
    /** 밑동에서 랜턴까지의 높이 — 보고의 밝기 검산에 쓴다. */
    public static final int LIGHT_UP = 4;
    /** 등기 없이 놓인 자리를 다시 훑기까지의 간격(틱) — 하루보다 짧게. */
    private static final long SCAN_PERIOD = 400L;
    /** 도로망이 이만큼은 자라야 가로등을 생각한다(도막 하나에 등을 세우면 우습다). */
    private static final int MIN_ROAD = 24;

    private LampPlanner() {
    }

    // ── 도면 ────────────────────────────────────────────────────────────────

    private static List<HomeTemplate.Placement> planCache;

    /**
     * {@code street_lamp.nbt} → <b>밑동 상대</b> 배치 계획(쌓는 순서).
     *
     * <p>이 도면에는 금블록 앵커가 없다({@link HomeTemplate} 의 저작 규약은 거처용이다).
     * 대신 <b>기둥 최하단 울타리</b>를 원점으로 삼는다 — 도면에 울타리 기둥은 한 열뿐이라
     * 원점이 유일하게 정해진다.
     *
     * <p>쌓는 순서는 기둥 → 갓(판자) → 지붕(반블록) → <b>랜턴</b>이다. 매달린 랜턴은 위 칸에
     * 붙으므로 지붕이 먼저 서야 시공 중간 상태가 공중에 뜬 랜턴으로 보이지 않는다.
     */
    public static Optional<List<HomeTemplate.Placement>> plan(ServerLevel sl) {
        if (planCache != null) {
            return Optional.of(planCache);
        }
        ResourceLocation rl = new ResourceLocation(EvoSimMod.MODID, "structures/street_lamp.nbt");
        var res = sl.getServer().getResourceManager().getResource(rl);
        if (res.isEmpty()) {
            return Optional.empty();
        }
        CompoundTag tag;
        try (InputStream in = res.get().open()) {
            tag = NbtIo.readCompressed(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("street_lamp: 도면을 읽을 수 없다 — " + e.getMessage(), e);
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
        List<HomeTemplate.Placement> raw = new ArrayList<>();
        BlockPos base = null;
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag b = blocksTag.getCompound(i);
            ListTag p = b.getList("pos", Tag.TAG_INT);
            BlockState st = states[b.getInt("state")];
            if (st.isAir()) {
                continue;
            }
            BlockPos pos = new BlockPos(p.getInt(0), p.getInt(1), p.getInt(2));
            raw.add(new HomeTemplate.Placement(pos, st));
            if (st.is(Blocks.OAK_FENCE) && (base == null || pos.getY() < base.getY())) {
                base = pos;
            }
        }
        if (base == null) {
            throw new IllegalStateException("street_lamp: 울타리 기둥이 없다 — 밑동을 정할 수 없다");
        }
        final BlockPos origin = base;
        List<HomeTemplate.Placement> out = new ArrayList<>(raw.size());
        for (HomeTemplate.Placement p : raw) {
            out.add(new HomeTemplate.Placement(p.rel().subtract(origin), p.state()));
        }
        // 기둥·갓 → 지붕 → 랜턴. 같은 층에서는 좌표 순으로 결정적.
        out.sort(Comparator
                .comparingInt((HomeTemplate.Placement p) ->
                        p.state().is(Blocks.LANTERN) ? 2 : (p.rel().getY() >= 5 ? 1 : 0))
                .thenComparingInt(p -> p.rel().getY())
                .thenComparingInt(p -> p.rel().getX())
                .thenComparingInt(p -> p.rel().getZ()));
        planCache = List.copyOf(out);
        return Optional.of(planCache);
    }

    /** 도면이 차지하는 칸 수 — 시공 진행률·보고용. */
    public static int blockCount(ServerLevel sl) {
        return plan(sl).map(List::size).orElse(0);
    }

    // ── 자리 고르기 ──────────────────────────────────────────────────────────

    private static long scanTick = Long.MIN_VALUE;
    private static BlockPos cached;

    /**
     * 다음에 세울 자리 — 없으면 null. 결과는 짧게 캐시한다(하루에 여러 가구가 물어본다).
     *
     * <p>착공한 쪽이 {@link LampStore} 에 즉시 등기하므로, 다음 호출은 그 자리를 간격 조건에서
     * 스스로 배제한다.
     */
    @Nullable
    public static BlockPos pickSite(ServerLevel sl) {
        long now = SimTime.tick(sl);
        if (cached != null && LampStore.get(sl).has(cached)) {
            cached = null; // 누가 이미 착공했다 — 다시 고른다
            scanTick = Long.MIN_VALUE;
        }
        if (cached != null) {
            return cached;
        }
        // <b>아직 한 번도 안 훑었으면 무조건 훑는다.</b> 여기서 {@code now - scanTick} 을 그냥
        // 빼면 안 된다 — 초기값이 {@link Long#MIN_VALUE} 라 뺄셈이 오버플로해 큰 <b>음수</b>가
        // 되고, 그러면 "방금 훑었다"로 읽혀 스캔 없이 null 이 나간다. scanTick 은 갱신되지
        // 않으므로 그 상태가 영구히 반복된다(실측: 문턱을 통과한 지주가 매번 "자리 없음"만
        // 받았고 등이 한 기도 서지 못했다).
        if (scanTick != Long.MIN_VALUE && now >= scanTick && now - scanTick < SCAN_PERIOD) {
            return null;
        }
        scanTick = now;
        cached = scan(sl);
        return cached;
    }

    /** 착공 직후 — 캐시를 비운다. */
    public static void taken() {
        cached = null;
        scanTick = Long.MIN_VALUE;
    }

    @Nullable
    private static BlockPos scan(ServerLevel sl) {
        RoadStore roads = RoadStore.get(sl);
        if (roads.size() < MIN_ROAD) {
            return null;
        }
        LampStore lamps = LampStore.get(sl);
        RoadPlanner.Obstacles ob = RoadPlanner.Obstacles.of(sl);
        FarmStore farms = FarmStore.get(sl);
        List<BlockPos> homes = HomeStore.get(sl).positions();
        java.util.Arrays.fill(REJ, 0);

        // 후보 = 중심선 칸. 점수는 ① 교차로 ② 막다른 골목(대개 문 앞 진입로) ③ 직선 순.
        List<double[]> cand = new ArrayList<>(); // {−점수, x, z}
        for (int[] c : roads.all()) {
            int x = c[0];
            int z = c[1];
            int deg = 0;
            for (int[] d : RoadPlanner.D4) {
                if (roads.has(x + d[0], z + d[1])) {
                    deg++;
                }
            }
            double score = deg >= 3 ? 3.0 : (deg == 1 ? 1.5 : (deg == 2 ? 1.0 : 0.5));
            int near = 0;
            for (BlockPos h : homes) {
                if (Math.abs(h.getX() - x) <= 12 && Math.abs(h.getZ() - z) <= 12 && ++near >= 6) {
                    break;
                }
            }
            score += 0.15 * near; // 통행량 대용 — 집이 몰린 곳이 더 밝아진다
            cand.add(new double[] {-score, x, z});
        }
        cand.sort(Comparator.<double[]>comparingDouble(a -> a[0])
                .thenComparingDouble(a -> a[1]).thenComparingDouble(a -> a[2]));

        // 한 번의 훑기에 <b>정밀 검사</b>는 이만큼만 — 자리가 하나도 없는 날(간격이 다 찬 마을)에
        // 후보 전부를 14칸씩 읽으면 그 틱만 수만 회 블록 조회가 된다. 못 찾으면 다음 주기에 다시 본다.
        int budget = 300;
        for (double[] c : cand) {
            int x = (int) c[1];
            int z = (int) c[2];
            if (lamps.nearest(x, z) < SPACING) {
                continue; // 이 근방은 이미 밝다 — 기둥 자리를 따져 볼 것도 없다
            }
            if (--budget < 0) {
                return null;
            }
            int cy = RoadPlanner.surfaceY(sl, x, z);
            if (cy == Integer.MIN_VALUE) {
                continue;
            }
            for (int[] off : offsets(roads, x, z)) {
                int px = x + off[0];
                int pz = z + off[1];
                if (ok(sl, roads, lamps, ob, farms, px, pz, cy)) {
                    return new BlockPos(px, RoadPlanner.surfaceY(sl, px, pz) + 1, pz);
                }
            }
        }
        return null;
    }

    /** 기둥 후보 방향 — 길이 뻗은 방향의 <b>수직</b>부터. 폭 3 띠 바로 바깥(거리 2)이다. */
    private static List<int[]> offsets(RoadStore roads, int x, int z) {
        boolean runX = roads.has(x + 1, z) || roads.has(x - 1, z);
        boolean runZ = roads.has(x, z + 1) || roads.has(x, z - 1);
        List<int[]> out = new ArrayList<>(8);
        if (runX && !runZ) {
            out.add(new int[] {0, 2});
            out.add(new int[] {0, -2});
            out.add(new int[] {2, 0});
            out.add(new int[] {-2, 0});
        } else {
            out.add(new int[] {2, 0});
            out.add(new int[] {-2, 0});
            out.add(new int[] {0, 2});
            out.add(new int[] {0, -2});
        }
        out.add(new int[] {2, 2});
        out.add(new int[] {2, -2});
        out.add(new int[] {-2, 2});
        out.add(new int[] {-2, -2});
        return out;
    }

    /**
     * 자리를 못 찾았을 때 <b>어디서 걸렀는지</b> 집계 — 길위·금지·간격·지형·바닥·겹침 순.
     *
     * <p>"자리 없음" 한 줄만 남기면 그게 기하 때문인지 판정 결함인지 가릴 수 없다. 실제로
     * 그 구분이 없어 시각 스캔이 통째로 죽은 것을 여러 날 놓쳤다.
     */
    private static final int[] REJ = new int[6];

    private static final String[] REJ_NAME =
            {"길위", "금지구역", "간격", "지형", "바닥", "겹침"};

    public static String rejectSummary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < REJ.length; i++) {
            if (REJ[i] > 0) {
                sb.append(sb.length() == 0 ? "" : " ").append(REJ_NAME[i]).append(REJ[i]);
            }
        }
        return sb.length() == 0 ? "후보 자체가 없음" : sb.toString();
    }

    /** 이 열에 기둥을 세워도 되는가 — 길·집·밭·지형·머리 위를 모두 본다. */
    private static boolean ok(ServerLevel sl, RoadStore roads, LampStore lamps,
                              RoadPlanner.Obstacles ob, FarmStore farms, int px, int pz, int cy) {
        // ① 포장된 띠(중심선 ±1) 위는 안 된다 — 기둥이 차선을 막는다.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (roads.has(px + dx, pz + dz)) {
                    REJ[0]++;
                    return false;
                }
            }
        }
        // ② 집 지면층·문앞 계단·정원·밭 몸통 — 길과 같은 금지 출처를 그대로 쓴다.
        if (ob.blocked(px, pz) || farms.nearBody(px, pz, 1)) {
            REJ[1]++;
            return false;
        }
        if (lamps.nearest(px, pz) < SPACING) {
            REJ[2]++;
            return false;
        }
        // ③ 지형 — 딛는 지면이 있고 길과 높이가 어긋나지 않아야 한다.
        int gy = RoadPlanner.surfaceY(sl, px, pz);
        if (gy == Integer.MIN_VALUE || Math.abs(gy - cy) > 1) {
            REJ[3]++;
            return false;
        }
        BlockPos ground = new BlockPos(px, gy, pz);
        if (!sl.getBlockState(ground).isFaceSturdy(sl, ground, net.minecraft.core.Direction.UP)) {
            REJ[4]++;
            return false; // 나뭇잎·풀·물가 — 기둥이 설 바닥이 아니다
        }
        // ④ 도면이 들어갈 자리가 통째로 비어 있어야 한다(처마·나무·다른 등과 겹치지 않게).
        var pl = plan(sl);
        if (pl.isEmpty()) {
            REJ[5]++;
            return false;
        }
        BlockPos base = ground.above();
        for (HomeTemplate.Placement p : pl.get()) {
            if (!sl.isEmptyBlock(base.offset(p.rel()))) {
                REJ[5]++;
                return false;
            }
        }
        return true;
    }

    /** 가로등 기둥이 차지하는 열 — 길이 나중에 이 칸을 지나가지 않도록 장애물에 실린다. */
    public static Set<Long> postColumns(ServerLevel sl) {
        Set<Long> out = new HashSet<>();
        for (BlockPos p : LampStore.get(sl).all()) {
            out.add(RoadStore.key(p.getX(), p.getZ()));
        }
        return out;
    }
}
