package com.evosim.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 거처 도면의 <b>월드 좌표 해석</b> — 앵커(homePos) 하나로 건축 계획·파낼 칸·정원 칸·점유 열·
 * 문 방향·반경을 전부 돌려준다. 건축·평탄화·정원·밭 회피·철거가 <b>이 한 곳</b>만 본다.
 *
 * <p>두 종류의 거처를 같은 인터페이스로 감싼다.
 * <ul>
 *   <li><b>스키메틱</b>({@link HomeTemplate}) — 지금 짓는 모든 집. 회전 4 × 좌우반전 2.</li>
 *   <li><b>천막</b>({@link HomeStructure}) — 이 변경 이전 월드에 이미 서 있는 집. 기하가 전혀
 *       다르므로(정원 8칸·모닥불) 그대로 보존한다. 등기 도면이 {@link HomeStore#TENT} 이면
 *       이 경로다. 새로 짓지는 않는다.</li>
 * </ul>
 *
 * <p>이 분기를 여기 한 곳에 가둔 이유: 호출부가 "천막이냐 스키메틱이냐"를 물으면 그 질문이
 * 스무 곳으로 번지고, 한 곳만 빠뜨려도 옛 월드의 집이 조용히 깨진다.
 */
public final class HomeBlueprint {

    /** 배치 한 칸 — 월드 좌표 + 놓을 블록. */
    public record Placement(BlockPos pos, BlockState state) {
    }

    private static final Rotation[] ROTS = {
            Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180,
            Rotation.COUNTERCLOCKWISE_90};

    private final BlockPos home;
    private final String design;
    private final boolean legacy;
    private final List<Placement> plan;
    private final List<BlockPos> clear;
    private final List<BlockPos> garden;
    private final int gardenCap;
    private final List<BlockPos> footprint;
    private final List<BlockPos> interior;
    private final List<BlockPos> groundFootprint;
    private final List<BlockPos> doorSteps;
    private final Direction doorDir;
    private final double reach;

    private HomeBlueprint(BlockPos home, String design, boolean legacy, List<Placement> plan,
                          List<BlockPos> clear, List<BlockPos> garden, int gardenCap,
                          List<BlockPos> footprint, List<BlockPos> interior,
                          List<BlockPos> groundFootprint, List<BlockPos> doorSteps,
                          Direction doorDir, double reach) {
        this.home = home;
        this.design = design;
        this.legacy = legacy;
        this.plan = plan;
        this.clear = clear;
        this.garden = garden;
        this.gardenCap = gardenCap;
        this.footprint = footprint;
        this.interior = interior;
        this.groundFootprint = groundFootprint;
        this.doorSteps = doorSteps;
        this.doorDir = doorDir;
        this.reach = reach;
    }

    /** 등기부의 도면으로 해석. 등기가 없으면(구 월드·미등기) 천막으로 본다. */
    public static HomeBlueprint of(ServerLevel sl, BlockPos home) {
        HomeStore.Entry e = HomeStore.get(sl).entry(home);
        return e == null ? legacy(home, (byte) 0)
                : of(sl, home, e.design(), e.rotation(), e.mirrored());
    }

    /** 도면을 명시해 해석 — 아직 등기 전인 <b>후보 부지</b>를 평가할 때 쓴다. */
    public static HomeBlueprint of(ServerLevel sl, BlockPos home, String design,
                                   byte rot, boolean mirror) {
        if (HomeStore.TENT.equals(design)) {
            return legacy(home, rot);
        }
        var opt = HomeTemplate.load(sl, design, ROTS[rot & 3],
                mirror ? HomeTemplate.MIRROR : Mirror.NONE);
        if (opt.isEmpty()) {
            // 도면 파일이 사라진 경우(데이터팩 누락). 천막으로 떨어뜨리면 <b>엉뚱한 기하</b>로
            // 남의 집을 헐 수 있으므로, 아무것도 없는 빈 도면으로 만들어 무해하게 둔다.
            return new HomeBlueprint(home, design, false, List.of(), List.of(), List.of(), 0,
                    List.of(home), List.of(home), List.of(), List.of(), doorOf(rot), 0.0);
        }
        HomeTemplate t = opt.get();
        List<Placement> pl = new ArrayList<>(t.plan().size());
        for (HomeTemplate.Placement p : t.plan()) {
            pl.add(new Placement(home.offset(p.rel()), p.state()));
        }
        List<BlockPos> cl = new ArrayList<>(t.clear().size());
        for (BlockPos c : t.clear()) {
            cl.add(home.offset(c));
        }
        List<BlockPos> gd = new ArrayList<>(t.gardenCells().size());
        for (BlockPos g : t.gardenCells()) {
            gd.add(home.offset(g));
        }
        List<BlockPos> fp = new ArrayList<>(t.footprint().size());
        for (BlockPos f : t.footprint()) {
            fp.add(new BlockPos(home.getX() + f.getX(), home.getY(), home.getZ() + f.getZ()));
        }
        List<BlockPos> in = new ArrayList<>(t.interior().size());
        for (BlockPos c : t.interior()) {
            in.add(home.offset(c));
        }
        List<BlockPos> gc = new ArrayList<>(t.groundCols().size());
        for (BlockPos c : t.groundCols()) {
            gc.add(new BlockPos(home.getX() + c.getX(), home.getY(), home.getZ() + c.getZ()));
        }
        List<BlockPos> ds = new ArrayList<>(t.doorSteps().size());
        for (BlockPos c : t.doorSteps()) {
            ds.add(new BlockPos(home.getX() + c.getX(), home.getY(), home.getZ() + c.getZ()));
        }
        // 스키메틱은 칸 = 상한이다(도면이 정확히 그 수만큼 자리를 낸다).
        return new HomeBlueprint(home, design, false, pl, cl, gd, gd.size(), fp, in, gc, ds,
                doorOf(rot), t.reach());
    }

    /**
     * 문이 향하는 바깥 방향. 도면 규약은 <b>−z 가 바깥</b>(문이 −z 쪽 벽에 있다)이므로,
     * 회전 R 을 먹인 북쪽이 곧 문 방향이다. 좌우반전은 x 만 뒤집으므로 문 방향에 영향이 없다.
     */
    private static Direction doorOf(byte rot) {
        return ROTS[rot & 3].rotate(Direction.NORTH);
    }

    /** 천막(구 월드) 해석 — {@link HomeStructure} 기하를 그대로 감싼다. */
    private static HomeBlueprint legacy(BlockPos home, byte facing2d) {
        Direction f = Direction.from2DDataValue(facing2d);
        List<Placement> pl = new ArrayList<>();
        for (HomeStructure.Placement p : HomeStructure.plan(home, f)) {
            pl.add(new Placement(p.pos(), p.token() == HomeStructure.TOKEN_FENCE
                    ? Blocks.OAK_FENCE.defaultBlockState()
                    : Blocks.WHITE_WOOL.defaultBlockState()));
        }
        List<BlockPos> gd = HomeStructure.gardenCells(home, f);
        List<BlockPos> fp = HomeStructure.footprint(home, f);
        double far = 0.0;
        for (BlockPos p : fp) {
            double dx = p.getX() - home.getX();
            double dz = p.getZ() - home.getZ();
            far = Math.max(far, Math.sqrt(dx * dx + dz * dz));
        }
        // 천막은 실내가 열려 있어 파낼 칸이 따로 없다 — 앵커(눕는 자리)만 비워 둔다.
        //
        // 상한은 <b>칸 수와 다르다</b>. 천막이 훑는 칸은 24(기본 8 + 지형 폴백 16)인데 상한은
        // 8이다 — 폴백은 "기본 칸이 막혔을 때 대신 쓸 자리"이지 늘어난 정원이 아니다. 상한을
        // 칸 수로 잡으면 구 천막 가구의 정원이 8 → 24로 <b>세 배</b>가 되어 식량이 부풀고,
        // 그 위에 세워진 밭·지대·출산 회계가 전부 어긋난다.
        // 천막은 최하층이 없어 지면층 점유 열 = 발자국이고, 문 앞 계단도 없다.
        // 파낼 칸(clear)은 앵커 하나 — 천막은 실내가 열려 있어 파낼 것이 없다.
        // 그러나 <b>실내</b>는 벽 사이 칸 전부를 넘긴다: 이 목록이 가구원 잠자리 배정에
        // 쓰이는데 한 칸만 주면 전원이 같은 블록을 노려 취침이 안 켜진다(HomeStructure.interior).
        return new HomeBlueprint(home, HomeStore.TENT, true, pl, List.of(home), gd,
                HomeStructure.berryTiles(home, f).size(), fp,
                HomeStructure.interior(home, f), fp, List.of(), f, far);
    }

    /**
     * 도면의 반경 — 회전·좌우반전과 무관하다(회전은 x·z 를 맞바꾸고 반전은 부호만 뒤집으므로
     * 앵커로부터의 최대 평면 거리는 그대로다). 그래서 회전 0 하나만 재면 된다.
     */
    public static double reachOf(ServerLevel sl, String design) {
        return of(sl, BlockPos.ZERO, design, (byte) 0, false).reach();
    }

    /**
     * 이 도면의 <b>축별 반폭</b> {x, z} — 회피 판정이 {@link #reachOf}(대각선) 대신 쓴다.
     *
     * <p>대각선 반경은 사각형을 원으로 부풀린 값이라 축 방향으로 <b>헛여유</b>가 붙는다.
     * 시설 부지 탐색에서 이 과대평가가 실제로 자리를 없앴다 — 실측(집 24채 월드): 후보
     * 약 700개 중 <b>611개를 집이 막았고</b> 밭은 15, 물·낙차는 0 이었다. 학교 반폭 10 +
     * 집 대각반경 6 + 여유 2 = 17 을 모든 집에서 띄워야 하는데, 집끼리의 간격 자체가
     * 15~19 라 마을 안에 그만한 구멍이 없다.
     *
     * <p>도면은 회전해도 x·z 가 맞바뀔 뿐이라 <b>둘 중 큰 쪽</b>을 양축에 쓴다 — 회전을
     * 몰라도 안전하고, 회전별로 캐시를 나눌 필요가 없다.
     */
    public static double[] halfExtentsOf(ServerLevel sl, String design) {
        HomeBlueprint bp = of(sl, BlockPos.ZERO, design, (byte) 0, false);
        double hx = 0.0;
        double hz = 0.0;
        for (BlockPos c : bp.footprint()) {
            hx = Math.max(hx, Math.abs(c.getX()));
            hz = Math.max(hz, Math.abs(c.getZ()));
        }
        double m = Math.max(hx, hz);
        return new double[] {m, m};
    }

    /**
     * 도면 대비 <b>실제로 서 있는 비율</b>(0~1) — "이 집이 아직 있는가"의 관측값.
     *
     * <p>종전에는 모닥불 블록의 존재로 이 질문에 답했다. 모닥불이 사라진 지금은 도면 자체가
     * 기준이다. 부분 붕괴·지형 침식에도 연속적으로 반응해서, 있고/없고보다 정직한 신호다.
     */
    public double standingRatio(ServerLevel sl) {
        if (plan.isEmpty()) {
            return 0.0;
        }
        int hit = 0;
        for (Placement p : plan) {
            if (sameIgnoringShape(sl.getBlockState(p.pos()), p.state())) {
                hit++;
            }
        }
        return (double) hit / plan.size();
    }

    /**
     * 두 상태가 <b>모서리 모양을 빼면</b> 같은가.
     *
     * <p>계단의 {@code shape} 는 바닐라가 이웃을 보고 도출하는 값이라, 완성된 구조물 위에서
     * 다시 도출하면(=사용자가 손으로 지었을 때의 모양) 도면에 적힌 값과 달라질 수 있다.
     * 그 차이는 <b>결함이 아니라 정답</b>이므로 무결성 지표가 실패로 세면 안 된다. 반대로 블록
     * 종류가 다르거나 방향·반쪽이 다르면 그건 진짜 어긋난 것이라 그대로 잡힌다.
     */
    public static boolean sameIgnoringShape(BlockState a, BlockState b) {
        if (a == b) {
            return true;
        }
        if (a.getBlock() != b.getBlock()
                || !(a.getBlock() instanceof net.minecraft.world.level.block.StairBlock)) {
            return false;
        }
        var f = net.minecraft.world.level.block.StairBlock.FACING;
        var h = net.minecraft.world.level.block.StairBlock.HALF;
        return a.getValue(f) == b.getValue(f) && a.getValue(h) == b.getValue(h);
    }

    public BlockPos home() {
        return home;
    }

    public String design() {
        return design;
    }

    /** 천막(구 월드)인가 — 모닥불 등 옛 부속을 다뤄야 하는 소수 지점만 이걸 묻는다. */
    public boolean legacy() {
        return legacy;
    }

    /** 건축 계획 — 낮은 층부터. 앵커·정원 칸은 빠져 있다(비워 둬야 하는 자리). */
    public List<Placement> plan() {
        return plan;
    }

    /** 파낼 칸 — 실내·문간·앵커·정원 자리. 착공 시 한 번 비운다. */
    public List<BlockPos> clear() {
        return clear;
    }

    /** 정원 칸 — 심기·집계·수확·청소가 모두 이 목록 하나를 본다(장부 대칭). */
    public List<BlockPos> garden() {
        return garden;
    }

    /**
     * 정원 그루 상한 — <b>칸 수와 같지 않을 수 있다</b>. 천막은 24칸을 훑되 상한은 8이다
     * (나머지 16칸은 기본 칸이 지형에 막혔을 때 쓰는 대체지). 스키메틱은 칸 수 = 상한.
     */
    public int gardenCap() {
        return gardenCap;
    }

    /**
     * 실내에 설 수 있는 칸 — 앵커에 가까운 순. 가구원이 <b>각자 자리</b>를 갖는 근거다.
     * 천막(레거시)은 앵커 한 칸뿐이다(원래 그런 구조다).
     */
    public List<BlockPos> interior() {
        return interior;
    }

    /** 점유 열(x·z, y=home.y) — 평탄화·밭 회피 판정의 단일 출처. */
    public List<BlockPos> footprint() {
        return footprint;
    }

    /**
     * <b>지면층 점유 열</b> — 도면 최하층에 실제 블록이 있는 칸. 길이 절대 못 지나는 곳이다.
     * {@link #footprint()} 는 처마까지 포함하지만 처마 밑 지면은 비어 있어 길이 지나가도 된다.
     */
    public List<BlockPos> groundFootprint() {
        return groundFootprint;
    }

    /** <b>문 앞 계단</b> 칸 — 길은 여기를 건너뛰고 그 바로 앞에서 시작한다. */
    public List<BlockPos> doorSteps() {
        return doorSteps;
    }

    /** 문이 향하는 바깥 방향(천막은 모닥불 쪽). 취침 시 머리를 그 반대로 둔다. */
    public Direction doorDir() {
        return doorDir;
    }

    /** 앵커에서 점유 열까지의 최대 평면 거리 — 거처 간 간격을 여기서 유도한다. */
    public double reach() {
        return reach;
    }
}
