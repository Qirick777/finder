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

    /** 추종 임계의 바닥. 가진 땅이 없어도 이만큼은 받아야 따른다. */
    public static final double MIN_BOND = 4.0;

    /**
     * 추종 임계 = max(MIN_BOND, <b>소유 밭 타일</b> × 이 값). 가진 땅이 많을수록 덜 묶인다.
     *
     * <p>처음에는 저장고를 재산으로 썼는데 두 가지가 틀렸다. 저장고 20 에 하루소모 7 이면
     * 그것은 <b>사흘치 버팀목</b>이지 부가 아니다 — 손에 쥔 것 없이 사는 사람에게 높은 임계를
     * 매긴 셈이었다. 게다가 저장고는 날마다 출렁여 임계가 흔들린다.
     *
     * <p>실측(P2 D16): 채무자당 신세 3.85 인데 저장고 기준 임계는 10 이라 <b>추종 성립 0</b>.
     *
     * <p>소유 타일은 내구성 있는 부이고 하루 단위로 흔들리지 않는다. 무토지 소작은 자동으로
     * 바닥 임계가 되고, 작은 지주가 큰 지주를 추종하는 차상위 계층(P3)도 같은 척도로 표현된다.
     * 0.2 면 40타일 지주의 임계가 8, 200타일 영주는 40 이라 사실상 아무도 안 따른다.
     */
    public static final double TILE_WORTH = 0.2;

    // ── 가중치(잠정 — P8 에서 실측으로 확정) ──────────────────────────────
    /** 위급 구휼 1 유닛당. 목숨을 구한 것이라 가장 무겁다. */
    public static final double W_RELIEF = 3.0;
    /** 굶던 자에게 일자리를 준 1회. */
    public static final double W_HIRE = 5.0;
    /**
     * 상시 소작으로 하루 일한 몫 — 작지만 매일 쌓인다.
     *
     * <p>0.3→0.6: 감쇠 0.95 와의 균형점이 0.3/(1−0.95)=6.0 이라 바닥 임계 4 를 겨우 넘고
     * 거기 닿는 데 수십 일이 걸렸다. 구휼은 좋은 시절에 아예 발동하지 않으므로(실측 P2 D16:
     * <b>0건</b> — P1 이 식량을 늘려 아무도 위급까지 굶지 않았다) 소작 관계가 예속의 주 경로다.
     * 0.6 이면 균형점 12, 닷새면 바닥 임계를 넘는다.
     */
    public static final double W_TENANCY = 0.6;

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

    /**
     * 개체별 <b>연속 궁핍 일수</b> — 제 가구가 하루를 못 넘긴 날이 며칠 연달았는가.
     *
     * <p>신세가 아니므로 원장의 본체는 아니지만, 천민 판정({@link SocialRank#LOW})의 두 척도
     * 중 하나이고 이 클래스가 신분 체계의 단일 출처라 여기 둔다. 하루 한 번 갱신되고,
     * 한 번이라도 제힘으로 하루를 넘기면 0 으로 되돌아간다 — 벗어난 자를 붙잡아 두지 않는다.
     */
    private final Map<Long, Integer> destitute = new HashMap<>();

    /**
     * 개체별 <b>연속 예속 일수</b> — 주인이 있고 제 땅이 없는 상태가 며칠 연달았는가.
     *
     * <p>천민 판정({@link SocialRank#LOW})의 주 척도. 처음에는 궁핍(가구가 하루를 못 넘김)으로
     * 재려 했는데 <b>측정에서 0 이 나왔다</b>(P3.5 D14: 계층별 평균 살림 20, 재산 최소 10 —
     * 가장 가난한 가구조차 하루치가 있다). 그것은 "굶는가"를 묻는 척도인데 천민의 정의는
     * "벗어나지 못하는가"다. 다른 질문을 재고 있었다.
     *
     * <p>그래서 예속 그 자체를 잰다. 벗어나는 길은 이미 정해져 있다 — 제 땅을 갖거나 스스로
     * 추종자를 얻으면 조건이 깨져 0 으로 돌아간다.
     */
    private final Map<Long, Integer> bound = new HashMap<>();
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
        destitute.keySet().retainAll(aliveIds); // 죽은 자의 기록은 남기지 않는다
        bound.keySet().retainAll(aliveIds);
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

    /**
     * 오늘 이 개체가 궁핍했는가를 적는다 — 연달았으면 +1, 아니면 0 으로 되돌린다.
     *
     * <p><b>기록만 한다.</b> 이 수를 읽는 것은 보고와 {@link SocialRank} 뿐이고, 어떤 행동도
     * 이 값으로 갈리지 않는다(P3.5 의 합격 조건: 다른 모든 지표가 그대로일 것).
     */
    public void noteDestitution(long id, boolean poorToday) {
        if (id == 0L) {
            return;
        }
        if (poorToday) {
            destitute.merge(id, 1, Integer::sum);
        } else if (destitute.remove(id) == null) {
            return; // 원래 0 이었다 — 더럽힐 것이 없다
        }
        setDirty();
    }

    /** 연속 궁핍 일수 — 계측용(아무도 굶지 않는다는 것을 숫자로 남긴다). */
    public int destituteDays(long id) {
        return destitute.getOrDefault(id, 0);
    }

    /**
     * 오늘 이 개체가 예속 상태였는가를 적는다 — 연달았으면 +1, 벗어났으면 0.
     *
     * <p>{@link #noteDestitution} 과 같이 <b>기록만 한다.</b>
     */
    public void noteBondage(long id, boolean boundToday) {
        if (id == 0L) {
            return;
        }
        if (boundToday) {
            bound.merge(id, 1, Integer::sum);
        } else if (bound.remove(id) == null) {
            return;
        }
        setDirty();
    }

    /** 연속 예속 일수 — 천민 판정의 주 척도. */
    public int boundDays(long id) {
        return bound.getOrDefault(id, 0);
    }

    /** 이 개체가 진 상환분 합 — 천민 판정의 나머지 척도. */
    public double owedOf(long debtorId) {
        double n = 0.0;
        for (Bond b : bondsOf(debtorId)) {
            n += b.owed;
        }
        return n;
    }

    /** 이 개체가 진 신세 목록(읽기 전용). */
    public List<Bond> bondsOf(long debtorId) {
        return bonds.getOrDefault(debtorId, List.of());
    }

    /**
     * <b>추종 대상</b>(파생) — 신세 합계가 가장 큰 상대. 임계를 못 넘으면 0.
     *
     * @param ownedTiles 이 개체가 소유한 밭 타일 수 — 임계가 여기에 비례한다.
     */
    public long patronOf(long debtorId, int ownedTiles) {
        double gate = Math.max(MIN_BOND, ownedTiles * TILE_WORTH);
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

    /**
     * <b>승계</b> — 죽은 자의 채권과 채무를 상속인에게 넘긴다. 밭 승계와 같은 단계에서 부른다.
     *
     * <ul>
     *   <li><b>채권</b>(남들이 죽은 자에게 진 신세) → 상속인에게 재배선. 아버지의 추종자들이
     *       아들을 따르게 된다 = 왕조.</li>
     *   <li><b>채무</b>(죽은 자가 남에게 진 신세) → 상속인에게 이전(기존 것과 합산).
     *       아버지의 예속이 아들에게 = 농노 세습.</li>
     * </ul>
     *
     * <p>상속인이 없으면 옮기지 않는다 — 그러면 다음 감쇠에서 정리된다(세력이 흩어진다).
     * 자기 자신에게 진 신세가 되는 간선은 버린다.
     */
    public void succeed(long deadId, long heirId) {
        if (deadId == 0L) {
            return;
        }
        if (heirId == 0L || deadId == heirId) {
            bonds.remove(deadId);
            for (List<Bond> list : bonds.values()) {
                list.removeIf(b -> b.patronId == deadId);
            }
            setDirty();
            return;
        }
        // ① 채권 — 남들의 간선이 상속인을 가리키게 한다.
        for (var e : bonds.entrySet()) {
            long debtor = e.getKey();
            List<Bond> list = e.getValue();
            Bond from = null;
            for (Bond b : list) {
                if (b.patronId == deadId) {
                    from = b;
                    break;
                }
            }
            if (from == null) {
                continue;
            }
            list.remove(from);
            if (debtor == heirId) {
                continue; // 상속인이 아버지에게 진 신세 — 자기 자신이 되므로 버린다
            }
            Bond to = null;
            for (Bond b : list) {
                if (b.patronId == heirId) {
                    to = b;
                    break;
                }
            }
            if (to == null) {
                to = new Bond(heirId, from.lastDay);
                list.add(to);
            }
            to.forgiven += from.forgiven;
            to.owed += from.owed;
            trim(list);
        }
        // ② 채무 — 죽은 자의 목록을 상속인 목록에 합친다.
        List<Bond> mine = bonds.remove(deadId);
        if (mine != null && !mine.isEmpty()) {
            List<Bond> heirList = bonds.computeIfAbsent(heirId, k -> new ArrayList<>());
            for (Bond b : mine) {
                if (b.patronId == heirId) {
                    continue; // 아버지가 아들에게 진 신세 — 자기 자신
                }
                Bond to = null;
                for (Bond h : heirList) {
                    if (h.patronId == b.patronId) {
                        to = h;
                        break;
                    }
                }
                if (to == null) {
                    to = new Bond(b.patronId, b.lastDay);
                    heirList.add(to);
                }
                to.forgiven += b.forgiven;
                to.owed += b.owed;
            }
            trim(heirList);
        }
        setDirty();
    }

    /**
     * <b>태생적 추종</b> — 갓 난 아이는 부모가 따르는 주인을 그대로 따른다(목표 8).
     *
     * <p>부모의 가장 큰 간선을 같은 값으로 복사한다. 값을 깎지 않는 이유: 아이는 그 집
     * 사람으로 <b>태어난</b> 것이지 스스로 은혜를 입은 것이 아니다. 아이가 자라 제 힘으로
     * 땅을 가지면 임계가 올라가 저절로 풀린다.
     */
    public void inheritAtBirth(long childId, long parentId, long day) {
        double best = 0.0;
        long patron = 0L;
        for (Bond b : bondsOf(parentId)) {
            if (b.total() > best) {
                best = b.total();
                patron = b.patronId;
            }
        }
        if (patron != 0L && patron != childId && best > 0.0) {
            record(childId, patron, best, 0.0, day);
        }
    }

    /**
     * 모든 개체의 추종 대상을 한 번에 구한다 — 세력 크기·계층 판정의 단일 출처.
     *
     * @param ownedTiles 개체 id → 소유 밭 타일 수
     */
    public Map<Long, Long> patronMap(java.util.function.LongUnaryOperator ownedTiles) {
        Map<Long, Long> out = new HashMap<>();
        for (long debtor : bonds.keySet()) {
            long p = patronOf(debtor, (int) ownedTiles.applyAsLong(debtor));
            if (p != 0L) {
                out.put(debtor, p);
            }
        }
        return out;
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
        ListTag poor = tag.getList("Destitute", Tag.TAG_COMPOUND);
        for (int i = 0; i < poor.size(); i++) {
            CompoundTag t = poor.getCompound(i);
            s.destitute.put(t.getLong("D"), t.getInt("N"));
        }
        ListTag bnd = tag.getList("Bound", Tag.TAG_COMPOUND);
        for (int i = 0; i < bnd.size(); i++) {
            CompoundTag t = bnd.getCompound(i);
            s.bound.put(t.getLong("D"), t.getInt("N"));
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
        ListTag poor = new ListTag();
        for (var e : destitute.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putLong("D", e.getKey());
            t.putInt("N", e.getValue());
            poor.add(t);
        }
        tag.put("Destitute", poor);
        ListTag bnd = new ListTag();
        for (var e : bound.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putLong("D", e.getKey());
            t.putInt("N", e.getValue());
            bnd.add(t);
        }
        tag.put("Bound", bnd);
        tag.putLong("DecayedDay", decayedDay);
        return tag;
    }
}
