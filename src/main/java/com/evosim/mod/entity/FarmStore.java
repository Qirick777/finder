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

    private final Map<Long, Plot> plots = new HashMap<>();
    private final java.util.HashSet<Long> tileIndex = new java.util.HashSet<>(); // pos.asLong — 무단 수확 가드용
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
        MimicEntity son = null;
        MimicEntity daughter = null;
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
        MimicEntity heir = son != null ? son : daughter; // 장남 우선, 없으면 장녀
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
        setDirty();
    }

    /** 검증 전용 정리 — 무대 밭 회수(규칙 7). 멱등. */
    public void debugRemove(long id) {
        Plot p = plots.remove(id);
        if (p != null) {
            for (long l : p.tiles) {
                tileIndex.remove(l);
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
            list.add(c);
        }
        tag.put("Plots", list);
        return tag;
    }
}
