package com.evosim.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>공공 시설</b> 구조물 템플릿 — 학교·교회. 거처({@link HomeTemplate})와 저작 규약이 다르다.
 *
 * <h3>왜 별도인가</h3>
 * 거처 규약은 <b>금블록 앵커 1개 + 스위트베리 6개</b>를 요구하는데, 저자가 올린 학교·교회 도면에는
 * 둘 다 없다(실측: {@code school.nbt} 21×21×18 · 문 1 · 종 1 · 책장 42 · 독서대 7 /
 * {@code church.nbt} 15×33×18 · 문 1 · 종 1 · 랜턴 10). 거처 로더에 억지로 태우려면 남의 도면을
 * 고쳐야 하는데, 그건 저자가 그린 건물을 우리 사정에 맞춰 변조하는 것이다.
 *
 * <h3>시설 저작 규약(로드 시 검증한다 — 어기면 예외)</h3>
 * <ul>
 *   <li><b>종(bell) 정확히 1개</b> = 앵커 기둥. 시설의 등기 좌표는 <b>종의 x·z 열에서 도면
 *       바닥면(y 최저층)</b>이다. 학교·교회 둘 다 종이 건물 한가운데 꼭대기에 하나 있어,
 *       따로 표지를 심지 않고도 중심이 정해진다.</li>
 *   <li><b>문 1개 이상</b> — 드나들 수 없는 시설은 시설이 아니다. 등하교·방문의 목적지는
 *       {@link #doorSteps()} 로 나간다.</li>
 * </ul>
 *
 * <p>좌표는 전부 <b>앵커 상대</b>다 — 등기 좌표에 그대로 더하면 월드 좌표다. 회전·대칭 변환은
 * 거처와 <b>같은 함수</b>({@link HomeTemplate#transform}·{@link HomeTemplate#fixStairMirror})를
 * 쓴다. 두 벌로 갈라지면 계단 모서리가 시설에서만 어긋나는 종류의 결함이 생긴다.
 */
public final class FacilityTemplate {

    /** 시설 종류 — 도면 이름과 규모의 단일 출처. */
    /**
     * 시설의 <b>갈래</b> — 크기가 달라도 같은 갈래면 같은 취급을 받는다.
     *
     * <p>교회는 큰 것과 작은 것이 있는데, 둘은 <b>용량만 다르고 하는 일은 같다</b>. 그래서
     * "이미 갖고 있는가"·"기존 것과 너무 가까운가" 같은 판정은 크기가 아니라 갈래로 해야
     * 한다 — 안 그러면 한 사람이 큰 교회와 작은 교회를 나란히 지을 수 있다.
     */
    public enum Group {
        SCHOOL("학교"),
        CHURCH("교회");

        public final String label;

        Group(String label) {
            this.label = label;
        }
    }

    public enum Kind {
        SCHOOL("school", "학교", Group.SCHOOL),
        CHURCH("church", "큰교회", Group.CHURCH),
        SMALL_CHURCH("small_church", "작은교회", Group.CHURCH);

        public final String design;
        public final String label;
        public final Group group;

        Kind(String design, String label, Group group) {
            this.design = design;
            this.label = label;
            this.group = group;
        }

        public static Kind of(String design) {
            for (Kind k : values()) {
                if (k.design.equals(design)) {
                    return k;
                }
            }
            return SCHOOL;
        }
    }

    /** 배치 한 칸 — 앵커 상대 좌표 + 놓을 상태. */
    public record Placement(BlockPos rel, BlockState state) {
    }

    private final Kind kind;
    private final List<Placement> plan;
    private final List<BlockPos> carve;
    private final List<BlockPos> groundCols;
    private final List<BlockPos> doorSteps;
    private final List<BlockPos> seats;
    private final double reach;
    private final double halfX;
    private final double halfZ;

    private FacilityTemplate(Kind kind, List<Placement> plan, List<BlockPos> carve,
                             List<BlockPos> groundCols, List<BlockPos> doorSteps,
                             List<BlockPos> seats, double reach, double halfX, double halfZ) {
        this.kind = kind;
        this.plan = plan;
        this.carve = carve;
        this.groundCols = groundCols;
        this.doorSteps = doorSteps;
        this.seats = seats;
        this.reach = reach;
        this.halfX = halfX;
        this.halfZ = halfZ;
    }

    public Kind kind() {
        return kind;
    }

    /** 놓을 블록 전부 — 낮은 층부터. */
    public List<Placement> plan() {
        return plan;
    }

    /** 도면이 "비어 있어야 한다"고 말한 칸 — 배치 전에 비운다. */
    public List<BlockPos> carve() {
        return carve;
    }

    /** 지면층 점유 열(x·z) — 밭·거처·길이 침범하면 안 되는 자리. */
    public List<BlockPos> groundCols() {
        return groundCols;
    }

    /**
     * <b>건물 밖 첫 칸</b> — 문에서 바깥으로 나가다 점유 열을 처음 벗어나는 자리. 길이 여기서
     * 끝난다(앞마당이 있는 도면이면 앞마당 바깥 끝이다 — {@code parse} 의 주석 참조).
     */
    public List<BlockPos> doorSteps() {
        return doorSteps;
    }

    /**
     * 실내에서 사람이 설 만한 자리 — 독서대(학교)·랜턴 아래(교회) 앞의 바닥 칸.
     *
     * <p>P5b 의 교사·학생 배치, P6 의 방문자 체류가 여기를 쓴다. 지금은 목록만 만들어 두고
     * 아무도 읽지 않는다 — 다만 <b>몇 자리인지</b>는 보고에 찍어, 자리가 0 인 도면을 세우고
     * 나중에야 알아채는 일이 없게 한다.
     */
    public List<BlockPos> seats() {
        return seats;
    }

    /** 앵커에서 가장 먼 점유 열까지의 수평 거리 — 부지 확보·회피 반경의 입력. */
    public double reach() {
        return reach;
    }

    /**
     * 축별 반폭 — 회피 판정에 {@link #reach()}(대각선) 대신 이것을 쓴다.
     *
     * <p>21×18 건물을 원으로 근사하면 반경이 13.8 이 되는데, 실제로 x 로 뻗은 것은 10.5,
     * z 로는 9 뿐이다. 그 차이가 집마다 3~5블록의 헛여유가 되어 마을 한복판에 학교가
     * 들어갈 구멍이 없어진다 — 실측: 이용자 무게중심에서 44~60블록 떨어진 자리에만 섰고
     * 소년 최근접거리 중앙이 54 로 통학 한계 48 을 넘었다.
     *
     * <p>거처(10×7)는 원 근사의 오차가 작아 문제가 없었다. 건물이 커지면서 드러난 것이다.
     */
    public double halfX() {
        return halfX;
    }

    public double halfZ() {
        return halfZ;
    }

    private static final Map<String, FacilityTemplate> CACHE = new HashMap<>();

    /** 도면·회전·대칭 조합 하나를 읽는다(캐시). */
    public static Optional<FacilityTemplate> of(ServerLevel level, Kind kind, byte rotation,
                                                boolean mirrored) {
        String key = kind.design + "/" + rotation + "/" + mirrored;
        FacilityTemplate hit = CACHE.get(key);
        if (hit != null) {
            return Optional.of(hit);
        }
        Optional<FacilityTemplate> made = parse(level, kind,
                Rotation.values()[Math.floorMod(rotation, 4)],
                mirrored ? HomeTemplate.MIRROR : Mirror.NONE);
        made.ifPresent(t -> CACHE.put(key, t));
        return made;
    }

    private static Optional<FacilityTemplate> parse(ServerLevel level, Kind kind,
                                                    Rotation rotation, Mirror mirror) {
        Optional<CompoundTag> raw = HomeTemplate.readNbt(level, kind.design);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        CompoundTag tag = raw.get();
        ListTag paletteTag = tag.contains("palette", Tag.TAG_LIST)
                ? tag.getList("palette", Tag.TAG_COMPOUND)
                : tag.getList("palettes", Tag.TAG_LIST).getCompound(0)
                        .getList("palette", Tag.TAG_COMPOUND);
        BlockState[] states = new BlockState[paletteTag.size()];
        var lookup = BuiltInRegistries.BLOCK.asLookup();
        for (int i = 0; i < paletteTag.size(); i++) {
            states[i] = HomeTemplate.fixStairMirror(
                    NbtUtils.readBlockState(lookup, paletteTag.getCompound(i)).mirror(mirror),
                    mirror).rotate(rotation);
        }

        ListTag blocksTag = tag.getList("blocks", Tag.TAG_COMPOUND);
        Map<BlockPos, BlockState> byPos = new HashMap<>();
        BlockPos bell = null;
        List<BlockPos> doors = new ArrayList<>();
        List<BlockPos> lecterns = new ArrayList<>();
        int minY = Integer.MAX_VALUE;
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag b = blocksTag.getCompound(i);
            ListTag p = b.getList("pos", Tag.TAG_INT);
            BlockState state = states[b.getInt("state")];
            BlockPos pos = HomeTemplate.transform(p.getInt(0), p.getInt(1), p.getInt(2),
                    rotation, mirror);
            byPos.put(pos, state);
            minY = Math.min(minY, pos.getY());
            if (state.is(Blocks.BELL)) {
                if (bell != null) {
                    throw new IllegalStateException(kind.design + ": 종이 2개 이상 — 1개여야 한다");
                }
                bell = pos;
            } else if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
                doors.add(pos);
            } else if (state.is(Blocks.LECTERN)) {
                lecterns.add(pos);
            }
        }
        if (bell == null) {
            throw new IllegalStateException(kind.design + ": 종이 없다 — 앵커 표지가 정확히 1개여야 한다");
        }
        if (doors.isEmpty()) {
            throw new IllegalStateException(kind.design + ": 문이 없다 — 드나들 수 없는 시설");
        }

        // 앵커 = 종의 x·z 열, 도면 바닥면의 <b>한 칸 위</b>(사람이 서는 높이).
        // 거처의 금블록 앵커와 같은 뜻이 되도록 맞춘다 — 등기 좌표 아래가 딛는 바닥이다.
        BlockPos anchor = new BlockPos(bell.getX(), minY + 1, bell.getZ());

        List<Placement> pl = new ArrayList<>();
        List<BlockPos> carve = new ArrayList<>();
        java.util.Set<BlockPos> cols = new java.util.HashSet<>();
        double far = 0.0;
        double hx = 0.0;
        double hz = 0.0;
        for (var e : byPos.entrySet()) {
            BlockPos rel = e.getKey().subtract(anchor);
            if (e.getValue().isAir()) {
                if (rel.getY() >= 0) {
                    carve.add(rel); // 처마 밑 여백까지 파면 건물 둘레에 해자가 생긴다(거처와 같은 이유)
                }
                continue;
            }
            cols.add(new BlockPos(rel.getX(), 0, rel.getZ()));
            far = Math.max(far, Math.sqrt(rel.getX() * rel.getX() + rel.getZ() * rel.getZ()));
            hx = Math.max(hx, Math.abs(rel.getX()));
            hz = Math.max(hz, Math.abs(rel.getZ()));
            pl.add(new Placement(rel, e.getValue()));
        }
        pl.sort(java.util.Comparator.comparingInt(q -> q.rel().getY()));

        // 문 바깥 — 문에서 건물 중심의 <b>반대 방향</b>으로, <b>점유 열을 벗어날 때까지</b> 나간다.
        //
        // 한 칸만 나가던 것이 학교에 길이 안 닿던 원인이었다. 거처는 문이 바깥벽에 붙어 있어
        // 한 칸이면 건물 밖이지만, 학교는 문(z −6) 앞에 제 앞마당이 z −10 까지 깔려 있다.
        // 그래서 "문 앞 한 칸"(z −7)은 <b>앞마당 한복판</b>이고 네 이웃이 모두 건물 제 바닥이라,
        // 길 찾기가 첫 칸도 못 떼고 매번 "경로 없음 — 고립 부지"로 끝났다(실측: 도면 기준
        // 진입칸 (0,−7) 의 이웃 열림 0/4). 부지를 어디에 잡든 결과가 같았으므로 이것은
        // 자리 문제가 아니라 <b>도면 해석의 문제</b>였다.
        //
        // 벗어날 때까지 나가면 길은 앞마당 <b>끝</b>에서 시작한다 — 건물이 제 앞마당을 갖고,
        // 마을 길이 그 앞마당에 와서 닿는 모양이다. 거처의 진입 칸 규칙과 뜻이 같고
        // (건물 밖 첫 칸), 다만 앞마당이 있는 도면에서도 성립하도록 일반화한 것뿐이다.
        List<BlockPos> steps = new ArrayList<>();
        for (BlockPos d : doors) {
            BlockPos rel = d.subtract(anchor);
            if (rel.getY() != 0) {
                continue; // 문은 두 칸이다 — 아래 칸만
            }
            int sx = Integer.signum(rel.getX());
            int sz = Integer.signum(rel.getZ());
            // 중심에서 더 멀어지는 축 하나로만 나간다(대각선으로 나가면 벽 모서리에 박힌다).
            if (Math.abs(rel.getX()) >= Math.abs(rel.getZ())) {
                sz = 0;
            } else {
                sx = 0;
            }
            // 상한은 도면 반경 + 2 — 도면이 아무리 커도 그 밖은 반드시 빈 열이라 반드시 끝난다.
            int cap = Math.max(Math.abs(rel.getX()), Math.abs(rel.getZ())) + (int) Math.max(hx, hz)
                    + 2;
            BlockPos step = null;
            for (int k = 1; k <= cap; k++) {
                BlockPos cand = new BlockPos(rel.getX() + sx * k, 0, rel.getZ() + sz * k);
                if (!cols.contains(cand)) {
                    step = cand;
                    break;
                }
            }
            if (step != null) {
                steps.add(step);
            }
        }

        // 자리 — 독서대 앞(학교). 교회는 독서대가 없으므로 문간 안쪽을 자리로 본다.
        List<BlockPos> seats = new ArrayList<>();
        for (BlockPos lc : lecterns) {
            BlockPos rel = lc.subtract(anchor);
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                BlockPos cand = new BlockPos(rel.getX() + d[0], rel.getY(), rel.getZ() + d[1]);
                BlockState here = byPos.get(cand.offset(anchor));
                BlockState below = byPos.get(cand.below().offset(anchor));
                if ((here == null || here.isAir()) && below != null && !below.isAir()) {
                    seats.add(cand);
                    break;
                }
            }
        }
        seats.sort(java.util.Comparator
                .comparingInt((BlockPos q) -> q.getX() * q.getX() + q.getZ() * q.getZ())
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));

        // <b>독서대가 없는 도면의 대체 자리</b> — 지붕 덮인 실내에서 설 수 있는 칸을 간격 2 로
        // 솎아 쓴다.
        //
        // 위 주석과 {@link #seats()} 문서가 "교회는 문간 안쪽을 자리로 본다"·"랜턴 아래(교회)"
        // 라고 적어 두었는데 <b>코드에는 그런 경로가 없었다</b>. 그래서 실측하면 교회 두 도면이
        // 모두 자리 0 이다 — 큰 교회는 독서대가 0개고, 작은 교회는 1개지만 그 둘레가 조건을
        // 못 맞춘다. 방문자가 갈 곳이 없다는 뜻이라 P6 를 붙이는 순간 터진다.
        //
        // 랜턴 아래로도 재 봤으나 큰 교회 4자리 · 작은 교회 2자리뿐이라 상한
        // ({@link Facilities#CHURCH_CAP} 12 · {@link Facilities#SMALL_CHURCH_CAP} 4)을 못 채운다.
        // 실내 칸을 솎으면 29 · 11 자리가 나와 넉넉하다. 간격 2 는 사람이 한 칸에 뭉치지 않게
        // 하는 최소값이다 — 학생 자리를 한 명씩 나눠 준 것과 같은 이유(앵커 하나로 보내면
        // 스무 명이 한 칸에서 밀치기만 한다).
        //
        // 독서대가 있는 도면(학교)은 위에서 이미 채워졌으므로 이 경로를 타지 않는다.
        if (seats.isEmpty()) {
            int top = Integer.MIN_VALUE;
            for (BlockPos p : byPos.keySet()) {
                top = Math.max(top, p.getY());
            }
            List<BlockPos> indoor = new ArrayList<>();
            for (var e : byPos.entrySet()) {
                BlockPos p = e.getKey();
                if (p.getY() != anchor.getY()) {
                    continue;
                }
                BlockState here = byPos.get(p);
                BlockState head = byPos.get(p.above());
                BlockState below = byPos.get(p.below());
                if ((here != null && !here.isAir()) || (head != null && !head.isAir())
                        || below == null || below.isAir()) {
                    continue; // 서 있을 수 없는 칸
                }
                boolean roofed = false;
                for (int y = p.getY() + 2; y <= top; y++) {
                    BlockState up = byPos.get(new BlockPos(p.getX(), y, p.getZ()));
                    if (up != null && !up.isAir()) {
                        roofed = true;
                        break;
                    }
                }
                if (roofed) {
                    indoor.add(p.subtract(anchor));
                }
            }
            indoor.sort(java.util.Comparator
                    .comparingInt((BlockPos q) -> q.getX() * q.getX() + q.getZ() * q.getZ())
                    .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
            for (BlockPos c : indoor) {
                boolean apart = true;
                for (BlockPos s : seats) {
                    if (Math.abs(c.getX() - s.getX()) < 2 && Math.abs(c.getZ() - s.getZ()) < 2) {
                        apart = false;
                        break;
                    }
                }
                if (apart) {
                    seats.add(c);
                }
            }
        }

        return Optional.of(new FacilityTemplate(kind, List.copyOf(pl), List.copyOf(carve),
                List.copyOf(cols), List.copyOf(steps), List.copyOf(seats), far, hx, hz));
    }
}
