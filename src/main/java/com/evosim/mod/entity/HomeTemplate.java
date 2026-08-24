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
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 거처 구조물 템플릿 — {@code data/evosim/structures/*.nbt}(바닐라 구조물 블록 형식)를 읽어
 * <b>앵커·정원 칸·건축 계획</b>으로 분해한다. 회전 4 × 좌우대칭 2 = 8배치를 바닐라 API가 처리한다.
 *
 * <h3>저작 규약(로드 시 검증한다 — 어기면 예외)</h3>
 * <ul>
 *   <li><b>금블록 1개</b> = 앵커({@code homePos}, 개체가 서는 빈 칸). 배치 시 공기로 치환.</li>
 *   <li>앵커 <b>바로 아래는 고체</b> — 딛는 바닥.</li>
 *   <li><b>스위트베리 6개</b> = 정원 칸. 배치 시 <b>심지 않는다</b>(자리만 기억) — 덤불은 가구가
 *       {@code BerryEconomy.BUSH_COST} 를 내고 심는 자산이라, 빌더가 공짜로 심으면 회계가 깨진다.</li>
 *   <li>정원 칸 <b>바로 아래는 흙</b>(grass_block/dirt) — "심을 땅"을 구조물이 보장한다.
 *       이 덕분에 지형이 어떻든 정원을 다른 자리로 옮기는 폴백이 필요 없다.</li>
 *   <li><b>문 1개 이상</b> — 없으면 드나들 수 없다.</li>
 * </ul>
 *
 * <p>좌표는 전부 <b>앵커 상대</b>다. 즉 {@code homePos} 에 그대로 더하면 월드 좌표가 된다.
 * 회전·대칭은 {@link StructurePlaceSettings} 에 실려 {@code filterBlocks} 가 변환된 좌표를
 * 돌려주므로, 정원 칸도 배치와 <b>같은 변환</b>을 받는다(대칭이면 정원이 반대쪽으로 간다).
 */
public final class HomeTemplate {

    /** 정원 칸 수 — 저작 규약. 8개 파일 전부 이 값이다. */
    public static final int GARDEN_CELLS = 6;

    /**
     * 좌우반전 축 — <b>{@code FRONT_BACK}(x 부호 반전)</b>이다. {@code LEFT_RIGHT} 가 아니다.
     *
     * <p>도면 규약이 <b>문은 (0,0,−z) 쪽, 정원은 +x 쪽</b>이고 정원의 z 는 문 축을 기준으로
     * 대칭이다. 그래서 {@code LEFT_RIGHT}(z 반전)를 걸면 정원 칸 집합 {−1,0,+1} 이 자기 자신으로
     * 돌아가 <b>정원이 제자리에 남고 문만 반대 면으로 간다</b> — 실측으로 8개 도면 중 7개의 정원
     * 중심이 (+5,0)→(+5,0) 로 불변이었다. {@code FRONT_BACK} 은 x 를 뒤집으므로 문은 그대로 두고
     * 정원이 +x→−x 로 건너간다. "좌우반전 = 베리 정원이 반대쪽에 나타난다"는 요구와 일치한다.
     */
    public static final Mirror MIRROR = Mirror.FRONT_BACK;

    /** 거처 등급 — 파일·수용 인원·건축비·유지비의 단일 출처. */
    public enum Tier {
        SMALL(4, 0.0, 0.05, "small1", "small2", "small3"),
        MIDDLE(6, 12.0, 0.15, "middle1", "middle2"),
        BIG(9, 30.0, 0.4, "big1", "big2"),
        MANSION(12, 70.0, 1.0, "mansion");

        /** 수용 인원 — 이 수를 넘으면 "협소"로 상위 등급 이사 트리거. */
        public final int capacity;
        /** 건축비(저장고 차감). 소형 0 — 지참금 14뿐인 신혼도 반드시 집을 가질 수 있어야 한다. */
        public final double buildCost;
        /** 하루 유지비 — 몰락 시 급격한 붕괴가 아니라 완만한 하향 압력. */
        public final double upkeep;
        /** 이 등급의 도면 이름들(랜덤 선택 대상). */
        public final String[] designs;

        Tier(int capacity, double buildCost, double upkeep, String... designs) {
            this.capacity = capacity;
            this.buildCost = buildCost;
            this.upkeep = upkeep;
            this.designs = designs;
        }
    }

    /** 배치 한 칸 — 앵커 상대 좌표 + 놓을 블록. */
    public record Placement(BlockPos rel, BlockState state) {
    }

    private final String design;
    private final Rotation rotation;
    private final Mirror mirror;
    private final List<Placement> plan;
    private final List<BlockPos> clear;
    private final List<BlockPos> gardenCells;
    private final List<BlockPos> footprint;
    private final double reach;

    private HomeTemplate(String design, Rotation rotation, Mirror mirror, List<Placement> plan,
                         List<BlockPos> clear, List<BlockPos> gardenCells,
                         List<BlockPos> footprint, double reach) {
        this.design = design;
        this.rotation = rotation;
        this.mirror = mirror;
        this.plan = plan;
        this.clear = clear;
        this.gardenCells = gardenCells;
        this.footprint = footprint;
        this.reach = reach;
    }

    public String design() {
        return design;
    }

    public Rotation rotation() {
        return rotation;
    }

    public Mirror mirror() {
        return mirror;
    }

    /** 건축 계획 — 앵커 상대 좌표. y 오름차순(낮은 층부터 쌓는 연출). 금블록·베리는 빠져 있다. */
    public List<Placement> plan() {
        return plan;
    }

    /**
     * 파낼 칸 — 앵커 상대. 도면의 <b>빈 칸</b>(실내·문간) + 앵커 + 정원 칸이다.
     *
     * <p>{@link #plan()} 에는 고체만 들어 있어서, 언덕·흙더미에 지으면 <b>실내에 흙이 남는다</b>.
     * 앵커 칸이 막히면 개체가 자기 집 중앙에 설 수 없고, 정원 칸이 막히면 덤불을 못 심는다.
     * 그래서 배치는 "파낸 뒤 쌓는" 두 단계다.
     */
    public List<BlockPos> clear() {
        return clear;
    }

    /** 정원 칸 — 앵커 상대. 심기·집계·수확이 모두 이 목록 하나를 본다(장부 대칭). */
    public List<BlockPos> gardenCells() {
        return gardenCells;
    }

    /** 점유 열(x·z) — 앵커 상대. 거처 간 간격·밭 회피 판정의 단일 출처. */
    public List<BlockPos> footprint() {
        return footprint;
    }

    /** 앵커에서 점유 열까지의 최대 평면 거리 — MIN_GAP·밭 회피 반경을 여기서 유도한다. */
    public double reach() {
        return reach;
    }

    /**
     * 도면을 회전·대칭 적용해 읽는다. 저작 규약 위반이면 {@link IllegalStateException}.
     *
     * @return 실패 시 empty — 도면 파일이 없을 때(데이터팩 미탑재)
     */
    // 해석 결과 캐시 — 도면 NBT 는 런타임에 바뀌지 않는데, 부지 후보 검증은 한 번의 신축에도
    // 열 번 가까이 도면을 묻는다. 매번 압축 NBT 를 다시 파싱하면 그게 곧 건축 지연이다.
    private static final java.util.Map<String, HomeTemplate> CACHE = new java.util.HashMap<>();

    public static Optional<HomeTemplate> load(ServerLevel level, String design,
                                              Rotation rotation, Mirror mirror) {
        String key = design + '|' + rotation + '|' + mirror;
        HomeTemplate hit = CACHE.get(key);
        if (hit != null) {
            return Optional.of(hit);
        }
        Optional<HomeTemplate> made = parse(level, design, rotation, mirror);
        made.ifPresent(t -> CACHE.put(key, t));
        return made;
    }

    private static Optional<HomeTemplate> parse(ServerLevel level, String design,
                                                Rotation rotation, Mirror mirror) {
        Optional<CompoundTag> raw = readNbt(level, design);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        CompoundTag tag = raw.get();

        // 팔레트(블록 종류 표) → 상태 배열. 회전·대칭은 상태에도 적용해야 문·계단이 같이 돈다.
        ListTag paletteTag = tag.contains("palette", Tag.TAG_LIST)
                ? tag.getList("palette", Tag.TAG_COMPOUND)
                : tag.getList("palettes", Tag.TAG_LIST).getCompound(0)
                        .getList("palette", Tag.TAG_COMPOUND);
        BlockState[] states = new BlockState[paletteTag.size()];
        var lookup = BuiltInRegistries.BLOCK.asLookup();
        for (int i = 0; i < paletteTag.size(); i++) {
            states[i] = NbtUtils.readBlockState(lookup, paletteTag.getCompound(i))
                    .mirror(mirror).rotate(rotation);
        }

        // 좌표 → 블록 색인. 좌표에도 <b>같은</b> 변환(대칭 후 회전, 피벗 원점)을 준다.
        // 바닐라 StructureTemplate.transform 과 동일한 식이라, 나중에 placeInWorld 로 바꿔도
        // 좌표가 어긋나지 않는다.
        ListTag blocksTag = tag.getList("blocks", Tag.TAG_COMPOUND);
        java.util.Map<BlockPos, BlockState> byPos = new java.util.HashMap<>();
        BlockPos anchor = null;
        List<BlockPos> bushes = new ArrayList<>();
        boolean hasDoor = false;
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag b = blocksTag.getCompound(i);
            ListTag p = b.getList("pos", Tag.TAG_INT);
            BlockState state = states[b.getInt("state")];
            BlockPos pos = transform(p.getInt(0), p.getInt(1), p.getInt(2), rotation, mirror);
            byPos.put(pos, state);
            if (state.is(Blocks.GOLD_BLOCK)) {
                if (anchor != null) {
                    throw new IllegalStateException(design + ": 앵커(금블록)가 2개 이상 — 1개여야 한다");
                }
                anchor = pos;
            } else if (state.is(Blocks.SWEET_BERRY_BUSH)) {
                bushes.add(pos);
            } else if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
                hasDoor = true;
            }
        }
        if (anchor == null) {
            throw new IllegalStateException(design + ": 앵커(금블록)가 없다 — 정확히 1개여야 한다");
        }
        if (bushes.size() != GARDEN_CELLS) {
            throw new IllegalStateException(design + ": 정원(베리)이 " + bushes.size()
                    + "개 — " + GARDEN_CELLS + "개여야 한다");
        }
        if (!hasDoor) {
            throw new IllegalStateException(design + ": 문이 없다 — 드나들 수 없는 거처");
        }

        // 앵커 상대로 옮긴다 — 이후 모든 좌표는 homePos 에 그대로 더하면 월드 좌표.
        java.util.Map<BlockPos, BlockState> byRel = new java.util.HashMap<>(byPos.size());
        for (var e : byPos.entrySet()) {
            byRel.put(e.getKey().subtract(anchor), e.getValue());
        }

        BlockState under = byRel.get(new BlockPos(0, -1, 0));
        if (under == null || under.isAir()) {
            throw new IllegalStateException(design + ": 앵커 바로 아래가 비어 있다 — 딛는 바닥이 없다");
        }

        List<BlockPos> garden = new ArrayList<>(GARDEN_CELLS);
        for (BlockPos bp : bushes) {
            BlockPos rel = bp.subtract(anchor);
            BlockState soil = byRel.get(rel.below());
            if (soil == null || !(soil.is(Blocks.GRASS_BLOCK) || soil.is(Blocks.DIRT)
                    || soil.is(Blocks.COARSE_DIRT) || soil.is(Blocks.PODZOL))) {
                throw new IllegalStateException(design + ": 정원 칸 " + rel
                        + " 아래가 흙이 아니다 — 심을 땅을 구조물이 보장해야 한다");
            }
            garden.add(rel);
        }

        // 건축 계획 — 앵커(공기로 둔다)와 베리(가구가 나중에 심는다)를 제외한 전부.
        List<Placement> pl = new ArrayList<>();
        java.util.Set<BlockPos> skip = new java.util.HashSet<>(garden);
        skip.add(BlockPos.ZERO);
        List<BlockPos> carve = new ArrayList<>(skip);
        java.util.Set<BlockPos> cols = new java.util.HashSet<>();
        double far = 0.0;
        for (var e : byRel.entrySet()) {
            BlockPos rel = e.getKey();
            if (!e.getValue().isAir()) {
                cols.add(new BlockPos(rel.getX(), 0, rel.getZ()));
                far = Math.max(far, Math.sqrt(rel.getX() * rel.getX() + rel.getZ() * rel.getZ()));
            }
            if (e.getValue().isAir()) {
                carve.add(rel); // 도면이 "여기는 비어 있어야 한다"고 말한 칸
                continue;
            }
            if (skip.contains(rel)) {
                continue;
            }
            pl.add(new Placement(rel, e.getValue()));
        }
        // 정원 칸도 점유 열이다 — 밭·이웃 거처가 침범하면 안 된다(덤불은 나중에 심기지만 자리는 이미 우리 것).
        for (BlockPos g : garden) {
            cols.add(new BlockPos(g.getX(), 0, g.getZ()));
            far = Math.max(far, Math.sqrt(g.getX() * g.getX() + g.getZ() * g.getZ()));
        }
        pl.sort(java.util.Comparator.comparingInt(p -> p.rel().getY())); // 낮은 층부터
        return Optional.of(new HomeTemplate(design, rotation, mirror, List.copyOf(pl),
                List.copyOf(carve), List.copyOf(garden), List.copyOf(cols), far));
    }

    /**
     * 도면 원본 NBT 를 읽는다. 바닐라 {@code StructureTemplate} 을 거치지 않는 이유:
     * 그 클래스의 블록 목록({@code palettes})은 private 이고, 공개된
     * {@code filterBlocks(pos, settings, block)} 는 <b>지정한 블록만</b> 돌려준다
     * (전체를 받는 공개 경로가 없다). 건축 계획은 전체 블록이 필요하므로 원본을 직접 읽는다.
     */
    private static Optional<CompoundTag> readNbt(ServerLevel level, String design) {
        ResourceLocation rl = new ResourceLocation(EvoSimMod.MODID,
                "structures/" + design + ".nbt");
        var res = level.getServer().getResourceManager().getResource(rl);
        if (res.isEmpty()) {
            return Optional.empty();
        }
        try (InputStream in = res.get().open()) {
            return Optional.of(NbtIo.readCompressed(in));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(design + ": 도면을 읽을 수 없다 — " + e.getMessage(), e);
        }
    }

    /**
     * 좌표 변환 — 바닐라 {@code StructureTemplate.transform} 과 같은 식(피벗 원점).
     * <b>대칭을 먼저, 회전을 나중에</b> 적용한다(블록 상태 변환 순서와 동일해야 문·계단이 맞는다).
     */
    private static BlockPos transform(int x, int y, int z, Rotation rot, Mirror mir) {
        if (mir == Mirror.LEFT_RIGHT) {
            z = -z;
        } else if (mir == Mirror.FRONT_BACK) {
            x = -x;
        }
        return switch (rot) {
            case CLOCKWISE_90 -> new BlockPos(-z, y, x);
            case CLOCKWISE_180 -> new BlockPos(-x, y, -z);
            case COUNTERCLOCKWISE_90 -> new BlockPos(z, y, -x);
            default -> new BlockPos(x, y, z);
        };
    }

    /** 실제 배치(검증·연출용) — 앵커 월드 좌표에 계획을 그대로 놓는다. 심는 것은 정원 시스템의 몫. */
    public void place(ServerLevel level, BlockPos anchorWorld) {
        for (BlockPos c : clear) {
            level.setBlock(anchorWorld.offset(c), Blocks.AIR.defaultBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                            | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
        }
        for (Placement p : plan) {
            // UPDATE_KNOWN_SHAPE(16) 필수 — 없으면 새 블록이 <b>이웃의 모양을 다시 계산</b>시킨다
            // (Level.setBlock 이 updateNeighbourShapes 를 호출). 실측: 계단 shape 가 도면과
            // 달라져 mansion 에서 1017칸 중 9칸이 어긋났다. 도면의 상태가 곧 정답이므로 재계산 금지.
            level.setBlock(anchorWorld.offset(p.rel()), p.state(),
                    net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                            | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
        }
    }
}
