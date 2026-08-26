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
 * <h3>어디에 세우는가 — <b>"중요한 자리"가 아니라 "어두운 자리"</b></h3>
 * 자리값 = <b>유형가중 × 어둠</b>이다. 어둠은 {@link #lampDist 길을 따라} 가장 가까운 등까지의
 * 걸음 수({@link #DARK_CAP} 에서 자름), 유형가중은 교차로 1.5 · 막다른 골목 1.2 · 직선 1.0 이다.
 * 점수가 같으면 <b>실제(자르지 않은) 어둠</b>이 큰 쪽이 이긴다 — 상한 위쪽이 통째로 동률이라
 * 그 자리를 좌표순으로 가르면 가장 어두운 곳이 좌표 운으로 밀린다.
 *
 * <p>처음에는 유형만 절대점수로 봤다(교차로 3.0 · 막다른 1.5 · 직선 1.0 + 집 밀도 가산).
 * 그러면 <b>이미 밝은 교차로가 캄캄한 직선을 언제나 이긴다</b> — 간격 하한은 하드 금지일 뿐
 * 우선순위에 반영되지 않기 때문이다. 하루 한 기씩 서는데 중심부는 계속 새 교차로를 만들어
 * 내므로 긴 진입로는 대기열 앞에 영영 오지 못했다(배제가 아니라 굶주림이다).
 *
 * <p>실측 근거(D16 관측 런, 등 12기 · 흙길 1742칸): 길을 따라 잰 등까지의 거리가
 * <b>최대 49칸</b>, 14칸 초과가 33%, 28칸 초과가 11% 였다. 등은 전부 마을 중심에 몰렸고
 * 남북 진입로 90여 칸에는 한 기도 없었다.
 *
 * <p>어둠을 곱하면 저절로 균형이 잡힌다 — 등이 하나 서면 그 주변의 어둠이 즉시 떨어지고
 * 다음 순번이 다른 곳으로 넘어간다. 그리고 직선 구간은 유형가중이 모두 같으므로 어둠이 가장
 * 큰 칸, 곧 <b>등과 등 사이의 한복판</b>이 뽑힌다. 집 밀도 가산은 뺐다 — 교차로 가중과
 * 중복이고, 중심부 편중을 키우는 쪽으로만 작용했다.
 *
 * <p>등이 하나도 없을 때는 모든 칸의 어둠이 상한으로 같아 유형가중만 남는다 — 첫 등은
 * 예전처럼 교차로에 선다(초기 거동에 회귀가 없다).
 *
 * <h3>누가 세우는가 — 규칙5</h3>
 * 밭을 가진 가구가 <b>여유가 넘칠 때만</b> 제 돈으로 세운다({@link #COST}). 하드코딩된
 * "마을 가로등 수"가 없다. 가난한 마을은 어둡고, 부유해지면 밝아진다 — 밀도가 저절로 정해진다.
 */
public final class LampPlanner {

    /** 등 사이 최소 평면 거리(빛 계산 근거는 클래스 주석). */
    public static final int SPACING = 14;
    /** 어둠의 상한(길 따라 걸음 수). 이보다 먼 칸은 <b>점수상</b> 전부 "가장 어둡다"로 같게 친다. */
    public static final int DARK_CAP = SPACING * 2;
    /** 등에서 길을 따라 닿지 못하는 칸(등이 아예 없거나 도로망이 끊긴 조각) — 가장 어둡다. */
    private static final int UNREACHED = DARK_CAP * 4;
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
        java.util.Arrays.fill(REJ, 0);

        // 후보 = 중심선 칸. 자리값 = <b>유형가중 × 어둠</b>(클래스 주석 참조).
        java.util.Map<Long, Integer> lit = lampDist(roads, lamps);
        List<double[]> cand = new ArrayList<>(); // {−점수, −실제어둠, x, z}
        for (int[] c : roads.all()) {
            int x = c[0];
            int z = c[1];
            int deg = 0;
            for (int[] d : RoadPlanner.D4) {
                if (roads.has(x + d[0], z + d[1])) {
                    deg++;
                }
            }
            // 유형가중은 <b>납작하다</b>. 예전의 3.0/1.5/1.0 은 교차로가 직선을 세 배로 눌러,
            // 어둠을 곱해도 중심부가 계속 이겼다. 여기서 유형은 동률을 가르는 정도만 한다.
            double type = deg >= 3 ? 1.5 : (deg == 1 ? 1.2 : (deg == 2 ? 1.0 : 0.8));
            int walk = lit.getOrDefault(RoadStore.key(x, z), UNREACHED);
            cand.add(new double[] {-(type * Math.min(walk, DARK_CAP)), -walk, x, z});
        }
        // 2순위가 <b>실제</b> 어둠이다. 점수는 상한에서 잘리므로 39칸 떨어진 칸과 28칸 떨어진
        // 칸이 동률로 묶이는데, 그 동률을 좌표순으로 가르면 가장 어두운 자리가 좌표 운으로
        // 밀린다(실측: 상한만 두었을 때 최대 무등화 거리가 39칸에 머물렀고, 남은 어둠 28칸이
        // 전부 한 spur 끝에 몰려 있었다). 어차피 의미 없이 갈리던 자리를 목적에 맞게 가른다 —
        // 1순위가 그대로라 상한이 유형가중과 이루는 균형에는 영향이 없다.
        cand.sort(Comparator.<double[]>comparingDouble(a -> a[0]).thenComparingDouble(a -> a[1])
                .thenComparingDouble(a -> a[2]).thenComparingDouble(a -> a[3]));

        // 한 번의 훑기에 <b>정밀 검사</b>는 이만큼만 — 자리가 하나도 없는 날(간격이 다 찬 마을)에
        // 후보 전부를 14칸씩 읽으면 그 틱만 수만 회 블록 조회가 된다. 못 찾으면 다음 주기에 다시 본다.
        int budget = 300;
        for (double[] c : cand) {
            int x = (int) c[2];
            int z = (int) c[3];
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

    /**
     * <b>어둠 지도</b> — 중심선 칸마다 "길을 따라 걸어" 가장 가까운 등까지의 걸음 수.
     *
     * <p>직선거리가 아니라 <b>도로거리</b>인 것이 핵심이다. 그래야 양끝에 등이 있는 구간에서
     * 최댓값이 정확히 그 구간의 <b>한복판</b>에 생긴다("먼 길의 중간에도 하나"). 직선거리로
     * 재면 길과 무관한 들판 건너 등이 가깝다고 잡혀 긴 우회 구간이 밝은 것으로 오판된다.
     *
     * <p>등은 중심선에서 2칸 물러나 서므로, 등 주변 5×5 안의 중심선 칸을 거리 0으로 놓는다.
     * 거리는 <b>자르지 않고</b> 그대로 돌려준다 — 상한은 {@link #scan 자리값}을 매길 때만
     * 씌운다. 여기서 미리 잘라 버리면 39칸 떨어진 칸과 28칸 떨어진 칸이 구분 자체가 안 되어,
     * 동률을 실제 어둠으로 가르는 2순위 정렬이 무력해진다.
     *
     * @return 칸 → 걸음 수. 표에 없는 칸은 {@link #DARK_CAP} 이상(=최대 어둠)이다.
     */
    public static java.util.Map<Long, Integer> lampDist(RoadStore roads, LampStore lamps) {
        java.util.Map<Long, Integer> d = new java.util.HashMap<>();
        java.util.ArrayDeque<Long> q = new java.util.ArrayDeque<>();
        Set<Long> cells = roads.raw();
        for (BlockPos b : lamps.all()) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    long k = RoadStore.key(b.getX() + dx, b.getZ() + dz);
                    if (cells.contains(k) && d.putIfAbsent(k, 0) == null) {
                        q.add(k);
                    }
                }
            }
        }
        while (!q.isEmpty()) {
            long cur = q.poll();
            int nd = d.get(cur) + 1;
            int x = RoadStore.keyX(cur);
            int z = RoadStore.keyZ(cur);
            for (int[] dd : RoadPlanner.D4) {
                long n = RoadStore.key(x + dd[0], z + dd[1]);
                if (cells.contains(n) && d.putIfAbsent(n, nd) == null) {
                    q.add(n);
                }
            }
        }
        return d;
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
