package com.evosim.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>시설 등기·장부</b> — 학교·교회가 어디에 서 있고, 누구 것이고, 얼마를 벌고 썼는가.
 *
 * <p>계획서 1.5 의 "자선이 아니다"를 <b>수치로</b> 성립시키는 그릇이다.
 * {@code 수입 = 사용료 × 이용자}, {@code 비용 = 건축비(1회) + 종사자 급여(매일)} 를 누계로
 * 들고 있어, "지배자가 시설로 이익을 보는가"(목표 4)를 주장이 아니라 뺄셈으로 답한다.
 *
 * <p><b>P5a 에서는 장부에 건축비만 적힌다.</b> 사용료·급여는 등하교가 붙는 P5b 의 몫이다.
 * 다만 그릇을 미리 두는 것이 아니라 <b>세우는 순간부터 비용이 실제로 빠지므로</b>, 이 단계에서도
 * 장부는 살아 있는 수를 담는다 — 읽는 곳 없는 죽은 장부가 되지 않게.
 */
public class FacilityStore extends SavedData {

    private static final String KEY = "evosim_facilities";

    /** 시설 한 채. */
    public static final class Entry {
        public final BlockPos pos;
        public final FacilityTemplate.Kind kind;
        public final byte rotation;
        public final boolean mirrored;
        /** 세운 자(그리고 사용료를 받는 자). 승계는 밭·채권과 같은 단계에서 옮긴다. */
        public long ownerId;
        public final long foundedDay;
        /** 누계 — 건축비·급여 합, 사용료 수입 합. */
        public double spent;
        public double earned;
        /** 지금 이 시설에서 일하는 자(P5b). 0 이면 비어 있다. */
        public long staffId;

        Entry(BlockPos pos, FacilityTemplate.Kind kind, byte rotation, boolean mirrored,
              long ownerId, long foundedDay) {
            this.pos = pos;
            this.kind = kind;
            this.rotation = rotation;
            this.mirrored = mirrored;
            this.ownerId = ownerId;
            this.foundedDay = foundedDay;
        }

        /** 순수지 — 이 시설이 주인에게 남긴 것. 양수면 자선이 아니다. */
        public double net() {
            return earned - spent;
        }
    }

    private final List<Entry> all = new ArrayList<>();

    public static FacilityStore get(ServerLevel level) {
        return level.getDataStorage()
                .computeIfAbsent(FacilityStore::load, FacilityStore::new, KEY);
    }

    public List<Entry> all() {
        return all;
    }

    /** 이 종류의 시설을 이 주인이 몇 채 갖고 있나 — 한 사람이 학교를 여럿 짓지 않게. */
    public int countOf(long ownerId, FacilityTemplate.Kind kind) {
        int n = 0;
        for (Entry e : all) {
            if (e.ownerId == ownerId && e.kind == kind) {
                n++;
            }
        }
        return n;
    }

    public int countOf(FacilityTemplate.Kind kind) {
        int n = 0;
        for (Entry e : all) {
            if (e.kind == kind) {
                n++;
            }
        }
        return n;
    }

    /** 이 좌표에서 가장 가까운 해당 종류 시설 — 없으면 null. */
    public Entry nearest(BlockPos from, FacilityTemplate.Kind kind) {
        Entry best = null;
        double bd = Double.MAX_VALUE;
        for (Entry e : all) {
            if (e.kind != kind) {
                continue;
            }
            double d = e.pos.distSqr(from);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    public Entry register(BlockPos pos, FacilityTemplate.Kind kind, byte rotation,
                          boolean mirrored, long ownerId, long day, double buildCost) {
        Entry e = new Entry(pos, kind, rotation, mirrored, ownerId, day);
        e.spent = buildCost;
        all.add(e);
        setDirty();
        return e;
    }

    /** 장부 기입 — 들어온 것/나간 것. */
    public void earn(Entry e, double amount) {
        e.earned += amount;
        setDirty();
    }

    public void spend(Entry e, double amount) {
        e.spent += amount;
        setDirty();
    }

    /** 소유 승계 — 밭·채권 승계와 같은 단계에서 부른다. */
    public void inheritTo(long deadId, long heirId) {
        for (Entry e : all) {
            if (e.ownerId == deadId) {
                e.ownerId = heirId;
            }
        }
        setDirty();
    }

    public static FacilityStore load(CompoundTag tag) {
        FacilityStore s = new FacilityStore();
        ListTag arr = tag.getList("Facilities", Tag.TAG_COMPOUND);
        for (int i = 0; i < arr.size(); i++) {
            CompoundTag t = arr.getCompound(i);
            Entry e = new Entry(BlockPos.of(t.getLong("Pos")),
                    FacilityTemplate.Kind.of(t.getString("Kind")),
                    (byte) t.getInt("Rot"), t.getBoolean("Mir"),
                    t.getLong("Owner"), t.getLong("Day"));
            e.spent = t.getDouble("Spent");
            e.earned = t.getDouble("Earned");
            e.staffId = t.getLong("Staff");
            s.all.add(e);
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag arr = new ListTag();
        for (Entry e : all) {
            CompoundTag t = new CompoundTag();
            t.putLong("Pos", e.pos.asLong());
            t.putString("Kind", e.kind.design);
            t.putInt("Rot", e.rotation);
            t.putBoolean("Mir", e.mirrored);
            t.putLong("Owner", e.ownerId);
            t.putLong("Day", e.foundedDay);
            t.putDouble("Spent", e.spent);
            t.putDouble("Earned", e.earned);
            t.putLong("Staff", e.staffId);
            arr.add(t);
        }
        tag.put("Facilities", arr);
        return tag;
    }
}
