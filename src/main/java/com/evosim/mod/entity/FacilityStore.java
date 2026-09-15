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
        /** 지금 이 시설에서 일하는 자(P5b). 0 이면 비어 있다. 교회에서는 목사(큰교회)·성직자. */
        public long staffId;
        /** 둘째 직원 — 큰교회의 선교사, 대학의 둘째 교수. 0 이면 비어 있다. */
        public long staff2Id;
        /** 셋째 직원 — 대학의 셋째 교수(강단 3). */
        public long staff3Id;
        /** 시설 계정(교회 고도화) — 하루 헌금이 쌓이고 밤 정산 때 급여를 내고 비운다. */
        public double account;
        /** 등기자 — 세운 자. 승계로 주인이 바뀌어도 남는다(땅 문서의 "창설"에 해당). */
        public long founderId;
        /** 지휘관(P3) — 막사 정원이 찬 밤에 임명. 0 이면 없다. */
        public long commanderId;
        /** 누계 — 주인이 사비로 메운 적자(교회 보전), 주인이 가져간 흑자(교회 분배). */
        public double covered;
        public double paidOut;
        /**
         * 이력 고리(UI P4) — 착공·직원 임명·정산·이탈 같은 굵직한 사건만 한 줄씩. 최근
         * {@link #HISTORY_CAP}건만 남긴다 — 매일 급여 같은 잔잔한 일은 넣지 않는다(고리가 하루 만에
         * 씻겨 나가면 이력이 아니다).
         */
        public final List<String> history = new ArrayList<>();

        Entry(BlockPos pos, FacilityTemplate.Kind kind, byte rotation, boolean mirrored,
              long ownerId, long foundedDay) {
            this.pos = pos;
            this.kind = kind;
            this.rotation = rotation;
            this.mirrored = mirrored;
            this.ownerId = ownerId;
            this.foundedDay = foundedDay;
            this.founderId = ownerId;
        }

        /** 순수지 — 이 시설이 주인에게 남긴 것. 양수면 자선이 아니다. */
        public double net() {
            return earned - spent;
        }
    }

    /** 이력 고리 길이 — 땅 문서 화면 6줄 스크롤 두 장 남짓. */
    public static final int HISTORY_CAP = 16;

    private final List<Entry> all = new ArrayList<>();

    /** 이력 한 줄 추가("d12 목사 임명 — …"). 고리가 차면 가장 오래된 줄을 버린다. */
    public void note(Entry e, long day, String text) {
        e.history.add("d" + day + " " + text);
        while (e.history.size() > HISTORY_CAP) {
            e.history.remove(0);
        }
        setDirty();
    }

    /** 이 좌표를 덮는 시설 — 도면의 축별 반폭(+1 여유) 안이고 높이 차 12 이내. 없으면 null. */
    public Entry covering(ServerLevel level, BlockPos pos) {
        Entry best = null;
        double bd = Double.MAX_VALUE;
        for (Entry e : all) {
            if (Math.abs(e.pos.getY() - pos.getY()) > 24) {
                continue;
            }
            var tpl = FacilityTemplate.of(level, e.kind, e.rotation, e.mirrored);
            if (tpl.isPresent()) {
                // 비대칭 점유 상자로 본다 — 종이 한쪽에 치우친 도면(대학)을 반폭으로 재면 건물 밖 넓은 띠까지 제 것으로 잡는다.
                if (!tpl.get().boxCovers(e.pos, pos.getX(), pos.getZ(), 1.0)) {
                    continue;
                }
            } else if (Math.abs(pos.getX() - e.pos.getX()) > 5.0
                    || Math.abs(pos.getZ() - e.pos.getZ()) > 5.0) {
                continue;
            }
            double d = e.pos.distSqr(pos);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

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

    /**
     * 이 <b>갈래</b>의 시설을 이 주인이 몇 채 갖고 있나 — 크기를 가리지 않는다.
     *
     * <p>교회는 큰 것과 작은 것이 용량만 다르고 하는 일은 같다. 종류로 세면 한 사람이
     * 큰 교회와 작은 교회를 나란히 지어 두 채가 되므로, 중복 판정은 갈래로 해야 한다.
     */
    public int countOf(long ownerId, FacilityTemplate.Group group) {
        int n = 0;
        for (Entry e : all) {
            if (e.ownerId == ownerId && e.kind.group == group) {
                n++;
            }
        }
        return n;
    }

    /** 이 갈래 시설의 전체 채수 — 보고용. */
    public int countOf(FacilityTemplate.Group group) {
        int n = 0;
        for (Entry e : all) {
            if (e.kind.group == group) {
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
        note(e, day, String.format("착공 — 건축비 %.0f", buildCost));
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
            e.staff2Id = t.getLong("Staff2");
            e.staff3Id = t.getLong("Staff3");
            e.account = t.getDouble("Account");
            e.founderId = t.contains("Founder") ? t.getLong("Founder") : e.ownerId;
            e.commanderId = t.getLong("Commander");
            e.covered = t.getDouble("Covered");
            e.paidOut = t.getDouble("PaidOut");
            ListTag hist = t.getList("Hist", Tag.TAG_STRING);
            for (int j = 0; j < hist.size(); j++) {
                e.history.add(hist.getString(j));
            }
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
            t.putLong("Staff2", e.staff2Id);
            t.putLong("Staff3", e.staff3Id);
            t.putDouble("Account", e.account);
            t.putLong("Founder", e.founderId);
            t.putLong("Commander", e.commanderId);
            t.putDouble("Covered", e.covered);
            t.putDouble("PaidOut", e.paidOut);
            ListTag hist = new ListTag();
            for (String h : e.history) {
                hist.add(net.minecraft.nbt.StringTag.valueOf(h));
            }
            t.put("Hist", hist);
            arr.add(t);
        }
        tag.put("Facilities", arr);
        return tag;
    }
}
