package com.evosim.mod.entity;

import com.evosim.core.Individual;
import com.evosim.core.Sex;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * 혈통 원장 (가계도·후손 통계의 데이터원). 등장한 모든 개체를 {@code id → 기록}으로 영구 보존한다 —
 * 개체가 죽어 엔티티가 사라져도 가계도는 이 원장으로 끝까지 거슬러 올라간다(§14).
 *
 * <p>기록 시점: 개체의 첫 서버 틱(출생·스폰·구세이브 로드 모두 포괄). 사망 시점: 엔티티 파괴.
 * 검증 무대 개체({@link MimicEntity#isStageActor()})는 통계 오염 방지를 위해 기록하지 않는다.
 * 일련번호(serial)는 표시용 안정 식별자 — 엔티티 id는 재접속마다 바뀌기 때문.
 */
public class FamilyLedger extends SavedData {

    private static final String KEY = "evosim_ledger";

    /** 개체 1명의 영구 기록. diedDay < 0 = 생존(또는 미확인). */
    public static final class Rec {
        public final long id;
        public final int serial;
        public final boolean female;
        public final int gen;
        public final long pa;
        public final long pb;
        public final long bornDay;
        public long diedDay = -1L;

        Rec(long id, int serial, boolean female, int gen, long pa, long pb, long bornDay) {
            this.id = id;
            this.serial = serial;
            this.female = female;
            this.gen = gen;
            this.pa = pa;
            this.pb = pb;
            this.bornDay = bornDay;
        }
    }

    private final Map<Long, Rec> recs = new HashMap<>();
    private int nextSerial = 1;

    public static FamilyLedger get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FamilyLedger::load, FamilyLedger::new, KEY);
    }

    /** 첫 관측 시 등록(멱등 — 이미 있으면 무시). */
    public void register(Individual ind, long day) {
        if (ind == null || recs.containsKey(ind.id())) {
            return;
        }
        recs.put(ind.id(), new Rec(ind.id(), nextSerial++, ind.sex() == Sex.FEMALE,
                ind.generation(), ind.parentAId(), ind.parentBId(), day));
        setDirty();
    }

    /** 사망 마킹(기록이 없으면 무시 — 무대 개체 등). */
    public void markDead(long id, long day) {
        Rec r = recs.get(id);
        if (r != null && r.diedDay < 0) {
            r.diedDay = day;
            setDirty();
        }
    }

    public Rec get(long id) {
        return recs.get(id);
    }

    public Map<Long, Rec> all() {
        return recs;
    }

    /** {@link com.evosim.core.Lineage} 입력용 부모 맵 뷰. */
    public Map<Long, long[]> parentsMap() {
        Map<Long, long[]> out = new HashMap<>(recs.size() * 2);
        for (Rec r : recs.values()) {
            out.put(r.id, new long[] {r.pa, r.pb});
        }
        return out;
    }

    public static FamilyLedger load(CompoundTag tag) {
        FamilyLedger l = new FamilyLedger();
        l.nextSerial = Math.max(1, tag.getInt("Next"));
        ListTag list = tag.getList("Recs", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            Rec r = new Rec(c.getLong("Id"), c.getInt("No"), c.getBoolean("F"),
                    c.getInt("Gen"), c.getLong("PA"), c.getLong("PB"), c.getLong("Born"));
            r.diedDay = c.contains("Died") ? c.getLong("Died") : -1L;
            l.recs.put(r.id, r);
        }
        return l;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("Next", nextSerial);
        ListTag list = new ListTag();
        for (Rec r : recs.values()) {
            CompoundTag c = new CompoundTag();
            c.putLong("Id", r.id);
            c.putInt("No", r.serial);
            c.putBoolean("F", r.female);
            c.putInt("Gen", r.gen);
            c.putLong("PA", r.pa);
            c.putLong("PB", r.pb);
            c.putLong("Born", r.bornDay);
            if (r.diedDay >= 0) {
                c.putLong("Died", r.diedDay);
            }
            list.add(c);
        }
        tag.put("Recs", list);
        return tag;
    }
}
