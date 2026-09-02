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

    /**
     * 상환분에 하루마다 붙는 이자.
     *
     * <p><b>탕감분은 옅어지고 상환분은 불어난다.</b> 종전 {@link #decayDaily} 는 둘 다
     * {@link #DECAY_PER_DAY} 로 깎았다 — 상환분이 P4 이전에는 항상 0 이라 무해했지만,
     * 세금 미납이 빚으로 쌓이기 시작하는 순간 <b>빚이 저절로 하루 5%씩 사라지는</b> 셈이었다.
     * 은혜는 잊혀도 빚은 잊히지 않는다는 설계와 정반대라 여기서 바로잡는다.
     *
     * <p>0.05 는 감쇠와 대칭이다. 갚지 않으면 약 14일에 두 배 — 수명(13일)과 같은 눈금이라,
     * 한 세대를 버티지 못한 빚은 자식에게 두 배가 되어 넘어간다(농노 세습의 산술).
     */
    public static final double INTEREST_PER_DAY = 0.05;

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

    // ── 적립 감쇠(체감) ────────────────────────────────────────────────────
    /**
     * <b>이 높이까지는 유입이 그대로 쌓인다.</b> 12 인 것은 우연이 아니라 <b>현행 소작 균형점</b>이다 —
     * 감쇠 0.95 에 하루 유입 {@link #W_TENANCY}(0.6)이면 균형이 0.6/0.05 = 12 에서 선다.
     *
     * <p>즉 매일 소작만 하는 사람의 결속은 <b>지금과 정확히 같은 자리</b>에 머문다. 감쇠를 넣어도
     * 기존 추종 관계가 하나도 안 흔들리는 이유가 이것이다 — 체감이 시작되는 지점을 현행 균형점에
     * 맞췄기 때문에, 거기 도달한 적 없는 관계는 이 함수를 거쳐도 계수 1.0 을 곱한 것과 같다.
     */
    public static final double ACCRUE_FREE = 12.0;

    /** 유입이 완전히 막히는 높이 — 여기 이상은 감쇠만 작동해 아래로 끌린다. */
    public static final double ACCRUE_CAP = 60.0;

    /**
     * <b>왜 체감이 필요한가.</b> 종전에는 유입 W 에 대해 균형이 늘 {@code 20W} 라, 하루 3.0 씩
     * 들어오는 구휼은 60 까지 <b>직선으로</b> 치솟았다. 그 값에서는 신뢰·충성·종속을 아무리
     * 나눠도 구휼 한 번 받은 사람이 곧바로 최상단을 관통해 단계가 뜻을 잃는다.
     *
     * <p>체감을 넣으면 균형점이 유입에 따라 <b>다른 높이에서</b> 선다.
     * 0.05·b = W·(1 − (b−12)/48) 을 풀면
     * <ul>
     *   <li>소작(W=0.6, 매일) → <b>12</b> — 신뢰. 지금과 같다.</li>
     *   <li>구걸(W=3.0, 매일) → <b>33.3</b> — 종속. 상습 구걸자만 여기 닿는다.</li>
     *   <li>교회 완화(감쇠 0.98)를 낀 소작 → <b>23</b> — 충성.</li>
     * </ul>
     * 단계가 산술로 갈린다 — 어느 값에도 신분 분기가 없다(규칙5).
     */
    private static double accrualDamp(double cur) {
        if (cur <= ACCRUE_FREE) {
            return 1.0;
        }
        if (cur >= ACCRUE_CAP) {
            return 0.0;
        }
        return 1.0 - (cur - ACCRUE_FREE) / (ACCRUE_CAP - ACCRUE_FREE);
    }

    // ── 추종 3단계 ────────────────────────────────────────────────────────
    /** <b>충성</b>의 문턱 — 정착. 소작만으로는(12) 닿지 않고 교회나 간헐적 구휼이 얹혀야 한다. */
    public static final double LOYAL_BOND = 15.0;

    /**
     * <b>종속</b>의 문턱 — 사실상 벗어나지 못한다. 위 산술상 <b>매일 시혜를 받는 자</b>만 닿는다
     * (균형 33.3). 땅이 있으면 아무리 신세를 져도 종속이 아니다 — 벗어날 수단이 있기 때문이다.
     */
    public static final double SERF_BOND = 30.0;

    /** 추종의 깊이. 주인이 없으면 {@link #NONE}. */
    public enum Tier {
        NONE("무관"), TRUST("신뢰"), LOYAL("충성"), SERF("종속");

        private final String label;

        Tier(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 한 채무자가 한 은인에게 진 신세. */
    public static final class Bond {
        public final long patronId;
        public double forgiven;  // 탕감분(무상)
        public double owed;      // 상환분(빚) — P2 에서는 0
        /**
         * 이 결속 중 <b>교회에서 온 몫</b>(관측 전용 — total() 에 이미 포함돼 있다).
         *
         * <p>귀속을 <b>세지 않고 말할 수 없기</b> 때문에 둔다. 사슬 깊이가 2 가 되었을 때
         * 그것이 교회 덕인지 밭 지대 덕인지 감쇠 완화 덕인지는 합계만 봐서는 갈리지 않는다.
         * 이 몫을 빼고 문턱을 다시 물으면 "교회가 없었다면" 이 그 자리에서 계산된다.
         */
        public double fromChurch;
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
        recordFrom(debtorId, patronId, forgivenAdd, owedAdd, day, false);
    }

    /** 교회 상납으로 생긴 결속 — 합계는 같고 출처만 따로 적는다({@link Bond#fromChurch}). */
    public void recordChurch(long debtorId, long patronId, double forgivenAdd, double owedAdd,
                             long day) {
        recordFrom(debtorId, patronId, forgivenAdd, owedAdd, day, true);
    }

    private void recordFrom(long debtorId, long patronId, double forgivenAdd, double owedAdd,
                            long day, boolean church) {
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
        // <b>체감은 은혜에만.</b> 빚(owed)은 이자로 불어나는 반대 방향의 물건이라 같은 계수를
        // 물리면 "많이 빌릴수록 덜 빚진다"가 되어 뜻이 뒤집힌다.
        double add = forgivenAdd * accrualDamp(hit.total());
        hit.forgiven += add;
        hit.owed += owedAdd;
        if (church) {
            // <b>탕감분만</b> 센다. 빚(owed)은 이자로 불어나고 탕감분은 감쇠로 줄어 dynamics 가
            // 반대라, 둘을 한 수에 섞으면 total()-fromChurch 가 음수로 새어 반사실이 깨진다.
            hit.fromChurch += add; // 부분집합 — 합계와 같은 계수로 들어가야 반사실이 안 깨진다
        }
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
    /**
     * <b>교회 주인의 감쇠 완화</b>(P6) — 이 사람들에게 진 신세는 하루 {@value} 로 옅어진다.
     *
     * <p>0.95 대신 0.98 이다. 균형점이 {@code W/(1−r)} 이므로 하루 유입 W 에 대해 0.95 는
     * 20배, 0.98 은 <b>50배</b>에서 멈춘다 — 같은 유입으로 두 배 반 깊은 사슬이 유지된다.
     *
     * <p><b>왜 "그날 감쇠를 건너뛴다" 가 아닌가</b>: 방문은 확률적이라 며칠 거르는 날이 반드시
     * 생기고, 건너뛰기 방식은 그런 날마다 사슬이 툭 끊긴다. P4·P5a 에서 지배 계층이 D18 에
     * 생겼다 D20~22 에 사라진 것이 정확히 그 모양이었고, 계획서가 "한 시점 관측은 무효 ·
     * <b>여러 날 연속</b> 유지" 를 판정 기준으로 못 박은 것도 같은 이유다. 감쇠율을 낮추면
     * 방문이 끊겨도 천천히 줄어 사슬이 버틴다.
     *
     * <p>대가로 한 번 생긴 사슬이 잘 안 풀린다. 그것은 목표 1·7("엘리트가 최상위 계층이 되고,
     * 죽어도 추종이 사라지지 않고 승계·축적된다")에 오히려 부합한다.
     */
    public static final double DECAY_RELIEVED = 0.98;

    /**
     * 감쇠 완화를 <b>끄는</b> 스위치 — 대조 런 전용(기본 켬, 저장되지 않는다).
     *
     * <p>완화가 실제로 사슬을 붙잡는지는 완화가 없는 같은 월드와 견주지 않으면 말할 수 없다.
     * 상수를 고쳐 빌드를 두 개 만드는 대신 스위치를 두어, 같은 jar 로 A/B 를 돌린다.
     */
    public static boolean RELIEF_ON = true;

    public void decayDaily(long day, java.util.Set<Long> aliveIds) {
        decayDaily(day, aliveIds, java.util.Set.of());
    }

    /**
     * 하루 정산 — {@code relievedPatrons} 에게 진 신세만 {@link #DECAY_RELIEVED} 로 옅어진다.
     *
     * <p>완화를 받는 것은 <b>채권자</b>다(교회 주인). 그가 베푼 은혜가 남들 기억에서 덜 지워지는
     * 것이지, 그가 남에게 진 빚이 가벼워지는 것이 아니다.
     */
    public void decayDaily(long day, java.util.Set<Long> aliveIds,
                           java.util.Set<Long> relievedPatrons) {
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
                // 교회 주인에게 진 신세는 덜 옅어진다(P6) — 그것이 지주 간 사슬을 붙잡는 못이다.
                double rate = relievedPatrons.contains(b.patronId) && RELIEF_ON
                        ? DECAY_RELIEVED : DECAY_PER_DAY;
                b.forgiven *= rate;
                b.fromChurch *= rate; // 부분집합이므로 같은 비율로 줄어야 한다
                b.owed *= 1.0 + INTEREST_PER_DAY; // 빚은 불어난다
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

    /**
     * <b>상환</b> — 갚은 만큼 빚을 지운다. 빚이 큰 간선부터 갚는다(이자가 가장 빨리 부는 쪽).
     *
     * <p>탕감분은 건드리지 않는다. 갚아서 없앨 수 있는 것은 빚이지 은혜가 아니다 — 빚을 다
     * 갚아도 <b>추종은 남는다</b>. 벗어나는 길은 여전히 스스로 올라서는 것뿐이다.
     *
     * @return 실제로 갚은 양
     */
    public double repay(long debtorId, double amount) {
        if (amount <= 0.0) {
            return 0.0;
        }
        List<Bond> list = bonds.get(debtorId);
        if (list == null || list.isEmpty()) {
            return 0.0;
        }
        List<Bond> order = new ArrayList<>(list);
        order.sort((a, b) -> Double.compare(b.owed, a.owed));
        double left = amount;
        for (Bond b : order) {
            if (left <= 0.0) {
                break;
            }
            double cut = Math.min(b.owed, left);
            b.owed -= cut;
            left -= cut;
        }
        double paid = amount - left;
        if (paid > 0.0) {
            setDirty();
        }
        return paid;
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
    /** 위엄이 문턱을 깎는 비율 / 물렁이 더하는 비율 — 0.25. 순위를 뒤집지 않는 폭. */
    public static final double COMMAND_GATE = 0.25;

    /** 넉살이 신세 적립을 키우는 비율 / 서먹이 줄이는 비율 — 0.30. */
    public static final double RAPPORT_GAIN = 0.30;

    /**
     * <b>추종 문턱 배수</b> — 위엄이면 낮추고 물렁이면 올린다. 이 문턱은 <b>따르는 쪽</b>이
     * 아니라 <b>거느리는 쪽</b>의 성질이라야 뜻이 맞는데, patronOf 는 채무자 기준으로 돌므로
     * 여기서는 <b>가장 유력한 후보(주인)</b>의 특성을 본다.
     *
     * <p>거느릴 사람이 없으면 이 함수가 불릴 일 자체가 없어 효과가 정확히 0이다.
     */
    private double commandGate(long debtorId,
                               java.util.function.LongFunction<com.evosim.core.Individual> who) {
        if (who == null) {
            return 1.0; // 해결자를 안 준 호출(보고용) — 촉매 없이 원래 문턱
        }
        long best = 0L;
        double bestVal = 0.0;
        for (Bond b : bondsOf(debtorId)) {
            if (b.total() > bestVal) {
                bestVal = b.total();
                best = b.patronId;
            }
        }
        com.evosim.core.Individual p = best == 0L ? null : who.apply(best);
        if (p == null) {
            return 1.0;
        }
        if (com.evosim.core.ExpressionResolver.isExpressed(p, com.evosim.core.Trait.COMMANDING)) {
            return 1.0 - COMMAND_GATE;
        }
        if (com.evosim.core.ExpressionResolver.isExpressed(p, com.evosim.core.Trait.MEEK)) {
            return 1.0 + COMMAND_GATE;
        }
        return 1.0;
    }

    /**
     * <b>신세 적립 배수</b> — 넉살이면 크게, 서먹이면 작게. 호출부가 채무자의 Individual 을
     * 알고 있을 때 가중치에 곱해 쓴다. 관계가 없으면 적립 자체가 없어 효과 0.
     */
    public static double rapport(com.evosim.core.Individual debtor) {
        if (debtor == null) {
            return 1.0;
        }
        if (com.evosim.core.ExpressionResolver.isExpressed(debtor, com.evosim.core.Trait.AFFABLE)) {
            return 1.0 + RAPPORT_GAIN;
        }
        if (com.evosim.core.ExpressionResolver.isExpressed(debtor,
                com.evosim.core.Trait.STANDOFFISH)) {
            return 1.0 - RAPPORT_GAIN;
        }
        return 1.0;
    }

    public long patronOf(long debtorId, int ownedTiles) {
        return patronOf(debtorId, ownedTiles, false);
    }

    /**
     * 추종 대상. {@code withoutChurch} 를 주면 <b>교회에서 온 몫을 빼고</b> 같은 판정을 한다 —
     * "교회가 없었다면 이 사람이 그를 따랐겠는가" 를 그 자리에서 되묻는 반사실 질의다.
     * 시뮬 결정에는 쓰지 않는다(보고 전용).
     */
    public long patronOf(long debtorId, int ownedTiles, boolean withoutChurch) {
        return patronOf(debtorId, ownedTiles, withoutChurch, null);
    }

    /** 위엄·물렁을 반영하는 판정 — {@code who} 가 id → Individual 해결자다(없으면 촉매 없음). */
    public long patronOf(long debtorId, int ownedTiles, boolean withoutChurch,
                         java.util.function.LongFunction<com.evosim.core.Individual> who) {
        double gate = Math.max(MIN_BOND, ownedTiles * TILE_WORTH);
        gate *= commandGate(debtorId, who);
        long best = 0L;
        double bestVal = 0.0;
        for (Bond b : bondsOf(debtorId)) {
            double v = withoutChurch ? b.total() - b.fromChurch : b.total();
            if (v > bestVal) {
                bestVal = v;
                best = b.patronId;
            }
        }
        return bestVal >= gate ? best : 0L;
    }

    /** 이 채무자가 <b>특정 은인</b>에게 진 신세 합. 없으면 0. */
    public double bondTo(long debtorId, long patronId) {
        for (Bond b : bondsOf(debtorId)) {
            if (b.patronId == patronId) {
                return b.total();
            }
        }
        return 0.0;
    }

    /**
     * <b>추종의 깊이</b>(파생) — 신뢰 / 충성 / 종속.
     *
     * <p><b>신뢰의 문턱은 새로 만들지 않았다.</b> 그것은 {@link #patronOf} 가 이미 쓰는 문턱
     * ({@code max(MIN_BOND, 타일×TILE_WORTH)}) 그 자체다. 그래야 이 단계를 얹어도 추종자 수·세력
     * 크기·밭 상한처럼 추종을 입력으로 쓰는 것들이 하나도 안 변한다. 위 두 단계만 <b>절대값</b>으로
     * 얹는다 — 문턱이 재산에 비례하는데 단계까지 비례하면 부자의 종속과 빈자의 종속이 같은 이름으로
     * 다른 물건이 되기 때문이다.
     *
     * <p>종속에 <b>무토지</b> 조건을 함께 거는 것도 같은 이유다. 땅이 있으면 아무리 신세를 져도
     * 벗어날 수단이 남아 있고, 그 사람을 천민이라 부르면 {@link SocialRank} 의 정의와 어긋난다.
     */
    public Tier tierOf(long debtorId, int ownedTiles,
                       java.util.function.LongFunction<com.evosim.core.Individual> who) {
        long p = patronOf(debtorId, ownedTiles, false, who);
        if (p == 0L) {
            return Tier.NONE;
        }
        double v = bondTo(debtorId, p);
        if (v >= SERF_BOND && ownedTiles == 0) {
            return Tier.SERF;
        }
        return v >= LOYAL_BOND ? Tier.LOYAL : Tier.TRUST;
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
            to.fromChurch += from.fromChurch; // 귀속도 함께 옮긴다 — 안 옮기면 반사실이 0 이 된다
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
                to.fromChurch += b.fromChurch;
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
        double bestChurch = 0.0;
        long patron = 0L;
        for (Bond b : bondsOf(parentId)) {
            if (b.total() > best) {
                best = b.total();
                bestChurch = b.fromChurch;
                patron = b.patronId;
            }
        }
        if (patron != 0L && patron != childId && best > 0.0) {
            record(childId, patron, best, 0.0, day);
            // 물려받은 결속의 <b>출처 비율도</b> 물려준다. 안 그러면 세대가 바뀔 때마다
            // 귀속이 씻겨 나가 "교회가 아무것도 안 했다" 로 보인다(실측: D23 결속19개 47.7 →
            // 하루 뒤 0개. 승계에서 fromChurch 를 안 옮긴 탓이었다).
            for (Bond b : bondsOf(childId)) {
                if (b.patronId == patron) {
                    b.fromChurch += bestChurch;
                    break;
                }
            }
        }
    }

    /**
     * 모든 개체의 추종 대상을 한 번에 구한다 — 세력 크기·계층 판정의 단일 출처.
     *
     * @param ownedTiles 개체 id → 소유 밭 타일 수
     */
    public Map<Long, Long> patronMap(java.util.function.LongUnaryOperator ownedTiles) {
        return patronMap(ownedTiles, null);
    }

    /** 위엄·물렁을 반영하는 추종 명부 — {@code who} 가 id → Individual 해결자다. */
    public Map<Long, Long> patronMap(java.util.function.LongUnaryOperator ownedTiles,
            java.util.function.LongFunction<com.evosim.core.Individual> who) {
        Map<Long, Long> out = new HashMap<>();
        for (long debtor : bonds.keySet()) {
            long p = patronOf(debtor, (int) ownedTiles.applyAsLong(debtor), false, who);
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
            b.fromChurch = t.getDouble("FC"); // 없으면 0 — 구세계는 귀속 불명으로 0 이 맞다
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
                t.putDouble("FC", b.fromChurch);
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
