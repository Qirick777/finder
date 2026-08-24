package com.evosim.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 거처 등기부 — 마을의 <b>모든</b> 거처를 좌표·도면·방향·주인·마지막 거주일로 월드에 영속한다.
 *
 * <h3>왜 필요한가</h3>
 * 종전에는 거처의 존재를 두 곳에서 <b>추론</b>했고, 둘 다 구멍이 있었다.
 * <ul>
 *   <li><b>개체 스캔</b>({@code collectExistingHomes}) — 반경 160 안의 <i>살아 있는 개체</i>의
 *       {@code homePos} 를 모은다. 그래서 <b>거주자가 전멸한 집은 존재하지 않는 것</b>이 되어,
 *       새 부부가 그 위에 겹쳐 짓는다. 청크가 언로드된 집도 마찬가지다.</li>
 *   <li><b>{@code ABANDONED_HOMES} 정적 리스트</b> — 폐가 목록이 <b>휘발성</b>이라 서버를 껐다
 *       켜면 통째로 사라진다. 재접속 후에는 빈집이 재사용 후보에서도, 겹침 회피 목록에서도
 *       빠진다("재접속하면 집을 못 찾는" 증상의 한 갈래).</li>
 * </ul>
 *
 * <p>등기부는 이 둘을 하나의 <b>영속</b> 사실로 바꾼다. 개체 스캔은 없애지 않고 <b>합집합</b>으로
 * 남긴다 — 등기 누락이 있어도 겹쳐 짓지는 않도록(안전한 쪽으로만 틀린다).
 *
 * <h3>빈집 판정</h3>
 * 두 경로가 있고, 서로를 보완한다.
 * <ul>
 *   <li><b>명시적 퇴거</b>({@link #vacate}) — 사망·이주·합류처럼 코드가 아는 순간에 즉시 빈집.</li>
 *   <li><b>거주 신호 소멸</b> — 가구 정산이 매번 {@link #touch} 로 날짜를 갱신한다.
 *       {@link #VACANT_DAYS} 일 동안 갱신이 없으면 빈집으로 본다. 명시적 퇴거가 닿지 못하는
 *       경로(강제 처치, 크래시, 구 월드에서 넘어온 집)를 이 폴백이 걷어낸다.</li>
 * </ul>
 * 판정은 <b>후보</b>일 뿐이다 — 실제 입주 직전에 개체 스캔으로 무주(無住)를 재확인한다.
 * 등기부는 "어디에 집이 있는가"를 알고, 개체 스캔은 "지금 누가 있는가"를 안다.
 */
public class HomeStore extends SavedData {

    private static final String KEY = "evosim_homes";

    /**
     * 명시적 퇴거 신호 없이 빈집으로 인정하기까지의 유예(일). 3일인 이유: 가구 정산은 하루에도
     * 여러 번 돌아 정상 거주 가구는 매일 갱신된다. 1일이면 서버가 하루 이상 꺼져 있다가 켜진
     * 직후에 <b>살아 있는 집이 잠깐 빈집으로 보이고</b>, 그 틈에 남의 집에 입주가 일어난다.
     * 3일은 그 창을 덮으면서도 실제 폐가가 오래 방치되지 않는 선이다.
     */
    public static final int VACANT_DAYS = 3;

    /** 명시적 퇴거 표식 — 어떤 실제 날짜보다 작아 즉시 "오래된" 것으로 판정된다. */
    private static final int VACATED = Integer.MIN_VALUE;

    /**
     * 등기 한 건.
     *
     * @param design   도면 이름(현 천막은 {@link #TENT}). 3단계에서 실제 스키메틱 이름이 들어온다.
     * @param rotation 방향 — 천막은 {@code Direction.get2DDataValue}, 스키메틱은 회전 0~3.
     * @param mirrored 좌우반전 여부(스키메틱 전용, 천막은 항상 false).
     * @param ownerId  마지막으로 확인된 가장의 개체 id. 0 = 무주.
     * @param lastSeenDay 마지막 거주 확인일.
     */
    public record Entry(String design, byte rotation, boolean mirrored,
                        long ownerId, int lastSeenDay, double upkeepDue, int showoffSince) {

        Entry(String design, byte rotation, boolean mirrored, long ownerId, int lastSeenDay) {
            this(design, rotation, mirrored, ownerId, lastSeenDay, 0.0, NOT_SHOWING_OFF);
        }
    }

    /** 과시 이사 조건을 아직 만족하지 않은 상태. */
    public static final int NOT_SHOWING_OFF = Integer.MIN_VALUE;

    /** 현 천막 거처의 도면 이름 — 3단계 전까지의 값. */
    public static final String TENT = "tent";

    private final Map<Long, Entry> homes = new HashMap<>();

    public static HomeStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(HomeStore::load, HomeStore::new, KEY);
    }

    public static HomeStore load(CompoundTag tag) {
        HomeStore store = new HomeStore();
        ListTag list = tag.getList("Homes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            store.homes.put(e.getLong("Pos"), new Entry(
                    e.getString("Design"), e.getByte("Rot"), e.getBoolean("Mirror"),
                    e.getLong("Owner"), e.getInt("Seen"), e.getDouble("Upkeep"),
                    e.contains("Showoff") ? e.getInt("Showoff") : NOT_SHOWING_OFF));
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<Long, Entry> e : homes.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putLong("Pos", e.getKey());
            c.putString("Design", e.getValue().design());
            c.putByte("Rot", e.getValue().rotation());
            c.putBoolean("Mirror", e.getValue().mirrored());
            c.putLong("Owner", e.getValue().ownerId());
            c.putInt("Seen", e.getValue().lastSeenDay());
            c.putDouble("Upkeep", e.getValue().upkeepDue());
            c.putInt("Showoff", e.getValue().showoffSince());
            list.add(c);
        }
        tag.put("Homes", list);
        return tag;
    }

    /** 신축·입주로 거처가 생겼다 — 등기하고 오늘 거주로 표시. */
    public void register(BlockPos home, String design, byte rotation, boolean mirrored,
                         long ownerId, int day) {
        homes.put(home.asLong(), new Entry(design, rotation, mirrored, ownerId, day));
        setDirty();
    }

    /**
     * 아직 거주 중이다 — 마지막 거주일 갱신. 등기가 없으면 <b>지금 등기한다</b>.
     *
     * <p>자동 등기가 있어야 이 변경 <b>이전</b>에 만들어진 월드의 집들이 등기부에 편입된다.
     * 그러지 않으면 구 월드에서는 등기부가 영원히 비어 있어 아무 효과가 없다.
     */
    public void touch(BlockPos home, String design, byte rotation, long ownerId, int day) {
        Entry cur = homes.get(home.asLong());
        homes.put(home.asLong(), cur == null
                ? new Entry(design, rotation, false, ownerId, day)
                : new Entry(cur.design(), cur.rotation(), cur.mirrored(), ownerId, day,
                        cur.upkeepDue(), cur.showoffSince()));
        setDirty();
    }

    /**
     * 유지비 잔돈 — 하루 유지비가 1 미만이라 <b>모아서</b> 정수로 낸다. 저장고 L 은 정수 불변식
     * (B-3)을 지켜야 하는데 소형 0.05/일을 매일 빼면 그 불변식이 깨진다. 그래서 잔돈을 여기
     * (집의 장부)에 쌓고 1을 넘길 때만 정수로 차감한다.
     */
    public void setUpkeepDue(BlockPos home, double due) {
        Entry c = homes.get(home.asLong());
        if (c == null) {
            return;
        }
        homes.put(home.asLong(), new Entry(c.design(), c.rotation(), c.mirrored(), c.ownerId(),
                c.lastSeenDay(), due, c.showoffSince()));
        setDirty();
    }

    /** 과시 조건을 처음 만족한 날(또는 {@link #NOT_SHOWING_OFF}) — 지속 일수 판정의 기준점. */
    public void setShowoffSince(BlockPos home, int day) {
        Entry c = homes.get(home.asLong());
        if (c == null || c.showoffSince() == day) {
            return;
        }
        homes.put(home.asLong(), new Entry(c.design(), c.rotation(), c.mirrored(), c.ownerId(),
                c.lastSeenDay(), c.upkeepDue(), day));
        setDirty();
    }

    /** 명시적 퇴거 — 구조물은 남기고 주인만 지운다(저장고는 그대로, 재사용 가구가 계승). */
    public void vacate(BlockPos home) {
        Entry cur = homes.get(home.asLong());
        if (cur == null) {
            return;
        }
        homes.put(home.asLong(), new Entry(cur.design(), cur.rotation(), cur.mirrored(),
                0L, VACATED, cur.upkeepDue(), NOT_SHOWING_OFF));
        setDirty();
    }

    /** 구조물 자체가 사라졌다 — 등기 말소. */
    public void remove(BlockPos home) {
        if (homes.remove(home.asLong()) != null) {
            setDirty();
        }
    }

    public Entry entry(BlockPos home) {
        return homes.get(home.asLong());
    }

    public int size() {
        return homes.size();
    }

    /** 등기된 모든 거처 좌표 — 겹침 회피의 단일 출처. */
    public List<BlockPos> positions() {
        List<BlockPos> out = new ArrayList<>(homes.size());
        for (Long k : homes.keySet()) {
            out.add(BlockPos.of(k));
        }
        return out;
    }

    /** 빈집 판정 — 명시적 퇴거이거나, {@link #VACANT_DAYS} 일 넘게 거주 신호가 없다. */
    public boolean isVacant(BlockPos home, int today) {
        Entry e = homes.get(home.asLong());
        return e != null && isVacant(e, today);
    }

    private static boolean isVacant(Entry e, int today) {
        return e.lastSeenDay() == VACATED || today - e.lastSeenDay() >= VACANT_DAYS;
    }

    /** 빈집 후보 — 가까운 순으로. 실제 입주 전에 호출부가 무주·구조 존재를 재확인해야 한다. */
    public List<BlockPos> vacantNear(BlockPos from, double radius, int today) {
        List<BlockPos> out = new ArrayList<>();
        double r2 = radius * radius;
        for (Map.Entry<Long, Entry> e : homes.entrySet()) {
            if (!isVacant(e.getValue(), today)) {
                continue;
            }
            BlockPos p = BlockPos.of(e.getKey());
            if (p.distSqr(from) <= r2) {
                out.add(p);
            }
        }
        out.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(from)));
        return out;
    }

    /** 사람이 사는 거처 좌표(long) — 마실·구혼 여행의 목적지 후보 출처. */
    public List<Long> occupiedPositions(int today) {
        List<Long> out = new ArrayList<>();
        for (Map.Entry<Long, Entry> e : homes.entrySet()) {
            if (!isVacant(e.getValue(), today)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** 빈집 수 — 관측·검증용. */
    public int vacantCount(int today) {
        int n = 0;
        for (Entry e : homes.values()) {
            if (isVacant(e, today)) {
                n++;
            }
        }
        return n;
    }
}
