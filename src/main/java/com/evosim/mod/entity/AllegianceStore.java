package com.evosim.mod.entity;

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
 * <b>신세 원장</b> — 누가 누구에게 무엇을 빚졌는가. 추종·신분 체계의 단일 출처.
 *
 * <p>간선 하나가 "A 가 B 에게 진 신세"다. 두 몫으로 나뉜다.
 * <ul>
 *   <li><b>탕감분</b> — 위급할 때 무상으로 받은 것. 갚을 필요가 없지만 <b>추종 점수는 된다</b>.</li>
 *   <li><b>상환분</b> — 빌린 것·못 낸 것. 이자가 붙고 갚아야 한다.</li>
 * </ul>
 *
 * <p><b>추종은 저장하지 않는다.</b> 신세 합계가 가장 큰 상대이면서 그 값이 임계를 넘을 때
 * 성립하는 <b>파생값</b>이다. 임계는 자기 재산에 비례하므로, 같은 은혜라도 가난한 자에게만
 * 구속이 된다 — 신분으로 분기하지 않고 비율로 갈린다(규칙5).
 *
 * <p>개체당 상위 {@link #TOP_K} 개만 남긴다. 전원↔전원을 두면 인구 100 에 간선 10,000 이고,
 * 실제로도 사람은 은인 몇 명만 기억한다.
 *
 * <p><b>이 단계(P2)에서는 아무 행동도 바꾸지 않는다.</b> 이미 흐르고 있는 이전(구휼·긴급고용·
 * 상시 소작)을 기록하기만 한다. 대출은 새 <b>행동</b>이라 여기 넣지 않았다 — 넣었다면
 * "다른 수치가 그대로인가"라는 이 단계의 합격 조건 자체가 성립하지 않는다.
 * 그래서 상환분은 P2 내내 0 이다.
 */
public class AllegianceStore extends SavedData {

    private static final String KEY = "evosim_allegiance";

    /** 개체가 기억하는 은인 수 상한. */
    public static final int TOP_K = 4;

    /**
     * 하루 감쇠 — 오래된 은혜는 옅어진다.
     *
     * <p>0.95 는 반감기 약 13.5일로 수명(성장2+성년8+노년3=13일)과 같은 눈금이다. 한 세대가
     * 지나면 갚지 않은 은혜가 절반으로 준다는 뜻. <b>측정 뒤에 확정할 값</b>이며, P2 의
     * 관측(추종 성립 수·집중도)이 그 근거가 된다.
     */
    public static final double DECAY_PER_DAY = 0.95;

    /** 이보다 작아진 간선은 지운다 — 원장이 먼지로 부풀지 않게. */
    private static final double EPSILON = 0.05;

    /** 추종 임계의 바닥. 재산이 0 에 가까워도 이만큼은 받아야 따른다. */
    public static final double MIN_BOND = 4.0;

    /** 추종 임계 = max(MIN_BOND, 재산 × 이 비율). 부유할수록 같은 은혜에 덜 묶인다. */
    public static final double WEALTH_RATIO = 0.5;

    // ── 가중치(잠정 — P8 에서 실측으로 확정) ──────────────────────────────
    /** 위급 구휼 1 유닛당. 목숨을 구한 것이라 가장 무겁다. */
    public static final double W_RELIEF = 3.0;
    /** 굶던 자에게 일자리를 준 1회. */
    public static final double W_HIRE = 5.0;
    /** 상시 소작으로 하루 일한 몫 — 작지만 매일 쌓인다. */
    public static final double W_TENANCY = 0.3;

    /** 한 채무자가 한 은인에게 진 신세. */
    public static final class Bond {
        public final long patronId;
        public double forgiven;  // 탕감분(무상)
        public double owed;      // 상환분(빚) — P2 에서는 0
        public long lastDay;

        Bond(long patronId, long day) {
            this.patronId = patronId;
            this.lastDay = day;
        }

        public double total() {
            return forgiven + owed;
        }
    }

    private final Map<Long, List<Bond>> bonds = new HashMap<>();
    private long decayedDay = Long.MIN_VALUE;

    public static AllegianceStore get(ServerLevel level) {
        return level.getDataStorage()
                .computeIfAbsent(AllegianceStore::load, AllegianceStore::new, KEY);
    }

    /** 신세를 더한다. 자기 자신·무효 id 는 무시한다. */
    public void record(long debtorId, long patronId, double forgivenAdd, double owedAdd,
                       long day) {
        if (debtorId == 0L || patronId == 0L || debtorId == patronId) {
            return;
        }
        if (forgivenAdd <= 0.0 && owedAdd <= 0.0) {
            return;
        }
        List<Bond> list = bonds.computeIfAbsent(debtorId, k -> new ArrayList<>());
        Bond hit = null;
        for (Bond b : list) {
            if (b.patronId == patronId) {
                hit = b;
                break;
            }
        }
        if (hit == null) {
            hit = new Bond(patronId, day);
            list.add(hit);
        }
        hit.forgiven += forgivenAdd;
        hit.owed += owedAdd;
        hit.lastDay = day;
        trim(list);
        setDirty();
    }

    /** 상위 K 만 남긴다 — 가장 작은 것부터 버린다. */
    private static void trim(List<Bond> list) {
        if (list.size() <= TOP_K) {
            return;
        }
        list.sort((a, b) -> Double.compare(b.total(), a.total()));
        while (list.size() > TOP_K) {
            list.remove(list.size() - 1);
        }
    }

    /**
     * 하루 감쇠 — 그날 한 번만 돈다. 살아 있지 않은 은인의 간선은 지운다.
     *
     * <p>승계(P3)가 붙기 전까지는 은인이 죽으면 그 신세가 사라진다. P3 에서 장남이 채권을
     * 물려받으면 이 소거 대신 이전이 된다.
     */
    public void decayDaily(long day, java.util.Set<Long> aliveIds) {
        if (day == decayedDay) {
            return;
        }
        decayedDay = day;
        var it = bonds.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (!aliveIds.contains(e.getKey())) {
                it.remove(); // 채무자가 죽었다
                continue;
            }
            List<Bond> list = e.getValue();
            list.removeIf(b -> !aliveIds.contains(b.patronId));
            for (Bond b : list) {
                b.forgiven *= DECAY_PER_DAY;
                b.owed *= DECAY_PER_DAY;
            }
            list.removeIf(b -> b.total() < EPSILON);
            if (list.isEmpty()) {
                it.remove();
            }
        }
        setDirty();
    }

    /** 이 개체가 진 신세 목록(읽기 전용). */
    public List<Bond> bondsOf(long debtorId) {
        return bonds.getOrDefault(debtorId, List.of());
    }

    /**
     * <b>추종 대상</b>(파생) — 신세 합계가 가장 큰 상대. 임계를 못 넘으면 0.
     *
     * @param wealth 이 개체의 재산(저장고 등) — 임계가 여기에 비례한다.
     */
    public long patronOf(long debtorId, double wealth) {
        double gate = Math.max(MIN_BOND, wealth * WEALTH_RATIO);
        long best = 0L;
        double bestVal = 0.0;
        for (Bond b : bondsOf(debtorId)) {
            if (b.total() > bestVal) {
                bestVal = b.total();
                best = b.patronId;
            }
        }
        return bestVal >= gate ? best : 0L;
    }

    /** 원장 전체(읽기 전용) — 보고용. */
    public Map<Long, List<Bond>> all() {
        return bonds;
    }

    public static AllegianceStore load(CompoundTag tag) {
        AllegianceStore s = new AllegianceStore();
        ListTag arr = tag.getList("Bonds", Tag.TAG_COMPOUND);
        for (int i = 0; i < arr.size(); i++) {
            CompoundTag t = arr.getCompound(i);
            long debtor = t.getLong("D");
            Bond b = new Bond(t.getLong("P"), t.getLong("Day"));
            b.forgiven = t.getDouble("F");
            b.owed = t.getDouble("O");
            s.bonds.computeIfAbsent(debtor, k -> new ArrayList<>()).add(b);
        }
        s.decayedDay = tag.getLong("DecayedDay");
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag arr = new ListTag();
        for (var e : bonds.entrySet()) {
            for (Bond b : e.getValue()) {
                CompoundTag t = new CompoundTag();
                t.putLong("D", e.getKey());
                t.putLong("P", b.patronId);
                t.putDouble("F", b.forgiven);
                t.putDouble("O", b.owed);
                t.putLong("Day", b.lastDay);
                arr.add(t);
            }
        }
        tag.put("Bonds", arr);
        tag.putLong("DecayedDay", decayedDay);
        return tag;
    }
}
