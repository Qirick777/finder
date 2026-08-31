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

    /** 확장 이력 상한(구획당) — GUI 타임라인·NBT 유계화. 초과 시 최고(最古) 항목부터 밀어냄. */
    public static final int MAX_EXPAND_LOG = 16;

    /** 밭 한 구획. tiles[i]=BlockPos.asLong, planted[i]=심은 gameTime(-1=미설치). ownerId 0 = 무주지. */
    public static final class Plot {
        public final long id;
        public final BlockPos anchor;
        public long ownerId;
        public long vacantSince = -1L; // 무주 시작 gameTime(-1=유주) — 만료 시 등록 소거
        public long[] tiles = new long[0];
        public long[] planted = new long[0];
        public double account = 0.0;
        public int blockedDays = 0; // 자금·노동 있는데 배치 0칸이던 연속 일수 — 1일이면 성숙 간주(공간 포화, 2배속)
        /**
         * <b>성장 방향</b> — bit0=+x 대신 −x, bit1=+z 대신 −z. 착공 때 앵커 둘레의 빈 공간을 재서
         * <b>한 번만</b> 정하고 그 뒤로는 바뀌지 않는다(포화 시 {@code turnDir} 로 한 번 튼다).
         *
         * <p>종전에는 방향을 <b>칸마다</b> 뒤집었다({@code FarmLayout.mirrors}). 이상 칸이 막히면
         * 앵커 반대편에 놓았는데, 폭 7까지 자란 구획에서 c=5 가 막히면 그 타일이 몸통에서 10칸
         * 떨어진 허공에 박힌다 — "한두 칸이 뚝 떨어져 있는" 모습의 정체다. 방향은 구획의 성질이지
         * 칸의 성질이 아니다.
         */
        public byte dir = 0;
        /** 이 방향으로 더는 못 자라 방향을 튼 적이 있나 — 무한 회전을 막는다. */
        public boolean turned = false;

        // ── 발자국(덩어리 도면) ──────────────────────────────────────────────
        /**
         * <b>지금 확보한 발자국</b> — 최소 모서리(fx, fz)와 크기(덩어리 수, 줄 수), 그리고 축.
         *
         * <p>밭은 이제 칸을 이어 붙여 자라지 않고 <b>발자국을 통째로</b> 넓힌다. 확보한 발자국의
         * 원목(테두리·길)은 즉시 깔리고 재배 칸만 노동에 따라 차오르므로, 밭은 언제 봐도 반듯한
         * 직사각형이고 안쪽 밀도만 변한다 — 계단·구멍·이웃과 붙음이 구성적으로 불가능해진다.
         *
         * <p>덩어리 축({@code bedAxisX})은 덩어리가 늘어나는 방향이다. 참이면 덩어리가 x 로,
         * 거짓이면 z 로 늘어난다. 착공 때 트인 쪽을 보고 한 번 정한다.
         *
         * <p>{@code beds == 0} 은 <b>구세계</b>(칸 수열로 자란 옛 구획)를 뜻한다. 그런 구획은
         * 발자국을 모르므로 타일 목록을 그대로 두고 더 자라지 않는다 — 옛 모양을 억지로
         * 새 도면에 끼워 맞추면 멀쩡한 밭을 부순다.
         */
        public int fx;
        public int fz;
        /**
         * 발자국이 앉은 <b>지면 높이</b>. 원목·재배 바닥은 baseY+1, 베리는 baseY+2 에 놓인다.
         *
         * <p>깔고 나면 지형 조회가 밭 표면을 돌려주므로 지면을 다시 알 수 없다. 착공 때 한 번
         * 적어 둔다. 발자국 전체가 평평해야 하므로 값 하나로 충분하다(분수와 같은 규칙).
         */
        public int baseY;
        public int beds;
        public int rows;
        public boolean bedAxisX = true;

        /**
         * <b>마지막으로 그린 테두리의 덤불 상자</b>(minX, minZ, maxX, maxZ) — 없으면 ringMinX &gt; ringMaxX.
         *
         * <p>밭이 자라면 테두리도 한 겹 바깥으로 물러나야 하는데, 옛 테두리를 지우지 않으면
         * 안쪽에 동심원처럼 겹겹이 남는다. 그렇다고 매번 넓은 범위를 훑어 지우면 <b>마을 길을
         * 같이 지울</b> 위험이 있다. 그래서 직전 상자를 기억해 두고 <b>옛 테두리 − 새 테두리</b>
         * 차집합만 정확히 되돌린다.
         */
        public int ringMinX = 0;
        public int ringMinZ = 0;
        public int ringMaxX = -1; // minX > maxX = "아직 그린 적 없음"
        public int ringMaxZ = -1;

        // ── 밭 원장(봉건 집중 P3) — 관측 전용 누계. 시뮬 결정에는 관여하지 않음(땅 문서 GUI 표시원). ──
        public long founderId;         // 최초 개간자 개체 id(상속·선점으로 ownerId가 바뀌어도 고정)
        public long foundedDay = -1L;  // 개간 게임일(-1=구세계 로드로 불명)
        public long tilesByFounder;    // 착공 시 최초 타일 수(9) — 부익부 대조 기준선
        public long tilesByOwner;      // 주인 자영 노동으로 추가된 타일 누계
        public long tilesByTenant;     // 소작 재투자로 추가된 타일 누계("소작이 밭을 키운다" 계량)
        public double totalYield;      // 누적 수확량(soft — L 정수성 무관 관측치)
        public double totalToOwner;    // 누적 주인 몫(지대 + 자영 수확)
        public double totalToTenant;   // 누적 소작 몫
        public int harvestCount;       // 수확 행동 횟수
        public long[] expandDay = new long[0];   // 확장 이력: 게임일
        public long[] expandBy = new long[0];    // 확장 이력: 기여자 개체 id
        public int[] expandTiles = new int[0];   // 확장 이력: 그 확장에서 더한 타일 수

        // ── 마름(클래스 시스템 v1.3) ──
        public long stewardId;          // 이 구획의 마름 개체 id(0=없음). 구획 E는 마름 능력 기준.
        public long stewardSince = -1L; // 임명 게임일(-1=없음) — 근속 수당 입력
        public boolean stewarded;       // 마름 운영 이력 — 공석 재임명 문턱 1(즉시 충원, 칭호 무붕괴)
        public double stewardDebt;      // 가문 편입 착공비 미상환분 — 밤 정산 때 영주→마름 이체(이월)

        // ── fee 분할(E11) — 지주 몫 초과분(누진분)의 잠금 축장. 밭 계정과 별도라 확장(growFarms)이
        //    건드리지 않는다. 밤 정산 때 정수 유닛만 지주 저장고로(L 정수성, 소수 이월). ──
        public double excessHoard = 0.0;

        Plot(long id, BlockPos anchor, long ownerId) {
            this.id = id;
            this.anchor = anchor;
            this.ownerId = ownerId;
            this.founderId = ownerId;
        }
    }

    /**
     * 확장 기록(P3) — 한 번의 확장(placed 타일)을 원장에 남긴다. 기여자별 자영/소작 누계를 갱신하고
     * 이력 링을 최근 {@link #MAX_EXPAND_LOG}건으로 유계화한다. 시뮬 결정과 무관(관측 전용).
     */
    public void recordExpand(Plot p, long contributorId, int placed, long day, boolean byTenant) {
        if (placed <= 0) {
            return;
        }
        if (byTenant) {
            p.tilesByTenant += placed;
        } else {
            p.tilesByOwner += placed;
        }
        int n = Math.min(p.expandDay.length + 1, MAX_EXPAND_LOG);
        int drop = p.expandDay.length + 1 - n; // 초과분(=1 또는 0)만큼 최고항 폐기
        long[] d = new long[n];
        long[] b = new long[n];
        int[] t = new int[n];
        System.arraycopy(p.expandDay, drop, d, 0, n - 1);
        System.arraycopy(p.expandBy, drop, b, 0, n - 1);
        System.arraycopy(p.expandTiles, drop, t, 0, n - 1);
        d[n - 1] = day;
        b[n - 1] = contributorId;
        t[n - 1] = placed;
        p.expandDay = d;
        p.expandBy = b;
        p.expandTiles = t;
        setDirty();
    }

    /** 수확 분배 기록(P3) — 관측 누계 갱신. totalYield == totalToOwner + totalToTenant 항등 유지. */
    public void recordHarvest(Plot p, double yield, double toOwner, double toTenant) {
        p.totalYield += yield;
        p.totalToOwner += toOwner;
        p.totalToTenant += toTenant;
        p.harvestCount++;
        setDirty();
    }

    /**
     * 위기 인출(E11 안전장치 ④) — 굶주리는 지주가 자기 밭 계정 식량을 <b>소지 식량으로 직접</b> 먹는
     * 비상 경로. 저장고를 우회하므로 귀가 지연(A-4)과 무관하게 현장에서 발동한다. 밭 계정(확장 재원)을
     * 우선 소진하고, 그래도 부족하면 초과분 축장(잠금)까지 헐어 생존을 잠금보다 앞세운다. 최대 want
     * 만큼 뽑아 실제 인출량을 반환(확장·정산보다 앞서 실행되어 확장이 생존 식량을 가로채지 못한다).
     */
    public double drainForOwner(long ownerId, double want) {
        if (want <= 0.0 || ownerId == 0L) {
            return 0.0;
        }
        double pulled = 0.0;
        for (Plot p : plots.values()) { // 1차: 밭 계정(확장 재원) 우선 소진
            if (p.ownerId != ownerId || pulled >= want) {
                continue;
            }
            double take = Math.min(p.account, want - pulled);
            if (take > 0.0) {
                p.account -= take;
                pulled += take;
            }
        }
        for (Plot p : plots.values()) { // 2차: 초과분 축장(잠금) — 생존이 잠금보다 우선
            if (p.ownerId != ownerId || pulled >= want) {
                continue;
            }
            double take = Math.min(p.excessHoard, want - pulled);
            if (take > 0.0) {
                p.excessHoard -= take;
                pulled += take;
            }
        }
        if (pulled > 0.0) {
            setDirty();
        }
        return pulled;
    }

    private final Map<Long, Plot> plots = new HashMap<>();
    private final java.util.HashSet<Long> tileIndex = new java.util.HashSet<>(); // pos.asLong — 무단 수확 가드용
    /**
     * <b>밭 몸통</b> 열 캐시 — 타일 + <b>그 사이 고랑</b>. {@link RoadStore#key} 형식(x/z만).
     *
     * <p>밭은 재배줄 + 한 칸 고랑 구조라(월드 깊이 = row×2), 타일만 보면 줄 사이가 뻥 뚫린
     * 것으로 읽힌다. 그 틈으로 길이 밭 한복판을 꿰뚫고, 신축 부지 판정도 그 틈을 빈 땅으로
     * 본다 — 실측 월드에서 <b>길 33칸이 두 구획을 관통했고, 집 한 채가 개간된 밭 위에 서서
     * 문이 밭으로 열렸다</b>. 그래서 "밭이 차지한 땅"의 단일 출처를 여기 둔다.
     *
     * <p>타일이 바뀔 때만 무효화하고 필요할 때 다시 만든다(하루 몇 번).
     */
    private transient java.util.HashSet<Long> bodyCache;
    /** 열 → 그 열을 몸통으로 가진 구획 id. {@link #bodyCache}와 같은 시점에 버린다. */
    private transient java.util.HashMap<Long, Long> bodyOwnerCache;
    private long nextId = 1;

    public static FarmStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FarmStore::load, FarmStore::new, KEY);
    }

    /** 이 주인의 최신(=id 최대) 구획 id — 직영지(주인이 직접 일구는 구획). 없으면 0. */
    public long newestOwnedPlot(long ownerId) {
        long best = 0L;
        for (Plot p : plots.values()) {
            if (p.ownerId == ownerId && p.id > best) {
                best = p.id;
            }
        }
        return best;
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
        tileIndex.add(pos.asLong());
        bodyCache = null;
        bodyOwnerCache = null;
        setDirty();
    }

    /** 이 좌표가 어느 밭의 타일인가 — 일반 채집 goal 의 무단 수확 가드(F: 남의 밭 보호). */
    public boolean isFarmTile(BlockPos pos) {
        return tileIndex.contains(pos.asLong());
    }

    /** 이 타일을 품은 구획(없으면 null) — 땅 문서 도구가 클릭 지점으로 구획을 찾는 데 사용. */
    public Plot plotAt(BlockPos pos) {
        long key = pos.asLong();
        for (Plot p : plots.values()) {
            for (long t : p.tiles) {
                if (t == key) {
                    return p;
                }
            }
        }
        return null;
    }

    /** 이 x/z 열에 밭 타일이 있는가(y 무시) — 신축 부지 검증용(천막 발자국은 y가 제각각). */
    /**
     * <b>구획 하나의 몸통</b> — 이 클래스가 정본이다.
     *
     * <p>종전에는 같은 알고리즘이 여기와 {@code MimicEntity.bodyOf} 두 곳에 복사돼 있었고,
     * 축 대응을 한쪽에만 넣은 탓에 전치 구획의 고랑이 도로 장애물에서 빠졌다. 복사본을 없애
     * 같은 실수가 다시 나지 않게 한다.
     */
    public static java.util.Set<Long> bodyOf(Plot p) {
        if (p.beds > 0) {
            // 발자국을 확보한 구획은 <b>사각형 그대로</b>가 몸통이다. 아직 안 심은 재배 칸도
            // 몸통이다 — 그래야 길·집·이웃 밭이 내가 자랄 자리를 침범하지 않는다(예약의 효과).
            java.util.HashSet<Long> out = new java.util.HashSet<>();
            int[] fp = com.evosim.core.FarmLayout.footprint(p.beds, p.rows);
            int w = p.bedAxisX ? fp[0] : fp[1];
            int h = p.bedAxisX ? fp[1] : fp[0];
            for (int dx = 0; dx < w; dx++) {
                for (int dz = 0; dz < h; dz++) {
                    out.add(RoadStore.key(p.fx + dx, p.fz + dz));
                }
            }
            return out;
        }
        boolean turnedAxis = (p.dir & 4) != 0;
        java.util.HashMap<Integer, int[]> span = new java.util.HashMap<>(); // 줄 → [최소, 최대]
        for (long l : p.tiles) {
            BlockPos t = BlockPos.of(l);
            int key = turnedAxis ? t.getZ() : t.getX();
            int val = turnedAxis ? t.getX() : t.getZ();
            span.compute(key, (k, v) -> v == null
                    ? new int[] {val, val}
                    : new int[] {Math.min(v[0], val), Math.max(v[1], val)});
        }
        java.util.HashSet<Long> out = new java.util.HashSet<>();
        for (var e : span.entrySet()) {
            for (int v = e.getValue()[0]; v <= e.getValue()[1]; v++) {
                out.add(turnedAxis ? RoadStore.key(v, e.getKey())
                        : RoadStore.key(e.getKey(), v));
            }
        }
        return out;
    }

    /**
     * <b>밭 몸통</b> 열 전체 — 타일과 <b>그 사이 고랑</b>. 길·신축 부지 판정의 단일 출처.
     *
     * <p>구획마다 재배줄을 따라 최소~최대를 메운다. 경계 상자로 메우면 성긴 구획이 주변 빈
     * 땅까지 통째로 삼키므로(실측: 타일 44개인데 상자는 299칸) 줄 단위로만 채운다.
     *
     * <p>메우는 축은 구획의 방향에 딸린다({@link Plot#dir} 비트2 = 전치). 재배줄이 동–서면
     * x 열마다 z 를 채우고, 전치된 구획은 z 줄마다 x 를 채운다. 축을 안 맞추면 전치 구획의
     * <b>고랑이 몸통에서 빠져</b> 길이 밭 한복판을 관통할 수 있다.
     */
    public java.util.Set<Long> bodyColumns() {
        if (bodyCache != null) {
            return bodyCache;
        }
        java.util.HashSet<Long> out = new java.util.HashSet<>();
        for (Plot p : plots.values()) {
            out.addAll(bodyOf(p));
        }
        bodyCache = out;
        return out;
    }

    /**
     * <b>다른 구획</b>의 몸통이 이 열에서 r칸 이내인가 — 구획 사이에 빈 띠를 남기는 판정.
     *
     * <p>착공 부지({@code findFarmSite})는 <b>앵커</b>끼리 20블록을 띄우지만, 구획은 착공 뒤에도
     * 자란다. 9타일로 시작한 밭이 40타일이 되면 앵커에서 열 방향으로 10칸 넘게 뻗으므로 앵커
     * 간격 20은 <b>몸통</b> 간격을 아무것도 보장하지 않는다. 실측(런: 21구획)에서 구획 간
     * 최소거리가 1.0까지 붙어 두 밭이 공중에서 한 덩어리로 보였다 — 각 구획이 저마다는 반듯한데
     * 사이 테두리가 끊겨 찌그러져 보이던 정체다.
     *
     * <p>그래서 <b>타일을 놓는 시점</b>에 남의 몸통과의 거리를 묻는다. 자기 몸통은 제외 —
     * 자기 자신에게서 물러설 이유는 없다. 이미 붙어 있는 옛 타일은 건드리지 않는다(소급 없음):
     * 새로 자라는 방향만 막으면 구획은 다른 쪽으로 뻗는다.
     */
    public boolean nearOtherBody(long selfPlotId, int x, int z, int r) {
        if (bodyOwnerCache == null) {
            java.util.HashMap<Long, Long> out = new java.util.HashMap<>();
            for (Plot p : plots.values()) {
                for (long c : bodyOf(p)) {
                    out.put(c, p.id);
                }
            }
            bodyOwnerCache = out;
        }
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                Long owner = bodyOwnerCache.get(RoadStore.key(x + dx, z + dz));
                if (owner != null && owner != selfPlotId) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 이 열이 밭 몸통(타일 또는 고랑)인가. */
    public boolean isFarmBody(int x, int z) {
        return bodyColumns().contains(RoadStore.key(x, z));
    }

    /** 밭 몸통에서 r칸 이내인가 — 길을 넓힐 때 여기로는 살을 안 찌운다. */
    public boolean nearBody(int x, int z, int r) {
        java.util.Set<Long> b = bodyColumns();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (b.contains(RoadStore.key(x + dx, z + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isFarmColumn(int x, int z) {
        for (long l : tileIndex) {
            BlockPos p = BlockPos.of(l);
            if (p.getX() == x && p.getZ() == z) {
                return true;
            }
        }
        return false;
    }

    /** 타일 1칸 소거(죽은 타일 정비 — 구조물에 깔려 복구 불능인 칸). 장부-실물 일치 복원. */
    public void removeTile(Plot p, int i) {
        if (i < 0 || i >= p.tiles.length) {
            return;
        }
        tileIndex.remove(p.tiles[i]);
        bodyCache = null;
        bodyOwnerCache = null;
        long[] t = new long[p.tiles.length - 1];
        long[] g = new long[p.planted.length - 1];
        System.arraycopy(p.tiles, 0, t, 0, i);
        System.arraycopy(p.tiles, i + 1, t, i, p.tiles.length - i - 1);
        System.arraycopy(p.planted, 0, g, 0, i);
        System.arraycopy(p.planted, i + 1, g, i, p.planted.length - i - 1);
        p.tiles = t;
        p.planted = g;
        setDirty();
    }

    /** 이 개체가 밭을 하나라도 소유하는가 — 조기 종료(ownedCount 전수보다 싸다, goal 캐시 갱신용). */
    public boolean owns(long ownerId) {
        if (ownerId == 0L) {
            return false;
        }
        for (Plot p2 : plots.values()) {
            if (p2.ownerId == ownerId) {
                return true;
            }
        }
        return false;
    }

    /** 총소유타일(전 구획 합) — 관리 효율 E의 분모(총량 기준 감쇠). */
    public int ownedTiles(long ownerId) {
        int n = 0;
        for (Plot p2 : plots.values()) {
            if (p2.ownerId == ownerId) {
                n += p2.tiles.length;
            }
        }
        return n;
    }

    /** 이 개체가 마름으로 있는 구획 id(첫 건) — 1구획 1마름·1인 1직 원칙. 없으면 0. */
    public long stewardOf(long id) {
        if (id == 0L) {
            return 0L;
        }
        for (Plot p : plots.values()) {
            if (p.stewardId == id) {
                return p.id;
            }
        }
        return 0L;
    }

    /** 이 소유자의 마름 수(위임 구획 수) — 클래스 판정 입력(지주=1·영주=1+구획2). */
    public int stewardCount(long ownerId) {
        int n = 0;
        for (Plot p : plots.values()) {
            if (p.ownerId == ownerId && p.stewardId != 0L) {
                n++;
            }
        }
        return n;
    }

    /** 무마름 구획 타일 합 — 지주 본인 관리 부담(E 분모). 위임분 제외(이중 페널티 방지, v1.3 P3). */
    public int unstewardedTiles(long ownerId) {
        int n = 0;
        for (Plot p : plots.values()) {
            if (p.ownerId == ownerId && p.stewardId == 0L) {
                n += p.tiles.length;
            }
        }
        return n;
    }

    /**
     * 클래스 판정(v1.3) — 파생값(저장 없음): 마름 / 영주(마름1+·구획2+) / 지주(마름1·구획1) /
     * 농장주(상시 소작 1+) / 농부(소작 0) / ""(무산). 칭호는 로그·감사·명령 표기에 쓴다.
     */
    public String classOf(ServerLevel level, long id) {
        if (stewardOf(id) != 0L) {
            return "마름";
        }
        int owned = ownedCount(id);
        if (owned == 0) {
            return "";
        }
        int stw = stewardCount(id);
        if (stw >= 1) {
            return owned >= 2 ? "영주" : "지주";
        }
        for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null && e.getTenantFarm() != 0L)) {
            Plot p = plots.get(m.getTenantFarm());
            if (p != null && p.ownerId == id) {
                return "농장주";
            }
        }
        return "농부";
    }

    /**
     * 마름 임명 — stewardId·임명일·이력 플래그 기록, 소작석에서 해방(마름은 소작 수에 계상 안 함).
     * how = "마름임명"(케이스 1·2) / "마름승계"(사망·사임 충원) / "마름편입"(케이스 3 가문 귀속).
     */
    public void appointSteward(ServerLevel level, Plot p, MimicEntity cand, String how) {
        p.stewardId = cand.getIndividual().id();
        p.stewardSince = com.evosim.mod.entity.SimTime.tick(level) / 24000L;
        p.stewarded = true;
        if (cand.getTenantFarm() != 0L) {
            cand.setTenant(0L, 0);
        }
        setDirty();
        com.evosim.mod.log.SimEvents.event(cand, how, String.format(
                "구획 %d 마름 ⟨마름⟩ — 관리 g%d·소유주 클래스 %s", p.id,
                com.evosim.core.Multipliers.manageAbilityGrade(cand.getIndividual()),
                classOf(level, p.ownerId)));
    }

    /**
     * 마름 후보 선발(이 구획의 상시 소작 중) — 관리 g 최고, <b>비야망가만</b>(이탈 방지 ①: 고용
     * 마름 한정 필터. 가문 편입은 이 경로를 타지 않는다). 동률은 근속(streak) 큰 순 → id 낮은 순.
     */
    public MimicEntity successorFor(ServerLevel level, Plot p) {
        return bestCandidate(level, m -> m.getTenantFarm() == p.id, p.tiles.length);
    }

    /** 영지 전체 상시 소작 중 후보(케이스 2 하청 개간의 신임 마름 선발). */
    public MimicEntity estateCandidate(ServerLevel level, long ownerId) {
        // 부담은 이 영지에서 가장 큰 구획으로 잡는다 — 신임 마름이 맡을 자리의 상한.
        int load = 0;
        for (Plot t : plots.values()) {
            if (t.ownerId == ownerId) {
                load = Math.max(load, t.tiles.length);
            }
        }
        final int fload = load;
        return bestCandidate(level, m -> {
            Plot t = plots.get(m.getTenantFarm());
            return t != null && t.ownerId == ownerId;
        }, fload);
    }


    /**
     * 구획 관리 효율 E (회차 S2 — 관리 바닥값). 마름 밭은 <b>max(마름 E, 지주 재흡수 E)</b>:
     * 지주의 오버사이트가 바닥이라 무능한 마름을 조기 임명해도 밭이 붕괴하지 않는다(리처드/킴벌리
     * 결함 근본 해소 — 게이트 대신 바닥값). 지주가 여러 밭으로 관리캡(133)을 초과해 재흡수 E가
     * 떨어지면 전담 마름의 E가 바닥을 넘어 <b>캡 돌파</b> — 조기 지위 부여와 후반 캡 돌파를 동시
     * 만족. 무마름 밭은 지주의 무마름 타일 합 기준(위임분 제외 — 이중 페널티 방지).
     */
    public double plotEfficiency(ServerLevel level, Plot p) {
        return plotEfficiency(scanOnce(level), p);
    }

    /**
     * <b>한 번의 순회로 모은 인구 색인</b> — 지주·마름 조회와 구획별 소작 수를 한꺼번에 담는다.
     *
     * <p>{@link #plotEfficiency}는 수확에 성공할 때마다 불리는데(<code>MimicFarmGoal</code>),
     * 종전에는 같은 순간의 같은 데이터를 <b>구획 수 + 3</b>번 나눠 읽었다 — 지주 1, 마름 1,
     * 이 구획 소작 1, 그리고 {@link #workedUnstewarded}가 구획마다 다시 1씩. 구획이 늘수록
     * 수확 한 번의 비용이 함께 늘어, 밭이 무한히 커진다는 규칙4·5와 정확히 같은 축으로 자랐다.
     *
     * <p>읽는 <b>시점과 내용은 그대로</b>다. 캐시가 아니라 한 번에 읽는 것이므로 낡은 값을 쓸
     * 여지가 없다. 술어도 같고, 구획 id 는 1부터 발급되므로(nextId=1) 소작 미배정(0) 버킷이
     * 구획 id 와 겹치지 않는다.
     */
    private static final class Census {
        final java.util.HashMap<Long, MimicEntity> byId = new java.util.HashMap<>();
        final java.util.HashMap<Long, Integer> tenants = new java.util.HashMap<>();

        MimicEntity find(long id) {
            return id == 0L ? null : byId.get(id);
        }

        int tenantsOn(long plotId) {
            return tenants.getOrDefault(plotId, 0);
        }
    }

    private Census scanOnce(ServerLevel level) {
        Census c = new Census();
        for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null)) {
            c.byId.putIfAbsent(m.getIndividual().id(), m); // 종전 findEntity 와 같은 "첫 일치"
            c.tenants.merge(m.getTenantFarm(), 1, Integer::sum);
        }
        return c;
    }

    /**
     * <b>수확 한 번에 필요한 구획 맥락</b> — 관리 효율 E 와 <b>마름의 채집 배율</b>.
     *
     * <p>둘을 따로 물으면 순회가 두 번이다. {@code plotEfficiency} 주석이 이미 경고한 그
     * 축(수확 한 번의 비용이 구획 수에 비례해 자란다)이라, 한 번에 읽어 같이 돌려준다.
     */
    public static final class Hand {
        /** 관리 효율 E — 지주 몫에 곱해진다. */
        public final double efficiency;
        /** 마름의 채집 배율(마름 없으면 0) — 소작농 산출의 <b>바닥</b>. */
        public final double stewardForage;

        Hand(double efficiency, double stewardForage) {
            this.efficiency = efficiency;
            this.stewardForage = stewardForage;
        }
    }

    /**
     * <b>마름이 일솜씨를 퍼뜨린다</b> — 마름의 채집 배율이 그 구획 소작농의 <b>바닥</b>이 된다.
     *
     * <p>종전에는 마름이 "얼마나 넓게 감독하나"(관리 용량 → E)만 정하고 "얼마나 잘 시키나"는
     * 정하지 않았다. 소작농은 저마다 제 능력으로 땄으므로, 마름을 잘 뽑을 이유가 임금 말고는
     * 없었다. 이제 마름 하나가 그 밭 전체의 산출을 끌어올린다 — 조직이 생산한다는 봉건 명제가
     * 수치가 된다.
     *
     * <p><b>교체가 아니라 바닥</b>인 이유: 마름보다 잘하는 소작농은 제 능력을 유지해야 재능
     * 있는 평민의 상승 경로가 남는다. 그리고 임명이 누구에게도 손해가 아니므로("마름을 두면
     * 잘하는 소작이 손해" 같은) 뒤틀린 유인이 생기지 않는다.
     *
     * <p>무마름 구획에는 <b>지주 바닥을 두지 않는다</b>. 두면 마름을 임명할 이유가 사라진다.
     */
    public Hand handOf(ServerLevel level, Plot p) {
        Census c = scanOnce(level);
        double sf = 0.0;
        if (p.stewardId != 0L) {
            MimicEntity stw = c.find(p.stewardId);
            if (stw != null && stw.getIndividual() != null) {
                sf = com.evosim.core.FoodEconomy.forageYieldMult(stw.getIndividual());
            }
        }
        return new Hand(plotEfficiency(c, p), sf);
    }

    private double plotEfficiency(Census c, Plot p) {
        MimicEntity ownerEnt = c.find(p.ownerId);
        int worked = workedTiles(c, p);
        if (p.stewardId != 0L) {
            MimicEntity stw = c.find(p.stewardId);
            double stewardE = stw != null ? com.evosim.core.FarmEconomy.manageEfficiency(
                    stw.getIndividual(), worked) : 0.0;
            double ownerFloor = ownerEnt != null ? com.evosim.core.FarmEconomy.manageEfficiency(
                    ownerEnt.getIndividual(), workedUnstewarded(c, p.ownerId) + worked) : 0.0;
            double e = Math.max(stewardE, ownerFloor);
            return e > 0.0 ? e : 1.0; // 양쪽 미로드 — 무penalty 폴백
        }
        return ownerEnt != null ? com.evosim.core.FarmEconomy.manageEfficiency(
                ownerEnt.getIndividual(), workedUnstewarded(c, p.ownerId)) : 1.0;
    }


    /**
     * 실제로 <b>경작되는</b> 타일 = min(등록 타일, 하루 수확 용량 합). E 의 분모를 소유 타일에서
     * 이 값으로 바꾼 이유: 아무도 손대지 않는 땅은 <b>관리 부담이 아니다</b>. 실측(r2)에서 최대
     * 지주가 447타일을 쥐었는데 실제로 수확되는 건 160타일(지주 8 + 소작 19×8)뿐이었고, 나머지
     * 287타일까지 분모에 넣은 탓에 E=(133/447)²=0.088로 지주 수입이 소작보다 낮아지는 역전이
     * 났다. 놀고 있는 땅은 이미 개간비(타일당 1.0)를 헛되이 쓴 것으로 벌을 받으므로, 관리 감쇠까지
     * 이중으로 물릴 이유가 없다. 실경작 기준이면 E=(133/160)²=0.69로 회복된다.
     * 규칙5(자산 무한 누적) 정합: 소작이 늘면 실경작 타일도 늘어 E가 내려가지만, 마름을 더 두면
     * 구획별로 마름 용량이 적용돼(max(마름E, 지주E)) 캡을 넘는다 — <b>조직을 키우면 무한히 커진다</b>는
     * 봉건 서사가 그대로 엔진이 된다. 죽어 있던 마름 제도가 여기서 실제 기능을 얻는다.
     */
    private int workedTiles(Census c, Plot p) {
        int labor = com.evosim.core.FarmEconomy.C_BASE * (1 + c.tenantsOn(p.id));
        return Math.min(p.tiles.length, labor);
    }

    /** 무마름 구획들의 실경작 타일 합 — 지주가 직접 지는 관리 부담(위임분 제외). */
    private int workedUnstewarded(Census c, long ownerId) {
        int n = 0;
        for (Plot p : plots.values()) {
            if (p.ownerId == ownerId && p.stewardId == 0L) {
                n += workedTiles(c, p);
            }
        }
        return n;
    }

    /**
     * <b>마름 후보 고르기</b> — 그 사람이 마름일 때 이 구획이 실제로 내는 값으로 줄 세운다.
     *
     * <pre>점수 = tileYield(후보) × manageEfficiency(후보, 부담 타일)</pre>
     *
     * <p>종전에는 관리 등급만 봤다. 그런데 마름은 이제 채집 배율도 퍼뜨리므로(handOf), 두 축을
     * <b>따로</b> 보면 어느 쪽도 옳지 않다 — 채집만 보면 관리 용량이 낮은 자가 뽑혀 E 가 무너지고,
     * 관리만 보면 솜씨 좋은 자를 놓친다. 위 곱은 휴리스틱이 아니라 <b>결과 그 자체</b>다.
     *
     * <p><b>야망가 제외를 없앤다.</b> 엘리트는 야망가라, 종전 규칙에서는 최고 인재가 아예 후보에
     * 들지 못했다 — 마름 제도가 이류만 뽑도록 설계돼 있던 셈이다. 이탈은 임금(근속 가산)과 착공
     * 마찰({@code STEWARD_FOUND_RESERVE_MULT})이 맡는다. 그리고 야망가 마름이 결국 돈을 모아
     * 제 밭을 열고 떠난다면 그것은 이탈이 아니라 <b>상승</b>이다 — 자영농이 생기는 경로다.
     *
     * @param load 이 자리가 질 관리 부담(타일). E 는 부담이 클수록 떨어지므로 순위가 달라진다.
     */
    private MimicEntity bestCandidate(ServerLevel level,
            java.util.function.Predicate<MimicEntity> pool, int load) {
        MimicEntity best = null;
        double bv = -1.0;
        int bs = -1;
        for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null && e.getTenantFarm() != 0L)) {
            if (!pool.test(m) || ownedCount(m.getIndividual().id()) > 0) {
                continue; // 소유자 제외(겸직 금지)
            }
            double v = com.evosim.core.FarmEconomy.tileYield(m.getIndividual())
                    * com.evosim.core.FarmEconomy.manageEfficiency(m.getIndividual(), load);
            int s = m.getTenantStreak();
            boolean better = best == null || v > bv + 1e-9
                    || (Math.abs(v - bv) <= 1e-9 && (s > bs
                            || (s == bs && m.getIndividual().id() < best.getIndividual().id())));
            if (better) {
                best = m;
                bv = v;
                bs = s;
            }
        }
        return best;
    }

    /**
     * 마름직 소거(사망·사임·소유 전환) — <b>같은 틱 승계</b>(v1.1): 그 구획 상시 중 최적 후보를
     * 즉시 임명해 칭호(지주·영주)가 무너지지 않는다. 후보가 없으면 공석(stewarded 이력 유지 —
     * 다음 상시 채용자가 문턱 없이 임명된다, assignDawn의 재임명 문턱 1).
     */
    public void stewardGone(ServerLevel level, long id, String reason) {
        if (id == 0L) {
            return;
        }
        for (Plot p : plots.values()) {
            if (p.stewardId != id) {
                continue;
            }
            p.stewardId = 0L;
            p.stewardSince = -1L;
            setDirty();
            MimicEntity next = successorFor(level, p);
            if (next != null) {
                appointSteward(level, p, next, "마름승계"); // 즉시 승계(붕괴는 plotEfficiency 바닥값이 방지)
            } else {
                com.evosim.mod.log.SimEvents.note(level, "마름공석", String.format(
                        "구획 %d — %s, 후계 상시 없음(지주 직영 복귀)", p.id, reason));
            }
        }
    }

    /** 소유 구획 수(신규 밭 체증 비용 입력). */
    public int ownedCount(long ownerId) {
        int n = 0;
        for (Plot p2 : plots.values()) {
            if (p2.ownerId == ownerId) {
                n++;
            }
        }
        return n;
    }

    /**
     * 상속 (설계 19) — 사망 소유자의 전 구획을 장자(원장 bornDay 최소 생존 자식, 원장 없으면
     * 최후순위) → 배우자 → 무주지 순으로 승계. 상시 소작 관계는 밭(plotId)에 붙어 있어 자동
     * 승계되고, 상속인 본인이 그 밭의 소작이었다면 해소(자기 소작 역설 — 계획 허점 6).
     */
    /**
     * 상속인 선정 (봉건 집중 P3) — <b>장남(생존 아들 중 bornDay 최소) → 없으면 장녀 → 배우자</b>.
     * 밭·식량 상속이 같은 상속인을 쓰도록 공용화. 원장 없는 무대 개체는 최후순위(bornDay MAX).
     */
    public static MimicEntity selectHeir(ServerLevel level, long deadId, long spouseId) {
        FamilyLedger ledger = FamilyLedger.get(level);
        FarmStore fs = get(level);
        MimicEntity ambSteward = null; // v1.3 상속 순위 1: 야망가 마름 자식 — 야망을 왕좌 경쟁으로
        MimicEntity son = null;
        MimicEntity daughter = null;
        long ambBorn = Long.MAX_VALUE;
        long sonBorn = Long.MAX_VALUE;
        long dauBorn = Long.MAX_VALUE;
        for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null)) {
            var ind = m.getIndividual();
            if (ind.parentAId() != deadId && ind.parentBId() != deadId) {
                continue;
            }
            FamilyLedger.Rec r = ledger.get(ind.id());
            long born = r == null ? Long.MAX_VALUE : r.bornDay;
            if (com.evosim.core.ExpressionResolver.isExpressed(ind, com.evosim.core.Trait.AMBITIOUS)
                    && fs.stewardOf(ind.id()) != 0L
                    && (ambSteward == null || born < ambBorn)) {
                ambBorn = born;
                ambSteward = m;
            }
            if (!m.isFemale()) {
                if (son == null || born < sonBorn) { // 최초 후보는 무조건(bornDay MAX 동률 방어)
                    sonBorn = born;
                    son = m;
                }
            } else if (daughter == null || born < dauBorn) {
                dauBorn = born;
                daughter = m;
            }
        }
        // 순위(v1.3): 야망가 마름 자식 > 장남 > 장녀 > 배우자 — 가문 편입으로 독립이 막힌 야망가의
        // 야망을 "영지 전체 승계"로 흡수(무능 상속인 리스크 완화 겸용 — 마름 경력 = 관리 검증).
        MimicEntity heir = ambSteward != null ? ambSteward : (son != null ? son : daughter);
        if (heir == null && spouseId != 0L) {
            for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                    e -> e.isAlive() && e.getIndividual() != null
                            && e.getIndividual().id() == spouseId)) {
                heir = m;
            }
        }
        return heir;
    }

    public void inherit(ServerLevel level, long deadId, long spouseId) {
        inheritTo(level, selectHeir(level, deadId, spouseId), deadId);
    }

    /**
     * 밭 상속 적용 — 사전 포착한 상속인(heir)에게 사망자 전 구획을 넘긴다. 상속인은 사망 콜백
     * <b>이전</b>에 조회해야 한다(제거 도중 getEntities가 자식을 놓치는 잠복 버그 — heir null → 무주지화).
     */
    public void inheritTo(ServerLevel level, MimicEntity heir, long deadId) {
        java.util.List<Plot> owned = new java.util.ArrayList<>();
        for (Plot p : plots.values()) {
            if (p.ownerId == deadId) {
                owned.add(p);
            }
        }
        if (owned.isEmpty()) {
            return;
        }
        for (Plot p : owned) {
            if (heir != null) {
                p.ownerId = heir.getIndividual().id();
                p.vacantSince = -1L;
                if (heir.getTenantFarm() == p.id) {
                    heir.setTenant(0L, 0); // 자기 밭의 소작이 될 수 없음 — 소유 흡수
                }
                com.evosim.mod.log.SimEvents.event(heir, "밭상속", String.format(
                        "구획 %d 승계(%d타일) — 소작 관계는 밭에 붙어 유지", p.id, p.tiles.length));
            } else {
                p.ownerId = 0L;
                p.vacantSince = com.evosim.mod.entity.SimTime.tick(level); // 무주지 — 선점 대기, 만료 시 소거
            }
        }
        // 겸직 금지(v1.3 P1): 상속인은 이제 소유자 — 본인이 맡고 있던 마름직(승계 구획 포함)은
        // 사임하고 같은 틱 승계를 발동한다(소유자는 마름 불가 — "자기 밭 소작 불가"의 확장).
        if (heir != null) {
            stewardGone(level, heir.getIndividual().id(), "상속 소유 전환 — 마름 사임");
        }
        setDirty();
    }

    /** 검증 전용 정리 — 무대 밭 회수(규칙 7). 멱등. */
    public void debugRemove(long id) {
        Plot p = plots.remove(id);
        if (p != null) {
            for (long l : p.tiles) {
                tileIndex.remove(l);
                bodyCache = null;
        bodyOwnerCache = null;
            }
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
            p.vacantSince = c.contains("Vacant") ? c.getLong("Vacant") : -1L;
            p.dir = c.getByte("Dir");
            p.turned = c.getBoolean("Turned");
            p.fx = c.getInt("Fx");
            p.baseY = c.getInt("BaseY");
            p.fz = c.getInt("Fz");
            p.beds = c.getInt("Beds");   // 없으면 0 = 구세계(칸 수열로 자란 구획)
            p.rows = c.getInt("Rows");
            p.bedAxisX = !c.contains("BedAxisX") || c.getBoolean("BedAxisX");
            if (c.contains("Ring")) {
                int[] r = c.getIntArray("Ring");
                if (r.length == 4) {
                    p.ringMinX = r[0];
                    p.ringMinZ = r[1];
                    p.ringMaxX = r[2];
                    p.ringMaxZ = r[3];
                }
            }
            // 밭 원장(P3) — 구세계 로드는 기본값(founder=owner, day=-1, 누계 0).
            p.founderId = c.contains("Founder") ? c.getLong("Founder") : p.ownerId;
            p.foundedDay = c.contains("FDay") ? c.getLong("FDay") : -1L;
            p.tilesByFounder = c.getLong("TByF");
            p.tilesByOwner = c.getLong("TByO");
            p.tilesByTenant = c.getLong("TByT");
            p.totalYield = c.getDouble("TY");
            p.totalToOwner = c.getDouble("TTO");
            p.totalToTenant = c.getDouble("TTT");
            p.harvestCount = c.getInt("HC");
            p.blockedDays = c.getInt("BlkD");
            p.expandDay = c.getLongArray("ExD");
            p.expandBy = c.getLongArray("ExB");
            p.expandTiles = c.getIntArray("ExT");
            // 마름(v1.3) — 구세계 로드는 기본값(무마름)
            p.stewardId = c.getLong("Stw");
            p.stewardSince = c.contains("StwSince") ? c.getLong("StwSince") : -1L;
            p.stewarded = c.getBoolean("StwEver");
            p.stewardDebt = c.getDouble("StwDebt");
            p.excessHoard = c.getDouble("ExHrd"); // fee 분할(E11) — 구세계 로드는 0
            s.plots.put(p.id, p);
            for (long l : p.tiles) {
                s.tileIndex.add(l);
            }
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
            if (p.vacantSince >= 0) {
                c.putLong("Vacant", p.vacantSince);
            }
            // 밭 원장(P3)
            c.putByte("Dir", p.dir);
            c.putBoolean("Turned", p.turned);
            c.putInt("Fx", p.fx);
            c.putInt("BaseY", p.baseY);
            c.putInt("Fz", p.fz);
            c.putInt("Beds", p.beds);
            c.putInt("Rows", p.rows);
            c.putBoolean("BedAxisX", p.bedAxisX);
            if (p.ringMaxX >= p.ringMinX) {
                c.putIntArray("Ring",
                        new int[] {p.ringMinX, p.ringMinZ, p.ringMaxX, p.ringMaxZ});
            }
            c.putLong("Founder", p.founderId);
            c.putLong("FDay", p.foundedDay);
            c.putLong("TByF", p.tilesByFounder);
            c.putLong("TByO", p.tilesByOwner);
            c.putLong("TByT", p.tilesByTenant);
            c.putDouble("TY", p.totalYield);
            c.putDouble("TTO", p.totalToOwner);
            c.putDouble("TTT", p.totalToTenant);
            c.putInt("HC", p.harvestCount);
            c.putInt("BlkD", p.blockedDays);
            c.putLongArray("ExD", p.expandDay);
            c.putLongArray("ExB", p.expandBy);
            c.putIntArray("ExT", p.expandTiles);
            c.putLong("Stw", p.stewardId);
            c.putLong("StwSince", p.stewardSince);
            c.putBoolean("StwEver", p.stewarded);
            c.putDouble("StwDebt", p.stewardDebt);
            c.putDouble("ExHrd", p.excessHoard);
            list.add(c);
        }
        tag.put("Plots", list);
        return tag;
    }
}
