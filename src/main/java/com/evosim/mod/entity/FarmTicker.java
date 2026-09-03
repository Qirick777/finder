package com.evosim.mod.entity;

import com.evosim.core.FarmEconomy;
import com.evosim.mod.EvoSimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 밭 익음 결정론 타이머 (M1, 실패 대응 F7). 200틱 주기로 전 구획을 스캔 — 심은 지
 * {@link FarmEconomy#RIPEN_TICKS} 경과한 타일을 익음(AGE 3)으로. 바닐라 랜덤틱 성장은
 * 보너스로 공존(타이머는 하한 보장). 미로드 청크는 건너뜀(isLoaded 가드 — 강제 로드 방지),
 * 로드 시 다음 스캔에서 일괄 성숙.
 */
@Mod.EventBusSubscriber(modid = EvoSimMod.MODID)
public final class FarmTicker {

    private static final int SCAN_INTERVAL = 200;

    /**
     * <b>작물 관리 명부</b> — 엔티티id → {구획id, 마지막 보고 틱, 케어범위×100}. 휘발(저장 안 함).
     *
     * <p>수확 goal 이 관리 중인 매 틱 {@link #reportTending} 을 부르고, 스캔이 유효한 항목만
     * 합산해 커버리지를 낸다. 마지막 보고가 한 스캔보다 오래됐으면 지운다 — 죽거나 그만둔
     * 개체가 명부에 남아 유령 가속을 만들지 않게.
     */
    private static final java.util.Map<Integer, long[]> TENDING = new java.util.HashMap<>();

    /** 관리 중임을 알린다(수확 goal 이 매 틱 호출). care = 이 개체의 케어범위(타일). */
    public static void reportTending(int entityId, long plotId, long nowTick, double care) {
        TENDING.put(entityId, new long[] {plotId, nowTick, (long) (care * 100.0)});
    }

    /** 구획별 {관리 인원, 커버리지} — 보고용. 스캔과 같은 유효성 기준을 쓴다. */
    public static double[] careOf(ServerLevel level, FarmStore.Plot plot) {
        return careOf(level, plot, -1);
    }

    /**
     * {@code exceptEntity} 를 뺀 관리 현황 — "내가 빠져도 이미 만석인가"를 묻는 데 쓴다.
     *
     * <p>커버리지는 1.0 에서 잘리므로 <b>만석인 밭에 더 붙어도 산출이 전혀 늘지 않는다</b>.
     * 그런데도 서 있으면 순수한 낭비이고, 좁은 밭에서는 서로 부대껴 끼임이 된다 — 육안 관측:
     * 6타일 밭(1인이면 이미 100%)에 5명이 몰려 있었다. 긴급고용이 밭 크기를 보지 않고 굶는
     * 개체를 밀어 넣는데(정원초과 허용), 관리가 생기면서 그들이 흩어지지 않고 눌러앉은 탓이다.
     */
    public static double[] careOf(ServerLevel level, FarmStore.Plot plot, int exceptEntity) {
        long now = com.evosim.mod.entity.SimTime.tick(level);
        int n = 0;
        double covered = 0.0;
        for (var e : TENDING.entrySet()) {
            long[] v = e.getValue();
            if (e.getKey() == exceptEntity || v[0] != plot.id || now - v[1] > SCAN_INTERVAL) {
                continue;
            }
            n++;
            covered += v[2] / 100.0;
        }
        int tiles = Math.max(1, plot.tiles.length);
        return new double[] {n, Math.min(1.0, covered / tiles)};
    }
    private static final double COMMUTE = 48.0;              // 통근 도달 기대(블록) — 안전판 기준
    private static final double DISSOLVE_DIST = 128.0;       // 상시 소작 해제 거리(이 밖 이주 시)
    /** 그날 배정표(휘발 — 재접속 시 하루 공침 허용, 계획 F6): entityId → plotId. */
    private static final java.util.Map<Integer, Long> ASSIGNED = new java.util.HashMap<>();
    private static long assignDay = -1;
    private static long rentDay = -1;
    private static long growDay = -1;
    /** 어제 배정 스냅샷(연속일 판정용, 휘발 — 재접속 시 연속일은 NBT 값에서 이어감). */
    private static final java.util.Map<Integer, Long> LAST_ASSIGNED = new java.util.HashMap<>();

    /**
     * 도달 실패 기록(개체 → 포기한 구획들, 새벽마다 비움) — 긴급 고용이 거리 무제한이라, 길이
     * 끊겨 갈 수 없는 밭에 계속 배정되면 밭일 goal(우선순위 6)이 채집(7)을 선점한 채 제자리에
     * 서 있게 되어 <b>오히려 더 빨리 죽는다</b>. MimicFarmGoal 이 무진전을 감지해 여기 적으면
     * 그날은 그 구획을 후보에서 뺀다.
     */
    private static final java.util.Map<Integer, java.util.Set<Long>> UNREACHABLE =
            new java.util.HashMap<>();

    /** 밭일 goal 이 표적에 도달하지 못했음을 보고 — 배정을 풀어 채집으로 돌려보낸다. */
    public static void reportUnreachable(int entityId, long plotId) {
        if (plotId == 0L) {
            return;
        }
        UNREACHABLE.computeIfAbsent(entityId, k -> new java.util.HashSet<>()).add(plotId);
        if (ASSIGNED.getOrDefault(entityId, 0L) == plotId) {
            ASSIGNED.remove(entityId);
        }
    }
    /** 오늘 소작 임금 원장(휘발, v1.3) — plotId → 지급 합. 마름 수당(1인 평균×계수)의 입력. */
    private static final java.util.Map<Long, Double> TENANT_PAY_TODAY = new java.util.HashMap<>();
    /** 오늘 이 구획에서 수확한 소작(개체 id 집합, 휘발) — 평균의 분모. */
    private static final java.util.Map<Long, java.util.Set<Integer>> TENANT_WORKERS_TODAY =
            new java.util.HashMap<>();

    private FarmTicker() {
    }

    /** 이 개체가 오늘 배정된 밭(없으면 0) — MimicFarmGoal 의 소작 경로 입력. */
    public static long assignedPlot(int entityId) {
        return ASSIGNED.getOrDefault(entityId, 0L);
    }

    /** 소작 임금 적립 기록(수확 시점) — 마름 수당 산정 입력(마름 본인 노동분은 호출부에서 제외). */
    public static void recordTenantPay(long plotId, double share, int workerId) {
        TENANT_PAY_TODAY.merge(plotId, share, Double::sum);
        TENANT_WORKERS_TODAY.computeIfAbsent(plotId, k -> new java.util.HashSet<>()).add(workerId);
    }

    /** 오늘 이 구획에 배정된 인원(상시+일용) — 마름 노동/관리 모드 판정(0 = 노동 모드). */
    /**
     * 긴급고용 인원 상한 A/B — 끄면 종전 거동(초과 배정 무제한). 상한이 <b>굶는 자를 일자리에서
     * 밀어내는 대가</b>를 치르므로(아사 증가) 껐다 켜며 재야 한다.
     */
    private static boolean hireCap = true;

    public static void setHireCap(boolean on) {
        hireCap = on;
    }

    public static boolean hireCap() {
        return hireCap;
    }

    // ── 구걸 ──────────────────────────────────────────────────────────────
    /**
     * 구걸 스위치 — 끄면 종전 거동(자리가 없어도 꽉 찬 밭에 밀어 넣기).
     *
     * <p>이것이 A/B 로 갈리는 것이 중요한 이유: 구걸로 돌리는 인원은 <b>밭이 요청한 인원이
     * 아니다.</b> d11 실측에서 긴급고용 84건 중 71건이 "정원초과"(빈자리 경로 open 이 null)
     * 였고, 그 71건은 이미 코드상 상시소작 승격도 막혀 있었다 — 하루 붙였다 떼는 잉여였다.
     * 그것을 빼도 밭이 안 줄어든다는 주장은 스위치로 견주지 않으면 말이 안 된다.
     */
    private static boolean begOn = true;

    public static void setBeg(boolean on) {
        begOn = on;
    }

    public static boolean beg() {
        return begOn;
    }

    /** 1회 시혜량 — 하루치를 겨우 넘기는 크기. 지원 폭격이 아니다. */
    public static final double ALMS_UNIT = 1.0;

    /** 한 가구가 하루에 내주는 상한(유닛) — 부자 한 집이 마을 전체를 먹여 살리지 못하게. */
    public static final int ALMS_HOME_CAP = 3;

    /** 거리 완충 — 점수가 {@code 잉여/(거리+K)} 라 아래 상한 안에서는 먼 집도 후보로 남는다. */
    private static final double BEG_DIST_K = 32.0;

    /**
     * <b>거리 상한은 두지 않는다.</b> 한때 96 을 걸었다가 되돌렸다 — 226블록에서 완주를 못 한
     * 원인이 거리가 아니라 <b>밤에 앵커를 놓던 것</b>이었기 때문이다(구혼 여행처럼 마감만
     * 보게 고쳤다: {@link MimicEntity#isBegging}). 원인을 고치기 전에 거리부터 줄이는 것은
     * 문제를 푼 것이 아니라 작게 만든 것이다.
     *
     * <p>여행이 실제로 되는 지금, 반경으로 자를 이유가 없다. 근처에 여유 있는 집이 하나도
     * 없다는 이유로 굶어 죽는 것이야말로 구걸이 막으려는 상황이고, 점수식이 나눗셈
     * ({@code 잉여/(거리+32)})이라 거리는 이미 순위를 강하게 누른다 — 200블록 밖의 집이
     * 이기려면 코앞의 집보다 여유가 여섯 배는 많아야 한다.
     *
     * <p>가도 못 닿는 경우의 안전판은 마감이다: {@link #BEG_TRAVEL} 이 지나면 앵커가 풀리고
     * 다음 새벽에 그날 형편으로 다시 고른다.
     */
    private static final long BEG_TRAVEL = com.evosim.core.Famine.TRAVEL_DURATION;

    /** 아는 집(이미 신세가 있는 상대) 가산 — 낯선 문을 두드리기보다 은인을 다시 찾는다. */
    private static final double BEG_KNOWN_BONUS = 1.5;

    /** 거처 → 오늘 내준 유닛 수. 하루가 바뀌면 비운다. */
    private static final java.util.Map<Long, Integer> ALMS_GIVEN = new java.util.HashMap<>();

    /**
     * 오늘 이미 손을 벌린 사람(엔티티 id) — <b>하루 한 번</b>이 이것으로 강제된다.
     *
     * <p>없으면 안 되는 이유: 이 정산은 200틱마다 도는데, 1유닛을 받고 나면 위급(H&lt;0.3)은
     * 풀려도 채집 시계({@code forageDry})는 그대로 말라 있다. 그러면 같은 사람이 하루에도
     * 여남은 번 다시 발동해 한 집을 훑는다 — 지원 폭격이고, 신세 균형점도 33 이 아니라 47 로
     * 뛰어 종속 문턱이 뜻을 잃는다. 성패와 무관하게 <b>목적지를 잡은 순간</b> 표시한다.
     */
    private static final java.util.Set<Integer> BEGGED_TODAY = new java.util.HashSet<>();

    private static long almsDay = Long.MIN_VALUE;

    /** 이 개체가 오늘 배정된 구획(0 = 없음) — 진단용. */
    public static long assignedPlotOf(int entityId) {
        return ASSIGNED.getOrDefault(entityId, 0L);
    }

    public static int assignedToPlot(long plotId) {
        int n = 0;
        for (long v : ASSIGNED.values()) {
            if (v == plotId) {
                n++;
            }
        }
        return n;
    }

    /**
     * 밤 지대 정산 — 하루 1회(취침 시간대 첫 스캔): 밭 계정에서 <b>정수 유닛만</b> 주인 거처
     * 저장고로 이체(잔여 소수는 이월 — L 정수성 보존, 계획 P2). 주인 부재(미로드·사망 직후)나
     * 무주택이면 건너뜀 — 계정은 구획에 붙어 있어 손실 없음(F8).
     */
    private static void settleRent(ServerLevel level) {
        long day = com.evosim.mod.entity.SimTime.tick(level) / 24000L;
        long tod = level.getDayTime() % 24000L;
        if (day == rentDay || tod < 13000L) {
            return;
        }
        rentDay = day;
        FarmStore store = FarmStore.get(level);
        LarderStore larder = LarderStore.get(level);
        for (FarmStore.Plot plot : store.all().values()) {
            BlockPos home = null;
            MimicEntity ownerEnt = null;
            for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                    e -> e.isAlive() && e.getIndividual() != null
                            && e.getIndividual().id() == plot.ownerId)) {
                home = m.getHomePos();
                ownerEnt = m;
            }
            // ── 마름 수당(v1.3) — 지대 이체 전에 구획 계정에서: 소작 1인 평균 일수취 ×
            // (0.5+0.05g+0.02×근속, 상한 1.0), 정수 유닛만(L 정수성). 소작 0(노동 모드)이면 무수당.
            MimicEntity stwEnt = null;
            if (plot.stewardId != 0L) {
                for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                        e -> e.isAlive() && e.getIndividual() != null
                                && e.getIndividual().id() == plot.stewardId)) {
                    stwEnt = m;
                }
                double paid = TENANT_PAY_TODAY.getOrDefault(plot.id, 0.0);
                int workers = TENANT_WORKERS_TODAY.getOrDefault(plot.id, java.util.Set.of()).size();
                if (stwEnt != null && stwEnt.getHomePos() != null && paid > 0.0 && workers > 0) {
                    int g = com.evosim.core.Multipliers.manageAbilityGrade(stwEnt.getIndividual());
                    long tenure = plot.stewardSince >= 0 ? day - plot.stewardSince : 0L;
                    // <b>소수를 버리지 않고 넘긴다.</b> 저장고는 정수 유닛으로만 움직이는데
                    // 매일 버리면 최대 1 미만이 사라진다 — 실측: 계수 1.00 에 소작 평균 1.54
                    // 인데 수당은 늘 +1 이었다(0.54 소실 = 35%). 계수나 상한을 올려도 버림이
                    // 그대로 먹으므로 그쪽으로는 안 풀린다. 축장·상환과 같은 방식이다.
                    double mult = com.evosim.core.FarmEconomy.stewardWageMult(g, tenure);
                    double due = paid / workers * mult + plot.wageCarry;
                    double wage = Math.min(plot.account, due);
                    int wUnits = (int) Math.floor(wage);
                    plot.wageCarry = Math.max(0.0, due - Math.max(0, wUnits));
                    if (wUnits >= 1) {
                        larder.set(stwEnt.getHomePos(), larder.get(stwEnt.getHomePos()) + wUnits);
                        plot.account -= wUnits;
                        com.evosim.mod.log.SimAudit.record(
                                com.evosim.mod.log.SimAudit.Src.WAGE, wUnits);
                        com.evosim.mod.log.SimEvents.event(stwEnt, "수당", String.format(
                                "구획 %d 마름 수당 +%d (평균 %.2f × 계수 %.2f · 근속 %d일 · 이월 %.2f)",
                                plot.id, wUnits, paid / workers, mult, tenure, plot.wageCarry));
                    }
                    store.setDirty();
                }
                // ── 편입 착공비 상환 — 영주 저장고 → 마름(예비 12 보호, 부족분 이월)
                if (plot.stewardDebt >= 1.0 && stwEnt != null && stwEnt.getHomePos() != null
                        && home != null) {
                    int pay = (int) Math.floor(Math.min(plot.stewardDebt, Math.max(0.0,
                            larder.get(home) - com.evosim.core.FarmEconomy.INVEST_RESERVE)));
                    if (pay >= 1) {
                        larder.set(home, larder.get(home) - pay);
                        larder.set(stwEnt.getHomePos(), larder.get(stwEnt.getHomePos()) + pay);
                        plot.stewardDebt -= pay;
                        store.setDirty();
                        com.evosim.mod.log.SimEvents.event(stwEnt, "상환", String.format(
                                "구획 %d 착공비 +%d (잔여 %.0f — 가문 편입 정산)",
                                plot.id, pay, plot.stewardDebt));
                    }
                }
            }
            if (home == null) {
                continue; // 주인 무주택·부재 — 계정·축장 모두 이월(다음 밤 재시도)
            }
            // fee 분할(E11) — 초과분 축장 정산: 잠금 축장의 정수 유닛을 지주 저장고로(확장 무관, 소수
            // 이월). 소득 감사(RENT)는 수확 시점에 이미 계상됐으므로 여기선 재계상하지 않음(중복 방지).
            int hoardUnits = (int) Math.floor(plot.excessHoard);
            if (hoardUnits > 0) {
                larder.set(home, larder.get(home) + hoardUnits);
                plot.excessHoard -= hoardUnits;
                store.setDirty();
                com.evosim.mod.log.SimEvents.event(ownerEnt, "축장", String.format(
                        "구획 %d: +%d 저장고(잠금 축장 — 이월 %.2f)", plot.id, hoardUnits, plot.excessHoard));
            }
            int units = (int) Math.floor(plot.account);
            if (units <= 0) {
                continue;
            }
            larder.set(home, larder.get(home) + units);
            plot.account -= units;
            store.setDirty();
            // 이체가 실제로 끝난 뒤에만 기록(결과값 원칙) — 소작농화 추적의 경제 사슬 링크.
            com.evosim.mod.log.SimEvents.event(ownerEnt, "지대", String.format(
                    "구획 %d: +%d 저장고(이월 %.2f)", plot.id, units, plot.account));
        }
        TENANT_PAY_TODAY.clear(); // 일일 원장 마감(수당 산정 후)
        TENANT_WORKERS_TODAY.clear();
    }

    /**
     * 밤 성장 — 하루 1회(지대 정산과 같은 시각창): ① 확장 — 소작 붙은 밭은 <b>가장 가까운 상시
     * 소작농</b>이 확장 주체(확장권 이전, 주인 금지), 무소작이면 주인. 주체의 거처 저장고에서
     * 타일당 EXPAND_COST 차감(INVEST_RESERVE 여유 필수), 하루 EXPAND_PER_DAY 상한(노동 병목 P1-ⓐ),
     * 규모 천장 없음 — 관리 효율 감쇠가 지대를 깎아 자연 정체(수치 유도). ② 신규 개간 — 주인 저장고가
     * newFarmCost(체증)+여유면 집 주변 빈 부지에 T1 착공(부지 없으면 건너뜀).
     */
    private static void growFarms(ServerLevel level) {
        long day = com.evosim.mod.entity.SimTime.tick(level) / 24000L;
        long tod = level.getDayTime() % 24000L;
        if (day == growDay || tod < 13000L) {
            return;
        }
        growDay = day;
        FarmStore store = FarmStore.get(level);
        LarderStore larders = LarderStore.get(level);
        java.util.List<MimicEntity> adults = new java.util.ArrayList<>(level.getEntities(
                com.evosim.mod.reg.ModEntities.MIMIC.get(),
                m -> m.isAlive() && m.getIndividual() != null
                        && (m.getStage() == com.evosim.core.LifeStage.ADULT
                                || m.getStage() == com.evosim.core.LifeStage.ELDER)));
        // ── 신세 원장(P2) — 이미 흐르는 관계를 기록만 한다. 행동은 아무것도 바꾸지 않는다.
        //    상시 소작은 작지만 <b>매일</b> 쌓인다(구휼·긴급고용은 그 사건 지점에서 기록).
        //    감쇠는 하루 한 번, 살아 있는 개체 집합을 넘겨 죽은 자의 간선을 함께 정리한다.
        {
            AllegianceStore ledger = AllegianceStore.get(level);
            java.util.List<MimicEntity> everyone = new java.util.ArrayList<>(
                    level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                            e -> e.isAlive() && e.getIndividual() != null));
            java.util.Set<Long> alive = new java.util.HashSet<>();
            for (MimicEntity m : everyone) {
                alive.add(m.getIndividual().id());
            }
            for (MimicEntity m : adults) {
                long pid = m.getTenantFarm();
                if (pid == 0L) {
                    continue;
                }
                FarmStore.Plot p = store.get(pid);
                if (p != null) {
                    ledger.record(m.getIndividual().id(), p.ownerId,
                            AllegianceStore.W_TENANCY, 0.0, day);
                }
            }
            // <b>교회 주인은 감쇠 완화를 받는다</b>(P6, 계획서 1.5). 이것이 상납 사슬을
            // 붙잡는 못이다 — 원장에 간선을 만드는 셋(소작·구휼·긴급고용)은 전부 가난한 쪽이
            // 받는 것이라 지주가 지주에게 신세 질 길이 없었고, 그래서 사슬이 깊이 1 에
            // 머물렀다. 교회는 추종에 매이지 않아 지주도 방문하고, 그 주인의 신세는 덜 옅어진다.
            java.util.Set<Long> churchOwners = new java.util.HashSet<>();
            for (FacilityStore.Entry fe : FacilityStore.get(level).all()) {
                if (fe.kind.group == FacilityTemplate.Group.CHURCH) {
                    churchOwners.add(fe.ownerId);
                }
            }
            ledger.decayDaily(day, alive, churchOwners);
            // ── 연속 궁핍 일수(P3.5) — <b>계측 전용</b>. "가구 저장고가 가구 하루소모에 못
            //    미치는가"를 새벽에 한 번 적는다. 처음에는 이것으로 천민을 재려 했는데 측정에서
            //    0 이 나왔다(D14: 계층별 평균 살림 20 · 재산 최소 10 — 가장 가난한 가구조차
            //    하루치가 있다). "굶는가"는 "벗어나지 못하는가"와 다른 질문이라 척도를 예속
            //    지속으로 바꿨고, 이 수는 <b>아무도 굶지 않는다</b>는 사실을 계속 보이기 위해
            //    남긴다. 가구 단위라 한 지붕 아래 사람은 아이까지 함께 적힌다.
            //    <b>기록만 하고 어떤 행동도 이 값으로 갈리지 않는다.</b>
            java.util.Map<net.minecraft.core.BlockPos, Boolean> poorHome = new java.util.HashMap<>();
            for (MimicEntity m : adults) {
                net.minecraft.core.BlockPos h = m.getHomePos();
                if (h == null || poorHome.containsKey(h)) {
                    continue;
                }
                poorHome.put(h, larders.get(h) < familyDailyNeed(level, m, adults));
            }
            for (MimicEntity m : everyone) {
                net.minecraft.core.BlockPos h = m.getHomePos();
                ledger.noteDestitution(m.getIndividual().id(),
                        h != null && Boolean.TRUE.equals(poorHome.get(h)));
            }
            // 세력 크기 — 밭 상한이 여기에 연동된다(목표 1·2·9 를 한 장치로).
            // 추종 판정은 소유 타일에 비례한 임계를 쓰므로 원장에서 한 번에 구한다.
            // 위엄·물렁(보조) — 추종 문턱은 <b>거느리는 쪽</b>의 성질이라 후보 주인의 특성을
            // 봐야 한다. 원장은 id 만 알므로 해결자를 넘긴다.
            java.util.Map<Long, com.evosim.core.Individual> byId = new java.util.HashMap<>();
            for (MimicEntity m : everyone) {
                byId.putIfAbsent(m.getIndividual().id(), m.getIndividual());
            }
            java.util.Map<Long, Long> patrons =
                    ledger.patronMap(id -> store.ownedTiles(id), byId::get);
            FOLLOWERS.clear();
            FOLLOWER_HOMES.clear();
            for (long p : patrons.values()) {
                FOLLOWERS.merge(p, 1, Integer::sum);
            }
            for (var pe : patrons.entrySet()) {
                MimicEntity f = null;
                for (MimicEntity m : everyone) {
                    if (m.getIndividual().id() == pe.getKey()) {
                        f = m;
                        break;
                    }
                }
                if (f != null && f.getHomePos() != null) {
                    FOLLOWER_HOMES.computeIfAbsent(pe.getValue(),
                            k -> new java.util.ArrayList<>()).add(f.getHomePos());
                }
            }
            // ── 연속 예속 일수(P3.5) — 천민 판정의 주 척도. 주인이 있고 제 땅이 없는 상태가
            //    오늘도 이어졌는가. 벗어나는 길은 이미 있다 — 땅을 갖거나 스스로 추종자를
            //    얻으면 조건이 깨져 0 으로 돌아간다. 이 역시 <b>기록만 한다.</b>
            for (MimicEntity m : everyone) {
                long id = m.getIndividual().id();
                ledger.noteBondage(id,
                        patrons.containsKey(id) && store.ownedTiles(id) == 0
                                && !FOLLOWERS.containsKey(id));
            }
            collectTribute(level, ledger, larders, adults, everyone, patrons, day);
            runSchools(level, ledger, larders, adults, everyone, patrons, day);
            runBarracks(level, ledger, larders, adults, patrons, day);
            settleOccupation(level, adults); // 배속이 끝난 뒤에 — 순회 순서로 승패가 갈리지 않게
            runChurches(level, ledger, larders, everyone, patrons, day);
        }
        // 개체별 당일 개간 노동 합계 — 다구획 주인 1인이 하루 EXPAND_PER_DAY 를 넘지 못하게(R3).
        java.util.Map<Integer, Integer> grownToday = new java.util.HashMap<>();
        // ① 확장 — 최신(직영) 구획 우선 순회(회차 26): 구 구획이 저장고 여유를 먼저 흡수하면
        // 최신 구획이 9타일에서 영구 동결 → 성숙(24) 불가 → 다음 밭 자격·예비가 12로 주저앉아
        // 3호가 원천 봉쇄되는 교착 v2(런18 티모시 실측: 구획2 지대 +2/일 고정). 케어 배분의
        // "주인 노동은 최신 구획부터"와 같은 원리를 자금 순회에도 적용.
        java.util.List<FarmStore.Plot> expandOrder =
                new java.util.ArrayList<>(store.all().values());
        expandOrder.sort(java.util.Comparator.comparingLong((FarmStore.Plot p) -> -p.id));
        for (FarmStore.Plot plot : expandOrder) {
            MimicEntity ownerEnt = null;
            MimicEntity tenantEnt = null;
            for (MimicEntity m : adults) {
                if (m.getIndividual().id() == plot.ownerId) {
                    ownerEnt = m;
                } else if (m.getTenantFarm() == plot.id && m.getHomePos() != null
                        && (tenantEnt == null || m.blockPosition().distSqr(plot.anchor)
                                < tenantEnt.blockPosition().distSqr(plot.anchor))) {
                    tenantEnt = m; // 확장 주체 후보 — 유주택 상시 소작(비용은 저장고에서)
                }
            }
            boolean hasTenant = tenantEnt != null;
            MimicEntity grower = hasTenant ? tenantEnt : ownerEnt; // 확장권 이전: 소작 있으면 주인 금지
            if (grower == null || grower.getHomePos() == null || ownerEnt == null) {
                continue; // 게이트 판단(주인 능력)·지불 원천이 없으면 보수적으로 건너뜀
            }
            // 확장 동기 게이트는 <b>자영 밭에만</b> 건다(소작 밭은 소작농이 알아서 넓힌다).
            //
            // 이 파일의 다른 세 곳이 이미 "확장은 소작농의 일"이라고 말하고 있었다: 확장 주체는
            // grower = 소작 우선(위 "확장권 이전"), 하루 확장량은 EXPAND_PER_DAY×(1+소작수)로
            // 소작 수에 비례, 자금은 소작 붙은 성숙 밭이면 plot.account 만 쓴다(아래 bootstrap
            // 분기 — 지주 저장고를 건드리지 않는다). 바로 위 주석조차 "소작농의 만족은 무관"이라고
            // 적는다.
            // 그런데 종전에는 <b>지주</b>의 만족이 그 전부를 멈췄다 — 지주가 배부르면 소작농이
            // 지대 계정으로 하는 개간까지 정지했다.
            //
            // 재투자 캡 복원으로 지주 저장고가 실제로 쌓이기 시작하면(+33/일) 지주는 곧 만족선을
            // 넘고, 야망가 예외도 밭 49타일에서 이미 소진된다(AMBITION_TILE_GOAL). 그 시점부터
            // 규칙4(밭의 지속 성장)가 통째로 멈추는 구조였다. 자영 밭은 종전대로 주인 동기에
            // 달려 있으므로 평민에 대한 "만족의 덫"(규칙2)은 그대로 유지된다.
            if (!hasTenant && (ownerEnt.isSatisfiedToday()
                    || com.evosim.core.Satisfaction.neverExpands(ownerEnt.getIndividual()))) {
                continue;
            }
            // 규모 상한 없음(하드캡 폐지) — 관리 효율 감쇠(FarmEconomy.manageEfficiency)가
            // 수확·지대를 깎아 확장 자금이 마르는 것으로 자연 정체(강제 규칙 → 수치 유도).
            // 소작 비례 확장(소작 루프 v2): 구획 하루 노동 = 3×(1+상시소작 수), 상한 12 —
            // "소작농들이 밭을 키운다"의 수치화. 자영(소작 0)은 종전대로 개체 단위 3.
            int nTen = 0;
            for (MimicEntity m : adults) {
                if (m.getTenantFarm() == plot.id) {
                    nTen++;
                }
            }
            int plotLabor = Math.min(com.evosim.core.FarmEconomy.EXPAND_DAY_MAX,
                    com.evosim.core.FarmEconomy.EXPAND_PER_DAY * (1 + nTen));
            int labor = hasTenant
                    ? plotLabor
                    : com.evosim.core.FarmEconomy.EXPAND_PER_DAY
                            - grownToday.getOrDefault(grower.getId(), 0); // 자영 상한은 개체 단위(R3)
            int room = labor;
            if (room <= 0) {
                continue;
            }
            // 자금(fee 분할 E11 ②③ + 부트스트랩 예외): 소작 붙은 성숙 밭은 <b>계정만</b>으로 —
            // 성숙 지주의 저장고 축장을 확장이 못 갉게 격리(누수 B 차단, 격차 생전 지속).
            // 그 밖(소작 없거나 아직 어린 밭)은 계정이 얇아 저장고가 유일 연료 — 9→24 성숙
            // 부트스트랩을 저장고로 굴린다(격리하면 신생 밭이 영구 동결). 그런 밭은 초과분
            // 축장이 거의 없으므로 저장고를 써도 격차에 무관.
            //
            // 재투자 캡(MATURE_REINVEST_SHARE) 복원 — "폐지 유지"가 규칙5를 구조적으로 불가능하게
            // 하고 있었다. 계정 <b>전액</b>을 타일로 바꾸면 지대가 저장고에 닿기 전에 확장이
            // 먹어치운다(v2 D16 실측: 지대 47.6/일 · 확장 지출 50/일 · 지주 저장고 9.7 — 소작
            // 17.4보다 가난한 지주). 상수는 이미 있었고 그 주석이 의도까지 적어 두었다("잔여
            // 70%는 밤 정산 때 지주 저장고로 이체 — '이름만 지주'가 아니라 현금이 쌓이는 지주"):
            // 라이브 코드에서만 꺼져 있었다. 복원 시 확장 14.3/일 · 저장고 +33.3/일.
            // 부수 효과로 확장 속도가 3.5배 느려져, 노동(소작 1인당 +8타일/일)이 확장을 따라잡는
            // 범위로 들어온다 — 익은 채 방치되던 타일(실측 79/303)이 함께 줄어든다.
            int afford = com.evosim.core.FarmEconomy.reinvestTiles(
                    plot.account * com.evosim.core.FarmEconomy.MATURE_REINVEST_SHARE, plot.steps + 1);
            double ownerFunds = 0.0;
            // 부트스트랩 — 밭이 <b>자립 규모에 못 미치면</b> 지주 저장고가 받친다.
            //
            // 종전 조건은 nTen == 0, 즉 "상시소작이 하나라도 있는가"였다. 밭의 크기를 전혀 보지
            // 않는 이분법이라, 10타일짜리 밭에 소작 한 명이 붙는 순간 지주 지원이 끊겼다.
            // 실측(사용자 월드): 12타일 · 계정 3.1 · 지주 저장고 60 → 계정 30%인 0.93 만 쓸 수
            // 있어 하루 1칸. 자립 못 하는 크기에서 자립시킨 것이다.
            //
            // 이 조건이 오래 버틴 이유는 승격이 정상 배정으로만 되던 시절에는 소작이 늦게
            // 붙어 "소작 없음 ≈ 아직 작음"이 우연히 성립했기 때문이다. 긴급고용에도 승격을
            // 달자(2923596) 그 우연이 깨졌다 — 굶기 직전인 사람을 아무 밭에나 붙이는 응급
            // 경로라 수가 많고, 밭이 6~12타일일 때 이미 상시소작이 생긴다.
            //
            // 그래서 우연히 성립하던 대리 지표를 버리고 <b>크기를 직접 본다</b>. 수는
            // SELF_FUNDING_TILES 주석의 실측 표에서 잡았다.
            boolean bootstrap = plot.tiles.length < com.evosim.core.FarmEconomy.SELF_FUNDING_TILES;
            if (bootstrap) {
                // 부트스트랩(미성숙) — 저장고 예비 위 잉여를 폴백 재원으로
                ownerFunds = ownerEnt.getHomePos() != null
                        ? larders.get(ownerEnt.getHomePos()) : 0.0;
                boolean eligible = nextFarmEligible(store, adults, plot.ownerId);
                double reserve = com.evosim.core.FarmEconomy.expandReserve(
                        eligible, store.ownedCount(plot.ownerId),
                        familyDailyNeed(level, ownerEnt, adults));
                afford += (int) Math.floor(Math.max(0.0, ownerFunds - reserve)
                        / com.evosim.core.FarmEconomy.expandCost(plot.steps + 1));
            }
            // 구획 타일 상한 — 밭은 흔하되 마구 커지지 않는다. 상한이 추종자 수에 비례해
            // 올라가므로 <b>사람을 거느린 자만</b> 크게 키운다. 일반민은 밭을 열 수는 있으나
            // 키울 수 없다(목표 9: 시도하나 능력이 안 됨).
            int cap = com.evosim.core.FarmEconomy.plotTileCap(
                    FOLLOWERS.getOrDefault(plot.ownerId, 0));
            int k = Math.min(Math.min(room, afford), Math.max(0, cap - plot.tiles.length));
            if (k <= 0) {
                continue;
            }
            // ── 덩어리 도면 성장 ────────────────────────────────────────────
            //
            // 칸을 이어 붙이지 않는다. 확보한 발자국 안의 <b>안 심은 재배 칸</b>을 도면 순서대로
            // 채우고, 다 채웠으면 다음 단계 발자국을 예약한다(원목은 예약 즉시 깔린다).
            // 그래서 미완성은 언제나 마지막 줄 하나에만 남고, 밭은 늘 반듯한 직사각형이다.
            int placed = 0;
            if (plot.beds <= 0) {
                continue; // 구세계 구획(칸 수열로 자란 옛 밭) — 모양을 건드리지 않는다
            }
            // <b>남은 칸이 전부 막혔으면 다음 단계를 연다.</b>
            //
            // 종전에는 reserveNext(= 다음 발자국 예약 = 네 방향 시도)를 <b>todo 가 빌 때만</b>
            // 불렀다. 그래서 발자국 안에 못 심는 칸이 하나라도 남아 있으면 todo 가 영영 안 비고,
            // 밭은 <b>바깥을 쳐다보지도 못한 채</b> 매일 그 칸에만 헛손질하다 placed==0 으로
            // 막힘 처리됐다. 사방이 빈 풀밭인데 12타일에서 멈추고 새 밭을 파던 것이 이것이다
            // (육안 관측) — 확장할 방향이 남아 있어도 시도할 기회 자체가 없었다.
            //
            // 그래서 두 번 본다: 먼저 남은 칸을 심어 보고, 한 칸도 못 심었으면 그 칸들은
            // 영구히 막힌 것으로 보고 다음 단계를 열어 그쪽에 심는다. 막힌 칸은 발자국 안에
            // 그대로 남지만(도면은 유지) 성장을 더는 붙들지 않는다.
            java.util.List<int[]> todo = unplanted(store, plot);
            if (todo.isEmpty() && reserveNext(level, store, plot, adults)) {
                todo = unplanted(store, plot);
            }
            placed = plantFrom(level, store, plot, ownerEnt, todo, k);
            if (placed == 0 && reserveNext(level, store, plot, adults)) {
                placed = plantFrom(level, store, plot, ownerEnt, unplanted(store, plot), k);
            }
            // 공간 포화 감지 — 자금·노동은 있었는데 한 칸도 못 심음. 2일 연속이면 성숙 간주(막힌
            // 밭도 다음 밭을 연다 — 교착 방지). 심었으면 리셋.
            if (placed == 0) {
                // 발자국 예약이 두 수 × 양쪽 = 네 방향을 이미 다 봤다 — 여기 오면 진짜 포화다.
                // 방향 전환(turnDir)은 뜻이 없어졌다: 도면이 매번 네 방향을 본다.
                plot.blockedDays++;
                store.setDirty();
                // <b>막힌 사유를 남긴다.</b> 이 카운터가 1 이 되면 nextFarmBlock 이 크기를 안 보고
                // 다음 밭 자격을 열어 준다 — 즉 이 한 줄이 "12칸 밭을 두고 새 밭을 판다"의
                // 방아쇠다. 그런데 종전에는 조용히 증가만 해서, 사방이 빈 풀밭인데 막힌 것을
                // 육안으로 보고도 원인을 짚을 수 없었다.
                com.evosim.mod.log.SimEvents.note(level, "밭막힘", String.format(
                        "구획 %d(%d타일 · %d단계) 확장 실패 %d일째 — 마지막 거부: %s"
                                + " (자금 %d칸분 · 노동 %d칸분은 있었다)",
                        plot.id, plot.tiles.length, plot.steps + 1, plot.blockedDays,
                        lastBoxFault, afford, room));
            } else if (plot.blockedDays != 0) {
                plot.blockedDays = 0;
                store.setDirty();
            }
            if (placed > 0) {
                // 지불: 밭 계정 먼저 소진, 잔여는 부트스트랩 밭 한정 주인 저장고. 소작 붙은
                // 성숙 밭은 afford=내림(계정)이라 fromLarder=0(저장고 무손실 — 축장 보호).
                // 회계 합 = placed×cost.
                //
                // <b>이 조건은 위 afford 산정과 반드시 같아야 한다.</b> 재원에 저장고를 더해
                // 놓고 여기서 안 빼면 그만큼 타일이 공짜로 생긴다(무에서 식량 창조 — 감사
                // 항등식 파괴). 그래서 위에서 한 번 판정한 bootstrap 을 그대로 쓴다.
                double bill = placed * com.evosim.core.FarmEconomy.expandCost(plot.steps + 1);
                plot.totalSpentExpand += bill; // 부양력 계산의 입력 — 번 것 중 되돌린 몫
                double fromAccount = Math.min(plot.account, bill);
                plot.account -= fromAccount;
                double fromLarder = bill - fromAccount;
                if (fromLarder > 0 && bootstrap && ownerEnt.getHomePos() != null) {
                    larders.set(ownerEnt.getHomePos(), Math.max(0.0,
                            larders.get(ownerEnt.getHomePos()) - fromLarder));
                }
                store.setDirty();
                grownToday.merge(grower.getId(), placed, Integer::sum);
                store.recordExpand(plot, grower.getIndividual().id(), placed,
                        com.evosim.mod.entity.SimTime.tick(level) / 24000L, hasTenant); // 밭 원장(P3): 자영/소작 귀속
                com.evosim.mod.log.SimEvents.event(grower, "밭확장", String.format(
                        "%s 구획 %d: +%d타일(총 %d) — 비용 %.0f(계정 소진) 소작 %d",
                        hasTenant ? "재투자" : "자영",
                        plot.id, placed, plot.tiles.length, bill, nTen));
            }
        }
        // ①c 죽은 타일 정비(A-3) — 블록이 사라진 타일은 무상 재식수, 구조물(천막 등)에 깔려
        //     복구 불능인 타일은 원장에서 소거. 깔린 타일이 원장에 남으면 영구 수확불능인데
        //     부족분 게시(고용 슬롯)만 부풀리는 유령 일자리가 된다(실측: 배정받고 수확 0).
        java.util.Set<Long> homeCells = homeCells(level);
        // 밭 몸통을 지나는 흙길 정비 — 구획별이 아니라 <b>전체 합집합</b>을 한 번에 훑는다.
        // 구획마다 제 몸통만 보면 어느 구획도 자기 것이라 여기지 않는 칸이 남는다(실측 P1).
        com.evosim.mod.entity.MimicEntity.tidyAllFarmRoads(level);
        for (FarmStore.Plot plot : store.all().values()) {
            // 밭 <b>몸통</b>을 지나는 흙길 정비 — 사라진 덤불을 다시 심는 것과 같은 종류의 일이다.
            //
            // 확장 때만 치우면(그것도 한다) <b>밭이 성장을 멈춘 뒤</b> 갇힌 길 자국이 영영 남는다.
            // 실측: 런1 은 밭이 계속 자라 D14 이후 관통 0 이었지만, D11 에서 멈춘 런2 는 1칸이
            // 남아 있었다. 밭은 재배줄 + 고랑 구조라 길이 줄 사이에 갇힐 수 있고, 그 칸은
            // 타일이 아니어서 타일 순회로는 영영 안 잡힌다.
            // 테두리도 매일 한 번 훑는다 — 그릴 당시 막혔던 칸이 풀리면 그때 메워진다.
            redrawBorder(level, store, plot);
            for (int i = plot.tiles.length - 1; i >= 0; i--) {
                BlockPos pos = BlockPos.of(plot.tiles[i]);
                if (!level.isLoaded(pos)) {
                    continue;
                }
                // 거처 위에 깔린 타일 소급 정비 — 회피 가드(nearSomeHome)는 <b>앞으로</b> 깔리는 것만
                // 막는다. 이미 남의 천막·입구·모닥불·정원에 박힌 타일은 원장에 남아 계속 재식재되어
                // 덤불이 되살아나고(귀가·급식 경로 훼손), 부족분 게시만 부풀린다. 블록을 치우고
                // 원장에서도 소거한다 — 가드 도입 이전에 생성된 월드의 자가 치유 경로.
                if (homeCells.contains(RoadStore.key(pos.getX(), pos.getZ()))) {
                    if (level.getBlockState(pos)
                            .is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH)) {
                        level.setBlockAndUpdate(pos,
                                net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                    }
                    store.removeTile(plot, i);
                    com.evosim.mod.log.SimEvents.note(level, "밭정비", String.format(
                            "@%d,%d 구획 %d 타일 소거(거처 위 — 잔여 %d타일)",
                            pos.getX(), pos.getZ(), plot.id, plot.tiles.length));
                    continue;
                }
                var st = level.getBlockState(pos);
                if (st.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH)) {
                    continue;
                }
                if (st.isAir() || st.canBeReplaced()) {
                    level.setBlockAndUpdate(pos.below(),
                            net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());
                    level.setBlockAndUpdate(pos,
                            net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH
                                    .defaultBlockState().setValue(
                                            net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 1));
                    plot.planted[i] = com.evosim.mod.entity.SimTime.tick(level);
                    store.setDirty();
                } else {
                    store.removeTile(plot, i);
                    com.evosim.mod.log.SimEvents.note(level, "밭정비", String.format(
                            "@%d,%d 구획 %d 타일 소거(구조물에 깔림 — 잔여 %d타일)",
                            pos.getX(), pos.getZ(), plot.id, plot.tiles.length));
                }
            }
        }
        // ①b 무주지 선점 — 유주택 성년이 통근 내 무주 구획을 흡수(개간 비용 없음 — 이미 일군 땅).
        //    몰락 가문의 땅이 신흥 가문으로. 하루 1건(개체당 아님 — 전역 완만).
        for (FarmStore.Plot plot : store.all().values()) {
            if (plot.ownerId != 0L) {
                continue;
            }
            MimicEntity claimer = null;
            for (MimicEntity m : adults) {
                if (m.getHomePos() != null
                        && m.blockPosition().distSqr(plot.anchor) <= DISSOLVE_DIST * DISSOLVE_DIST
                        && (claimer == null || m.blockPosition().distSqr(plot.anchor)
                                < claimer.blockPosition().distSqr(plot.anchor))) {
                    claimer = m;
                }
            }
            if (claimer != null) {
                plot.ownerId = claimer.getIndividual().id();
                plot.vacantSince = -1L;
                if (claimer.getTenantFarm() == plot.id) {
                    claimer.setTenant(0L, 0); // 소작하던 무주지를 선점 — 소유 흡수
                }
                store.setDirty();
                com.evosim.mod.log.SimEvents.event(claimer, "밭선점", String.format(
                        "무주 구획 %d(%d타일) 흡수", plot.id, plot.tiles.length));
                break;
            }
        }
        // ② 신규 개간 — 주인 단위(첫 자격자 1건/일: 폭주 제동). 판정 순회는 수확 능력(G)
        // 내림차순 — "개간은 능력 있는 자가 이끈다"의 결정론화(런7 실측: 무정렬 순회에서 가구
        // 자금 30을 저능력 아내가 먼저 소진해 엘리트 왕조가 아내 명의로 꼬임 — 순서 운 제거).
        java.util.List<MimicEntity> founders = new java.util.ArrayList<>(adults);
        founders.sort(java.util.Comparator.comparingDouble(
                (MimicEntity e) -> -com.evosim.core.FarmEconomy.tileYield(e.getIndividual()))
                .thenComparingInt(MimicEntity::getId));
        // 문턱에 가장 가까운 후보(자금 미달) — 루프가 끝난 뒤 한 줄만 남긴다.
        MimicEntity foundNear = null;
        String foundNearText = null;
        double foundNearGap = -Double.MAX_VALUE;
        for (MimicEntity m : founders) {
            if (m.getHomePos() == null) {
                continue;
            }
            // 착공 = 독립가구 자격(설계 지시): 미혼 성년(spouseId==0)은 아직 부모 가구 동거인이라
            // 착공 불가 — 부모집 공유 저장고로 자식이 부모보다 먼저 착공하던 경로(ⓕ 위반)를 봉쇄한다.
            // 사별자는 spouseId를 유지(widowed)하므로 여기 걸리지 않는다(기성 가장 보호).
            if (m.getSpouseId() == 0L) {
                continue;
            }
            if (m.isSatisfiedToday() || com.evosim.core.Satisfaction.neverExpands(m.getIndividual())) {
                continue; // 만족·무욕 — 신규 개간 안 함
            }
            // 독립 잠금(계층 분화 v2) — 하드게이트 없음. 잠금은 "만족의 덫": 위의 만족 게이트 +
            // 아래 자금 임계(30 = 18+12)가 서로를 배제한다. 궁핍한 평민은 자금이 없고, 자금이 모인
            // 평민은 이미 만족선(≤27.6)을 지나 만족 → 개간 동기 소멸. 동기특성×능력 소득만이 돌파.
            int owned = store.ownedCount(m.getIndividual().id());
            double cost = com.evosim.core.FarmEconomy.newFarmCost(owned);
            double funds = larders.get(m.getHomePos());
            // 가구 회계 존중: 예비 = max(12, 2×가구 소모)(foundReserve — 만족의 덫 보편화, P1)
            // + 가구 내 <b>주 지주</b>(최대 소유 타일)의 다음 밭 몫 가산(자신이 주 지주면 가산 없음
            // — 부부 상호 차단 부메랑 방지). 가구 자금은 주 왕조 몫 먼저, 부속 개간은 그 뒤.
            double famNeed = com.evosim.core.FoodEconomy.consumptionPerDay(
                    m.getStage(), com.evosim.core.Activity.MOVE, m.getIndividual(), false);
            long headId = 0L;
            int headTiles = -1;
            for (MimicEntity h : adults) {
                if (h.getHomePos() != null && h.getHomePos().equals(m.getHomePos())) {
                    int t = store.ownedTiles(h.getIndividual().id());
                    if (t > headTiles) {
                        headTiles = t;
                        headId = h.getIndividual().id();
                    }
                    // 부양 예비의 성인 계상은 <b>배우자만</b>(회차 21): 동거 성인 자녀는 자급
                    // 노동자라 부양 대상이 아닌데 계상하면 성인 1인당 임계 +6 vs 순기여 +1~2/일
                    // — 착공 임계가 저축을 앞서 도주하는 러닝머신(런15 실측: 성인 딸 2로 임계
                    // 48 > 저축 37, 착공 창 붕괴. 런13의 중혼 변종과 동류). 유아·소년은 유지.
                    if (h != m && (h.getSpouseId() == m.getIndividual().id()
                            || m.getSpouseId() == h.getIndividual().id())) {
                        famNeed += com.evosim.core.FoodEconomy.consumptionPerDay(
                                h.getStage(), com.evosim.core.Activity.MOVE,
                                h.getIndividual(), false);
                    }
                }
            }
            for (MimicEntity c : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                    e -> e.isAlive() && e.getIndividual() != null
                            && m.getHomePos().equals(e.getHomePos())
                            && (e.getStage() == com.evosim.core.LifeStage.INFANT
                                    || e.getStage() == com.evosim.core.LifeStage.BOY))) {
                famNeed += com.evosim.core.FoodEconomy.consumptionPerDay(
                        c.getStage(), com.evosim.core.Activity.MOVE, c.getIndividual(), false);
            }
            double reserve = com.evosim.core.FarmEconomy.foundReserve(famNeed);
            if (headTiles > 0 && headId != m.getIndividual().id()) {
                reserve += com.evosim.core.FarmEconomy.newFarmCost(store.ownedCount(headId));
            }
            if (store.stewardOf(m.getIndividual().id()) != 0L) {
                // 재직 마름의 착공 마찰(이탈 방지 ④, v1.3) — 금지가 아닌 예비 ×3(수치 문턱).
                // 비야망가 선발(①)+만족의 덫(②)+근속 수당(③)을 뚫는 예외(유산 유입 등)를 봉쇄.
                reserve *= com.evosim.core.FarmEconomy.STEWARD_FOUND_RESERVE_MULT;
            }
            // <b>막힌 사유를 남긴다.</b> 종전에는 세 관문 전부 조용히 continue 해서, 저장고가
            // 문턱을 넘은 야망가가 며칠째 개간을 안 해도 로그에 단서가 한 줄도 없었다(실측:
            // 엘리트 저장고 29 · 문턱 ~21 · d5 까지 밭 0). 막사 쪽에서 같은 침묵으로 원인을
            // 못 찾은 적이 있다 — 침묵은 진단이 아니다.
            //
            // 하루 한 번, <b>가장 부유한 무산 후보</b>에게만 찍는다(전원 매일이면 도배된다).
            // 자금 미달은 정상 상태라 조용히 두고, 자금을 넘긴 뒤 막히는 것만 남긴다.
            if (funds < cost + reserve) {
                // 자금 미달은 정상 상태지만 <b>얼마나 모자란지</b>는 남겨야 한다. 이 수가 없으면
                // "아무도 개간을 안 한다"에서 문턱이 높은 건지 벌이가 없는 건지 가릴 수 없다
                // (실측: d5 까지 밭 0 · 세계 최고 저장고 25 인데 문턱을 몰라 추측만 했다).
                // 하루 한 번, 문턱에 <b>가장 가까운</b> 한 명만 찍는다 — 전원 매일이면 도배된다.
                if (funds - (cost + reserve) > foundNearGap) {
                    foundNearGap = funds - (cost + reserve);
                    foundNear = m;
                    foundNearText = String.format(
                            "저장고 %.0f < 문턱 %.0f (착공비 %.0f + 예비 %.0f — 가구 하루소모 %.1f)",
                            funds, cost + reserve, cost, reserve, famNeed);
                }
                continue; // 자금(주 지주·단독 가구면 저장고≥30/39…)
            }
            if (owned > 0 && !nextFarmEligible(store, adults, m.getIndividual().id())) {
                com.evosim.mod.log.SimEvents.event(m, "개간보류", String.format(
                        "자금 %.0f ≥ 문턱 %.0f 인데 — %s", funds, cost + reserve,
                        nextFarmBlock(level, m.getIndividual().id())));
                continue; // 성숙 트리거(P6) — nextFarmEligible 참조(확장 예비 산정과 단일 출처)
            }
            // <b>가구 우회 차단</b> — 성숙 트리거는 개인 단위인데 <b>돈은 가구 공동</b>이다.
            //
            // 그래서 가장이 트리거에 걸려 있는 동안, 밭이 없는 배우자·첩이 <b>같은 저장고</b>로
            // 자기 명의 밭을 열 수 있었다(육안 관측: "엘리트가 첩을 들이면 첩이 남편 돈으로
            // 자기 명의 밭과 학교를 만들어버림"). 그러면 성숙 트리거가 통째로 무력해진다 —
            // 가구원 수만큼 우회로가 있는 셈이다.
            //
            // 그 뒤가 더 크다. 밭을 가지면 추종자가 붙고, 시설 창건자는 <b>가구에서 추종자가
            // 가장 많은 사람</b>으로 뽑히므로(considerFacility), 학교·교회도 첩 명의가 된다.
            // 그러면 온 마을이 남편을 따르는데 시설 주인은 첩이라 아무도 쓰지 않는다.
            //
            // 가구의 주 지주가 다음 밭 자격을 얻기 전에는 그 가구가 밭을 더 열지 못하게 한다.
            // 주 지주 본인이 자격을 갖추면 위 정렬(수확 능력 내림차순)에서 그가 먼저 걸리므로,
            // 이 조항이 정상적인 확장을 막지는 않는다.
            if (headTiles > 0 && headId != m.getIndividual().id()
                    && !nextFarmEligible(store, adults, headId)) {
                FamilyLedger.Rec hr = FamilyLedger.get(level).get(headId);
                com.evosim.mod.log.SimEvents.event(m, "개간보류", String.format(
                        "자금 %.0f ≥ 문턱 %.0f 인데 — 같은 가구의 주 지주 %s 가 아직 %s"
                                + "(가구는 한 번에 한 명의로만 넓힌다)",
                        funds, cost + reserve,
                        hr != null && hr.name != null ? hr.name : "#" + headId,
                        nextFarmBlock(level, headId)));
                continue;
            }
            int[] spot = findFootprint(level, store, m.getHomePos(), adults);
            if (spot == null) {
                com.evosim.mod.log.SimEvents.event(m, "개간보류", String.format(
                        "자금 %.0f ≥ 문턱 %.0f 인데 — 거처 @%d,%d 주변에 1단계 발자국이 들어갈"
                                + " 빈 자리가 없다(집·밭·물·낙차로 전부 거부)",
                        funds, cost + reserve,
                        m.getHomePos().getX(), m.getHomePos().getZ()));
                continue; // 1단계 발자국(4×5)이 들어갈 자리가 없다
            }
            BlockPos site = new BlockPos(spot[0], spot[3] + 1, spot[1]);
            if (m.getTenantFarm() != 0L) {
                // 유령 상시 해소(런6 실측): 상시 소작이 독립 개간 후에도 명부에 남아 슬롯을
                // 점유(covered 포화) → 신규 고용 게시가 봉쇄되고 지대가 말랐다(d10~13 farm 소득 0).
                // 자기 밭 주인은 남의 밭 상시일 수 없다 — 상속 경로와 같은 회계 정리.
                m.setTenant(0L, 0);
                com.evosim.mod.log.SimEvents.event(m, "소작해제", "독립 개간 — 소작 관계 정리");
            }
            // ── 가문 편입(케이스 3, v1.3·S3): 무산 착공자의 부모·형제 중 <b>지주+</b>(마름 1 보유)가
            // 있으면 소유권은 그 머리에게 귀속되고 착공자는 그 구획의 마름이 된다(착공비는 밤 정산 때
            // 상환). 야망가 포함 예외 없음 — 착공 시도가 곧 영지 확장 노동이 되는 순환(발사대 봉쇄).
            // 편입된 첫 밭이 머리를 2호 보유로 만들어 영주로 부트스트랩(자력 2호 대기 불요).
            // <b>자력이면 제 이름으로 등기한다.</b>
            //
            // 편입은 영주를 부트스트랩하려고 넣은 것인데, 조건이 "무토지면 무조건"이라 저축을
            // 해도 자영농이 되지 못했다. 실측(런 A): 개간 19건 중 대부분이 가문 귀속이었고,
            // 타일러 블랙우드는 혼자 5구획을 개간했는데 한 뼘도 제 것이 아니었다. 그러면 소작
            // → 마름 → 자영으로 오르는 사다리에 마지막 칸이 없다.
            //
            // 문턱은 <b>착공 문턱의 두 배</b>다. 가문의 등에 업혀 간신히 여는 자와, 제 힘으로
            // 한 번 더 열 만큼 모은 자를 가른다 — 신분 분기가 아니라 저축액이 가른다(규칙5).
            double selfThreshold = 2.0 * (cost + reserve);
            boolean selfMade = funds >= selfThreshold;
            MimicEntity familyLord = (owned == 0 && !selfMade)
                    ? findFamilyLord(level, store, adults, m) : null;
            if (owned == 0 && selfMade) {
                com.evosim.mod.log.SimEvents.event(m, "자력착공", String.format(
                        "저장고 %.0f ≥ 자력문턱 %.0f (착공 %.0f + 예비 %.0f) — 가문 귀속 없이 제 이름으로",
                        funds, selfThreshold, cost, reserve));
            }
            long newOwnerId = familyLord != null
                    ? familyLord.getIndividual().id() : m.getIndividual().id();
            FarmStore.Plot plot = store.create(site, newOwnerId);
            plot.founderId = m.getIndividual().id(); // 원장: 창설자 = 착공 실행자(귀속과 무관)
            plot.foundedDay = com.evosim.mod.entity.SimTime.tick(level) / 24000L; // 밭 원장(P3) — 개간 게임일
            // 1단계 발자국을 그대로 앉힌다 — 원목은 즉시, 재배 칸은 노동에 따라 차오른다.
            int[] br1 = com.evosim.core.FarmLayout.stage(1);
            plot.beds = br1[0];
            plot.rows = br1[1];
            plot.bedAxisX = spot[2] != 0;
            plot.fx = spot[0];
            plot.fz = spot[1];
            plot.baseY = spot[3];
            plot.tilesByFounder = com.evosim.core.FarmLayout.tiles(br1[0], br1[1]);
            store.setDirty();
            layLogs(level, plot);
            for (int[] cr : com.evosim.core.FarmLayout.cropOrder(plot.beds, plot.rows)) {
                if (plantAt(level, store, plot, cr[0], cr[1])) {
                    int[] xz = colOf(plot, cr[0], cr[1]);
                    com.evosim.mod.entity.MimicEntity.farmTookRoad(level, m, plot,
                            new BlockPos(xz[0], plot.baseY + 2, xz[1]));
                }
            }
            larders.set(m.getHomePos(), funds - cost);
            if (familyLord != null) {
                plot.stewardDebt = cost; // 착공비 상환 채무(영주→마름, 밤 정산 이월)
                store.stewardGone(level, m.getIndividual().id(), "가문 편입 재배치"); // 기존 직 사임
                store.appointSteward(level, plot, m, "마름편입");
                com.evosim.mod.log.SimEvents.event(m, "밭개간", String.format(
                        "신규 구획 %d 착공 — 가문 귀속(영주 %s) 비용 %.0f G%.2f", plot.id,
                        familyLord.getIndividual().shortName(), cost,
                        com.evosim.core.FarmEconomy.tileYield(m.getIndividual())));
            } else {
                // 수율 G 병기 — "능력자만 독립" 검수: 개간자의 G가 낮은 사례가 잦으면 잠금 누수 신호.
                com.evosim.mod.log.SimEvents.event(m, "밭개간", String.format(
                        "신규 구획 %d(%d번째) 착공 — 비용 %.0f G%.2f", plot.id, owned + 1, cost,
                        com.evosim.core.FarmEconomy.tileYield(m.getIndividual())));
                // ── 하청 개간(케이스 2, v1.3): 지주(마름 1+)의 신규 밭은 신임 마름이 즉시 운영
                // (완전 하청 — 영주 사다리 가속). 후보(영지 상시·비야망가) 없으면 본인 직영 폴백
                // (교착 방지 P2) — 케이스 1이 추후 임명한다.
                if (store.stewardCount(m.getIndividual().id()) >= 1) {
                    MimicEntity stw = store.estateCandidate(level, m.getIndividual().id());
                    if (stw != null) {
                        store.appointSteward(level, plot, stw, "마름임명");
                    }
                }
            }
            foundNear = null; // 오늘은 착공이 있었다 — 보류 사유를 남기지 않는다
            break; // 하루 1건
        }
        // 오늘 아무도 착공하지 못했다면, 문턱에 가장 가까웠던 한 명의 수를 남긴다.
        if (foundNear != null && foundNearText != null) {
            com.evosim.mod.log.SimEvents.event(foundNear, "개간보류", foundNearText);
        }
    }

    /**
     * 가문 영주 탐색(케이스 3, v1.3) — 착공자의 <b>부모 → 조부모 → 형제</b> 순으로 지주
     * (마름 1+)를 찾는다. 동급이면 최대 타일 보유자. 무산 착공자(owned 0)에게만 적용 —
     * 이미 자기 영지를 가진 친족은 독립 가문으로 존중(경쟁 가문 경로).
     *
     * <p><b>조부모 확대</b>(실측 사례): 휴고 스톰윈드가 전 영지를 쥔 d17에, 그의 손자 세드릭이
     * 33타일을 <b>자기 명의로</b> 착공했다. 종전 판정이 부모·형제까지만 봐서 조부가 후보에
     * 아예 없었고, 그 시점 부모(리바이)는 아직 무산이라 가문 머리가 없다고 판정된 것이다.
     * 성년까지 2일(유아 0.75 + 소년 1.25)이라 3세대가 상시 공존하는데 편입 판정만 2세대를
     * 보고 있었다 — 손자 세대가 통째로 새는 구멍이었다. 상속이 단독(장남 몰아주기)이라 한
     * 세대 안에 재통합되긴 하나, 그 과도기 동안 쪼개진 작은 밭은 구인 문턱(shortfall)에 못
     * 미쳐 마름·소작이 붙지 않고 지대도 생기지 않는다(실측 문서: 마름 —, 계정 0.00).
     *
     * <p>조부모는 {@link FamilyLedger}에서 부모의 부모를 읽는다 — 원장은 사망자 기록을
     * 유지하므로(diedDay) 조부가 이미 죽었어도 조회된다. 해시맵 조회 4회라 비용은 없다.
     */
    private static MimicEntity findFamilyLord(ServerLevel level, FarmStore store,
            java.util.List<MimicEntity> adults, MimicEntity m) {
        long pa = m.getIndividual().parentAId();
        long pb = m.getIndividual().parentBId();
        // 조상 계층: 부모(0) > 조부모(1) — 가까운 쪽이 먼저 가문 머리가 된다.
        java.util.Map<Long, Integer> ancestorRank = new java.util.HashMap<>();
        FamilyLedger led = FamilyLedger.get(level);
        for (long p : new long[] {pa, pb}) {
            if (p == 0L) {
                continue;
            }
            ancestorRank.put(p, 0);
            FamilyLedger.Rec r = led.get(p);
            if (r == null) {
                continue;
            }
            for (long g : new long[] {r.pa, r.pb}) {
                if (g != 0L) {
                    ancestorRank.putIfAbsent(g, 1);
                }
            }
        }
        MimicEntity best = null;
        int bestRank = Integer.MAX_VALUE; // 0 부모 · 1 조부모 · 2 형제
        int bestTiles = -1;
        for (MimicEntity h : adults) {
            if (h == m || h.getIndividual() == null) {
                continue;
            }
            long hid = h.getIndividual().id();
            if (store.stewardCount(hid) < 1) {
                continue; // 지주+ (마름 1 보유 = 편입 자격). 회차 S3: 영주(2호 필요)에서 지주로
                // 낮춤 — 자식 성년(출생+2일)·독립(~d5~6)이 영주 등장(자력 2호, ~d7~8)보다 빨라
                // 조기 자식이 빠져나가던 경합 해소. 편입된 첫 밭이 머리를 2호 보유 → 영주로 부트스트랩.
            }
            Integer ar = ancestorRank.get(hid);
            int rank;
            if (ar != null) {
                rank = ar;
            } else {
                long hpa = h.getIndividual().parentAId();
                long hpb = h.getIndividual().parentBId();
                boolean sibling = (pa != 0L && (pa == hpa || pa == hpb))
                        || (pb != 0L && (pb == hpa || pb == hpb));
                if (!sibling) {
                    continue;
                }
                rank = 2;
            }
            int tiles = store.ownedTiles(hid);
            if (best == null || rank < bestRank || (rank == bestRank && tiles > bestTiles)) {
                best = h;
                bestRank = rank;
                bestTiles = tiles;
            }
        }
        return best;
    }

    /** 무주지 만료 — VACANT_EXPIRE_TICKS 경과 시 등록 소거(베리는 야생으로 남음 — 일반 채집 재개방). */
    private static void expireVacant(ServerLevel level) {
        FarmStore store = FarmStore.get(level);
        for (FarmStore.Plot p : new java.util.ArrayList<>(store.all().values())) {
            // 센티넬은 정확히 -1(무주 아님)만 — '>= 0' 이면 점검용 과거화(now − 60001)가 젊은 월드에서
            // 음수가 될 때 만료 스캔이 영영 건너뛴다(음수-시각 계열). 실플레이 값은 항상 ≥0이라 무변화.
            if (p.ownerId == 0L && p.vacantSince != -1L
                    && com.evosim.mod.entity.SimTime.tick(level) - p.vacantSince > com.evosim.core.FarmEconomy.VACANT_EXPIRE_TICKS) {
                store.debugRemove(p.id); // 등록·타일 색인 소거(멱등 정리 경로 재사용)
            }
        }
    }

    /**
     * 공간 적응 배치(v1) — 수열 칸 (c,r)의 4방 미러([기본, 좌우반전, 상하반전, 대각]) 중 첫
     * 설치 가능 지점. 설치 가능 = 그 자리 블록이 자연 대체물(공기·풀·꽃)이고 밭 타일이 아니며
     * 발밑이 자연 지반(잔디/흙) — 천막 지붕·구조물 위 설치를 차단한다. 전부 막히면 null(스킵).
     */
    /**
     * 다음 밭 자격(성숙 트리거 P6) — 최신(직영) 밭이 24타일 + 상시 소작 ≥1(인계 완료) 또는
     * 공간 포화(연속 배치 0). 신규 개간 판정과 확장 예비 산정(expandReserve)의 단일 출처.
     */
    private static boolean nextFarmEligible(FarmStore store,
            java.util.List<MimicEntity> adults, long ownerId) {
        long newest = store.newestOwnedPlot(ownerId);
        FarmStore.Plot np = store.get(newest);
        int permTenants = 0;
        for (MimicEntity t : adults) {
            if (t.getTenantFarm() == newest) {
                permTenants++;
            }
        }
        return nextFarmBlock(np, permTenants).isEmpty();
    }

    /**
     * 다음 밭 자격의 <b>미충족 사유</b>(빈 문자열이면 자격 있음) — 판정({@link #nextFarmEligible})과
     * 렌즈 표시가 같은 함수를 보게 하는 단일 출처.
     *
     * <p>검사봉의 "개간" 게이지는 자금 문턱(착공비 + 예비)만 보여 줬는데, 실제 착공은 그 위에
     * 이 성숙 트리거를 하나 더 요구한다. 그래서 저장고 40 · 최신 밭 21타일인 지주에게 "개간
     * 충족 · 동기✓"라고 표시하면서도 착공하지 않는 상태가 나온다(실측 스크린샷) — 번식 게이지가
     * 쿨다운을 감춰 "충족인데 왜 안 낳지"가 되던 것과 같은 종류의 표시-판정 비대칭이다.
     */
    static String nextFarmBlock(FarmStore.Plot newestPlot, int permTenants) {
        if (newestPlot == null) {
            return "밭 없음";
        }
        // <b>교착 탈출은 사흘 연속 막혔을 때만.</b>
        //
        // 종전은 blockedDays >= 1 — <b>하루</b> 못 자란 밭이 곧바로 크기 조건(24타일)을
        // 통째로 건너뛰고 다음 밭 자격을 얻었다. 그래서 12타일짜리 밭을 두고 새 밭을 파는
        // 장면이 나온다(육안 관측). 게다가 새 밭이 최신 구획이 되면 직영지 원칙에 따라
        // 주인의 자가 노동이 그 빈 밭으로 옮겨가, 옛 밭도 새 밭도 아무도 안 돌보게 된다.
        //
        // 하루 막힌 것과 사흘 연속 막힌 것은 다르다. 확장은 자금·노동·지형이 그날 다 맞아야
        // 하는 일이라 하루쯤 어긋나는 것은 흔하고, 그것은 포화가 아니다. 탈출구 자체는 남긴다
        // — 진짜로 사방이 막힌 밭이 왕조를 영구 정지시키면 안 된다는 원 취지는 그대로다.
        boolean stuck = newestPlot.blockedDays >= BLOCKED_ESCAPE_DAYS;
        int tiles = newestPlot.tiles.length;
        if (!stuck && tiles < com.evosim.core.FarmEconomy.MATURE_TILES) {
            return String.format("성숙 %d/%d타일", tiles, com.evosim.core.FarmEconomy.MATURE_TILES);
        }
        if (!stuck && permTenants < 1) {
            return "상시 소작 필요";
        }
        return "";
    }

    /** 렌즈용 — 이 주인의 다음 밭 자격 미충족 사유(빈 문자열이면 자격 있음). */
    public static String nextFarmBlock(ServerLevel level, long ownerId) {
        FarmStore store = FarmStore.get(level);
        FarmStore.Plot np = store.get(store.newestOwnedPlot(ownerId));
        if (np == null) {
            return "밭 없음";
        }
        final long pid = np.id;
        int permTenants = level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getTenantFarm() == pid).size();
        return nextFarmBlock(np, permTenants);
    }

    /**
     * <b>밭 테두리 다시 그리기</b> — 덤불 상자 바로 바깥 한 겹을 흙길로. 상자가 안 바뀌었으면 무동작.
     *
     * <p>테두리는 <b>길이자 경계</b>다. 재배줄은 x 방향으로 빈틈이 없어서 고랑에서 고랑으로 가려면
     * 줄 끝을 돌아야 하는데, 그 끝이 덤불과 딱 붙어 있으면 지나는 개체가 스위트베리에 <b>피해를
     * 입고 느려진다</b>. 한 겹 흙길이 그 손해를 없애면서 동시에 "여기까지가 이 밭"을 보여 준다.
     * 울타리가 아니라 흙길인 이유는 ① 통행을 막지 않아 문이 필요 없고 ② 마을 길과 같은 블록이라
     * 자연히 이어지며 ③ 이미 있는 포장 판정을 그대로 쓰기 때문이다.
     *
     * <p><b>도로망에 등기하지 않는다.</b> 등기하면 밭이 자라 제 테두리를 삼킬 때마다 "밭이 길을
     * 먹었다"로 잡혀 우회로 로직이 헛돈다. 생김새만 흙길이고 도로망의 일원은 아니다.
     */
    static void redrawBorder(ServerLevel level, FarmStore store, FarmStore.Plot plot) {
        if (plot.tiles.length == 0) {
            return;
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long l : plot.tiles) {
            BlockPos t = BlockPos.of(l);
            minX = Math.min(minX, t.getX());
            maxX = Math.max(maxX, t.getX());
            minZ = Math.min(minZ, t.getZ());
            maxZ = Math.max(maxZ, t.getZ());
        }
        boolean grew = minX != plot.ringMinX || maxX != plot.ringMaxX
                || minZ != plot.ringMinZ || maxZ != plot.ringMaxZ;
        RoadStore roads = RoadStore.get(level);
        // ① 옛 테두리 철거는 <b>상자가 바뀐 때만</b>. 넓게 훑어 지우면 마을 길까지 지운다.
        if (grew && plot.ringMaxX >= plot.ringMinX) {
            for (long k : ringOf(plot.ringMinX, plot.ringMinZ, plot.ringMaxX, plot.ringMaxZ)) {
                int x = RoadStore.keyX(k);
                int z = RoadStore.keyZ(k);
                if (onRing(x, z, minX, minZ, maxX, maxZ)) {
                    continue; // 새 테두리이기도 하다 — 그대로 둔다
                }
                if (roads.has(x, z)) {
                    continue; // <b>등기된 마을 길은 건드리지 않는다</b>
                }
                unpaveTo(level, x, z);
            }
        }
        // ② 새 테두리를 깐다. <b>상자가 그대로여도 매번 훑는다.</b> 그리지 못한 칸이
        // 생길 수 있기 때문이다 — 그릴 당시 남의 밭 몸통이었다가 그 타일이 죽어 자유로워지는
        // 경우가 실제로 있었다(실측: D16 에 한 구획 왼쪽 모서리 7칸이 맨흙·잔디로 남았고,
        // 상자가 안 바뀌어 영영 재시도되지 않았다). 포장은 멱등이라 이미 깔린 칸은 건너뛴다.
        RoadPlanner.Obstacles ob = RoadPlanner.Obstacles.of(level);
        for (long k : ringOf(minX, minZ, maxX, maxZ)) {
            int x = RoadStore.keyX(k);
            int z = RoadStore.keyZ(k);
            if (ob.blocked(x, z) || store.isFarmTile(new BlockPos(x, 0, z))
                    || store.isFarmBody(x, z)) {
                // 집 지면층·문앞 계단·정원·가로등·다른 밭 — 길과 같은 금지 출처.
                // <b>이미 깔려 있던 테두리는 걷어낸다.</b> 테두리를 그린 뒤에 그 자리에 집이
                // 서거나 남의 밭이 넓어질 수 있는데(집 부지 판정은 밭 <b>타일</b>만 보므로
                // 계단 구획의 빈 상자 안에 합법적으로 집이 들어선다 — 실측), 건너뛰기만 하면
                // 그 아래 흙길이 잔재로 남는다. 등기된 마을 길은 물론 건드리지 않는다.
                if (!roads.has(x, z)) {
                    unpaveTo(level, x, z);
                }
                continue;
            }
            int y = RoadPlanner.surfaceY(level, x, z);
            if (y == Integer.MIN_VALUE) {
                continue; // 물·용암·미로드 청크
            }
            BlockPos g = new BlockPos(x, y, z);
            if (level.getBlockState(g).is(net.minecraft.world.level.block.Blocks.DIRT_PATH)) {
                continue; // 이미 깔려 있다
            }
            if (!RoadPlanner.pavable(level, g)) {
                continue; // 모래·자갈·돌 — 길과 같은 한계를 공유한다
            }
            level.setBlock(g, net.minecraft.world.level.block.Blocks.DIRT_PATH.defaultBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                            | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
        }
        if (grew) {
            plot.ringMinX = minX;
            plot.ringMinZ = minZ;
            plot.ringMaxX = maxX;
            plot.ringMaxZ = maxZ;
            store.setDirty();
        }
    }

    /** 덤불 상자 바깥 한 겹의 칸들(모서리 포함). */
    private static java.util.List<Long> ringOf(int minX, int minZ, int maxX, int maxZ) {
        java.util.List<Long> out = new java.util.ArrayList<>();
        for (int x = minX - 1; x <= maxX + 1; x++) {
            out.add(RoadStore.key(x, minZ - 1));
            out.add(RoadStore.key(x, maxZ + 1));
        }
        for (int z = minZ; z <= maxZ; z++) {
            out.add(RoadStore.key(minX - 1, z));
            out.add(RoadStore.key(maxX + 1, z));
        }
        return out;
    }

    private static boolean onRing(int x, int z, int minX, int minZ, int maxX, int maxZ) {
        boolean inX = x >= minX - 1 && x <= maxX + 1;
        boolean inZ = z >= minZ - 1 && z <= maxZ + 1;
        boolean edge = x == minX - 1 || x == maxX + 1 || z == minZ - 1 || z == maxZ + 1;
        return inX && inZ && edge;
    }

    /** 이 열의 흙길을 잔디로 되돌린다(테두리 철거 전용 — 등기된 길은 호출 전에 걸러진다). */
    private static void unpaveTo(ServerLevel level, int x, int z) {
        int y = RoadPlanner.surfaceY(level, x, z);
        if (y == Integer.MIN_VALUE) {
            return;
        }
        BlockPos g = new BlockPos(x, y, z);
        if (!level.getBlockState(g).is(net.minecraft.world.level.block.Blocks.DIRT_PATH)) {
            return;
        }
        level.setBlock(g, net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(),
                net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                        | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
    }

    /** 주인별 추종자 수 — 일일 패스 첫머리에 한 번 채우고 그날 내내 쓴다(밭 상한 입력). */
    private static final java.util.Map<Long, Integer> FOLLOWERS = new java.util.HashMap<>();

    /** 이 개체를 따르는 자가 몇인가 — 시설 착공 자격(이용자가 곧 수입)의 입력. */
    public static int followersOf(long id) {
        return FOLLOWERS.getOrDefault(id, 0);
    }

    /**
     * <b>못 다니는 학생 수</b> — 이 주인을 따르는 가구의 소년 중, 그가 가진 어떤 학교에서도
     * 통학 한계 밖인 아이가 몇인가.
     *
     * <p>착공 조건을 "한 사람이 학교 하나"로 두었더니 마을이 바깥으로 자라도 학교가 늘지
     * 못했다 — 실측(D23): 소년 최근접거리 중앙이 하루 만에 39→73 으로 벌어졌는데(새 가구가
     * 변두리에 정착), 자격자 둘 중 하나는 이미 갖고 있고 다른 하나는 <b>승계로 두 채를
     * 물려받아</b> 막혀 있었다. 등교는 7명에서 0명이 됐다.
     *
     * <p>수를 세어 <b>안 닿는 학생이 있을 때만</b> 더 짓게 한다. 문턱을 교사 급여의 손익분기
     * (학생 3명)와 같은 수로 두면, 새 학교가 서자마자 흑자가 되는 자리에서만 지어진다.
     */
    public static int unservedStudents(ServerLevel level, long ownerId) {
        FacilityStore reg = FacilityStore.get(level);
        // <b>내 학교만 세면 안 된다.</b> 남이 세운 학교라도 그 아이가 다닐 수 있으면 수요는
        // 이미 채워진 것이고, 그걸 무시하면 같은 자리에 학교가 겹쳐 선다. 게다가 <b>빈자리가
        // 남은 학교</b>가 근처에 있으면 새로 지을 이유가 없다 — 정원이 찬 학교만 "못 받는"
        // 것으로 본다.
        java.util.List<BlockPos> reachable = new java.util.ArrayList<>();
        for (FacilityStore.Entry e : reg.all()) {
            if (e.kind != FacilityTemplate.Kind.SCHOOL) {
                continue;
            }
            int seats = FacilityTemplate.of(level, e.kind, e.rotation, e.mirrored)
                    .map(t -> t.seats().size()).orElse(0);
            int taken = ENROLLED.getOrDefault(e.pos.asLong(), java.util.List.of()).size();
            if (taken < seats) {
                reachable.add(e.pos); // 아직 받을 수 있는 학교
            }
        }
        java.util.List<BlockPos> mine = reachable;
        int n = 0;
        for (MimicEntity b : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                m -> m.isAlive() && m.getIndividual() != null && m.getHomePos() != null
                        && m.getStage() == com.evosim.core.LifeStage.BOY)) {
            boolean follows = false;
            for (BlockPos h : FOLLOWER_HOMES.getOrDefault(ownerId, java.util.List.of())) {
                if (h.equals(b.getHomePos())) {
                    follows = true;
                    break;
                }
            }
            if (!follows) {
                continue;
            }
            boolean served = false;
            for (BlockPos sp : mine) {
                if (b.getHomePos().distSqr(sp)
                        <= Facilities.COMMUTE_RANGE * Facilities.COMMUTE_RANGE) {
                    served = true;
                    break;
                }
            }
            if (!served) {
                n++;
            }
        }
        return n;
    }

    /**
     * 이 주인을 따르는 가구의 <b>소년들이 사는 집</b> — 부지 고르기의 점수 입력.
     *
     * <p>부지를 "중심에서 가까운 첫 깨끗한 자리"로 고르면 그 자리가 학생을 몇 명 덮는지는
     * 보지 않는다. 21×21 건물은 집 간격(15~19)보다 넓어 마을 <b>안</b>에는 못 들어가므로,
     * 어차피 가장자리에 설 수밖에 없다 — 그렇다면 <b>가장 많이 덮는</b> 가장자리를 골라야 한다.
     */
    public static java.util.List<BlockPos> studentHomesOf(ServerLevel level, long ownerId) {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        java.util.List<BlockPos> homes = FOLLOWER_HOMES.getOrDefault(ownerId, java.util.List.of());
        if (homes.isEmpty()) {
            return out;
        }
        for (MimicEntity b : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                m -> m.isAlive() && m.getIndividual() != null && m.getHomePos() != null
                        && m.getStage() == com.evosim.core.LifeStage.BOY)) {
            if (homes.contains(b.getHomePos())) {
                out.add(b.getHomePos());
            }
        }
        return out;
    }

    /** 주인별 추종자 <b>거처</b> — 시설 부지를 이용자 쪽으로 당기는 입력(통학거리). */
    private static final java.util.Map<Long, java.util.List<BlockPos>> FOLLOWER_HOMES =
            new java.util.HashMap<>();

    /**
     * 이 주인을 따르는 자들의 거처 목록 — 시설을 <b>이용자가 모인 곳</b>에 세우기 위한 것.
     *
     * <p>종전에는 부지를 주인의 집 기준으로만 골랐다. 그러면 학교가 주인 옆에 서고 학생은
     * 멀리 흩어진 채로 남는다 — 실측(D24): 통학거리 중앙 47 · 한계 48 에 걸치고, 통학초과로
     * 못 간 소년이 8명이었다. 학교는 주인의 과시물이 아니라 <b>이용자가 오는 곳</b>이다.
     */
    public static java.util.List<BlockPos> followerHomesOf(long id) {
        return FOLLOWER_HOMES.getOrDefault(id, java.util.List.of());
    }

    // ── 당일 봉건 수지(P4) — 보고 전용. 매일 새벽에 지워지고 다시 채워진다. ──────────
    /** 개체가 오늘 <b>받은</b> 것 — 추종자 세금 + 아래에서 올라온 상납. */
    private static final java.util.Map<Long, Double> TAX_IN = new java.util.HashMap<>();
    /** 개체가 오늘 <b>낸</b> 것 — 제 세금 + 제 빚 상환 + 위로 올린 상납. */
    private static final java.util.Map<Long, Double> TAX_OUT = new java.util.HashMap<>();
    /** [징수, 미납, 상납, 상환] 총액과 [납부자, 미납자] 수 — 한 줄 보고용. */
    private static final double[] TAX_SUM = new double[4];
    private static final int[] TAX_CNT = new int[2];

    public static java.util.Map<Long, Double> taxIn() {
        return TAX_IN;
    }

    public static java.util.Map<Long, Double> taxOut() {
        return TAX_OUT;
    }

    public static double[] taxSums() {
        return TAX_SUM.clone();
    }

    public static int[] taxCounts() {
        return TAX_CNT.clone();
    }

    /**
     * <b>세금·상납·상환</b>(P4) — 추종이 성립한 성년이 매일 주인에게 낸다.
     *
     * <p>여기서 처음으로 추종이 <b>물건을 움직인다.</b> P2~P3.5 까지 추종은 장부에만 있는
     * 관계였고 주인이 얻는 것이 하나도 없었다. 목표 4("지배자는 손해가 아니라 이익을 본다")는
     * 이 이전이 있어야만 수치로 성립한다.
     *
     * <p>세 갈래가 <b>한 순회</b>에서 일어난다.
     * <ul>
     *   <li><b>세금</b> — 정액. 가구 예비를 남기고 낼 수 있는 만큼만 낸다.</li>
     *   <li><b>미납</b> — 못 낸 몫은 빚(상환분)이 된다. 이자가 붙고 갚아야 한다. 이것이
     *       천민으로 가는 두 번째 경로다(첫째는 예속 지속).</li>
     *   <li><b>상환</b> — 세금을 다 내고도 남은 여유의 일부로 빚을 던다.</li>
     * </ul>
     *
     * <p>성년만 낸다. 아이는 일하지 않고, 태생적 추종까지 과세하면 세 부담이 출산 수에
     * 비례해 불어나 가구를 무너뜨린다 — 세금이 인구를 잡아먹으면 지배자도 손해다.
     *
     * <p>순회는 <b>개체 id 순</b>이다. 같은 가구에 추종자가 여럿이면 저장고가 순서대로 줄어드는데,
     * 그 순서가 런마다 달라지면 누가 미납자가 되는지가 운으로 갈린다.
     */
    private static void collectTribute(ServerLevel level, AllegianceStore ledger,
                                       LarderStore larders,
                                       java.util.List<MimicEntity> adults,
                                       java.util.List<MimicEntity> everyone,
                                       java.util.Map<Long, Long> patrons, long day) {
        TAX_IN.clear();
        TAX_OUT.clear();
        java.util.Arrays.fill(TAX_SUM, 0.0);
        java.util.Arrays.fill(TAX_CNT, 0);

        java.util.Map<Long, MimicEntity> byId = new java.util.HashMap<>();
        for (MimicEntity m : everyone) {
            byId.putIfAbsent(m.getIndividual().id(), m);
        }
        java.util.List<MimicEntity> payers = new java.util.ArrayList<>(adults);
        payers.sort(java.util.Comparator.comparingLong(m -> m.getIndividual().id()));

        for (MimicEntity m : payers) {
            long id = m.getIndividual().id();
            Long patronId = patrons.get(id);
            if (patronId == null) {
                continue;
            }
            net.minecraft.core.BlockPos home = m.getHomePos();
            MimicEntity lord = byId.get(patronId);
            // 주인이 거처를 잃었으면 걷지 않는다 — 받을 곳간이 없는 세금은 식량을 증발시킨다.
            if (home == null || lord == null || lord.getHomePos() == null
                    || home.equals(lord.getHomePos())) {
                continue;
            }
            double larder = larders.get(home);
            double spare = com.evosim.core.Tribute.payable(
                    larder, familyDailyNeed(level, m, adults));
            double due = com.evosim.core.Tribute.due(true);
            double pay = Math.min(due, spare);
            spare -= pay;
            double arrears = due - pay;
            double repayCut = com.evosim.core.Tribute.repayment(spare, ledger.owedOf(id));
            double moved = pay + repayCut;

            if (moved > 0.0) {
                larders.set(home, larder - moved);
                larders.set(lord.getHomePos(), larders.get(lord.getHomePos()) + moved);
                TAX_OUT.merge(id, moved, Double::sum);
                TAX_IN.merge(patronId, moved, Double::sum);
            }
            if (repayCut > 0.0) {
                ledger.repay(id, repayCut);
                TAX_SUM[3] += repayCut;
            }
            if (arrears > 0.0) {
                // 못 낸 세금은 빚이 된다 — 추종 점수도 함께 오른다(더 깊이 묶인다).
                ledger.record(id, patronId, 0.0, arrears, day);
                TAX_SUM[1] += arrears;
                TAX_CNT[1]++;
            }
            if (pay > 0.0) {
                TAX_SUM[0] += pay;
                TAX_CNT[0]++;
            }
        }

        // ── 상납 — 걷은 자가 스스로 누군가를 따르면 그 몫의 일부를 위로 올린다.
        //    말단부터가 아니라 <b>사슬이 깊은 자부터</b> 올려야 한 번의 순회로 왕까지 닿는다.
        //    받는 자는 전부 주인이므로 후보는 <b>주인 전체</b>다. TAX_IN 의 키만 쓰면, 직속
        //    추종자가 오늘 한 푼도 못 낸 중간 지배자가 아래에서 올라온 상납을 그대로 깔고 앉는다.
        java.util.List<Long> lords = new java.util.ArrayList<>(
                new java.util.HashSet<>(patrons.values()));
        lords.sort(java.util.Comparator
                .comparingInt((Long id) -> -chainDepth(patrons, id))
                .thenComparingLong(id -> id));
        for (long id : lords) {
            Long up = patrons.get(id);
            if (up == null) {
                continue;
            }
            MimicEntity me = byId.get(id);
            MimicEntity boss = byId.get(up);
            if (me == null || boss == null || me.getHomePos() == null
                    || boss.getHomePos() == null || me.getHomePos().equals(boss.getHomePos())) {
                continue;
            }
            double send = com.evosim.core.Tribute.tributeUp(TAX_IN.getOrDefault(id, 0.0));
            send = Math.min(send, larders.get(me.getHomePos()));
            if (send <= 0.0) {
                continue;
            }
            larders.set(me.getHomePos(), larders.get(me.getHomePos()) - send);
            larders.set(boss.getHomePos(), larders.get(boss.getHomePos()) + send);
            TAX_OUT.merge(id, send, Double::sum);
            TAX_IN.merge(up, send, Double::sum);
            TAX_SUM[2] += send;
        }
    }

    /** 학교별 등록 학생 — 하루 한 번 새로 짠다. 등하교 goal 과 보고가 이것을 읽는다. */
    private static final java.util.Map<Long, java.util.List<Integer>> ENROLLED =
            new java.util.HashMap<>();
    /** 학생 개체 id → 다닐 학교 좌표. 통학거리 보고의 입력. */
    private static final java.util.Map<Integer, BlockPos> SCHOOL_OF = new java.util.HashMap<>();
    /**
     * 학생 개체 id → <b>제 자리</b>(월드 좌표). 등하교 goal 의 목적지.
     *
     * <p>학교 앵커 하나로 보내면 스무 명이 한 칸에 뭉쳐 밀치기만 한다. 도면의 독서대 앞
     * 자리({@link FacilityTemplate#seats})를 한 명씩 나눠 주면 저절로 흩어져 앉는다 —
     * 자리 수가 곧 정원이므로 배정과 수용이 <b>같은 수</b>에서 나온다.
     */
    private static final java.util.Map<Integer, BlockPos> SEAT_OF = new java.util.HashMap<>();
    /** [등교, 대상 소년, 수업료 수입, 미납, 급여] — 한 줄 보고용. */
    private static final double[] SCHOOL_SUM = new double[5];

    /**
     * <b>등교하지 못한 사유</b> — [가구대표가 주인을 안 따름, 가구의 누구도 안 따름, 멀다, 자리없음].
     *
     * <p>등교 0 이 나왔을 때 <b>왜</b> 0 인지 보고가 스스로 말하게 한다. 이 세션에서 궁핍 0 ·
     * 학교 0채 · 밭 구멍이 전부 같은 이유로 헛돌았다 — 세면서 사유를 안 남기면 원인을 추측하게
     * 된다. 특히 앞의 두 칸은 서로 다른 가설을 가른다: 대표만 못 따르는 것인지(내 판정이
     * 좁은 것), 가구 전체가 안 따르는 것인지(정말 대상이 아닌 것).
     */
    private static final int[] SCHOOL_MISS = new int[4];

    public static int[] schoolMiss() {
        return SCHOOL_MISS.clone();
    }

    /** 이 소년이 오늘 다닐 학교 — 없으면 null. 통학거리 보고의 단일 출처. */
    @javax.annotation.Nullable
    public static BlockPos schoolOf(MimicEntity boy) {
        return SCHOOL_OF.get(boy.getId());
    }

    /** 이 소년의 오늘 자리 — 없으면 null. 등하교 goal 의 단일 출처. */
    @javax.annotation.Nullable
    public static BlockPos seatOf(MimicEntity boy) {
        return SEAT_OF.get(boy.getId());
    }

    public static double[] schoolSums() {
        return SCHOOL_SUM.clone();
    }

    /**
     * <b>학교 운영</b>(P5b) — 교사 급여 · 등록 · 수업료 · 신세.
     *
     * <p>이 단계가 P4 에서 드러난 결핍을 메운다. 원장에 간선을 만드는 셋(소작·구휼·긴급고용)은
     * 전부 <b>가난한 쪽이 받는 것</b>이라 지주는 어느 것도 받지 않고, 그래서 지주 간 신세가
     * 하루 5% 씩 옅어지기만 해 <b>지배 계층이 생겼다 사라졌다</b> 했다(실측 D18 지배1·깊이2 →
     * D20~22 지배0·깊이1). 학교는 사슬을 새로 만드는 장치가 아니라 <b>이미 생긴 사슬을 고정하는
     * 못</b>이다 — 밭을 가진 자도 신세가 임계를 넘으면 주인을 갖는데, 그 지주의 아들이 주인의
     * 학교에 다니면 매일 신세가 채워져 감쇠를 이긴다.
     *
     * <p><b>대상은 추종 가구의 소년</b>(계획서 1.5)이다. 아무나 다닐 수 있게 하면 학교가
     * 예속의 도구가 아니라 공공재가 되어 버린다.
     *
     * <p>수업료와 신세를 <b>둘 다</b> 매기는 것은 이중 부과가 아니다. 소액 수업료가 교육의 값을
     * 다 치르지 못하고 그 차액이 은혜로 남는 것이 후원의 실체다 — 소작이 임금을 받으면서도
     * 신세를 쌓는 것({@link AllegianceStore#W_TENANCY})과 같은 구조이고 선례가 이미 있다.
     */
    // ── 막사(군인) ────────────────────────────────────────────────────────────────────
    /**
     * 막사 좌표 → 주인. 그리고 오늘의 추종 명부 — <b>전투 goal 이 매 틱 읽는다.</b>
     *
     * <p>적 판정("이 둘은 다른 세력인가")을 매번 원장에서 다시 계산하면 전투 판정마다
     * 추종 명부를 새로 만들게 된다. 하루 한 번 정산에서 찍어 두고 그것을 읽는다.
     */
    private static final java.util.Map<Long, Long> POST_OWNER = new java.util.HashMap<>();
    private static final java.util.Map<Long, Long> PATRONS_TODAY = new java.util.HashMap<>();

    /** 막사 → 전투 가능 병사가 없는 채로 적에게 둘러싸인 연속 일수. {@link Facilities#OCCUPY_DAYS} 에서 넘어간다. */
    private static final java.util.Map<Long, Integer> OCCUPY_COUNT = new java.util.HashMap<>();

    /**
     * <b>세력의 뿌리</b> — 추종 사슬을 타고 올라간 최상위 주인. 자기 자신이면 독립이다.
     *
     * <p>깊이 상한 8 은 순환 방어다. {@code patronMap} 은 순환을 만들지 않도록 되어 있지만,
     * 전투가 이 값에 매달리므로 여기서 한 번 더 막는다 — 무한 루프는 서버를 세운다.
     */
    public static long factionRootOf(long id) {
        long cur = id;
        for (int i = 0; i < 8; i++) {
            Long up = PATRONS_TODAY.get(cur);
            if (up == null || up == 0L || up == cur) {
                break;
            }
            cur = up;
        }
        return cur;
    }

    /**
     * 두 병사가 <b>적</b>인가 — 배속 막사가 다르고, 두 막사 주인의 세력 뿌리가 다를 때.
     *
     * <p>같은 뿌리면 한 나라의 병사이므로 싸우지 않는다. 굴복해서 봉신이 된 세력의 병사도
     * 이 판정으로 저절로 아군이 된다 — 신분을 묻지 않고 사슬만 탄다.
     */
    public static boolean hostileSoldiers(MimicEntity a, MimicEntity b) {
        BlockPos pa = POST_OF.get(a.getId());
        BlockPos pb = POST_OF.get(b.getId());
        if (pa == null || pb == null || pa.equals(pb)) {
            return false;
        }
        Long oa = POST_OWNER.get(pa.asLong());
        Long ob = POST_OWNER.get(pb.asLong());
        if (oa == null || ob == null || oa.equals(ob)) {
            return false;
        }
        return factionRootOf(oa) != factionRootOf(ob);
    }

    /** 개체 → 배속된 막사 좌표. 하루 단위로 다시 짠다(학교의 SCHOOL_OF 와 같은 구조). */
    private static final java.util.Map<Integer, BlockPos> POST_OF = new java.util.HashMap<>();

    /** 개체 → 그 막사에서 맡은 자리(경계 위치). */
    private static final java.util.Map<Integer, BlockPos> GUARD_SEAT = new java.util.HashMap<>();

    /** 개체 → 봉급 미납 연속 일수. 이탈 판정의 입력. */
    private static final java.util.Map<Long, Integer> UNPAID_DAYS = new java.util.HashMap<>();

    /** [배속, 지급총액, 세수총액, 이탈, 구휼] — 보고용 누계. */
    private static final double[] GUARD_SUM = new double[5];

    // ── 압박(WAR-PLAN.md P3) ─────────────────────────────────────────────
    /**
     * 막사 → {표적 거처 → 표적 개체 id}. 하루에 한 번 새로 짠다.
     *
     * <p><b>표적은 무장하지 않은 독자세력 머리뿐이다</b> — ① 추종자가 있고 ② 주인이 없고
     * ③ 제 막사가 없는 자. ③이 두 경로를 가른다: 막사를 가진 상대는 전투로 갈리므로
     * 여기 들어오지 않는다. 그래서 세력권이 겹쳐도 압박 표적이 중복되지 않는다.
     */
    private static final java.util.Map<Long, java.util.Map<Long, Long>> PRESSURE_TARGETS =
            new java.util.HashMap<>();

    /** 막사 → 어제 정산 이후 병사가 <b>실제로 닿은</b> 표적 거처. 순찰 goal 이 채운다. */
    private static final java.util.Map<Long, java.util.Set<Long>> PRESSURE_REACHED =
            new java.util.HashMap<>();

    /**
     * 막사 → 어제 이후 병사가 표적에 <b>가장 가까이 간 거리</b>. 진단 전용.
     *
     * <p>"도달 0"의 원인이 셋으로 갈린다: 병사가 아예 안 갔는가(거리가 처음 그대로),
     * 가긴 갔는데 문턱에서 걸렸는가(3~10), 아니면 표적이 없었는가. 이 수 하나가 그것을
     * 가른다 — 거리를 안 재고 추측으로 좁히다 두 번 헛돌았다.
     */
    private static final java.util.Map<Long, Double> PRESSURE_NEAREST =
            new java.util.HashMap<>();

    /** 표적 집에 이만큼 다가서면 "문 앞에 섰다"로 본다. 도착 판정(2.5)보다 넉넉한 이유:
     *  거처 좌표는 천막 구조물 <b>안쪽</b>이라 병사가 그 자리에 설 수 없다. */
    private static final double PRESSURE_NEAR = 6.0;

    /**
     * 병사의 현재 위치를 표적들과 견준다 — 순찰 goal 이 매 틱 부른다.
     *
     * <p>{@code spot} 이 무엇인지 묻지 않고 <b>몸이 어디 있는가</b>만 본다. 목적지 판정에
     * 기대면 도착 문턱·구조물·경로 사정에 결과가 매달리는데, 물음은 "그날 병사가 그 집
     * 앞에 왔는가" 하나뿐이다.
     */
    public static void reportPressureNear(BlockPos barracks, BlockPos where) {
        var targets = PRESSURE_TARGETS.get(barracks.asLong());
        if (targets == null || targets.isEmpty()) {
            return;
        }
        for (long h : targets.keySet()) {
            BlockPos home = BlockPos.of(h);
            double dx = home.getX() - where.getX();
            double dz = home.getZ() - where.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            PRESSURE_NEAREST.merge(barracks.asLong(), d, Math::min);
            if (d <= PRESSURE_NEAR) {
                PRESSURE_REACHED.computeIfAbsent(barracks.asLong(),
                        k -> new java.util.HashSet<>()).add(h);
            }
        }
    }

    /** 이 사람 명의의 막사가 있는가 — 있으면 무장 세력이라 압박이 아니라 전투 대상이다. */
    private static boolean barracksOwnedBy(FacilityStore reg, long id) {
        for (FacilityStore.Entry e : reg.all()) {
            if (e.ownerId == id
                    && e.kind.group == FacilityTemplate.Group.BARRACKS) {
                return true;
            }
        }
        return false;
    }

    /**
     * 지금 압박 중인 막사가 하나라도 있는가 — {@link NightSkipTicker} 가 읽는다.
     *
     * <p>밤 스킵의 전제는 "취침은 소모 0 이라 시뮬 결과 불변"인데, <b>군인에게 밤은 근무
     * 시간</b>이라 그 전제가 깨진다. 압박이 걸린 밤을 지우면 병사가 표적 집에 갈 시간이
     * 통째로 사라진다(실측: 순찰 창 100틱). 평시에는 여전히 지워도 되므로, 표적이 있을
     * 때만 막는다 — 관측 속도의 손해를 전쟁 국면으로 한정한다.
     */
    public static boolean pressureActive() {
        for (var e : PRESSURE_TARGETS.values()) {
            if (!e.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** 이 막사의 압박 표적 거처 목록 — 순찰 경로가 읽는다(읽기 전용). */
    public static java.util.List<BlockPos> pressureHomesOf(BlockPos barracks) {
        var m = PRESSURE_TARGETS.get(barracks.asLong());
        if (m == null || m.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (long h : m.keySet()) {
            out.add(BlockPos.of(h));
        }
        // 커서가 매일 같은 순서를 돌도록 좌표로 정렬(람다 타입을 명시해야 추론된다).
        out.sort(java.util.Comparator.comparingInt((BlockPos p) -> p.getX())
                .thenComparingInt((BlockPos p) -> p.getZ()));
        return out;
    }

    /**
     * <b>병사가 표적 집 앞에 섰다</b> — 순찰 goal 이 도착했을 때 부른다.
     *
     * <p>신세는 여기서 적립하지 <b>않는다</b>. 도착은 밤새 여러 번 일어날 수 있어, 여기서
     * 적립하면 순찰 횟수가 압박의 세기가 되어 버린다(병사가 많을수록·빠를수록 빨리 굴복).
     * 압박은 "그날 병사가 왔는가"의 문제이므로 표시만 하고, 적립은 하루 한 번 정산이 한다.
     */
    public static void reportPressureVisit(BlockPos barracks, BlockPos home) {
        if (PRESSURE_TARGETS.getOrDefault(barracks.asLong(), java.util.Map.of())
                .containsKey(home.asLong())) {
            PRESSURE_REACHED.computeIfAbsent(barracks.asLong(),
                    k -> new java.util.HashSet<>()).add(home.asLong());
        }
    }

    /**
     * <b>전사자 유족 보상</b> — 배속된 군인이 죽으면 그 가구에 영주가 식량을 넣는다.
     *
     * <p>{@link MimicEntity#die} 에서 부른다. 군인 선발 조건에 {@code isProviderRole()} 이
     * 있어 <b>모든 군인은 가구 부양자</b>이므로, 전사 1명은 가구 1개의 붕괴다. 그것은 전쟁의
     * 대가이지 결함이 아니라서 이 지급은 완충이지 면제가 아니다 —
     * {@link Facilities#DEATH_BENEFIT_DAYS}(7일)치만 준다.
     *
     * <p>구휼로 기록하므로 유족이 영주에게 신세를 진다. <b>전쟁 손실이 오히려 가문을
     * 결속시킨다</b>. 영주 저장고가 모자라면 있는 만큼만 주고 그 사실을 남긴다 — 침묵은
     * 진단이 아니다.
     */
    public static void payDeathBenefit(ServerLevel level, MimicEntity dead) {
        BlockPos post = POST_OF.get(dead.getId());
        if (post == null || dead.getIndividual() == null || dead.getHomePos() == null) {
            return; // 배속된 군인이 아니다
        }
        var reg = FacilityStore.get(level);
        FacilityStore.Entry bk = null;
        for (FacilityStore.Entry e : reg.all()) {
            if (post.equals(e.pos)) {
                bk = e;
                break;
            }
        }
        if (bk == null || bk.ownerId == 0L) {
            return;
        }
        MimicEntity owner = null;
        java.util.List<MimicEntity> adults = new java.util.ArrayList<>();
        for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null
                        && (e.getStage() == com.evosim.core.LifeStage.ADULT
                                || e.getStage() == com.evosim.core.LifeStage.ELDER))) {
            adults.add(m);
            if (m.getIndividual().id() == bk.ownerId) {
                owner = m;
            }
        }
        if (owner == null || owner.getHomePos() == null) {
            return;
        }
        // 기준선은 봉급 상한과 같은 눈금을 쓴다 — 그 가구 성인의 명목 하루소모 합.
        double adultNeed = 0.0;
        for (MimicEntity a : adults) {
            if (dead.getHomePos().equals(a.getHomePos()) && a != dead) {
                adultNeed += com.evosim.core.FoodEconomy.consumptionPerDay(a.getStage(),
                        com.evosim.core.Activity.MOVE, a.getIndividual(), false);
            }
        }
        if (adultNeed <= 0.0) {
            // 남은 성인이 없다 — 유아·소년만 남은 집이다. 죽은 자의 몫으로라도 셈한다.
            adultNeed = com.evosim.core.FoodEconomy.consumptionPerDay(dead.getStage(),
                    com.evosim.core.Activity.MOVE, dead.getIndividual(), false);
        }
        LarderStore larders = LarderStore.get(level);
        double want = adultNeed * Facilities.DEATH_BENEFIT_DAYS;
        double have = larders.get(owner.getHomePos());
        double pay = Math.min(want, Math.max(0.0, have));
        if (pay <= 0.0) {
            com.evosim.mod.log.SimEvents.event(dead, "전사", String.format(
                    "막사 @%d,%d 소속 — 유족 보상 %.1f 필요하나 영주 저장고 %.1f, 지급 없음",
                    post.getX(), post.getZ(), want, have));
            return;
        }
        larders.set(owner.getHomePos(), have - pay);
        larders.set(dead.getHomePos(), larders.get(dead.getHomePos()) + pay);
        reg.spend(bk, pay);
        long day = com.evosim.mod.entity.SimTime.tick(level) / 24000L;
        // 유족 쪽에 신세를 남긴다 — 죽은 자에게 적으면 다음 감쇠에서 사라진다.
        AllegianceStore ledger = AllegianceStore.get(level);
        for (MimicEntity a : adults) {
            if (dead.getHomePos().equals(a.getHomePos()) && a != dead) {
                ledger.record(a.getIndividual().id(), bk.ownerId,
                        AllegianceStore.W_RELIEF * pay
                                * AllegianceStore.rapport(a.getIndividual()), 0.0, day);
            }
        }
        com.evosim.mod.log.SimEvents.event(dead, "전사", String.format(
                "막사 @%d,%d 소속 — 유족 보상 %.1f 지급(요구 %.1f · 영주 저장고 %.1f→%.1f)",
                post.getX(), post.getZ(), pay, want, have, have - pay));
    }

    /**
     * 점검용 — 개체를 그 막사 소속 병사로 못박는다(/evosim foetest).
     *
     * <p>일일 정산을 기다리지 않고 적 판정({@link #hostileSoldiers})과 교전을 그 자리에서
     * 확인하기 위한 것이다. 세계가 자라며 추종 가구가 사방으로 퍼지면 순찰 경로가 적 근처를
     * 지나지 않아 조우 자체가 운에 매달린다 — 바꾼 것(표적 선정)만 따로 재려면 조우를
     * 조성해야 한다.
     */
    public static void debugAssignPost(MimicEntity m, BlockPos barracks, long ownerId) {
        POST_OF.put(m.getId(), barracks);
        POST_OWNER.put(barracks.asLong(), ownerId);
    }

    /**
     * <b>점령 판정</b> — 배속이 <b>전부 끝난 뒤</b> 따로 돈다.
     *
     * <p>이것을 막사 순회 안에 두면 순서만으로 결과가 갈린다: POST_OF 는 정산 시작 시
     * 비워지므로, 먼저 처리된 막사는 병사를 얻고 나중 막사는 아직 0 명이다. 그러면 뒤쪽
     * 막사가 늘 무방비로 보여 점령 카운트가 오른다(실측: 양쪽 다 배속 1명인데 한쪽만
     * 카운트). 먼저 처리되는 세력이 언제나 이기는 셈이라 전쟁이 순회 순서로 결정된다.
     */
    private static void settleOccupation(ServerLevel level, java.util.List<MimicEntity> adults) {
        FacilityStore reg = FacilityStore.get(level);
        for (FacilityStore.Entry bk : new java.util.ArrayList<>(reg.all())) {
            if (bk.kind.group != FacilityTemplate.Group.BARRACKS || bk.ownerId == 0L) {
                continue;
            }
            long okey = bk.pos.asLong();
            boolean capable = false;
            for (MimicEntity s2 : adults) {
                if (bk.pos.equals(POST_OF.get(s2.getId())) && !s2.isWounded()) {
                    capable = true;
                    break;
                }
            }
            MimicEntity foe = null;
            for (MimicEntity o : adults) {
                BlockPos op = POST_OF.get(o.getId());
                if (op == null || op.equals(bk.pos) || o.isWounded()) {
                    continue;
                }
                Long oo = POST_OWNER.get(op.asLong());
                if (oo == null || factionRootOf(oo) == factionRootOf(bk.ownerId)) {
                    continue;
                }
                if (o.blockPosition().distSqr(bk.pos)
                        <= Facilities.COMMUTE_RANGE * Facilities.COMMUTE_RANGE) {
                    foe = o;
                    break;
                }
            }
            if (!capable && foe != null) {
                int n = OCCUPY_COUNT.merge(okey, 1, Integer::sum);
                Long newOwner = POST_OWNER.get(POST_OF.get(foe.getId()).asLong());
                com.evosim.mod.log.SimEvents.note(level, "점령", String.format(
                        "막사 @%d,%d — 전투 가능 병사 0 · 적 병사 반경 안 · %d/%d일",
                        bk.pos.getX(), bk.pos.getZ(), n, Facilities.OCCUPY_DAYS));
                if (n >= Facilities.OCCUPY_DAYS && newOwner != null && newOwner != 0L) {
                    long old = bk.ownerId;
                    bk.ownerId = newOwner;
                    reg.setDirty();
                    OCCUPY_COUNT.remove(okey);
                    POST_OWNER.put(okey, newOwner);
                    com.evosim.mod.log.SimEvents.note(level, "점령", String.format(
                            "막사 @%d,%d 주인 #%d → #%d — 주권이 넘어갔다."
                                    + " 땅은 그대로다(패자는 이제 압박 표적이 된다)",
                            bk.pos.getX(), bk.pos.getZ(), old, newOwner));
                }
            } else {
                OCCUPY_COUNT.remove(okey);
            }
        }
    }

    /**
     * <b>가장 가까운 아군 막사</b> — 부상병이 후송될 곳. 소속 막사가 아니어도 된다.
     *
     * <p>아군 = 두 막사 주인의 {@link #factionRootOf 세력 뿌리}가 같을 것. 굴복해 봉신이
     * 된 세력의 막사도 저절로 아군이 된다 — 적 판정과 같은 잣대를 쓴다.
     */
    public static BlockPos nearestFriendlyBarracks(ServerLevel level, MimicEntity m) {
        BlockPos mine = POST_OF.get(m.getId());
        if (mine == null) {
            return null;
        }
        Long myOwner = POST_OWNER.get(mine.asLong());
        if (myOwner == null) {
            return null;
        }
        long root = factionRootOf(myOwner);
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (FacilityStore.Entry e : FacilityStore.get(level).all()) {
            if (e.kind.group != FacilityTemplate.Group.BARRACKS || e.ownerId == 0L
                    || factionRootOf(e.ownerId) != root) {
                continue;
            }
            double d = m.blockPosition().distSqr(e.pos);
            if (d < bd) {
                bd = d;
                best = e.pos;
            }
        }
        return best;
    }

    /**
     * <b>후송 급양</b> — 부상병이 아군 막사에 닿으면 영주 저장고에서 소지 식량을 채운다.
     *
     * <p>회복 자체는 새로 만들지 않는다. {@code MimicEntity.regenTick} 이 이미
     * {@code holding > 0} 에서만 돌므로, 이 한 줄이 <b>지배자의 지갑을 전투지속력으로</b>
     * 바꾼다: 적의 저장고가 마르면 급양이 끊기고 → 회복이 멎고 → 부상병이 계속 전투불가로
     * 남고 → {@link Facilities#OCCUPY_DAYS} 뒤 막사가 넘어간다.
     *
     * <p>이미 배가 부르면 주지 않는다 — 매 틱 도착 판정이 도므로, 그러지 않으면 저장고가
     * 순식간에 마른다.
     */
    public static void medicate(ServerLevel level, MimicEntity m, BlockPos barracks) {
        if (m.getHolding() >= Facilities.MEDIC_RATION || m.getHomePos() == null) {
            return;
        }
        FacilityStore.Entry bk = null;
        for (FacilityStore.Entry e : FacilityStore.get(level).all()) {
            if (barracks.equals(e.pos)) {
                bk = e;
                break;
            }
        }
        if (bk == null || bk.ownerId == 0L) {
            return;
        }
        MimicEntity owner = null;
        for (MimicEntity o : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null
                        && e.getIndividual().id() == bk2Id(barracks))) {
            owner = o;
        }
        if (owner == null || owner.getHomePos() == null) {
            return;
        }
        LarderStore larders = LarderStore.get(level);
        double have = larders.get(owner.getHomePos());
        double pay = Math.min(Facilities.MEDIC_RATION, Math.max(0.0, have));
        if (pay <= 0.0) {
            com.evosim.mod.log.SimEvents.event(m, "후송", String.format(
                    "막사 @%d,%d 에 닿았으나 영주 저장고가 말랐다(%.1f) — 회복 없음 · 체력 %.0f%%",
                    barracks.getX(), barracks.getZ(), have,
                    100.0 * m.getHealth() / m.getMaxHealth()));
            return;
        }
        larders.set(owner.getHomePos(), have - pay);
        m.setDayHarvest(m.getHolding() + pay);
        FacilityStore.get(level).spend(bk, pay);
        com.evosim.mod.log.SimEvents.event(m, "후송", String.format(
                "막사 @%d,%d 급양 %.1f — 체력 %.0f%% · 영주 저장고 %.1f→%.1f",
                barracks.getX(), barracks.getZ(), pay,
                100.0 * m.getHealth() / m.getMaxHealth(), have, have - pay));
    }

    private static long bk2Id(BlockPos barracks) {
        Long o = POST_OWNER.get(barracks.asLong());
        return o == null ? 0L : o;
    }

    public static BlockPos postOf(MimicEntity m) {
        return POST_OF.get(m.getId());
    }

    public static BlockPos guardSeatOf(MimicEntity m) {
        return GUARD_SEAT.get(m.getId());
    }

    /**
     * <b>상시소작 승격에 필요한 연속 출근일</b> — 끈기 −1(하한 1) · 변덕 +1.
     *
     * <p>근성(보조)이 붙는 자리다. 일자리가 없으면 셀 것이 없어 효과가 정확히 0이다.
     */
    private static int promoteDays(MimicEntity m) {
        int d = com.evosim.core.FarmEconomy.PROMOTE_DAYS;
        var ind = m.getIndividual();
        if (ind == null) {
            return d;
        }
        if (com.evosim.core.ExpressionResolver.isExpressed(ind, com.evosim.core.Trait.TENACIOUS)) {
            return Math.max(1, d - 1);
        }
        if (com.evosim.core.ExpressionResolver.isExpressed(ind, com.evosim.core.Trait.FICKLE)) {
            return d + 1;
        }
        return d;
    }

    /**
     * <b>군인 적합도</b> — 완력과 경계를 각각 중립 대비 배수로 재서 더한다(중립 = 2.0).
     *
     * <p>완력은 <b>맨몸</b>으로 잰다({@link com.evosim.core.Physique#barehandMight}). 철검이
     * 공격력 +5 를 얹어 기본 2.0 을 7.0 으로 만들기 때문에, 무장 뒤 값으로 재면 힘 특성 차이가
     * 10% 안으로 묻혀 단순무식·야성의 거래가 선발에서 사라진다.
     *
     * <p>경계는 {@link com.evosim.core.Combat#detectionRange} 를 기본값 8 로 나눈 것이다.
     * 용감(+6)·산만(+4)·천리안이 여기 얹히고, 겁쟁이(−3)·멍청·근시안이 깎는다.
     *
     * <p>실측 눈금: 중립 2.00 · 용감 2.75 · 산만Ⅴ 2.50 · 단순무식Ⅴ 2.30 ·
     * 단순무식Ⅴ+야성(결손 0.5) 2.63 · 멍청+겁쟁이 1.53.
     */
    private static double soldierFitness(com.evosim.core.Individual ind) {
        if (ind == null) {
            return 0.0;
        }
        return com.evosim.core.Physique.barehandMight(ind)
                + com.evosim.core.Combat.detectionRange(ind) / 8.0;
    }

    /** <b>군인이 봉급 미납을 참는 날수</b> — 끈기 +1 · 변덕 −1(하한 1). 배속이 없으면 효과 0. */
    private static int desertDays(MimicEntity m) {
        int d = Facilities.SOLDIER_DESERT_DAYS;
        var ind = m.getIndividual();
        if (ind == null) {
            return d;
        }
        if (com.evosim.core.ExpressionResolver.isExpressed(ind, com.evosim.core.Trait.TENACIOUS)) {
            return d + 1;
        }
        if (com.evosim.core.ExpressionResolver.isExpressed(ind, com.evosim.core.Trait.FICKLE)) {
            return Math.max(1, d - 1);
        }
        return d;
    }

    public static boolean isSoldier(MimicEntity m) {
        return POST_OF.containsKey(m.getId());
    }

    public static double[] guardSums() {
        return GUARD_SUM.clone();
    }

    /**
     * <b>주둔 정산</b> — 배속 · 보호세 · 봉급 · 이탈. 하루 1회, 학교 정산과 같은 틱.
     *
     * <p>순서가 뜻을 갖는다: 먼저 <b>세금을 걷고</b>(보호받는 가구 → 지주), 그 돈이 섞인
     * 저장고에서 <b>봉급을 준다</b>. 그래서 세금은 지주의 부담을 줄이는 것이지 별도 금고가
     * 아니다 — 지시 사양 그대로다.
     *
     * <p>정원은 자리 수가 아니라 <b>지킬 가구 수</b>에서 나온다. 지킬 사람이 없는데 자리를 다
     * 채우는 것은 낭비다({@link Facilities#HOUSEHOLDS_PER_SOLDIER}).
     */
    private static void runBarracks(ServerLevel level, AllegianceStore ledger, LarderStore larders,
                                    java.util.List<MimicEntity> adults,
                                    java.util.Map<Long, Long> patrons, long day) {
        // 어제의 배속 명단을 들고 있다가, 오늘 다시 앉지 못한 자에게서 무장을 벗긴다.
        // POST_OF 는 매일 새로 짜므로 이 스냅숏이 없으면 이탈·해임된 자가 죽을 때까지
        // 검과 갑옷을 걸치고 다닌다 — 육안으로 군인 수를 셀 수 없게 된다.
        java.util.Set<Integer> wasPosted = new java.util.HashSet<>(POST_OF.keySet());
        // 막사마다 세수·압박·후보까지 확정해 담아 두고, <b>자리 배정만</b> 뒤로 미룬다.
        // 3패스로 앉혀야 순회 순서가 승패를 가르지 않고 최소 수비가 먼저 채워진다(seatAll).
        java.util.List<Garrison> plans = new java.util.ArrayList<>();
        POST_OF.clear();
        POST_OWNER.clear();
        PATRONS_TODAY.clear();
        PATRONS_TODAY.putAll(patrons); // 전투 goal 이 읽을 오늘의 추종 명부
        GUARD_SEAT.clear();
        java.util.Arrays.fill(GUARD_SUM, 0.0);
        FacilityStore reg = FacilityStore.get(level);
        FarmStore fs = FarmStore.get(level);
        for (FacilityStore.Entry bk : reg.all()) {
            if (bk.kind.group != FacilityTemplate.Group.BARRACKS) {
                continue;
            }
            MimicEntity owner = null;
            for (MimicEntity m : adults) {
                if (m.getIndividual() != null && m.getIndividual().id() == bk.ownerId) {
                    owner = m;
                    break;
                }
            }
            if (owner == null || owner.getHomePos() == null) {
                continue; // 주인 부재(사망·미로드) — 그날은 쉰다
            }
            var tpl = FacilityTemplate.of(level, bk.kind, bk.rotation, bk.mirrored);
            if (tpl.isEmpty() || tpl.get().seats().isEmpty()) {
                continue;
            }
            // ① 지킬 가구 — 이 막사의 경계 반경 안에 있는 추종 가구.
            //
            // <b>가구 단위로 센다.</b> FOLLOWER_HOMES 는 everyone(유아·소년 포함) 순회로 채워져
            // 한 지붕 아래 4명이 살면 같은 좌표가 4번 들어간다. List 로 그대로 세면
            // "4가구당 군인 1명"이 실제로는 "추종자 4명당 1명"이 되어 정원이 부풀고, 지주가
            // 감당 못 할 수의 병사를 두게 된다. 세금도 가구 단위로 걷으므로(아래 ②) 여기서
            // 중복을 없애야 세수와 정원이 같은 분모를 본다.
            java.util.Set<BlockPos> guarded = new java.util.LinkedHashSet<>();
            for (BlockPos h : followerHomesOf(bk.ownerId)) {
                if (h.distSqr(bk.pos) <= Facilities.COMMUTE_RANGE * Facilities.COMMUTE_RANGE) {
                    guarded.add(h);
                }
            }
            int cap = Math.min(tpl.get().seats().size(),
                    guarded.size() / Facilities.HOUSEHOLDS_PER_SOLDIER);
            if (cap <= 0) {
                // <b>여기서도 사유를 남긴다.</b> 종전에는 조용히 continue 해서, "막사는 섰는데
                // 군인이 0명"인 상태에 로그가 한 줄도 없었다(실측 — 사용자가 원인을 못 찾음).
                // 침묵은 진단이 아니다.
                com.evosim.mod.log.SimEvents.note(level, "주둔", String.format(
                        "막사 @%d,%d — 지킬가구 %d < %d → 정원 0 · 배속 없음"
                                + "(추종 가구가 경계 반경 %d 안에 더 들어와야 한다)",
                        bk.pos.getX(), bk.pos.getZ(), guarded.size(),
                        Facilities.HOUSEHOLDS_PER_SOLDIER, (int) Facilities.COMMUTE_RANGE));
                continue; // 지킬 사람이 없다 — 병사도 세금도 없다
            }
            // ② 보호세 — 지킬 가구가 저장고 비율로 낸다(캡·2일치 유보). 못 내면 빚(신세).
            double taxIn = 0.0;
            for (BlockPos h : guarded) {
                double stock = larders.get(h);
                double due = Math.min(Facilities.GUARD_TAX_CAP, stock * Facilities.GUARD_TAX_RATE);
                MimicEntity res = null;
                for (MimicEntity m : adults) {
                    if (h.equals(m.getHomePos())) {
                        res = m;
                        break;
                    }
                }
                double pay = res == null ? 0.0
                        : Math.min(due, com.evosim.core.Tribute.payable(stock,
                                familyDailyNeed(level, res, adults)));
                if (pay > 0.0) {
                    larders.set(h, stock - pay);
                    taxIn += pay;
                }
                double unpaid = due - pay;
                if (unpaid > 0.0 && res != null && res.getIndividual() != null) {
                    ledger.record(res.getIndividual().id(), bk.ownerId, 0.0, unpaid, day);
                }
            }
            larders.set(owner.getHomePos(), larders.get(owner.getHomePos()) + taxIn);
            reg.earn(bk, taxIn);
            GUARD_SUM[2] += taxIn;

            POST_OWNER.put(bk.pos.asLong(), bk.ownerId);

            // ②-b 압박 — 어제 병사가 닿은 표적에게 신세를 적립하고, 오늘의 표적을 새로 짠다.
            //
            // <b>순서가 중요하다</b>: 적립을 먼저 하고 명부를 다시 짠다. 반대로 하면 어제
            // 표적이었다가 오늘 굴복해 명단에서 빠진 자가 마지막 하루치를 못 받는다.
            long bkKey = bk.pos.asLong();
            java.util.Map<Long, Long> yday =
                    PRESSURE_TARGETS.getOrDefault(bkKey, java.util.Map.of());
            java.util.Set<Long> reached =
                    PRESSURE_REACHED.getOrDefault(bkKey, java.util.Set.of());
            for (var e : yday.entrySet()) {
                if (!reached.contains(e.getKey())) {
                    continue; // 병사가 못 갔다 — 압박은 <b>발이 닿은</b> 날에만 있다
                }
                long tid = e.getValue();
                MimicEntity tgt = null;
                for (MimicEntity m : adults) {
                    if (m.getIndividual().id() == tid) {
                        tgt = m;
                        break;
                    }
                }
                if (tgt == null) {
                    continue;
                }
                double before = ledger.bondTo(tid, bk.ownerId);
                ledger.record(tid, bk.ownerId,
                        Facilities.W_PRESSURE * AllegianceStore.rapport(tgt.getIndividual()),
                        0.0, day);
                double after = ledger.bondTo(tid, bk.ownerId);
                int tiles = fs.ownedTiles(tid);
                double gate = Math.max(AllegianceStore.MIN_BOND,
                        tiles * AllegianceStore.TILE_WORTH);
                com.evosim.mod.log.SimEvents.event(tgt, "압박", String.format(
                        "막사 @%d,%d 의 병사가 문 앞에 섰다 — 신세 %.1f→%.1f(문턱 %.1f · %d타일)%s",
                        bk.pos.getX(), bk.pos.getZ(), before, after, gate, tiles,
                        after >= gate ? " · §c굴복§r" : ""));
            }
            PRESSURE_REACHED.remove(bkKey);

            // 오늘의 표적 — 경계 반경 안의 <b>무장하지 않은</b> 독자세력 머리.
            //
            // 탈락 사유를 센다. "압박이 0건"일 때 원인이 표적 선정인지 도달인지 갈리지
            // 않으면 추측만 쌓인다 — 배속 후보 집계와 같은 이유로 여기에도 둔다.
            java.util.Map<Long, Long> targets = new java.util.LinkedHashMap<>();
            int pRejSelf = 0;
            int pRejHasPatron = 0;
            int pRejNoFollower = 0;
            int pRejArmed = 0;
            int pRejFar = 0;
            for (MimicEntity m : adults) {
                if (m.getIndividual() == null || m.getHomePos() == null) {
                    continue;
                }
                long mid = m.getIndividual().id();
                if (mid == bk.ownerId) {
                    pRejSelf++;
                    continue;
                }
                if (patrons.containsKey(mid)) {
                    pRejHasPatron++;
                    continue; // 이미 주인이 있다
                }
                if (!patrons.containsValue(mid)) {
                    pRejNoFollower++;
                    continue; // 따르는 자가 없다 — 세력 머리가 아니다
                }
                if (barracksOwnedBy(reg, mid)) {
                    pRejArmed++;
                    continue; // 무장 세력 — 전투로 갈린다(P4). 압박 표적이 아니다
                }
                if (m.getHomePos().distSqr(bk.pos)
                        > Facilities.COMMUTE_RANGE * Facilities.COMMUTE_RANGE) {
                    pRejFar++;
                    continue;
                }
                targets.put(m.getHomePos().asLong(), mid);
            }
            com.evosim.mod.log.SimEvents.note(level, "압박집계", String.format(
                    "막사 @%d,%d — 표적 %d명 · 어제 도달 %d · <b>최근접 %s</b> · 성인 %d명 중"
                            + " 탈락: 자신 %d · 주인있음 %d · 추종자없음 %d · 무장 %d · 원거리 %d",
                    bk.pos.getX(), bk.pos.getZ(), targets.size(), reached.size(),
                    PRESSURE_NEAREST.containsKey(bkKey)
                            ? String.format("%.1f블록", PRESSURE_NEAREST.get(bkKey)) : "간 적 없음",
                    adults.size(), pRejSelf, pRejHasPatron, pRejNoFollower, pRejArmed, pRejFar));
            PRESSURE_NEAREST.remove(bkKey);
            if (targets.isEmpty()) {
                PRESSURE_TARGETS.remove(bkKey);
            } else {
                PRESSURE_TARGETS.put(bkKey, targets);
            }

            // ③ 배속 — 추종하는 무전(無田) 성년, 가까운 순, 정원만큼.
            // 후보 탈락 사유 집계 — 선발 조건을 바꾼 뒤 "병사가 왜 이 사람들인가"를 수치로
            // 확인할 수 있어야 한다. 성별 편향을 고친 변경이라 더욱 그렇다.
            int rejLand = 0;      // 가구가 밭 보유
            int rejNotHead = 0;   // 가구 부양자가 아님
            int rejPatron = 0;    // 이 주인을 따르지 않음
            int rejFar = 0;       // 통근 한계 밖
            java.util.List<MimicEntity> pick = new java.util.ArrayList<>();
            for (MimicEntity m : adults) {
                if (m.getIndividual() == null || m.getHomePos() == null
                        || POST_OF.containsKey(m.getId())) {
                    continue;
                }
                long mid = m.getIndividual().id();
                if (mid == bk.ownerId || fs.ownedTiles(mid) > 0) {
                    continue; // 지주 자신·제 땅 가진 자는 군인이 되지 않는다(자영농 층 보존)
                }
                // <b>땅 판정은 가구 단위로.</b> 종전에는 개인 명의만 봐서, 밭을 가진 가구의
                // <b>아내·첩</b>이 후보에 남았다(그들의 ownedTiles 는 0이다). 지배자 본인의
                // 아내조차 그의 병사가 될 수 있었다 — 주인 본인만 걸러냈기 때문이다.
                //
                // 그 결과가 "군인이 싹 다 여자"다(육안 관측). 경제 전체에서 성별이 들어가는
                // 곳은 MALE_FORAGE 1.5 / FEMALE_FORAGE 0.5 하나뿐인데, 그 3배 차이가 남성을
                // 개간 문턱 너머로 보내 지주로 만들고(후보 제외), 여성은 자립을 못 해 무전으로
                // 남긴다. 거기에 지주의 아내까지 얹히면 후보 풀이 통째로 여성이 된다.
                //
                // 저장고도 가구 공동이고 개간 우회로도 같은 이유로 가구 기준으로 막았다.
                // ownsFarm() 이 이미 "자기 or 배우자"를 보는 캐시라 그대로 쓴다.
                if (m.ownsFarm()) {
                    rejLand++;
                    continue; // 가구가 밭을 가졌다 — 가난해서 창을 드는 자리가 아니다
                }
                // <b>군인은 가구를 먹여 살리는 사람이 나가는 일</b>이다 — 봉급이 그 가구의 주
                // 소득을 대체한다. isProviderRole 은 혼인 링크상 가장이라, 부부에서는 남편이
                // 걸리고 사별·미혼 1인 가구에서는 그 사람 본인이 걸린다(여성도 가능).
                // 성별 규칙을 한 줄도 쓰지 않고, 벌이 3배라는 기존 비대칭이 결과를 만든다.
                if (!m.isProviderRole()) {
                    rejNotHead++;
                    continue;
                }
                if (!Long.valueOf(bk.ownerId).equals(patrons.get(mid))
                        && !owner.marriedTo(patrons.getOrDefault(mid, 0L))) {
                    rejPatron++;
                    continue; // 이 주인(또는 그 배우자)을 따르는 자만
                }
                // <b>여기서는 파견 반경까지 담는다.</b> 통근 반경(96) 밖의 후보도 명단에는
                // 남기고, 실제로 앉힐 수 있는 거리는 패스마다 다르게 건다(seatSoldiers 의
                // maxRangeSq): 평시 배속은 통근 반경, 교전 막사 증원만 파견 반경까지.
                if (m.getHomePos().distSqr(bk.pos)
                        > Facilities.DISPATCH_RANGE * Facilities.DISPATCH_RANGE) {
                    rejFar++;
                    continue;
                }
                pick.add(m);
            }
            // <b>적합도 순</b> — 종전에는 거리순이라 능력을 아예 안 봤다. 완력과 경계를 각각
            // 중립 대비 배수로 재서 더한다(둘 다 중립 1.0, 합 2.0). 두 항을 두는 것이 요점이다:
            // 단순무식·야성은 앞항으로, 산만·용감은 뒷항으로 뽑힌다 — 창을 드는 길이 하나가
            // 아니라 둘이라야 "경계에 능한 자"가 자리를 얻는다.
            //
            // 거리는 동률 갈림으로 내린다. 이미 통근 한계(COMMUTE_RANGE)로 잘려 있어 남은
            // 후보는 전부 출근 가능하고, 거기서 더 가까운 것보다 더 쓸모 있는 것이 먼저다.
            pick.sort(java.util.Comparator.comparingDouble(
                    (MimicEntity m) -> -soldierFitness(m.getIndividual()))
                    .thenComparingDouble(m -> m.getHomePos().distSqr(bk.pos))
                    .thenComparingLong(m -> m.getIndividual().id()));
            plans.add(new Garrison(bk, owner, tpl.get(), cap, guarded.size(), taxIn, pick,
                    rejLand, rejNotHead, rejPatron, rejFar));
        }
        seatAll(level, ledger, larders, adults, reg, plans, day);
        // 어제는 병사였으나 오늘 자리를 못 받은 자 — 무장을 벗긴다(이탈·정원 축소·주인 사망).
        for (MimicEntity m : adults) {
            if (wasPosted.contains(m.getId()) && !POST_OF.containsKey(m.getId())) {
                m.setSoldierGear(false);
            }
        }
    }

    /**
     * 하루치 배속 계획 — 막사 하나가 <b>앉히기 전까지</b> 확정해 둔 것. 자리 배정만 뒤로 미룬다.
     */
    private static final class Garrison {
        final FacilityStore.Entry bk;
        final MimicEntity owner;
        final FacilityTemplate tpl;
        final int cap;
        final int guarded;
        final double taxIn;
        final java.util.List<MimicEntity> pick;
        final int rejLand;
        final int rejNotHead;
        final int rejPatron;
        final int rejFar;
        boolean contested;
        int seated;
        int seatedM;
        int dispatched; // 통근 반경 밖에서 불려온 수 — 증원이 실제로 걸렸는지의 눈금

        Garrison(FacilityStore.Entry bk, MimicEntity owner, FacilityTemplate tpl, int cap,
                 int guarded, double taxIn, java.util.List<MimicEntity> pick,
                 int rejLand, int rejNotHead, int rejPatron, int rejFar) {
            this.bk = bk;
            this.owner = owner;
            this.tpl = tpl;
            this.cap = cap;
            this.guarded = guarded;
            this.taxIn = taxIn;
            this.pick = pick;
            this.rejLand = rejLand;
            this.rejNotHead = rejNotHead;
            this.rejPatron = rejPatron;
            this.rejFar = rejFar;
        }
    }

    /**
     * <b>배속 3패스</b> — 국력을 전선에 도달시키되 후방을 비우지 않는다(WAR-PLAN §1.3).
     *
     * <p>종전에는 막사를 하나씩 순회하며 그 자리에서 정원을 채웠다. 그러면 두 가지가 어긋난다.
     * 하나는 <b>순회 순서가 승패를 가른다</b> — 먼저 처리된 막사가 후보를 다 가져간다(점령
     * 판정에서 이미 같은 함정을 겪었다). 다른 하나는 각 막사가 <b>제 통근 반경 안 사람만</b>
     * 쓰므로 세력이 아무리 커도 국력이 전선에 오지 못하고, 국지에서 운으로 진다.
     *
     * <p>배속은 매일 새로 짜이므로 이것은 <b>이동 문제가 아니라 배분 문제</b>다. 병사를 옮기는
     * 대신 처음부터 전선에 더 앉힌다 — 새 상태가 늘지 않는다.
     *
     * <ol>
     *   <li>모든 막사를 {@link Facilities#GARRISON_MIN} 까지 — 어디도 비지 않게 <b>먼저</b>.
     *       "최소 수비를 남기고 초과분만 파견"이 순서만으로 성립한다.</li>
     *   <li>교전 막사만 제 정원까지 — 후보 거리를 {@link Facilities#DISPATCH_RANGE} 로 넓힌다.
     *       이 패스에서만 통근 반경 밖 사람이 불려온다.</li>
     *   <li>나머지 막사를 제 정원까지, 남은 후보로.</li>
     * </ol>
     *
     * <p>봉급은 앉히는 자리에서 나가므로 파견 병사의 봉급은 <b>교전 막사 주인</b>이 낸다.
     * 감당 못 하면 이탈로 저절로 줄어든다 — 억제가 자동이다(설계서 §1.3).
     */
    private static void seatAll(ServerLevel level, AllegianceStore ledger, LarderStore larders,
                                java.util.List<MimicEntity> adults, FacilityStore reg,
                                java.util.List<Garrison> plans, long day) {
        // 교전 판정 — <b>같은 통근 반경 안에 다른 세력 뿌리의 막사가 있는가</b>.
        //
        // 병사 위치로 재고 싶지만 이 시점의 POST_OF 는 비어 있다(오늘 배속을 지금 짜는 중).
        // 막사 좌표로 재면 정적이고 결정론적이며, "세력권이 겹치면 싸운다"는 원래 틀과도 맞는다.
        double r2 = Facilities.COMMUTE_RANGE * Facilities.COMMUTE_RANGE;
        for (Garrison g : plans) {
            for (Garrison o : plans) {
                if (o != g && factionRootOf(o.bk.ownerId) != factionRootOf(g.bk.ownerId)
                        && o.bk.pos.distSqr(g.bk.pos) <= r2) {
                    g.contested = true;
                    break;
                }
            }
        }
        double commute2 = Facilities.COMMUTE_RANGE * Facilities.COMMUTE_RANGE;
        double dispatch2 = Facilities.DISPATCH_RANGE * Facilities.DISPATCH_RANGE;
        // 봉급을 하루 두 번 받지 않게 — 패스를 넘어 공유한다(seatSoldiers 의 handled 주석).
        java.util.Set<Integer> handled = new java.util.HashSet<>();
        for (Garrison g : plans) { // ① 최소 수비
            seatSoldiers(level, ledger, larders, adults, reg, g,
                    Math.min(g.cap, Facilities.GARRISON_MIN), commute2, handled, day);
        }
        for (Garrison g : plans) { // ② 교전 막사 증원
            if (g.contested) {
                // <b>전시 정원은 좌석 수까지 연다.</b> 평시 정원(지킬가구/4)을 그대로 두면
                // 먼 사람이 가까운 사람을 대신할 뿐 수가 늘지 않아, "국력이 전선에 온다"가
                // 성립하지 않는다. 가구 비율은 <b>평시의 자금 대용</b>이고, 전쟁에서는 지배자가
                // 가진 것을 던진다 — 그 억제는 봉급이 한다(설계서 §1.3: "봉급도 그만큼 나가므로,
                // 감당 못 하면 이탈로 저절로 줄어든다"). 건물의 좌석 수가 물리적 상한이다.
                seatSoldiers(level, ledger, larders, adults, reg, g, g.tpl.seats().size(),
                        dispatch2, handled, day);
            }
        }
        for (Garrison g : plans) { // ③ 나머지 정원
            seatSoldiers(level, ledger, larders, adults, reg, g, g.cap, commute2, handled, day);
        }
        for (Garrison g : plans) {
            com.evosim.mod.log.SimEvents.note(level, "주둔", String.format(
                    "막사 @%d,%d%s — 지킬가구 %d → 정원 %d(전시 %d) · 배속 %d명(남%d 여%d%s) · 세수 %.1f"
                            + " · 봉급 %.1f · 주인 저장고 %.1f | 후보 %d명 · 탈락: 유전가구 %d ·"
                            + " 비부양자 %d · 타주인 %d · 원거리 %d",
                    g.bk.pos.getX(), g.bk.pos.getZ(), g.contested ? " §c[교전]§r" : "",
                    g.guarded, g.cap, g.contested ? g.tpl.seats().size() : g.cap,
                    g.seated, g.seatedM, g.seated - g.seatedM,
                    g.dispatched > 0 ? " · §e증원 " + g.dispatched + "명§r" : "", g.taxIn,
                    GUARD_SUM[1], larders.get(g.owner.getHomePos()),
                    g.pick.size(), g.rejLand, g.rejNotHead, g.rejPatron, g.rejFar));
        }
    }

    /** 한 막사에 {@code limit} 명까지, {@code maxRangeSq} 안의 후보만 앉힌다(누적 호출). */
    private static void seatSoldiers(ServerLevel level, AllegianceStore ledger,
                                     LarderStore larders, java.util.List<MimicEntity> adults,
                                     FacilityStore reg, Garrison g, int limit, double maxRangeSq,
                                     java.util.Set<Integer> handled, long day) {
        FacilityStore.Entry bk = g.bk;
        MimicEntity owner = g.owner;
        var tpl = java.util.Optional.of(g.tpl);
        for (MimicEntity s : g.pick) {
            if (g.seated >= limit) {
                break;
            }
            if (POST_OF.containsKey(s.getId())) {
                continue; // 앞 패스에서 이미 어딘가에 앉았다
            }
            // <b>하루에 한 번만 손댄다.</b> 봉급은 앉기 전에 지불되는데, 못 받아 이탈한 자는
            // 급여를 받고 배속만 건너뛴다(아래 continue). 패스를 셋으로 늘리면 그 사람이
            // 다음 패스에서 다시 뽑혀 <b>같은 날 봉급을 두세 번</b> 받고 이탈 카운터도 그만큼
            // 올라간다 — 단일 패스에서는 없던 결함이다. 배속 여부와 무관하게 여기서 막는다.
            if (!handled.add(s.getId())) {
                continue;
            }
            if (s.getHomePos().distSqr(bk.pos) > maxRangeSq) {
                handled.remove(s.getId()); // 거리로 걸린 것은 손댄 것이 아니다 — 다음 패스에 남긴다
                continue;
            }
                long sid = s.getIndividual().id();
                // ④ 봉급 — 가난할수록 많이. 기준선은 그 가구 성인 명목소모 × 3.5.
                double adultNeed = 0.0;
                for (MimicEntity a : adults) {
                    if (s.getHomePos().equals(a.getHomePos())) {
                        adultNeed += com.evosim.core.FoodEconomy.consumptionPerDay(a.getStage(),
                                com.evosim.core.Activity.MOVE, a.getIndividual(), false);
                    }
                }
                double capLine = Math.max(1.0E-6, adultNeed * Facilities.SOLDIER_WAGE_CAP_DAYS);
                double r = Math.min(1.0, Math.max(0.0, larders.get(s.getHomePos())) / capLine);
                double wage = Facilities.SOLDIER_WAGE_MIN
                        + (Facilities.SOLDIER_WAGE_MAX - Facilities.SOLDIER_WAGE_MIN) * (1.0 - r);
                // 위급한 병사는 <b>고용주가 즉시 책임진다</b> — 전업이라 스스로 벌 수단이 없다.
                if (s.isCritical()) {
                    wage += com.evosim.core.FoodEconomy.CRITICAL * 4.0;
                    GUARD_SUM[4]++;
                }
                double have = larders.get(owner.getHomePos());
                double paid = Math.min(wage, have);
                if (paid > 0.0) {
                    larders.set(owner.getHomePos(), have - paid);
                    larders.set(s.getHomePos(), larders.get(s.getHomePos()) + paid);
                    reg.spend(bk, paid);
                    GUARD_SUM[1] += paid;
                }
                if (paid < wage - 1.0E-9) {
                    int miss = UNPAID_DAYS.merge(sid, 1, Integer::sum);
                    if (miss >= desertDays(s)) {
                        UNPAID_DAYS.remove(sid);
                        GUARD_SUM[3]++;
                        com.evosim.mod.log.SimEvents.event(s, "이탈", String.format(
                                "봉급 %.1f 중 %.1f 만 받음이 %d일 연속 — 막사 @%d,%d 를 떠난다"
                                        + "(주인 저장고 %.1f)",
                                wage, paid, miss, bk.pos.getX(), bk.pos.getZ(), have));
                        continue; // 배속하지 않는다
                    }
                } else {
                    UNPAID_DAYS.remove(sid);
                }
                // ⑤ 신세 — 봉급은 지주→군인 방향으로 예속을 쌓는다(소작 임금과 같은 구조).
                ledger.record(sid, bk.ownerId,
                        AllegianceStore.W_TENANCY * AllegianceStore.rapport(s.getIndividual()),
                        0.0, day);
                POST_OF.put(s.getId(), bk.pos);
                GUARD_SEAT.put(s.getId(),
                        bk.pos.offset(tpl.get().seats().get(g.seated % tpl.get().seats().size())));
                s.setSoldierGear(true); // 무장은 배속의 표시 — 값도 내구도도 없다
                if (s.getIndividual().sex() == com.evosim.core.Sex.MALE) {
                    g.seatedM++;
                }
                // 통근 반경 밖에서 불려왔으면 증원으로 센다 — "국력이 전선에 왔는가"의 눈금.
                if (s.getHomePos().distSqr(bk.pos)
                        > Facilities.COMMUTE_RANGE * Facilities.COMMUTE_RANGE) {
                    g.dispatched++;
                    com.evosim.mod.log.SimEvents.event(s, "증원", String.format(
                            "교전 막사 @%d,%d 로 파견 — 집에서 %.0f블록(통근 %d 밖)"
                                    + " · 같은 주인 #%d",
                            bk.pos.getX(), bk.pos.getZ(),
                            Math.sqrt(s.getHomePos().distSqr(bk.pos)),
                            (int) Facilities.COMMUTE_RANGE, bk.ownerId));
                }
                g.seated++;
                GUARD_SUM[0]++;
        }
    }

    private static void runSchools(ServerLevel level, AllegianceStore ledger, LarderStore larders,
                                   java.util.List<MimicEntity> adults,
                                   java.util.List<MimicEntity> everyone,
                                   java.util.Map<Long, Long> patrons, long day) {
        ENROLLED.clear();
        SCHOOL_OF.clear();
        SEAT_OF.clear();
        java.util.Arrays.fill(SCHOOL_SUM, 0.0);
        java.util.Arrays.fill(SCHOOL_MISS, 0);
        FacilityStore reg = FacilityStore.get(level);
        java.util.Map<Long, MimicEntity> byId = new java.util.HashMap<>();
        for (MimicEntity m : everyone) {
            byId.putIfAbsent(m.getIndividual().id(), m);
        }
        java.util.List<FacilityStore.Entry> schools = new java.util.ArrayList<>();
        for (FacilityStore.Entry e : reg.all()) {
            if (e.kind == FacilityTemplate.Kind.SCHOOL) {
                schools.add(e);
            }
        }
        // 소년 전수 — 대상 수를 먼저 세야 등교율의 분모가 정직해진다.
        java.util.List<MimicEntity> boys = new java.util.ArrayList<>();
        for (MimicEntity m : everyone) {
            if (m.getStage() == com.evosim.core.LifeStage.BOY && m.getHomePos() != null) {
                boys.add(m);
            }
        }
        SCHOOL_SUM[1] = boys.size();
        if (schools.isEmpty() || boys.isEmpty()) {
            return;
        }
        boys.sort(java.util.Comparator.comparingLong(m -> m.getIndividual().id()));

        for (FacilityStore.Entry sc : schools) {
            MimicEntity owner = byId.get(sc.ownerId);
            if (owner == null || owner.getHomePos() == null) {
                continue; // 주인이 죽고 상속인도 없다 — 다음 승계까지 문을 닫는다
            }
            var tpl = FacilityTemplate.of(level, sc.kind, sc.rotation, sc.mirrored);
            int seats = tpl.map(t -> t.seats().size()).orElse(0);
            if (seats <= 0) {
                continue;
            }
            // ── 교사 — 이 주인을 따르는 <b>무토지 성년</b>(계획서 1.7: 종사자는 일반 계층).
            //    급여를 받고 세금도 낸다. 죽었거나 자격을 잃으면 다시 뽑는다.
            MimicEntity teacher = byId.get(sc.staffId);
            if (teacher == null || !Long.valueOf(sc.ownerId).equals(patrons.get(sc.staffId))
                    || FarmStore.get(level).ownedTiles(sc.staffId) > 0) {
                teacher = null;
                for (MimicEntity m : adults) {
                    long id = m.getIndividual().id();
                    if (id != sc.ownerId && Long.valueOf(sc.ownerId).equals(patrons.get(id))
                            && FarmStore.get(level).ownedTiles(id) == 0
                            && m.getHomePos() != null && !m.getHomePos().equals(owner.getHomePos())) {
                        teacher = m;
                        break;
                    }
                }
                sc.staffId = teacher == null ? 0L : teacher.getIndividual().id();
                reg.setDirty();
            }
            // ── 등록 — 이 주인을 따르는 가구의 소년, 통학 한계 안, 자리 수만큼. 가까운 순.
            java.util.List<MimicEntity> pick = new java.util.ArrayList<>();
            for (MimicEntity b : boys) {
                if (SCHOOL_OF.containsKey(b.getId())) {
                    continue; // 한 아이는 한 학교만
                }
                boolean headFollows = ownerSide(owner, sc.ownerId,
                        patrons.get(householdPatronKey(b, adults)));
                boolean anyFollows = headFollows;
                if (!anyFollows) {
                    for (MimicEntity a : adults) {
                        if (a.getIndividual() != null && a.getHomePos() != null
                                && a.getHomePos().equals(b.getHomePos())
                                && ownerSide(owner, sc.ownerId,
                                        patrons.get(a.getIndividual().id()))) {
                            anyFollows = true;
                            break;
                        }
                    }
                }
                // <b>자격은 가구가 갖는다</b>(계획서 1.5: "추종 가구의 소년"). 처음에는 가구
                // 대표(밭 최다 보유자)가 따르는지만 봤는데, 어머니가 따르고 아버지가 안 따르는
                // 집이 통째로 빠졌다 — 실측(D20): 대표만안따름 5건. 시설 착공 자격에서 이미
                // 같은 함정을 밟았고(대표는 UUID 최소로 뽑혀 사실상 무작위), 저장고가 가구
                // 공동이니 자격도 가구 것으로 봐야 앞뒤가 맞는다.
                //
                // 다만 <b>신세를 지는 자</b>는 여전히 대표다 — 지주의 아들이 다니면 지주가
                // 빚져야 지주 간 사슬이 생긴다.
                if (!anyFollows) {
                    SCHOOL_MISS[1]++;
                    continue;
                }
                if (!headFollows) {
                    SCHOOL_MISS[0]++; // 계측만 — 이제 자격은 준다
                }
                if (b.getHomePos().distSqr(sc.pos)
                        > Facilities.COMMUTE_RANGE * Facilities.COMMUTE_RANGE) {
                    SCHOOL_MISS[2]++;
                    continue;
                }
                pick.add(b);
            }
            pick.sort(java.util.Comparator.comparingDouble(
                    (MimicEntity b) -> b.getHomePos().distSqr(sc.pos))
                    .thenComparingLong(b -> b.getIndividual().id()));
            java.util.List<Integer> roll = new java.util.ArrayList<>();
            for (MimicEntity b : pick) {
                if (roll.size() >= seats) {
                    SCHOOL_MISS[3]++;
                    continue;
                }
                net.minecraft.core.BlockPos home = b.getHomePos();
                double larder = larders.get(home);
                double pay = Math.min(Facilities.TUITION_PER_DAY,
                        com.evosim.core.Tribute.payable(larder, familyDailyNeed(level, b, adults)));
                double unpaid = Facilities.TUITION_PER_DAY - pay;
                if (pay > 0.0) {
                    larders.set(home, larder - pay);
                    larders.set(owner.getHomePos(), larders.get(owner.getHomePos()) + pay);
                    reg.earn(sc, pay);
                    SCHOOL_SUM[2] += pay;
                }
                // 미납은 <b>가구의 어른</b>이 진다 — 아이에게 빚을 지우면 그 아이가 자라
                // 제 땅을 가져도 갚을 것이 남의 것이 된다. 세대 간 예속은 승계가 담당한다.
                long debtor = householdPatronKey(b, adults);
                if (unpaid > 0.0) {
                    ledger.record(debtor, sc.ownerId, 0.0, unpaid, day);
                    SCHOOL_SUM[3] += unpaid;
                }
                // 신세 — 교육의 값과 수업료의 차액. 이것이 사슬을 붙잡는 못이다.
                ledger.record(debtor, sc.ownerId, Facilities.W_SCHOOLING, 0.0, day);
                SCHOOL_OF.put(b.getId(), sc.pos);
                SEAT_OF.put(b.getId(), sc.pos.offset(tpl.get().seats().get(roll.size())));
                roll.add(b.getId()); // 자리 색인은 <b>넣기 전</b>의 크기다(0..seats-1)
                SCHOOL_SUM[0]++;
            }
            ENROLLED.put(sc.pos.asLong(), roll);
            // ── 급여 — 학생이 하나라도 있어야 수업이 있고, 수업이 있어야 급여다.
            if (teacher != null && !roll.isEmpty() && teacher.getHomePos() != null) {
                double have = larders.get(owner.getHomePos());
                double wage = Math.min(Facilities.TEACHER_WAGE_PER_DAY, have);
                if (wage > 0.0) {
                    larders.set(owner.getHomePos(), have - wage);
                    larders.set(teacher.getHomePos(), larders.get(teacher.getHomePos()) + wage);
                    reg.spend(sc, wage);
                    SCHOOL_SUM[4] += wage;
                }
            }
        }
    }

    /** [방문, 헌금 수입, 미납, 성직자 급여] — 한 줄 보고용. */
    private static final double[] CHURCH_SUM = new double[4];

    public static double[] churchSums() {
        return CHURCH_SUM.clone();
    }

    /**
     * <b>교회 운영</b>(P6) — 헌금 · 신세 · 성직자 급여.
     *
     * <p>어제(또는 그 뒤) 다녀온 방문을 여기서 한 번에 정산한다. 방문 goal 은 "왔다" 는 사실만
     * 적는다 — 저장고를 goal 과 정산 두 곳에서 만지면 같은 곳간을 동시에 고치게 된다.
     *
     * <p><b>이것이 상납 사슬을 붙잡는 못이다.</b> 원장에 간선을 만드는 셋(소작·구휼·긴급고용)은
     * 전부 가난한 쪽이 받는 것이라 지주가 지주에게 신세 질 길이 없었고, 그래서 사슬이 이번
     * 세션 내내 깊이 1 에 머물렀다. 교회는 추종에 매이지 않아 <b>지주도 방문한다</b>.
     */
    private static void runChurches(ServerLevel level, AllegianceStore ledger,
                                    LarderStore larders,
                                    java.util.List<MimicEntity> everyone,
                                    java.util.Map<Long, Long> patrons, long day) {
        java.util.Arrays.fill(CHURCH_SUM, 0.0);
        FacilityStore reg = FacilityStore.get(level);
        java.util.Map<Long, FacilityStore.Entry> byPos = new java.util.HashMap<>();
        for (FacilityStore.Entry e : reg.all()) {
            if (e.kind.group == FacilityTemplate.Group.CHURCH) {
                byPos.put(e.pos.asLong(), e);
            }
        }
        if (byPos.isEmpty()) {
            return;
        }
        java.util.Map<Long, MimicEntity> byId = new java.util.HashMap<>();
        for (MimicEntity m : everyone) {
            byId.putIfAbsent(m.getIndividual().id(), m);
        }
        java.util.Set<Long> paidClergy = new java.util.HashSet<>();
        for (MimicEntity m : everyone) {
            BlockPos cp = m.pendingChurch();
            if (cp == null || m.getHomePos() == null) {
                continue;
            }
            FacilityStore.Entry ch = byPos.get(cp.asLong());
            m.clearPendingChurch(); // 같은 방문을 두 번 물리지 않는다
            if (ch == null || ch.ownerId == m.getIndividual().id()) {
                continue; // 헐린 교회이거나 제 교회 — 자기에게 신세 질 수는 없다
            }
            MimicEntity owner = byId.get(ch.ownerId);
            if (owner == null || owner.getHomePos() == null) {
                continue;
            }
            CHURCH_SUM[0]++;
            double have = larders.get(m.getHomePos());
            double pay = Math.min(Facilities.TITHE_PER_VISIT, Math.max(0.0, have));
            double unpaid = Facilities.TITHE_PER_VISIT - pay;
            if (pay > 0.0) {
                larders.set(m.getHomePos(), have - pay);
                larders.set(owner.getHomePos(), larders.get(owner.getHomePos()) + pay);
                reg.earn(ch, pay);
                CHURCH_SUM[1] += pay;
            }
            if (unpaid > 0.0) {
                CHURCH_SUM[2] += unpaid;
            }
            // 헌금과 신세를 <b>둘 다</b> 매기는 것은 이중 부과가 아니다 — 소액 헌금이 위안의
            // 값을 다 치르지 못하고 그 차액이 은혜로 남는 것이 후원의 실체다(학교와 같은 구조).
            ledger.recordChurch(m.getIndividual().id(), ch.ownerId, Facilities.W_CHURCH, unpaid, day);
            // ── 성직자 급여 — 방문이 있어야 예배가 있고, 예배가 있어야 급여다(학교와 같다).
            if (paidClergy.add(ch.pos.asLong())) {
                MimicEntity clergy = byId.get(ch.staffId);
                if (clergy == null
                        || !Long.valueOf(ch.ownerId).equals(patrons.get(ch.staffId))
                        || FarmStore.get(level).ownedTiles(ch.staffId) > 0) {
                    clergy = null;
                    for (MimicEntity a : everyone) {
                        long aid = a.getIndividual().id();
                        if (aid != ch.ownerId && a.getStage() == com.evosim.core.LifeStage.ADULT
                                && Long.valueOf(ch.ownerId).equals(patrons.get(aid))
                                && FarmStore.get(level).ownedTiles(aid) == 0
                                && a.getHomePos() != null
                                && !a.getHomePos().equals(owner.getHomePos())) {
                            clergy = a;
                            break;
                        }
                    }
                    ch.staffId = clergy == null ? 0L : clergy.getIndividual().id();
                    reg.setDirty();
                }
                if (clergy != null && clergy.getHomePos() != null) {
                    double purse = larders.get(owner.getHomePos());
                    double wage = Math.min(Facilities.CLERGY_WAGE_PER_DAY, purse);
                    if (wage > 0.0) {
                        larders.set(owner.getHomePos(), purse - wage);
                        larders.set(clergy.getHomePos(),
                                larders.get(clergy.getHomePos()) + wage);
                        reg.spend(ch, wage);
                        CHURCH_SUM[3] += wage;
                    }
                }
            }
        }
    }

    /**
     * 이 아이의 <b>가구를 대표해 신세를 지는 자</b> — 같은 집 성년 중 소유 밭이 가장 많은 자.
     *
     * <p>지주의 아들이 다니면 <b>지주</b>가 신세를 져야 지주 간 사슬이 생긴다. 아이 본인에게
     * 달면 아이는 이미 태생적 추종자라 아무것도 바뀌지 않는다. 성년이 없으면 아이 자신이다.
     */
    /**
     * 이 추종 대상이 <b>시설 주인 쪽</b>인가 — 주인 본인이거나 그 배우자면 참.
     *
     * <p>육안 관측: "야망가 수컷이 벌어온 걸 마누라가 받아서 학교를 지으니, 다들 추종은 수컷인데
     * 지은 사람이 마누라여서 학교 사용을 안 함."
     *
     * <p>학생 자격은 이미 <b>가구 단위</b>로 넓혀 두었다 — 어머니가 따르고 아버지가 안 따르는
     * 집이 통째로 빠지던 실측 결함을 고치면서, 그 주석이 이유까지 적어 두었다("저장고가 가구
     * 공동이니 자격도 가구 것으로 봐야 앞뒤가 맞는다"). 그런데 그 논리를 <b>학생 쪽에만</b>
     * 적용했다. 주인 쪽은 여전히 한 사람이라, 건축비를 낸 저장고가 부부 공동인데도 배우자
     * 명의로 등기되면 온 마을이 남남이 된다.
     *
     * <p>같은 이유이므로 같은 처방을 한다. {@code MimicFarmGoal} 이 배우자 소유 밭을 양방향
     * ({@code marriedTo})으로 보는 것과 같은 장치다 — 그쪽도 단방향일 때 "첩 소유 밭이 남의
     * 밭으로 잡혀 자기 가구 수확이 새어 나가는" 같은 병을 앓았다.
     */
    private static boolean ownerSide(MimicEntity owner, long ownerId, Long patron) {
        if (patron == null) {
            return false;
        }
        return patron == ownerId || (owner != null && owner.marriedTo(patron));
    }

    private static long householdPatronKey(MimicEntity boy, java.util.List<MimicEntity> adults) {
        long best = boy.getIndividual().id();
        int bestTiles = -1;
        for (MimicEntity a : adults) {
            if (a.getHomePos() != null && a.getHomePos().equals(boy.getHomePos())
                    && a.getIndividual() != null) {
                int t = FarmStore.get((ServerLevel) a.level()).ownedTiles(a.getIndividual().id());
                if (t > bestTiles) {
                    bestTiles = t;
                    best = a.getIndividual().id();
                }
            }
        }
        return best;
    }

    /** 이 개체 위로 주인이 몇 단이나 있는가 — 상납 순서(깊은 쪽 먼저)를 정한다. */
    private static int chainDepth(java.util.Map<Long, Long> patrons, long id) {
        int d = 0;
        long cur = id;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        while (patrons.containsKey(cur) && seen.add(cur) && d < 64) {
            cur = patrons.get(cur);
            d++;
        }
        return d;
    }

    /** 막힌 칸을 만났을 때 이상 수열을 더 훑는 여유분 — 이만큼이면 집 하나쯤은 우회한다. */
    private static final int SCAN_SLACK = 24;

    /** 지금 방향을 뺀 나머지 셋 중 가장 트인 쪽 — 없으면 현재 방향 그대로. */
    private static byte pickDirExcept(ServerLevel level, FarmStore store, BlockPos anchor,
                                      java.util.List<MimicEntity> adults, byte cur, long selfId) {
        byte best = cur;
        int bestFree = -1;
        int off = spin(anchor);
        for (int i = 0; i < 8; i++) {
            byte d = (byte) ((i + off) % 8);
            // <b>축은 바꾸지 않는다</b>(비트2 = 전치). 부호만 튼다.
            //
            // 축까지 뒤집으면 전환 <b>이전</b> 타일은 옛 격자에, 이후 타일은 새 격자에 놓인다.
            // 두 격자가 섞인 몸통은 어느 축으로도 직사각형이 아니고, 계측기부터 무너진다 —
            // 실측(#3, D23): 채움 133%(100% 초과) · 고랑오염 34/72타일 · 덩어리9. 물리적으로
            // 불가능한 값이 나온 것은 밭이 이상해서가 아니라 재구성이 성립하지 않아서다.
            //
            // P3c 의 인접 조건은 <b>연결성</b>을 보장하지만 <b>격자 일관성</b>은 보장하지 않는다.
            // 축은 착공 때 {@link #spin} 이 한 번 정하고, 그 뒤로는 막혀도 부호만 튼다.
            if (d == cur || (d & 4) != (cur & 4)) {
                continue;
            }
            int free = freeIn(level, store, anchor, adults, d, selfId);
            if (free > bestFree) {
                bestFree = free;
                best = d;
            }
        }
        return bestFree <= 0 ? cur : best;
    }

    /**
     * <b>동점을 가르는 시작 방향</b> — 앵커 좌표에서 뽑는다.
     *
     * <p>평지에서는 여덟 방향이 전부 같은 점수라, 늘 0번부터 훑으면 첫 번째가 이겨 마을의
     * 밭이 하나같이 같은 방향으로 눕는다(실측: 9덩어리 전부 x축). 훑는 순서를 구획마다
     * 돌려 놓으면 개방도가 갈릴 땐 여전히 트인 쪽이 이기고, 엇비슷할 때만 갈린다.
     *
     * <p>난수 대신 좌표 해시를 쓰는 이유는 <b>재현</b>이다 — 같은 월드·같은 앵커면 늘 같은
     * 값이라 A/B 대조 런이 성립한다. {@code level.random} 을 쓰면 런마다 달라져 회귀 판정이
     * 흐려진다.
     */
    private static int spin(BlockPos anchor) {
        return Math.floorMod(anchor.getX() * 31 + anchor.getZ() * 17, 8);
    }

    /**
     * <b>성장 방향 고르기</b> — 앵커 둘레 네 사분면 중 7×7 격자가 가장 많이 트인 쪽.
     *
     * <p>구획당 한 번만 부른다. 칸마다 방향을 뒤집던 종전 방식이 흩어짐의 원인이었으므로,
     * 그 유연성을 <b>구획 단위</b>로 격하시켜 보존한다 — 막힌 쪽을 피해 자라되 몸통은 하나다.
     */
    private static byte pickDir(ServerLevel level, FarmStore store, BlockPos anchor,
                                java.util.List<MimicEntity> adults, long selfId) {
        byte best = 0;
        int bestFree = -1;
        int off = spin(anchor);
        for (int i = 0; i < 8; i++) {
            byte d = (byte) ((i + off) % 8);
            int free = freeIn(level, store, anchor, adults, d, selfId);
            if (free > bestFree) {
                bestFree = free;
                best = d;
            }
        }
        return best;
    }

    /** 이 방향 7×7 격자에서 실제로 개간 가능한 칸 수. */
    private static int freeIn(ServerLevel level, FarmStore store, BlockPos anchor,
                              java.util.List<MimicEntity> adults, byte d, long selfId) {
        int free = 0;
        for (int c = 0; c < 7; c++) {
            for (int r = 0; r < 7; r++) {
                BlockPos gp = level.getHeightmapPos(
                        net.minecraft.world.level.levelgen.Heightmap.Types
                                .MOTION_BLOCKING_NO_LEAVES,
                        gridOffset(anchor, d, c, r));
                // 방향 고르기도 <b>실제로 심을 수 있는 칸</b>만 세야 맞다 — 배치와 같은 관문.
                if (gateReason(level, store, selfId, gp, adults) == null) {
                    free++;
                }
            }
        }
        return free;
    }

    /**
     * <b>이상 칸 하나</b>를 그 구획의 성장 방향으로 놓아 본다 — 거울 없이. 못 놓으면 null.
     *
     * <p>방향은 {@link FarmStore.Plot#dir} 로 구획마다 <b>한 번</b> 정해져 있다. 칸마다 뒤집던
     * 종전 방식({@code FarmLayout.mirrors})은 막힌 칸을 앵커 반대편에 놓아 몸통에서 떨어져
     * 나온 타일을 만들었다.
     */
    /**
     * <b>격자 좌표 → 월드 위치.</b> 열 c 는 재배줄을 따라, 줄 r 은 고랑을 하나 끼고 나아간다.
     *
     * <p>{@link FarmStore.Plot#dir} 의 비트 0·1 은 x·z 부호, <b>비트 2 는 축 전치</b>다.
     * 전치가 없던 동안에는 부호만 뒤집혀 재배줄이 <b>언제나 동–서</b>였고, 그래서 마을의
     * 밭이 하나도 빠짐없이 같은 방향으로 누웠다(실측 seed 7 D16: 밭 9덩어리 전부 x축, z축 0).
     *
     * <p>이 함수 하나로 배치({@link #idealSpot})와 방향 고르기({@link #freeIn})가 같은 모양을
     * 보게 묶는다 — 둘이 어긋나면 실제로 못 심을 자리를 트였다고 세게 된다.
     */
    /**
     * <b>뽑고 심을 수 있는 잡풀인가</b> — 꽃과 묘목.
     *
     * <p>잔디·고사리는 대체 가능 블록이라 그냥 덮이지만 꽃은 아니다. 그래서 민들레 한 송이가
     * 밭 칸을 <b>영구히</b> 막았다 — 확장은 매일 수열을 처음부터 다시 훑는데 그 칸은 계속
     * 거부되므로 구멍이 영영 남는다. 실측(C런 D16, 구획 5 @58,−8): `구멍2 줄끊김2` 의 두 칸이
     * (68,0,−13)·(66,0,−11) 이었고 둘 다 <b>minecraft:dandelion</b> 이었다. 아래는 잔디,
     * 위는 공기로 겉보기엔 멀쩡해 공중격자에서도 안 보였다.
     *
     * <p>묘목도 함께 뽑는다 — 밭 한복판에서 나무로 자라면 그 줄이 통째로 막힌다.
     */
    private static boolean weed(net.minecraft.world.level.block.state.BlockState st) {
        return st.is(net.minecraft.tags.BlockTags.FLOWERS)
                || st.is(net.minecraft.tags.BlockTags.SAPLINGS);
    }

    private static BlockPos gridOffset(BlockPos anchor, byte dir, int c, int r) {
        int sx = (dir & 1) != 0 ? -1 : 1;
        int sz = (dir & 2) != 0 ? -1 : 1;
        return (dir & 4) != 0
                ? anchor.offset(r * 2 * sx, 0, c * sz)   // 재배줄 남–북 · 고랑 동–서
                : anchor.offset(c * sx, 0, r * 2 * sz);  // 재배줄 동–서 · 고랑 남–북
    }

    private static BlockPos idealSpot(ServerLevel level, FarmStore store, FarmStore.Plot plot,
                                      int c, int r, java.util.List<MimicEntity> adults,
                                      java.util.Set<Long> mine) {
        BlockPos gp = level.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                gridOffset(plot.anchor, plot.dir, c, r));
        if (mine.contains(gp.asLong()) || gateReason(level, store, plot.id, gp, adults) != null) {
            return null; // 관문은 gateReason 하나에 모아 둔다 — 배치와 진단이 같은 것을 본다
        }
        // <b>몸통과 맞닿을 것</b> — 연결을 구성적으로 보장한다.
        //
        // 종전에는 이상 수열이 연결적이라는 데 기댔다((w,r)은 (w−1,r)에, (c,k)는 (c,k−1)에
        // 붙는다). 그런데 <b>붙을 앞 칸이 막히면</b> 그 논거가 무너진다 — 한 열이 통째로 막히면
        // 다음 열은 허공에 놓인다. 빈집까지 회피 대상에 넣자 실제로 조각이 났다
        // (실측 P3b D14: 몸통갈린구획 1/12, 종전 0).
        //
        // 수열이 원래 그렇게 생겼으므로 정상적인 경우에는 아무것도 바뀌지 않는다. 앞 칸이
        // 막혔을 때만 걸린다. 첫 칸은 붙을 데가 없으므로 예외다.
        if (!mine.isEmpty()) {
            boolean touches = false;
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                BlockPos nb = level.getHeightmapPos(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        gridOffset(plot.anchor, plot.dir, c + d[0], r + d[1]));
                if (mine.contains(nb.asLong())) {
                    touches = true;
                    break;
                }
            }
            if (!touches) {
                return null;
            }
        }
        var at = level.getBlockState(gp);
        var below = level.getBlockState(gp.below());
        boolean natural = at.isAir() || at.canBeReplaced() || weed(at);
        boolean ground = below.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                || below.is(net.minecraft.world.level.block.Blocks.DIRT)
                || below.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT)
                || below.is(net.minecraft.world.level.block.Blocks.DIRT_PATH);
        return natural && ground ? gp : null;
    }

    /**
     * 이 좌표가 어느 거처의 몸통·입구·모닥불·정원 반경 안인가 — 밭 타일이 집을 덮는 것을 막는다.
     * 착공 부지({@link #findFarmSite})는 거처 12블록을 피하지만 그 뒤 확장은 제약이 없어, 영지가
     * 커지면 반경 12를 넘어 이웃 거처를 삼켰다. 스위트베리는 지나는 개체에 피해를 주고 이동을
     * 늦추므로 미관이 아니라 귀가·급식 경로의 문제다.
     *
     * <p>종전 판정은 <b>gp 주변 12블록의 미믹 엔티티를 훑어</b> 그들의 거처 칸과 비교했다. 그래서
     * 그 순간 집을 비운 가구(밭 출근·통근 소작·원거리 배회)는 <b>보이지 않아</b> 그 위에 타일이
     * 깔렸다 — 실측 스크린샷에서 모닥불 양옆·입구에 덤불이 계속 생기던 원인. 엔티티 위치에
     * 의존하지 않도록, {@link #findFarmSite}와 같은 <b>성년 명단</b>의 거처 좌표를 직접 본다.
     *
     * <p>거처 칸 목록 대신 반경으로 보는 것은 <b>방향 무관</b>하게 만들기 위해서다. 발자국은
     * dx ±3·dz −2..+3, 정원 폴백은 dx ±4·dz −4..+1이므로 앵커에서 최대 √32 ≈ 5.66이고,
     * 6.5면 어느 facing 이든 전부 덮는다. y 는 지형마다 달라 x·z 평면 거리로만 판정한다.
     */
    /**
     * <b>거처가 실제로 점유한 칸</b> — 발자국·정원·문앞 계단. 소급 정비는 이것으로 판정한다.
     *
     * <p>종전에는 앵커 반경 6.5 로 쟀는데, 발자국·정원이 앵커에서 닿는 최대 거리가 5.66 이라
     * <b>집 바깥 한 겹까지 덤불을 지웠다</b>. 실측(B런 d7): 집 앵커 (29,−35) 가 서자 그날 밤
     * 구획 2 의 타일 7칸이 "거처 위"로 소거됐는데, 그중 @25,−30 은 앵커에서 √41 ≈ 6.40 —
     * <b>집 위가 아니다</b>. 같은 밤 `밭위건축 0`·`부지경고 0` 이었으니 발자국은 겹치지도
     * 않았다. 근거 없이 밭 면적만 잃던 자리다.
     *
     * <p>새로 심는 쪽은 이미 {@link #PLANT_CLEAR} 로 집에서 물러나 있으므로, 정비를 실제
     * 점유로 좁혀도 덤불이 집으로 기어들지 않는다.
     */
    private static java.util.Set<Long> homeCells(ServerLevel level) {
        java.util.Set<Long> out = new java.util.HashSet<>();
        HomeStore reg = HomeStore.get(level);
        for (BlockPos h : reg.positions()) {
            HomeStore.Entry e = reg.entry(h);
            if (e == null) {
                continue;
            }
            HomeBlueprint bp = HomeBlueprint.of(level, h, e.design(), e.rotation(), e.mirrored());
            for (BlockPos c : bp.groundFootprint()) {
                out.add(RoadStore.key(c.getX(), c.getZ()));
            }
            for (BlockPos c : bp.garden()) {
                out.add(RoadStore.key(c.getX(), c.getZ()));
            }
            for (BlockPos c : bp.doorSteps()) {
                out.add(RoadStore.key(c.getX(), c.getZ()));
            }
        }
        return out;
    }

    /** 반경 판정 — 앞으로 심을 칸이 어느 거처에 너무 가까운가. 반경은 부르는 쪽이 정한다. */
    private static boolean nearSomeHome(ServerLevel level, java.util.List<MimicEntity> adults,
                                        BlockPos gp, double margin) {
        return index(level, adults, margin).near(gp);
    }

    /**
     * <b>이 칸에 밭을 놓을 수 없는 이유</b> — 놓을 수 있으면 null. 보고 전용(느려도 된다).
     *
     * <p>존재 이유는 하나다: <b>보고가 배치와 같은 코드에 물어야 한다.</b> 밭형태 보고의
     * "구멍"을 블록 종류만 보고 분류했더니, 집 여유 반경 안이라 밭이 <b>옳게</b> 비켜 간 맨
     * 잔디 칸이 진짜 구멍으로 잡혔다(실측 #2@-12,-10 = air/grass_block). 판정 규칙을 보고
     * 쪽에 베껴 쓰면 밭 격자 재구성 때처럼 두 벌이 갈라진다.
     *
     * <p>{@link #idealSpot} 의 관문을 <b>같은 순서로</b> 다시 묻는다. 인접 조건은 뺀다 —
     * 그것은 "놓을 수 있는가"가 아니라 "지금 이어 붙일 차례인가"라서, 이미 몸통 사이에 낀
     * 칸에는 해당하지 않는다.
     */
    @javax.annotation.Nullable
    public static String plantBlockReason(ServerLevel level, BlockPos gp) {
        java.util.List<MimicEntity> adults = new java.util.ArrayList<>(level.getEntities(
                com.evosim.mod.reg.ModEntities.MIMIC.get(),
                m -> m.isAlive() && m.getIndividual() != null
                        && (m.getStage() == com.evosim.core.LifeStage.ADULT
                                || m.getStage() == com.evosim.core.LifeStage.ELDER)));
        if (nearSomeHome(level, adults, gp, PLANT_CLEAR)) {
            return "집여유";
        }
        if (nearFacility(level, gp)) {
            return "시설여유";
        }
        if (nearStreet(level, gp)) {
            return "가로수여유";
        }
        var at = level.getBlockState(gp);
        var below = level.getBlockState(gp.below());
        if (!(at.isAir() || at.canBeReplaced() || weed(at))) {
            return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(at.getBlock()).getPath();
        }
        boolean ground = below.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                || below.is(net.minecraft.world.level.block.Blocks.DIRT)
                || below.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT)
                || below.is(net.minecraft.world.level.block.Blocks.DIRT_PATH);
        if (!ground) {
            return "땅아님:" + net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(below.getBlock()).getPath();
        }
        return null;
    }

    /**
     * <b>시설을 피한다</b>(P5a) — 학교·교회의 점유 반경 안이면 밭을 놓지 않는다.
     *
     * <p>거처 회피와 <b>별도</b>인 이유: 시설은 21×18 로 거처보다 훨씬 커서 {@code PLANT_CLEAR}
     * 같은 고정 여유로는 못 덮는다. 도면이 스스로 아는 반경({@code reach})에 심는 여유를 더한다.
     *
     * <p>이 검사가 없으면 밭이 학교 위로 자라도 막을 것이 없었다. 첫 학교가 밭에서 멀리 선 것은
     * <b>운</b>이지 안전이 아니다 — 집·가로등과 밭이 겹치던 것과 같은 종류의 빈틈이다.
     * 시설은 많아야 서너 채라 선형 순회로 충분하다.
     */
    // ── 덩어리 도면 기하 ────────────────────────────────────────────────────

    /** 발자국 격자 (열 c, 행 r) → 월드 열 {x, z}. 덩어리 축이 z 면 c/r 이 바뀐다. */
    public static int[] colOf(FarmStore.Plot p, int c, int r) {
        return p.bedAxisX ? new int[] {p.fx + c, p.fz + r} : new int[] {p.fx + r, p.fz + c};
    }

    /** 발자국의 월드 상자 {x0, z0, 폭, 깊이}. */
    public static int[] boxOf(FarmStore.Plot p, int beds, int rows) {
        int[] fp = com.evosim.core.FarmLayout.footprint(beds, rows);
        return p.bedAxisX ? new int[] {p.fx, p.fz, fp[0], fp[1]}
                : new int[] {p.fx, p.fz, fp[1], fp[0]};
    }

    /**
     * 이 월드 상자를 <b>밭으로 쓸 수 있는가</b> — 비었고, 평평하고, 이웃과 간격이 있는가.
     *
     * <p>칸마다 묻던 종전과 달리 <b>한 번에</b> 묻는다. 이것이 덩어리 도면의 핵심이다:
     * 통과하면 모양이 보장되고, 실패하면 아무것도 놓지 않는다 — 반쯤 놓여 계단이 되는 중간
     * 상태가 존재하지 않는다.
     *
     * @param baseY 요구 지면 높이(-1 이면 첫 칸의 높이를 기준으로 삼는다)
     */
    /**
     * <b>거부한 관문의 이름과 자리</b> — {@link #boxUsable} 가 false 를 돌릴 때마다 갱신된다.
     *
     * <p>밭이 왜 안 자라는지 물었을 때 "자리가 없다"고만 답할 수 있었다. 실제로는 사방이
     * 빈 풀밭인데 막혀 있었고(육안), 그러면 남는 것은 추측뿐이다. 어느 관문이 어느 칸에서
     * 걸었는지 남긴다 — 판정과 같은 코드가 남기므로 어긋날 수 없다.
     */
    private static String lastBoxFault = "?";

    /**
     * 교착 탈출 문턱 — 이 일수만큼 <b>연속으로</b> 한 칸도 못 심어야 "진짜 포화"로 본다.
     *
     * <p>1 이었다. 하루 못 자란 밭이 곧바로 크기 조건을 건너뛰어 다음 밭 자격을 얻었고,
     * 12타일 밭을 두고 새 밭을 파는 장면이 나왔다(육안 관측). 확장은 자금·노동·지형이 그날
     * 다 맞아야 하는 일이라 하루쯤 어긋나는 것은 흔하다 — 그것은 포화가 아니다.
     */
    private static final int BLOCKED_ESCAPE_DAYS = 3;

    private static boolean boxUsable(ServerLevel level, FarmStore store, long selfId,
                                     int x0, int z0, int w, int d, int baseY,
                                     java.util.List<MimicEntity> adults) {
        int want = baseY;
        for (int x = x0; x < x0 + w; x++) {
            for (int z = z0; z < z0 + d; z++) {
                int y = RoadPlanner.surfaceY(level, x, z);
                if (y == Integer.MIN_VALUE) {
                    lastBoxFault = String.format("지표 없음 @%d,%d", x, z);
                    return false;
                }
                if (want < 0) {
                    want = y;
                } else if (y != want) {
                    lastBoxFault = String.format("높이 어긋남 @%d,%d (y%d, 기준 y%d)", x, z, y, want);
                    return false; // 평평하지 않다 — 한 칸이라도 어긋나면 밭이 계단이 된다
                }
                BlockPos gp = new BlockPos(x, y + 1, z);
                if (!level.isLoaded(gp)) {
                    lastBoxFault = String.format("미로드 @%d,%d", x, z);
                    return false;
                }
                if (store.nearOtherBody(selfId, x, z, PLOT_GAP)) {
                    lastBoxFault = String.format("다른 밭이 %d칸 안 @%d,%d", PLOT_GAP, x, z);
                    return false;
                }
                if (nearSomeHome(level, adults, gp, PLANT_CLEAR)) {
                    lastBoxFault = String.format("거처가 %.0f칸 안 @%d,%d", PLANT_CLEAR, x, z);
                    return false;
                }
                if (nearFacility(level, gp)) {
                    lastBoxFault = String.format("시설이 가까움 @%d,%d", x, z);
                    return false;
                }
                if (nearStreet(level, gp)) {
                    lastBoxFault = String.format("가로수·분수가 가까움 @%d,%d", x, z);
                    return false;
                }
                if (RoadStore.get(level).has(x, z)) {
                    lastBoxFault = String.format("등기된 길 @%d,%d", x, z);
                    return false; // 등기된 마을 길 위에는 안 놓는다
                }
                var at = level.getBlockState(gp);
                if (!(at.isAir() || at.canBeReplaced() || weed(at))) {
                    lastBoxFault = String.format("%s 이(가) 서 있음 @%d,%d",
                            at.getBlock().getName().getString(), x, z);
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 확보한 발자국을 깐다 — 비재배 칸은 원목(테두리·길), <b>아직 안 심은 재배 칸은 흙바닥</b>.
     *
     * <p>종전에는 원목만 깔았다. 재배 칸의 바닥은 {@code plantAt} 이 심는 순간에야 놓이므로,
     * 발자국은 확보됐는데 아직 안 심은 칸은 {@code baseY+1} 이 비어 있었다 — 둘레 원목이 한 칸
     * 위에 서 있으니 그 자리가 <b>1칸 깊이 구덩이</b>로 보인다(육안 관측). 노동이 하루 몇 칸이라
     * 이 상태가 며칠씩 간다.
     *
     * <p>{@code FarmLayout.isCrop} 이 선언한 불변식이 여기서 깨지고 있었다: "발자국 안에 빈
     * 잔디는 없다 — 모든 칸이 재배 아니면 원목이다". 바닥을 먼저 깔아 그 말을 지킨다. 심는
     * 것은 그대로 노동에 달렸다 — 바뀌는 것은 <b>보이는 모습뿐</b>이고 경제는 건드리지 않는다.
     */
    private static void layLogs(ServerLevel level, FarmStore.Plot p) {
        int[] fp = com.evosim.core.FarmLayout.footprint(p.beds, p.rows);
        for (int c = 0; c < fp[0]; c++) {
            for (int r = 0; r < fp[1]; r++) {
                if (com.evosim.core.FarmLayout.isCrop(c, r, p.beds, p.rows)) {
                    // 재배 칸 — 바닥만 채운다. 이미 무언가 서 있으면(심긴 흙·베리) 손대지 않는다.
                    //
                    // <b>단, 옛 테두리 원목은 걷어낸다.</b> 단계가 오르면 footprint 가 커져
                    // 어제까지 테두리였던 줄(r == fp[1]-1 등)이 오늘은 안쪽 재배 칸이 된다.
                    // 그런데 아래 조건(비었을 때만 흙을 깔기)은 심긴 베리를 지키려는 것인데
                    // <b>옛 원목까지 같이 지켜</b>, 밭 한복판에 원목 기둥이 한 줄 남는다
                    // (육안 관측). 철거를 plantAt 에만 맡겨 두었더니 그것은 하루 k칸(자금·노동
                    // 한도)이라 며칠씩 남고, 자금이 마르면 영영 남는다.
                    //
                    // layLogs 는 <b>도면대로 까는 멱등 함수</b>다. 도면이 재배 칸이라고 말하는
                    // 자리에 원목이 서 있으면 그것을 치우는 것이 이 함수의 본래 일이다.
                    // 베리는 baseY+2 라 여기서 건드리지 않고, 타일 등기도 그 덤불 기준이라
                    // 그대로다 — 이 칸은 여전히 "안 심긴 칸"이고 plantAt 이 정상적으로 심는다.
                    // 가로등 기둥은 참나무 <b>울타리</b>(LampPlanner)라 이 조건에 걸리지 않는다.
                    int[] cxz = colOf(p, c, r);
                    BlockPos soil = new BlockPos(cxz[0], p.baseY + 1, cxz[1]);
                    var cur = level.getBlockState(soil);
                    if (cur.isAir() || cur.canBeReplaced()
                            || cur.is(net.minecraft.world.level.block.Blocks.OAK_LOG)) {
                        level.setBlockAndUpdate(soil, net.minecraft.world.level.block.Blocks
                                .GRASS_BLOCK.defaultBlockState());
                    }
                    continue;
                }
                int[] xz = colOf(p, c, r);
                BlockPos at = new BlockPos(xz[0], p.baseY + 1, xz[1]);
                if (!level.getBlockState(at).is(net.minecraft.world.level.block.Blocks.OAK_LOG)) {
                    level.setBlockAndUpdate(at,
                            net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
                }
                // 길 위에 옛 베리가 남아 있으면 걷어낸다(줄이 늘 때 재배 칸이 길로 바뀌는 경우).
                BlockPos above = at.above();
                if (level.getBlockState(above)
                        .is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH)) {
                    level.setBlockAndUpdate(above,
                            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /** 재배 칸 하나를 심는다 — 지면+1 잔디블록, 그 위 베리. 이미 심겼으면 false. */
    private static boolean plantAt(ServerLevel level, FarmStore store, FarmStore.Plot p,
                                   int c, int r) {
        int[] xz = colOf(p, c, r);
        BlockPos soil = new BlockPos(xz[0], p.baseY + 1, xz[1]);
        BlockPos bush = soil.above();
        if (store.isFarmTile(bush)) {
            return false;
        }
        level.setBlockAndUpdate(soil,
                net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(bush,
                net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 1));
        store.addTile(p, bush, com.evosim.mod.entity.SimTime.tick(level));
        return true;
    }

    /** 아직 안 심은 재배 칸 — {@code FarmLayout.cropOrder} 순(위 줄부터, 줄 안은 왼쪽부터). */
    /** 목록의 칸을 상한까지 심고 <b>실제로 심은 수</b>를 돌려준다(성장 루프의 단일 심기 경로). */
    private static int plantFrom(ServerLevel level, FarmStore store, FarmStore.Plot plot,
                                 MimicEntity ownerEnt, java.util.List<int[]> todo, int k) {
        int n = 0;
        for (int[] cr : todo) {
            if (n >= k) {
                break;
            }
            if (plantAt(level, store, plot, cr[0], cr[1])) {
                int[] xz = colOf(plot, cr[0], cr[1]);
                com.evosim.mod.entity.MimicEntity.farmTookRoad(level, ownerEnt, plot,
                        new BlockPos(xz[0], plot.baseY + 2, xz[1]));
                n++;
            }
        }
        return n;
    }

    private static java.util.List<int[]> unplanted(FarmStore store, FarmStore.Plot p) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (int[] cr : com.evosim.core.FarmLayout.cropOrder(p.beds, p.rows)) {
            int[] xz = colOf(p, cr[0], cr[1]);
            if (!store.isFarmTile(new BlockPos(xz[0], p.baseY + 2, xz[1]))) {
                out.add(cr);
            }
        }
        return out;
    }

    /**
     * <b>다음 단계를 예약해 본다</b> — 되면 발자국이 커지고 원목이 즉시 깔린다.
     *
     * <p>표준 수열의 다음 수(덩어리 추가 또는 줄 늘리기)를 먼저 시도하고, 그 쪽이 막히면
     * <b>다른 수</b>를 시도한다. 각 수는 양쪽 방향을 다 본다 — 방향을 고정하면 한쪽이 막힌
     * 것만으로 밭이 멈춘다. 넷 다 안 되면 포화다.
     *
     * <p>붙이는 띠는 언제나 <b>3칸</b>이다. 덩어리를 늘리면 옛 테두리 열이 사이 길이 되고,
     * 줄을 늘리면 옛 테두리 줄이 재배 줄이 된다 — 그래서 새로 필요한 땅은 3칸뿐이다.
     */
    private static boolean reserveNext(ServerLevel level, FarmStore store, FarmStore.Plot p,
                                       java.util.List<MimicEntity> adults) {
        if (p.beds <= 0) {
            return false; // 구세계 구획 — 건드리지 않는다
        }
        // 다음 수는 <b>모양</b>이 정한다(단계 번호가 아니라). 번호로 정하면 대체 수를 한 번
        // 쓴 순간 수열 밖이 되어 영영 자라지 못한다 — FarmLayout.addBedNext 참조.
        boolean[] first = com.evosim.core.FarmLayout.addBedNext(p.beds, p.rows)
                ? new boolean[] {true, false} : new boolean[] {false, true};
        for (boolean addBed : first) {
            int nb = addBed ? p.beds + 1 : p.beds;
            int nr = addBed ? p.rows : p.rows + com.evosim.core.FarmLayout.ROW_STEP;
            int[] old = boxOf(p, p.beds, p.rows);
            int[] neu = boxOf(p, nb, nr);
            int gw = neu[2] - old[2];   // 늘어난 폭
            int gd = neu[3] - old[3];   // 늘어난 깊이
            // + 방향: 상자 끝에 붙인다. − 방향: 원점을 당긴다.
            int[][] tries = gw > 0
                    ? new int[][] {{old[0] + old[2], old[1], gw, old[3]}, {old[0] - gw, old[1], gw, old[3]}}
                    : new int[][] {{old[0], old[1] + old[3], old[2], gd}, {old[0], old[1] - gd, old[2], gd}};
            for (int t = 0; t < tries.length; t++) {
                int[] strip = tries[t];
                if (!boxUsable(level, store, p.id, strip[0], strip[1], strip[2], strip[3],
                        p.baseY, adults)) {
                    continue;
                }
                if (t == 1) {
                    // − 방향이면 원점이 그만큼 당겨진다. gw·gd 는 boxOf 가 돌려준 <b>월드</b>
                    // 폭·깊이 증가분이라 축이 이미 반영돼 있다 — 여기서 축을 또 곱하면 원점이
                    // 엉뚱하게 움직여 옛 타일이 발자국 밖으로 밀려난다(실측: 발자국밖 타일 6).
                    p.fx -= gw;
                    p.fz -= gd;
                }
                p.beds = nb;
                p.rows = nr;
                p.steps++;
                store.setDirty();
                layLogs(level, p);
                com.evosim.mod.log.SimEvents.note(level, "밭단계", String.format(
                        "구획 %d — %d단계로(덩어리%d 줄%d · 재배%d칸) %s쪽으로 %s @%d,%d",
                        p.id, p.steps + 1, nb, nr, com.evosim.core.FarmLayout.tiles(nb, nr),
                        t == 0 ? "+" : "−", addBed ? "덩어리 추가" : "줄 늘리기", p.fx, p.fz));
                return true;
            }
        }
        return false;
    }

    /**
     * <b>이 칸에 못 심는 사유</b> — 못 심으면 이름, 심을 수 있으면 null.
     *
     * <p>{@link #idealSpot}(배치) · {@link #freeIn}(방향) · {@link #unfilledReasons}(진단) 이
     * 같은 관문을 봐야 한다. 종전에는 관문이 배치와 방향 두 곳에 복사돼 있었고, 새 조건을 한쪽에만
     * 넣으면 "실제로는 못 심을 자리를 트였다고 세는" 어긋남이 생겼다. 사유를 <b>문자열로</b>
     * 돌려주므로 진단이 같은 코드를 다시 쓰지 않고 histogram 을 만들 수 있다.
     *
     * <p>연결 조건(몸통과 맞닿을 것)은 여기 없다 — 그것은 땅의 성질이 아니라 순서의 성질이다.
     */
    @javax.annotation.Nullable
    private static String gateReason(ServerLevel level, FarmStore store, long selfId,
                                     BlockPos gp, java.util.List<MimicEntity> adults) {
        if (!level.isLoaded(gp)) {
            return "미로드";
        }
        // 이웃 검사를 먼저 — 그래야 "이미밭" 이 <b>우리</b> 타일만 뜻한다(남의 타일은 제 몸통이라
        // 여기서 이웃구획으로 잡힌다). 호출부가 "넘어갈 칸" 과 "막힌 칸" 을 가르는 근거가 된다.
        if (store.nearOtherBody(selfId, gp.getX(), gp.getZ(), PLOT_GAP)) {
            return "이웃구획";
        }
        if (store.isFarmTile(gp)) {
            return "이미밭";
        }
        if (nearSomeHome(level, adults, gp, PLANT_CLEAR)) {
            return "집여유";
        }
        if (nearFacility(level, gp)) {
            return "시설여유";
        }
        if (nearStreet(level, gp)) {
            return "가로수여유";
        }
        var at = level.getBlockState(gp);
        var below = level.getBlockState(gp.below());
        if (!(at.isAir() || at.canBeReplaced() || weed(at))) {
            return net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(at.getBlock()).getPath();
        }
        boolean ground = below.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                || below.is(net.minecraft.world.level.block.Blocks.DIRT)
                || below.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT)
                || below.is(net.minecraft.world.level.block.Blocks.DIRT_PATH);
        return ground ? null : "땅아님";
    }

    /**
     * <b>이 구획이 제 사각형을 왜 못 채우는가</b> — 다음 이상 칸들이 걸리는 사유 histogram.
     *
     * <p>실측에서 큰 구획의 채움이 70% 언저리로 주저앉고 재배줄 길이가 1~12 로 벌어졌다.
     * 배치 수열은 열을 넓힐 때 <b>모든 줄에 한 칸씩</b> 더하므로 줄 길이는 원래 ±1 이어야 한다.
     * 그런데 막힌 칸을 만나면 건너뛰고 다음 칸으로 가므로, 어떤 줄 하나가 <b>영구히</b> 막히면
     * 그 줄만 거기서 멈추고 나머지는 계속 넓어진다 — 오른쪽이 계단처럼 깎이는 모양의 정체다.
     *
     * <p>그러면 "무엇이 막았나" 가 남는데, 사각형이 안 찬 자리를 세어 보지 않으면 알 수 없다.
     * 이 함수는 아무것도 바꾸지 않고 읽기만 한다.
     */
    public static java.util.Map<String, Integer> unfilledReasons(
            ServerLevel level, FarmStore store, FarmStore.Plot plot, int look) {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        if (plot.beds <= 0) {
            out.merge("구세계구획", 1, Integer::sum);
            return out;
        }
        java.util.List<MimicEntity> adults = new java.util.ArrayList<>(level.getEntities(
                com.evosim.mod.reg.ModEntities.MIMIC.get(),
                m -> m.isAlive() && m.getIndividual() != null
                        && (m.getStage() == com.evosim.core.LifeStage.ADULT
                                || m.getStage() == com.evosim.core.LifeStage.ELDER)));
        int un = unplanted(store, plot).size();
        if (un > 0) {
            out.merge("아직 안 심음(순서대기)", un, Integer::sum);
            return out; // 아직 이번 단계를 채우는 중 — 막힌 것이 아니다
        }
        // 다음 단계의 네 후보 띠가 각각 무엇에 걸리는지 센다.
        for (boolean addBed : new boolean[] {true, false}) {
            int nb = addBed ? plot.beds + 1 : plot.beds;
            int nr = addBed ? plot.rows : plot.rows + com.evosim.core.FarmLayout.ROW_STEP;
            int[] old = boxOf(plot, plot.beds, plot.rows);
            int[] neu = boxOf(plot, nb, nr);
            int gw = neu[2] - old[2];
            int gd = neu[3] - old[3];
            int[][] tries = gw > 0
                    ? new int[][] {{old[0] + old[2], old[1], gw, old[3]},
                                   {old[0] - gw, old[1], gw, old[3]}}
                    : new int[][] {{old[0], old[1] + old[3], old[2], gd},
                                   {old[0], old[1] - gd, old[2], gd}};
            for (int[] st : tries) {
                String why = null;
                for (int x = st[0]; x < st[0] + st[2] && why == null; x++) {
                    for (int z = st[1]; z < st[1] + st[3] && why == null; z++) {
                        int y = RoadPlanner.surfaceY(level, x, z);
                        if (y == Integer.MIN_VALUE || y != plot.baseY) {
                            why = "높이다름";
                            break;
                        }
                        if (RoadStore.get(level).has(x, z)) {
                            why = "마을길";
                            break;
                        }
                        String r = gateReason(level, store, plot.id,
                                new BlockPos(x, y + 1, z), adults);
                        if (r != null) {
                            why = r;
                        }
                    }
                }
                out.merge(why == null ? "열려있음(자금대기)" : why, 1, Integer::sum);
            }
        }
        return out;
    }

    /**
     * <b>가로수·분수를 피한다</b> — 꾸밈이 밭 칸을 덮지 않게.
     *
     * <p>나무 쪽({@code StreetPlanner.ok})도 밭을 피하지만, 어느 쪽이 먼저 서느냐는 정해져
     * 있지 않다. 나무가 먼저면 밭이 물러서야 하고 밭이 먼저면 나무가 물러서야 한다 — 집·시설을
     * 양쪽에서 다 막는 것과 같은 이유다. 한쪽만 막으면 순서에 따라 새는 빈틈이 남는다.
     *
     * <p>실측(D28): 구획 #11 의 구멍 한 칸이 {@code oak_leaves} 였다. 잎은 뽑히지 않으므로
     * 그 칸은 영영 구멍이다. 꾸밈은 많아야 수십 개라 선형 순회로 충분하다.
     */
    private static boolean nearStreet(ServerLevel level, BlockPos gp) {
        var street = com.evosim.mod.entity.StreetStore.get(level);
        for (boolean fnt : new boolean[] {false, true}) {
            // 나무는 잎이 뻗는 두 칸, 분수는 5×5 의 절반. 거기에 밭 테두리 한 겹을 더한다.
            int need = (fnt ? 2 : com.evosim.mod.entity.StreetPlanner.TREE_CANOPY) + 1;
            if (street.nearest(gp.getX(), gp.getZ(), fnt) <= need) {
                return true;
            }
        }
        return false;
    }

    private static boolean nearFacility(ServerLevel level, BlockPos gp) {
        for (FacilityStore.Entry e : FacilityStore.get(level).all()) {
            var tpl = FacilityTemplate.of(level, e.kind, e.rotation, e.mirrored);
            if (tpl.isEmpty()) {
                continue;
            }
            double need = tpl.get().reach() + PLANT_MARGIN;
            double dx = e.pos.getX() - gp.getX();
            double dz = e.pos.getZ() - gp.getZ();
            if (dx * dx + dz * dz < need * need) {
                return true;
            }
        }
        return false;
    }

    /**
     * 회피 대상 거처 좌표 — <b>등기된 모든 집</b>이다. 사는 사람이 있든 없든.
     *
     * <p>종전에는 성인·노년 명단의 {@code getHomePos()} 만 봤다. 그래서 <b>빈집이 회피에서
     * 통째로 빠졌고</b>, 밭이 그 벽까지 자랐다. 그런데 감사({@code /evosim homes} 의 "밭까지")는
     * 등기된 집 전부를 재므로, 계측기와 회피기가 서로 다른 질문을 하고 있었다 —
     * 실측(P3 D26, 빈집 21채): 밭까지 최소 1 · 2칸 이하 3채.
     *
     * <p>빈집도 재사용 대상이고 철거되지 않으므로, 피하는 것이 맞다.
     */
    private static java.util.List<BlockPos> avoidHomes(ServerLevel level,
                                                       java.util.List<MimicEntity> adults) {
        java.util.LinkedHashSet<BlockPos> out = new java.util.LinkedHashSet<>(
                HomeStore.get(level).positions());
        for (MimicEntity m : adults) {
            if (m.getHomePos() != null) {
                out.add(m.getHomePos()); // 아직 등기 전인 신축 — 명단 쪽이 더 이르다
            }
        }
        return new java.util.ArrayList<>(out);
    }

    /**
     * <b>거처 공간 색인</b> — 회피 판정에서 성인 전원을 훑지 않기 위한 16칸 격자.
     *
     * <p>이 판정은 <b>칸마다</b> 불린다. 방향 고르기만 해도 49칸 × 8방향이고 확장 수열까지
     * 더하면 구획 하나가 하루에 수천 번 부른다. 거기에 성인 수를 곱하면 인구와 구획이 함께
     * 자랄 때 제곱으로 커진다.
     *
     * <p>판정식은 <b>그대로</b>다 — 같은 거리 비교, 같은 반경. 격자는 "명백히 먼 집"을 후보에서
     * 빼는 것뿐이고, 훑는 격자 범위를 <b>최대 반경</b>으로 잡으므로 걸릴 집을 놓치지 않는다.
     */
    private static final int CELL = 16;

    private static Object idxKeyLevel;
    private static java.util.List<MimicEntity> idxKeyAdults;
    private static double idxKeyMargin = -1.0;
    private static HomeIndex idxCache;

    private static HomeIndex index(ServerLevel level, java.util.List<MimicEntity> adults,
                                   double margin) {
        // 일일 처리는 성인 명단을 <b>새 리스트</b>로 만들어 넘긴다. 그 동일성으로 한 판(pass)을
        // 구분하면, 판 안에서는 다시 만들지 않고 판이 바뀌면 반드시 다시 만든다.
        if (idxCache == null || idxKeyLevel != level || idxKeyAdults != adults
                || idxKeyMargin != margin) {
            idxCache = HomeIndex.build(level, adults, margin);
            idxKeyLevel = level;
            idxKeyAdults = adults;
            idxKeyMargin = margin;
        }
        return idxCache;
    }

    private static final class HomeIndex {
        private final java.util.HashMap<Long, java.util.List<double[]>> cells =
                new java.util.HashMap<>(); // 격자 → [x, z, 반경²]
        private double maxClear;

        static HomeIndex build(ServerLevel level, java.util.List<MimicEntity> adults,
                               double margin) {
            HomeIndex ix = new HomeIndex();
            HomeStore reg = HomeStore.get(level);
            for (BlockPos h : avoidHomes(level, adults)) {
                double clear = Math.max(margin, homeReach(level, reg, h) + PLANT_MARGIN);
                ix.maxClear = Math.max(ix.maxClear, clear);
                ix.cells.computeIfAbsent(
                        RoadStore.key(Math.floorDiv(h.getX(), CELL), Math.floorDiv(h.getZ(), CELL)),
                        k -> new java.util.ArrayList<>())
                        .add(new double[] {h.getX(), h.getZ(), clear * clear});
            }
            return ix;
        }

        boolean near(BlockPos gp) {
            int r = (int) Math.ceil(maxClear / CELL) + 1;
            int cx = Math.floorDiv(gp.getX(), CELL);
            int cz = Math.floorDiv(gp.getZ(), CELL);
            for (int ax = -r; ax <= r; ax++) {
                for (int az = -r; az <= r; az++) {
                    var list = cells.get(RoadStore.key(cx + ax, cz + az));
                    if (list == null) {
                        continue;
                    }
                    for (double[] e : list) {
                        double dx = e[0] - gp.getX();
                        double dz = e[1] - gp.getZ();
                        if (dx * dx + dz * dz < e[2]) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    /**
     * <b>이 거처가 앵커에서 뻗는 최대 평면 거리</b> — 도면마다 다르다.
     *
     * <p>회전·좌우반전과 무관하므로(회전은 x·z 를 맞바꾸고 반전은 부호만 뒤집는다) 도면 이름만
     * 열쇠로 삼아 기억한다. 도면 수는 아홉이라 표가 커지지 않고, {@code HomeBlueprint.reachOf}
     * 는 도면을 통째로 만들므로 칸마다 부르면 안 된다.
     */
    private static final java.util.HashMap<String, Double> REACH = new java.util.HashMap<>();

    private static double homeReach(ServerLevel level, HomeStore reg, BlockPos home) {
        HomeStore.Entry e = reg.entry(home);
        String design = e == null ? HomeStore.TENT : e.design();
        Double hit = REACH.get(design);
        if (hit != null) {
            return hit;
        }
        double r = HomeBlueprint.reachOf(level, design);
        REACH.put(design, r);
        return r;
    }

    /**
     * <b>신규 개간 회피 반경</b> — 새로 심는 칸은 여기까지 물러선다.
     *
     * <p>종전 회피 반경 6.5 는 <b>앵커 기준</b>인데 발자국·정원이 이미 앵커에서 5.66 까지 뻗는다.
     * 그래서 집 바깥 칸에서 실효 여유가 0.84 밖에 안 되고, 덤불이 집 벽에 그대로 맞붙었다.
     * 실측(seed 7 D16): 집 22채의 가장 가까운 밭까지 거리 최소 1 · 중앙 6, 거리 2 이하 2채.
     * 그 자리엔 밭 제 테두리(흙길 한 겹)조차 못 깔려 가로등·집과 겹쳐 보였다
     * (실측: 밭 (1,0,42) — 가로등 기둥 (3,0,42) 사이 (2,−1,42) 가 잔디).
     *
     * <p>5.66(발자국 도달) + 1(밭 테두리) + 1(지나다닐 한 칸) ≈ 7.7 → 8.0.
     * 막힌 칸은 이상 수열의 다음 칸으로 건너뛰므로(SCAN_SLACK 24) 면적이 아니라
     * <b>자리만</b> 바깥으로 밀린다.
     *
     * <p><b>이 값은 바닥일 뿐이다.</b> 5.66 은 <b>소형 도면</b>의 도달 거리이고, 중·대형과
     * 저택은 훨씬 멀리 뻗는다. 그래서 앵커에서 8칸을 띄워도 저택 벽에서는 한 칸까지 붙었다 —
     * 실측(D26, 소28·중4·대8·저택4): {@code 밭까지 최소1 · 2칸 이하 4채}, 그런데
     * {@code 부지경고 0 · 밭 위 0} 이었다. 집이 밭으로 간 게 아니라 <b>밭이 집으로 자란</b>
     * 것이다. D16 검증에서 안 걸린 이유도 분명하다 — 그때는 대형이 한 채뿐이었다.
     *
     * <p>이제 거처마다 제 도면의 도달 거리 + {@link #PLANT_MARGIN} 을 쓰고, 이 상수는 그
     * 아래로 내려가지 않게 하는 하한으로만 남는다(소형은 종전과 같고 큰 집만 더 물러난다).
     */
    private static final double PLANT_CLEAR = 8.0;

    /**
     * 발자국 바깥에 남겨야 할 여유 — 밭 테두리 한 겹 + 지나다닐 한 칸.
     *
     * <p>2.0→3.0. 2 로 두면 <b>테두리만 들어가고 통로가 없다</b>. 실측(P3b D14)에서 그 모양이
     * 그대로 나왔다 — 밭 → 흙길 한 칸 → 집 벽이 맞붙어 집–밭 거리 2 가 됐다. 도달 거리는
     * 앵커에서 발자국까지이므로, 그 바깥으로 두 칸을 더 띄워야 테두리와 통로가 <b>둘 다</b>
     * 들어간다.
     */
    private static final double PLANT_MARGIN = 3.0;

    /**
     * <b>구획 사이에 남길 빈 칸</b>(체비셰프) — 새 타일은 남의 몸통에서 이만큼 떨어져야 한다.
     *
     * <p>집을 피하는 {@link #PLANT_MARGIN}과 같은 셈이다: 내 테두리 한 겹 + 남의 테두리 한 겹
     * + 지나다닐 한 칸 = 3. 이보다 좁히면 테두리가 서로 겹쳐 그려지지 못하고, 두 밭이 공중에서
     * <b>한 덩어리</b>로 읽힌다(실측: 구획 간 최소거리 1.0 — 각각은 반듯한데 사이가 없어
     * 찌그러져 보였다).
     *
     * <p>앵커 간격(20)으로는 이걸 보장할 수 없다. 앵커는 착공 순간의 점일 뿐이고 구획은 그
     * 뒤로 열 방향으로 계속 뻗기 때문이다 — 간격은 <b>타일을 놓는 순간</b>에 물어야 한다.
     */
    public static final int PLOT_GAP = 3;

    /**
     * <b>1단계 발자국이 들어갈 자리</b> — {x0, z0, 덩어리축이 x면 1, 지면 y}. 없으면 null.
     *
     * <p>점 하나를 고르던 종전과 다르다. 밭이 이제 통째로 앉으므로 <b>상자가 들어가는지</b>를
     * 물어야 한다 — 점만 보고 앉히면 모서리가 집·길·이웃 밭에 걸려 반쯤 놓인 밭이 생긴다.
     * 후보 점마다 두 축(덩어리가 x로 늘어나는 배치와 z로 늘어나는 배치)을 다 본다.
     */
    @javax.annotation.Nullable
    private static int[] findFootprint(ServerLevel level, FarmStore store, BlockPos home,
                                       java.util.List<MimicEntity> adults) {
        int[] br = com.evosim.core.FarmLayout.stage(1);
        int[] fp = com.evosim.core.FarmLayout.footprint(br[0], br[1]);
        for (int radius = 15; radius <= 70; radius += 5) {
            for (int d = 0; d < 16; d++) {
                double ang = d * Math.PI / 8.0;
                int cx = home.getX() + (int) Math.round(Math.cos(ang) * radius);
                int cz = home.getZ() + (int) Math.round(Math.sin(ang) * radius);
                for (int axis = 0; axis < 2; axis++) {
                    int w = axis == 1 ? fp[0] : fp[1];
                    int dpt = axis == 1 ? fp[1] : fp[0];
                    int x0 = cx - w / 2;
                    int z0 = cz - dpt / 2;
                    int y = RoadPlanner.surfaceY(level, x0, z0);
                    if (y == Integer.MIN_VALUE) {
                        continue;
                    }
                    boolean nearHome = false;
                    for (MimicEntity a2 : adults) {
                        if (a2.getHomePos() != null
                                && a2.getHomePos().distSqr(new BlockPos(cx, y + 1, cz)) < 12 * 12) {
                            nearHome = true;
                            break;
                        }
                    }
                    if (nearHome) {
                        continue;
                    }
                    if (boxUsable(level, store, 0L, x0, z0, w, dpt, y, adults)) {
                        return new int[] {x0, z0, axis, y};
                    }
                }
            }
        }
        return null;
    }

    /** 신규 밭 부지 — 집 기준 8방위 20블록, 기존 밭 앵커 20·거처 12 회피(발자국 근사). 없으면 null. */
    private static BlockPos findFarmSite(ServerLevel level, FarmStore store, BlockPos home,
                                         java.util.List<MimicEntity> adults) {
        // 부지 확산(B2) — 근거리부터 바깥으로 촘촘히 탐색해 "수많은 밭"을 지도에 분산.
        // 8방향×3반경(24후보) → 16방향×반경 15~70(176후보): 20쌍(21거처) 밀집에서 성긴
        // 격자가 전부 거처 12블록 내에 걸려 부지 실패 → 자금 충족에도 착공 불가이던 결함
        // (런12 실측: 에드먼드 39 ≥ 임계 34.8, 미착공). 간격 조건은 유지 — 해상도만 보정.
        for (int radius = 15; radius <= 70; radius += 5) {
            for (int d = 0; d < 16; d++) {
                double ang = d * Math.PI / 8.0;
                BlockPos c = home.offset((int) Math.round(Math.cos(ang) * radius), 0,
                        (int) Math.round(Math.sin(ang) * radius));
                BlockPos site = level.getHeightmapPos(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c);
                boolean bad = false;
                for (FarmStore.Plot p : store.all().values()) {
                    if (p.anchor.distSqr(site) < 20 * 20) {
                        bad = true;
                    }
                }
                // 앵커 간격(20)만으로는 부족하다 — 이웃이 이미 이쪽으로 <b>자라 왔을</b> 수 있다.
                // 몸통까지 재서, 놓자마자 남의 밭과 붙는 자리를 부지 단계에서 걸러낸다.
                if (store.nearOtherBody(0L, site.getX(), site.getZ(), PLOT_GAP)) {
                    bad = true;
                }
                for (MimicEntity a : adults) {
                    if (a.getHomePos() != null && a.getHomePos().distSqr(site) < 12 * 12) {
                        bad = true;
                    }
                }
                if (!bad) {
                    return site;
                }
            }
        }
        return null;
    }

    /**
     * 검증 전용 — 이 자리에 <b>1단계 밭을 세우고</b> 원하는 단계까지 키운다.
     *
     * <p>실제 착공·확장과 <b>같은 함수</b>({@link #layLogs}·{@link #plantAt}·{@link #reserveNext})를
     * 쓴다. 검증용 배치 코드를 따로 두면 실연이 실제와 어긋나 아무것도 보증하지 못한다.
     *
     * @return 세운 구획(자리가 없으면 null)
     */
    @javax.annotation.Nullable
    public static FarmStore.Plot debugRaise(ServerLevel level, BlockPos at, int stage) {
        FarmStore store = FarmStore.get(level);
        java.util.List<MimicEntity> adults = new java.util.ArrayList<>(level.getEntities(
                com.evosim.mod.reg.ModEntities.MIMIC.get(),
                m -> m.isAlive() && m.getIndividual() != null
                        && (m.getStage() == com.evosim.core.LifeStage.ADULT
                                || m.getStage() == com.evosim.core.LifeStage.ELDER)));
        int[] br = com.evosim.core.FarmLayout.stage(1);
        int[] fp = com.evosim.core.FarmLayout.footprint(br[0], br[1]);
        for (int axis = 0; axis < 2; axis++) {
            int w = axis == 1 ? fp[0] : fp[1];
            int d = axis == 1 ? fp[1] : fp[0];
            int x0 = at.getX() - w / 2;
            int z0 = at.getZ() - d / 2;
            int y = RoadPlanner.surfaceY(level, x0, z0);
            if (y == Integer.MIN_VALUE || !boxUsable(level, store, 0L, x0, z0, w, d, y, adults)) {
                continue;
            }
            FarmStore.Plot p = store.create(new BlockPos(x0, y + 1, z0), 0L);
            p.beds = br[0];
            p.rows = br[1];
            p.bedAxisX = axis != 0;
            p.fx = x0;
            p.fz = z0;
            p.baseY = y;
            p.foundedDay = com.evosim.mod.entity.SimTime.tick(level) / 24000L;
            p.tilesByFounder = com.evosim.core.FarmLayout.tiles(br[0], br[1]);
            store.setDirty();
            layLogs(level, p);
            for (int[] cr : com.evosim.core.FarmLayout.cropOrder(p.beds, p.rows)) {
                plantAt(level, store, p, cr[0], cr[1]);
            }
            if (stage > 1) {
                debugAdvance(level, p, stage - 1);
            }
            return p;
        }
        return null;
    }

    /** 검증 전용 — 이 구획을 최대 n단계 더 키운다(자금·노동 우회). 실제로 진행한 단계 수 반환. */
    public static int debugAdvance(ServerLevel level, FarmStore.Plot p, int steps) {
        FarmStore store = FarmStore.get(level);
        java.util.List<MimicEntity> adults = new java.util.ArrayList<>(level.getEntities(
                com.evosim.mod.reg.ModEntities.MIMIC.get(),
                m -> m.isAlive() && m.getIndividual() != null
                        && (m.getStage() == com.evosim.core.LifeStage.ADULT
                                || m.getStage() == com.evosim.core.LifeStage.ELDER)));
        int done = 0;
        for (int i = 0; i < steps; i++) {
            for (int[] cr : unplanted(store, p)) {
                plantAt(level, store, p, cr[0], cr[1]);
            }
            if (!reserveNext(level, store, p, adults)) {
                break;
            }
            done++;
        }
        for (int[] cr : unplanted(store, p)) {
            plantAt(level, store, p, cr[0], cr[1]);
        }
        return done;
    }

    /** 검증 조성 훅 — "어제 이 밭에 출근했음"을 주입(규칙 9: 조성만, 승격 결말은 실경로). */
    public static void debugSeedAssignment(int entityId, long plotId) {
        ASSIGNED.put(entityId, plotId);
    }

    /** 오늘 배정(일용 포함) 인원 수 — AUDIT 관측용. */
    public static int assignedCount() {
        return ASSIGNED.size();
    }

    /** 검증 인수 — 무대 시작 시 배정 잔재 제거(같은 자리 2회 규칙). */
    public static void clearAssignments() {
        ASSIGNED.clear();
        LAST_ASSIGNED.clear();
        UNREACHABLE.clear();
        TENANT_PAY_TODAY.clear();
        TENANT_WORKERS_TODAY.clear();
        assignDay = -1;
        rentDay = -1;
        growDay = -1;
    }

    /** 검증 전용 — 소작 임금 원장 주입(수당 회계 고립 검증: 수확 유동성 배제). */
    public static void debugSeedTenantPay(long plotId, double share, int workerId) {
        recordTenantPay(plotId, share, workerId);
    }

    /** 검증 전용 — 밤 정산(수당·상환·지대 이체)을 즉시 1회 강제(rentDay 리셋). */
    public static void debugSettle(ServerLevel level) {
        rentDay = -1;
        settleRent(level);
    }

    /** 검증 전용 — 밤 성장(개간·확장)을 즉시 1회 강제(growDay 리셋). 다중스텝의 growDay 충돌·
     *  신규 엔티티 인덱싱 레이스를 우회해 결정론 판정(엔티티가 인덱싱된 poll 시점에 호출). */
    public static void debugGrow(ServerLevel level) {
        growDay = -1;
        growFarms(level);
    }

    /** 검증 전용 — "첫 새벽" 배정을 즉시 강제(assignDay 리셋 + ASSIGNED 선청소로 LAST_ASSIGNED가
     *  비어 미도달 안전판 무발동). 신규 워커 인덱싱 레이스 우회 — 단일 배정 관측용. */
    public static void debugAssign(ServerLevel level) {
        assignDay = -1;
        ASSIGNED.clear();
        assignDawn(level);
    }

    /**
     * 새벽 배정 — 하루 1회(노동 시작 이후 첫 스캔): 구획별 부족분(T − 가구 ΣC, 최소 일감 게이트)을
     * 구직자(성년·비소유·저장고 비넉넉·통근 내·소유 가구 제외)로 거리순 커버. 운(익음) 무관 결정론.
     */
    private static void assignDawn(ServerLevel level) {
        long day = com.evosim.mod.entity.SimTime.tick(level) / 24000L;
        long tod = level.getDayTime() % 24000L;
        if (day == assignDay || tod < 1000L || tod > 9000L) {
            return;
        }
        assignDay = day;
        // 동기 갱신(M7) — 만족·경쟁 캐시는 하루 단위 결정(성년 전원, 배정 계산 전에)
        for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null
                        && (e.getStage() == com.evosim.core.LifeStage.ADULT
                                || e.getStage() == com.evosim.core.LifeStage.ELDER))) {
            m.updateMotivation(level);
        }
        LAST_ASSIGNED.clear();
        LAST_ASSIGNED.putAll(ASSIGNED);
        ASSIGNED.clear();
        UNREACHABLE.clear(); // 하루 지나면 다시 시도(지형·군집은 변한다)
        FarmStore store = FarmStore.get(level);
        java.util.List<MimicEntity> adults = new java.util.ArrayList<>(level.getEntities(
                com.evosim.mod.reg.ModEntities.MIMIC.get(),
                m -> m.isAlive() && m.getIndividual() != null
                        && (m.getStage() == com.evosim.core.LifeStage.ADULT
                                || m.getStage() == com.evosim.core.LifeStage.ELDER)));
        // 가구 케어 예산 — 주인+동거 배우자(실제로 그 밭을 수확할 수 있는 노동만: MimicFarmGoal 과
        // 대칭, 동거 아들 등은 수확·고용 모두 불가라 제외)의 용량을 "가까운 구획부터" 소진해 구획별
        // 자가 케어 몫을 확정. 케어가 닿지 않는 원거리 구획은 몫 0 → 부족분 전량 게시 = 100% 소작.
        // (종전엔 주인 용량이 소유 전 구획에서 중복 차감돼 원거리 밭이 구획당 12타일씩 과소 고용됐음.)
        java.util.Map<Long, Integer> effCap = new java.util.HashMap<>();   // plotId → 자가 케어 몫
        java.util.Map<Long, BlockPos> ownerHomes = new java.util.HashMap<>(); // ownerId → 거처
        {
            java.util.Map<Long, java.util.List<FarmStore.Plot>> byOwner = new java.util.HashMap<>();
            for (FarmStore.Plot p : store.all().values()) {
                byOwner.computeIfAbsent(p.ownerId, k -> new java.util.ArrayList<>()).add(p);
            }
            for (var entry : byOwner.entrySet()) {
                long oid = entry.getKey();
                MimicEntity ownerEnt = null;
                for (MimicEntity m : adults) {
                    if (m.getIndividual().id() == oid) {
                        ownerEnt = m;
                    }
                }
                BlockPos home = ownerEnt == null ? null : ownerEnt.getHomePos();
                if (home != null) {
                    ownerHomes.put(oid, home);
                }
                int budget = 0;
                if (ownerEnt != null && !ownerEnt.isSatisfiedToday()) { // 만족 구성원 제외 유지(R6)
                    budget += com.evosim.core.FarmEconomy.capacity(
                            ownerEnt.getIndividual(), ownerEnt.getStage());
                }
                if (ownerEnt != null && home != null) {
                    // 배우자 노동 합산은 수확 권한(MimicFarmGoal.nearestWorkRipe: 수확자 spouseId==주인)과
                    // 대칭이어야 한다 — "주인을 배우자로 가리키는" 동거 성년만 계상. 다처에서 getSpouseId
                    // 는 비대칭(남편→본처, 첩→남편)이라 "주인의 배우자"로 세면 유령 용량이 재발한다:
                    // 남편 소유 밭은 첩들(각자 spouseId==남편·수확 가능) 누락 → 과다 게시, 아내 소유 밭은
                    // 수확 못 하는 남편(spouseId=본처) 계상 → 과소 게시·방치. 이 형태가 양쪽을 바로잡는다.
                    for (MimicEntity m : adults) {
                        // 유령 노동예산 제거(N2): 돌봄 구속 배우자는 실제 밭 수확 0(런1·런3 실측:
                        // farm_self = 주인 단독 8타일×G 정확 일치)인데 예산 8을 계상해 게시 문턱을
                        // 18타일로 부풀렸다. 장부를 실노동과 대칭화 — 문턱 10타일(첫 소작 1일 조기화).
                        if (m.getIndividual().id() != oid && m.getSpouseId() == oid
                                && home.equals(m.getHomePos()) && !m.isSatisfiedToday()
                                && !m.isCaregiverBound()) {
                            budget += com.evosim.core.FarmEconomy.capacity(
                                    m.getIndividual(), m.getStage());
                        }
                    }
                }
                java.util.List<FarmStore.Plot> plots = entry.getValue();
                // 직영지(소작 루프 v2): 주인 노동은 <b>최신 구획</b>부터 — 신규 개간 즉시 구 구획의
                // 자가 몫이 0이 되어 부족분 전량 게시 = 100% 소작 인계, 주인은 지대만 수취.
                plots.sort(java.util.Comparator
                        .comparingLong((FarmStore.Plot p) -> -p.id));
                int[] tiles = new int[plots.size()];
                for (int i = 0; i < plots.size(); i++) {
                    tiles[i] = plots.get(i).tiles.length;
                }
                int[] care = com.evosim.core.FarmEconomy.allocateCare(tiles, budget);
                for (int i = 0; i < plots.size(); i++) {
                    effCap.put(plots.get(i).id, care[i]);
                }
            }
        }
        // 노동시장 순번(집중 유도): 구획 순회를 규모(타일) 내림차순으로 — "일감 많고 임금 실적
        // 좋은 큰 밭에 먼저 줄 선다"의 산술화. 큰 밭 = 능력 지주(E·확장력의 산물)이므로 능력
        // 경사 그대로이고, 강제 없이 대지주 구획이 후보 풀을 우선 흡수한다(런6 실측: 무순서
        // 순회가 공급을 8구획에 1명씩 분산 → 왕조 의존 스케일 정체).
        java.util.List<FarmStore.Plot> market = new java.util.ArrayList<>(store.all().values());
        market.sort(java.util.Comparator
                .comparingInt((FarmStore.Plot p) -> -p.tiles.length)
                .thenComparingLong(p -> p.id));
        for (FarmStore.Plot plot : market) {
            int ownCap = effCap.getOrDefault(plot.id, 0);
            BlockPos ownerHome = ownerHomes.get(plot.ownerId);
            int need = com.evosim.core.FarmEconomy.shortfall(plot.tiles.length, ownCap);
            // 예약석: 상시 소작은 슬롯 산식과 무관하게 매일 우선 배정(고용 진동 차단 — 계획 허점 2).
            // 통근 초과 이주·구획 소멸이면 관계 해제(F: 소작농 이주 미정의 보완).
            // <b>예약석에도 정원이 있다.</b>
            //
            // 종전에는 상시 소작이면 밭 크기와 무관하게 <b>전원</b> 매일 앉혔다. 상시가 드물게
            // 생기던 시절에는 드러나지 않았지만, 승격이 흔해지자 12타일 밭에 수십 명이 달라붙는
            // 상태가 됐다(육안 관측). 그러면 ① 소작들이 익은 것을 순식간에 다 가져가 지주는
            // 수확할 것이 없고 ② 케어가 만석이라 관리 자리도 없어, 밭 주인이 제 밭에서
            // <b>아무것도 못 하는</b> 상태로 굳는다.
            //
            // 정원은 새로 만들지 않는다 — 바로 아래에서 쓰는 need(부족분)와 capacity(1인 하루
            // 수확량)를 그대로 쓴다. 부족분을 덮고 나면 더 앉혀도 산출이 늘지 않는다.
            // 넘치는 인원은 예약석에서 <b>풀어</b> 다른 밭으로 갈 수 있게 한다 — 붙들어 두면
            // 일감 없는 밭에 묶여 하루를 버린다.
            //
            // 근속이 긴 쪽을 남긴다(고용 진동 차단이라는 예약석의 원래 취지).
            java.util.List<MimicEntity> perm = new java.util.ArrayList<>();
            for (MimicEntity m : adults) {
                if (m.getTenantFarm() != plot.id) {
                    continue;
                }
                if (m.blockPosition().distSqr(plot.anchor) > DISSOLVE_DIST * DISSOLVE_DIST) {
                    m.setTenant(0L, 0);
                    com.evosim.mod.log.SimEvents.event(m, "소작해제", "원거리 이주(>128) — 관계 소멸");
                    continue;
                }
                if (store.ownedCount(m.getIndividual().id()) > 0) {
                    // 유령 상시 방어 정리 — 밭 주인이 된 상시는 명부에서 해제(기존 월드 소급 포함)
                    m.setTenant(0L, 0);
                    com.evosim.mod.log.SimEvents.event(m, "소작해제", "지주 전환 — 상시 명부 정리");
                    continue;
                }
                perm.add(m);
            }
            perm.sort(java.util.Comparator
                    .comparingInt((MimicEntity m) -> -m.getTenantStreak()) // 근속 긴 쪽 우선
                    .thenComparingInt(MimicEntity::getId));                // 동률 결정론
            int covered = 0;
            for (MimicEntity m : perm) {
                if (covered >= need) {
                    m.setTenant(0L, 0);
                    com.evosim.mod.log.SimEvents.event(m, "소작해제", String.format(
                            "구획 %d 정원 초과 — %d타일에 부족분 %d, 이미 %d 충당(내 자리 없음)",
                            plot.id, plot.tiles.length, need, covered));
                    continue;
                }
                ASSIGNED.put(m.getId(), plot.id);
                covered += com.evosim.core.FarmEconomy.capacity(m.getIndividual(), m.getStage());
            }
            if (need <= 0 || covered >= need) {
                continue;
            }
            final BlockPos oh = ownerHome;
            java.util.List<MimicEntity> cands = new java.util.ArrayList<>();
            for (MimicEntity m : adults) {
                // 노동시장 개방(소작 루프 v2): 빈곤 조건 삭제 — 소작 벌이가 잔존 채집을 압도해 넉넉한
                // 무밭 성인도 응한다. 만족자 제외(노동 정지 설계). 통근 거리 상한 삭제(B1 — 밭을
                // 사방에 펼침): 가까운순 정렬로 실제론 근거리부터 배정되고, F1 호위가 원거리 출근을
                // 실현한다. 안전판(아사 방지): 어제 이 밭에 배정됐으나 미도달(>COMMUTE)이면 하루 유예
                // (배정 소멸) — 채집으로 생계 후 재배정. 무한 원거리 강제통근 차단.
                boolean failedReach = LAST_ASSIGNED.getOrDefault(m.getId(), 0L) == plot.id
                        && m.blockPosition().distSqr(plot.anchor) > COMMUTE * COMMUTE;
                if (m.getIndividual().id() == plot.ownerId || ASSIGNED.containsKey(m.getId())
                        || (oh != null && oh.equals(m.getHomePos()))
                        || store.ownedCount(m.getIndividual().id()) > 0
                        || m.isSatisfiedToday()
                        || failedReach) {
                    continue;
                }
                cands.add(m);
            }
            // 연속성 선호(회차 17): 어제 이 밭에서 일한 사람이 같은 밭에 다시 줄 선다 — 전원 공통
            // 습관 규칙. 종전 (빈곤, 새벽 거리) 정렬은 거리가 전날 배회 종료 위치 추첨이고 어제
            // 노동자는 임금으로 빈곤 키에서도 밀려나 3일 연속 전원 교체(런12 실측: 스트릭 전부
            // 연속 1일) → PROMOTE_DAYS 도달 불가·상시 승격 구조적 봉쇄. 재고용 우선이 운 의존을
            // 제거하고 스트릭을 결정론화한다(만족·이주·지주 전환은 기존 필터로 자연 이탈).
            final long pid = plot.id;
            cands.sort(java.util.Comparator
                    .comparingInt((MimicEntity m) ->
                            LAST_ASSIGNED.getOrDefault(m.getId(), 0L) == pid ? 0 : 1) // 재고용 우선
                    .thenComparingInt(m -> m.larderComfortable() ? 1 : 0) // 빈곤 우선
                    .thenComparingDouble(m -> m.blockPosition().distSqr(plot.anchor))
                    .thenComparingInt(MimicEntity::getId)); // 동률 결정론
            for (MimicEntity m : cands) {
                if (covered >= need) {
                    break;
                }
                ASSIGNED.put(m.getId(), plot.id);
                covered += com.evosim.core.FarmEconomy.capacity(m.getIndividual(), m.getStage());
                // 연속 출근 카운터: 어제도 같은 밭이면 +1, 아니면 1 — PROMOTE_DAYS 도달 시 상시 승격
                int streak = LAST_ASSIGNED.getOrDefault(m.getId(), 0L) == plot.id
                        ? m.getTenantStreak() + 1 : 1;
                // 일용 배정도 기록(배정 확정 후) — 승격 전 이력(연속 1→2)과 관성 단절(streak 1 회귀)을
                // 로그만으로 재구성 가능하게. 예약석 재배정은 안정 상태라 기록하지 않음(스팸 방지).
                FamilyLedger.Rec ownerRec = FamilyLedger.get(level).get(plot.ownerId);
                com.evosim.mod.log.SimEvents.event(m, "배정", String.format(
                        "구획 %d(지주 %s) 일용(연속 %d일)", plot.id,
                        ownerRec != null && ownerRec.name != null ? ownerRec.name
                                : (plot.ownerId == 0 ? "무주지" : "?"), streak));
                if (m.getTenantFarm() == 0L) {
                    if (streak >= promoteDays(m)) {
                        m.setTenant(plot.id, streak);
                        com.evosim.mod.log.SimEvents.event(m, "상시소작", String.format(
                                "%d일 연속 출근 — 구획 %d 예약석 승격", streak, plot.id));
                    } else {
                        m.setTenant(0L, streak);
                    }
                }
            }
        }
        // ── 마름 유지·임명(케이스 1, v1.3) — 배정과 독립 순회(부족분 0 구획도 임명 대상). ──
        for (FarmStore.Plot plot : market) {
            if (plot.ownerId == 0L) {
                continue;
            }
            // 겸직 정리(안전망): 마름이 밭 소유자가 되면 사임 → 같은 틱 승계(P1 확장)
            if (plot.stewardId != 0L && store.ownedCount(plot.stewardId) > 0) {
                store.stewardGone(level, plot.stewardId, "지주 전환");
            }
            if (plot.stewardId != 0L) {
                continue;
            }
            int perm = 0;
            for (MimicEntity m : adults) {
                if (m.getTenantFarm() == plot.id) {
                    perm++;
                }
            }
            // 최초 문턱 상시 2명, 마름 운영 이력 구획은 1명(공석 즉시 충원 — 칭호 무붕괴 v1.1)
            if (perm >= (plot.stewarded ? 1
                    : com.evosim.core.FarmEconomy.STEWARD_APPOINT_TENANTS)) {
                MimicEntity cand = store.successorFor(level, plot);
                // 조기 임명(회차 S2) — 상시 2명이면 즉시 임명(영주 사다리 조기화). 무능 마름의 밭
                // 붕괴는 게이트가 아니라 plotEfficiency 바닥값(지주 오버사이트)이 막는다.
                if (cand != null) {
                    store.appointSteward(level, plot, cand, "마름임명");
                    ASSIGNED.remove(cand.getId()); // 소작 배정 해방 — 노동/관리 모드 판정 정합
                }
            }
        }
    }

    /** 지주 가구 하루소모 — 본인 + 배우자(회차 21: 동거 성인 자녀 제외) + 동거 유아·소년.
     *  착공 famNeed 스캔과 동일 규칙(회차 25: 확장 예비를 착공 임계와 단일 산식으로 묶는 입력). */
    private static double familyDailyNeed(ServerLevel level, MimicEntity owner,
                                          java.util.List<MimicEntity> adults) {
        if (owner == null || owner.getIndividual() == null || owner.getHomePos() == null) {
            return 6.0; // 방어 기본값(부부 소모)
        }
        double need = com.evosim.core.FoodEconomy.consumptionPerDay(
                owner.getStage(), com.evosim.core.Activity.MOVE, owner.getIndividual(), false);
        for (MimicEntity h : adults) {
            if (h != owner && h.getHomePos() != null && h.getHomePos().equals(owner.getHomePos())
                    && (h.getSpouseId() == owner.getIndividual().id()
                            || owner.getSpouseId() == h.getIndividual().id())) {
                need += com.evosim.core.FoodEconomy.consumptionPerDay(
                        h.getStage(), com.evosim.core.Activity.MOVE, h.getIndividual(), false);
            }
        }
        for (MimicEntity c : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null
                        && owner.getHomePos().equals(e.getHomePos())
                        && (e.getStage() == com.evosim.core.LifeStage.INFANT
                                || e.getStage() == com.evosim.core.LifeStage.BOY))) {
            need += com.evosim.core.FoodEconomy.consumptionPerDay(
                    c.getStage(), com.evosim.core.Activity.MOVE, c.getIndividual(), false);
        }
        return need;
    }

    /**
     * 위기 구휼(봉건 쌍무 — P2) — 200틱 스캔: 위급(H&lt;0.3) 상시 소작농을 영주 저장고에서 구제.
     * FEE 0.6으로 소작 임금(2.4/일)이 소비(3.0)를 밑돌아 발생하는 만성 부족을 메워 <b>생존은
     * 시키되 축적은 못 하게</b>(독립 차단·소작 고착). 이체 = min(2, 저장고−예비6)로 영주 예비를
     * 보호. 영주가 예비조차 없으면(몰락) 이행 불가 → 관계 해제(지대↔보호의 쌍무성).
     */
    private static void protectTenants(ServerLevel level) {
        for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null && e.getTenantFarm() != 0L)) {
            if (!m.isCritical()) {
                continue;
            }
            FarmStore.Plot plot = FarmStore.get(level).get(m.getTenantFarm());
            if (plot == null) {
                m.setTenant(0L, 0); // 구획 소멸 — 관계 정리
                continue;
            }
            BlockPos home = null;
            for (MimicEntity o : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                    e -> e.isAlive() && e.getIndividual() != null
                            && e.getIndividual().id() == plot.ownerId)) {
                home = o.getHomePos();
            }
            double larder = home == null ? 0.0 : LarderStore.get(level).get(home);
            double aid = Math.min(2.0, larder - com.evosim.core.FarmEconomy.INVEST_RESERVE);
            if (home != null && aid >= 1.0) {
                int units = (int) Math.floor(aid); // 정수 유닛(L 정수성)
                LarderStore.get(level).set(home, larder - units);
                m.addHarvest(units);
                com.evosim.mod.log.SimAudit.record(com.evosim.mod.log.SimAudit.Src.AID, aid);
                com.evosim.mod.log.SimEvents.event(m, "구휼", String.format(
                        "영주 저장고 %d 나눔 — H %.2f (구획 %d)", units, m.getHolding(), plot.id));
                // 신세 — 위급할 때 무상으로 받은 것. 갚을 필요는 없지만 추종 점수는 된다.
                AllegianceStore.get(level).record(m.getIndividual().id(), plot.ownerId,
                        units * AllegianceStore.W_RELIEF, 0.0,
                        com.evosim.mod.entity.SimTime.tick(level) / 24000L);
            } else {
                m.setTenant(0L, 0);
                com.evosim.mod.log.SimEvents.event(m, "소작해제", "영주 구휼 불이행(몰락) — 관계 소멸");
            }
        }
    }

    /**
     * 위기 긴급 고용(봉건 쌍무의 입구 쪽) — 굶어 죽어가는 무밭 성년을 <b>다음 새벽까지 기다리지
     * 않고</b> 그 자리에서 배정한다.
     *
     * <p>종전 안전망은 {@link #protectTenants}(위기 구휼) 하나뿐인데 그것은 <b>이미 상시 소작인</b>
     * 사람만 본다. 아직 아무 밭에도 속하지 않은 무밭 성년은 배정이 하루 한 번({@link #assignDawn},
     * tod 1000~9000)뿐이라 낮에 위기에 빠지면 다음 새벽까지 버텨야 했다 — 들풀이 마른 뒤로는
     * 그 사이에 죽는다(실측: 소작이 못 되고 아사).
     *
     * <p>배정 조건은 새벽 시장과 같은 산술이다: 그 구획의 <b>타일 수가 오늘 배정된 노동
     * 용량(C_BASE×(1+인원))을 넘으면</b> 아직 걷지 못하는 일감이 남아 있다는 뜻이므로 자리를 준다.
     * 즉 없는 일자리를 만들어 주는 것이 아니라, 이미 게시돼 있으나 새벽에 못 채운 자리를 즉시
     * 채우는 것이다. 통근 한계(COMMUTE)와 지주·기존 소작 제외도 시장과 동일하다.
     *
     * <p>배정은 {@code ASSIGNED} 에 얹히므로 다음 새벽 시장이 정상 재계산하고, 연속 출근으로
     * 이어지면 {@code PROMOTE_DAYS} 를 거쳐 상시 소작으로 승격된다 — 응급 처치가 그대로 신분
     * 상승 경로에 접속한다.
     */
    /**
     * 지금 이 구획에서 딸 수 있는(AGE 3) 타일 수 — 긴급 배정은 <b>실제로 먹을 것이 있는</b> 밭에만
     * 붙인다. 익은 타일이 0인 밭에 보내면 도착해도 표적이 없어 goal 이 꺼지고, 리시가 거처로
     * 되끌고, 한 칸 익으면 다시 출발하는 왕복만 반복하다 굶는다.
     */
    private static int ripeTiles(ServerLevel level, FarmStore.Plot p) {
        int n = 0;
        for (long l : p.tiles) {
            BlockPos pos = BlockPos.of(l);
            if (!level.isLoaded(pos)) {
                continue;
            }
            var st = level.getBlockState(pos);
            if (st.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH)
                    && st.getValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE) >= 3) {
                n++;
            }
        }
        return n;
    }

    /**
     * 점검용 — 긴급고용·구걸 정산을 <b>지금</b> 한 번 돈다(/evosim begdry).
     *
     * <p>다른 코드가 아니라 {@link #emergencyHire} 그 자체다. 200틱 스캔을 기다리면 그 사이
     * 개체가 풀 한 포기를 뜯어 관문이 닫히는 <b>경합</b>이 생겨, 시험이 발동하고 안 하고가
     * 운에 매달린다. 조건을 세운 그 틱에 바로 돌리면 그 운이 사라진다.
     */
    public static void emergencyHireNow(ServerLevel level) {
        emergencyHire(level);
    }

    private static void emergencyHire(ServerLevel level) {
        FarmStore store = FarmStore.get(level);
        // 시혜 장부는 밭이 없어도 비워야 한다 — 밭 없는 세계에서도 구걸은 돈다.
        long today = com.evosim.mod.entity.SimTime.tick(level) / 24000L;
        if (today != almsDay) {
            almsDay = today;
            ALMS_GIVEN.clear();
            BEGGED_TODAY.clear();
            // <b>마감이 지난 것만</b> 지운다. 날이 바뀌었다고 일괄로 지우면 이틀짜리 여행
            // (BEG_TRAVEL)을 첫날 밤에 끊어 버려, 원거리 구걸을 못 하게 만든 그 결함이
            // 그대로 되살아난다. 마감이 남았으면 가던 길을 계속 간다 — 구혼 여행과 같다.
            if (begOn) {
                for (MimicEntity e : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                        x -> x.isAlive() && x.getBegHome() != null && !x.isBegging())) {
                    e.clearBeg(); // 만료 — 다음 정산에서 오늘 형편으로 다시 고른다
                }
            }
        }
        // 밭 일자리를 얻은 사람은 구걸을 접는다 — 둘을 동시에 들면 앵커가 밭과 남의 집을
        // 오가며 정확히 그 "목표가 계속 바뀌며 움찔거리는" 모양이 된다.
        if (begOn) {
            for (MimicEntity e : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                    x -> x.isAlive() && x.getBegHome() != null
                            && ASSIGNED.containsKey(x.getId()))) {
                e.clearBeg();
            }
        }
        if (store.all().isEmpty()) {
            return;
        }
        // 발동 조건은 위급(H 고갈)뿐 아니라 <b>채집 시계가 마른 것</b>도 포함한다. 위급은 이미 늦은
        // 신호다 — 들풀이 사라진 자리에서 H가 바닥날 때까지 기다리면 걸어갈 기력도 남지 않는다.
        // forageDry()는 기근 판정이 "주변에 먹을 게 없다"의 증거로 쓰는 그 시계(Famine.STARVE_WINDOW,
        // 1게임일)를 그대로 본다 — 새 문턱을 만들지 않는다. 밭이 멀어 못 찾고 방치되던 개체가
        // 굶기 <b>전에</b> 일자리로 붙는다.
        for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null
                        && (e.isCritical() || e.forageDry())
                        && e.getTenantFarm() == 0L
                        && (e.getStage() == com.evosim.core.LifeStage.ADULT
                                || e.getStage() == com.evosim.core.LifeStage.ELDER))) {
            if (ASSIGNED.containsKey(m.getId())
                    || store.ownedCount(m.getIndividual().id()) > 0) {
                continue; // 이미 오늘 일감이 있거나, 제 밭을 가진 지주
            }
            // <b>가는 중인 사람의 목적지를 다시 고르지 않는다.</b> 이 정산은 200틱마다 도는데,
            // 여기서 매번 다시 고르면 후보 저장고가 출렁일 때마다 목표가 갈려 길 위에서 방향만
            // 튼다 — 목적지를 밖에서 못박은 의미가 통째로 사라진다. 앵커는 수령·허탕(receiveAlms)
            // 이나 하루 만료로만 풀린다.
            if (m.getBegHome() != null) {
                continue;
            }
            // 탐색은 <b>거리 무제한</b>(가까운 순) — 통근 한계 COMMUTE(48)는 평시 시장의 효율
            // 기준이지 생사의 기준이 아니다. 반경을 걸면 근처에 밭이 없다는 이유만으로 죽는데,
            // 미믹은 하루 안에 수백 블록을 걷고 출근 앵커(MimicFarmGoal 의 setWorkAnchor)가 리시를
            // 눌러 원거리 출근을 실현한다. 멀어도 배정하는 편이 확실한 아사보다 낫다.
            //
            // 일감 조건도 2단계로 완화한다: ① 남는 일감이 있는 밭을 우선하고, ② 전부 인원이 찼으면
            // 가장 가까운 밭에라도 붙인다. 초과 배정은 회계를 깨지 않는다 — 소작 수취는 자기가
            // 딴 타일만큼이고(tenantShare), 관리 효율의 실경작 타일은 min(타일, 노동)으로 캡된다.
            FarmStore.Plot open = null;
            FarmStore.Plot any = null;
            double od = Double.MAX_VALUE;
            double ad = Double.MAX_VALUE;
            for (FarmStore.Plot p : store.all().values()) {
                if (p.ownerId == 0L) {
                    continue; // 무주지 — 지대 관계가 성립하지 않는다
                }
                if (UNREACHABLE.getOrDefault(m.getId(), java.util.Set.of()).contains(p.id)) {
                    continue; // 오늘 도달 실패한 밭 — 다시 붙이면 같은 자리에 또 선다
                }
                if (ripeTiles(level, p) <= 0) {
                    continue; // 지금 딸 게 없는 밭 — 보내 봐야 헛걸음이다(아래 주석)
                }
                // <b>정원초과에도 물리적 상한은 있다.</b> 종전에는 초과 경로(any)에 아무 제한이
                // 없어 6타일 밭에 5명이 붙었다(육안 관측). 원인은 위 '여유 있는 밭' 조건이
                // 타일 > C_BASE(8) × (1+인원) 이라는 것이다 — 9타일 미만 밭은 인원이 0이어도
                // 영영 만석으로 판정되어 <b>항상</b> 초과 경로로 빠진다. 한 사람 몫의 최소가
                // MIN_JOB(2)타일이므로 그 이상 붙어 봐야 같은 타일을 두고 서로 비빌 뿐이다.
                if (hireCap && assignedToPlot(p.id)
                        >= Math.max(1, p.tiles.length / com.evosim.core.FarmEconomy.MIN_JOB)) {
                    continue;
                }
                double d = m.blockPosition().distSqr(p.anchor);
                if (d < ad) {
                    ad = d;
                    any = p;
                }
                if (p.tiles.length > com.evosim.core.FarmEconomy.C_BASE
                        * (1 + assignedToPlot(p.id)) && d < od) {
                    od = d;
                    open = p;
                }
            }
            // <b>여기가 천민이 갈라져 나오는 곳이다.</b> open == null 은 "오늘 익은 밭 어디에도
            // 내 자리가 없다"는 뜻이고, 종전에는 그런 사람을 꽉 찬 밭에 밀어 넣었다(d11 실측
            // 71/84건). 그 배정은 아래 승격 조건(open != null)에 막혀 <b>영영 일용</b>이고, 남의
            // 익은 칸을 나눠 먹어 기존 소작의 몫만 얇게 만들었다. 밭이 요청한 인원이 아니었다.
            //
            // 그 잉여를 밭 대신 남의 문간으로 돌린다. 밭 노동은 한 명도 안 준다(요청분은 위
            // 정상 배정이 이미 채웠다). 대신 매일 쌓이는 시혜 신세가 이 사람을 종속으로 끌고
            // 간다 — 하루짜리 고용 신세(1회성 W_HIRE)로는 결코 못 가던 자리다.
            if (open == null && begOn && assignBeg(level, m)) {
                continue;
            }
            FarmStore.Plot best = open != null ? open : any;
            if (best != null) {
                ASSIGNED.put(m.getId(), best.id);
                // <b>긴급으로 왔어도 출근은 출근이다</b> — 연속 카운터를 정상 배정과 똑같이 쌓는다.
                //
                // 종전에는 여기서 ASSIGNED 만 넣고 끝냈다. 그러면 위급으로 온 사람은 같은 밭에
                // 며칠을 나와도 streak 이 0에 머물러 <b>영원히 일용</b>이고 상시소작이 되지
                // 못한다. 실측(d7): 긴급고용 24건 · 정상 배정 4건 · 전부 "연속 1일" · 상시소작 0.
                //
                // 그 하나가 사슬 전체를 막고 있었다. 상시소작이 0이면 밭이 "자영"으로 판정되어
                // (nTen==0) 확장비가 밭 계정이 아니라 <b>지주 저장고</b>에서 나가고, 그래서 지주
                // 저장고가 0~27 을 오가며 한 푼도 쌓이지 않는다. 그 결과 막사·교회·학교가 전부
                // "저장고 20 < 문턱 48/66/76"으로 영영 보류된다 — 군인을 얹을 토대 자체가
                // 서지 않는다.
                //
                // 단, <b>정원초과로 밀어 넣은 배정은 승격시키지 않는다</b>(open != null 일 때만).
                // 초과 경로의 유일한 상한은 타일/MIN_JOB(2)인데, 그것은 "같은 칸을 두고 몸으로
                // 비비지는 말자"는 물리적 방어선이지 적정 인력이 아니다 — 하루 붙였다 떼는
                // 임시 배정이라 문제가 안 됐다. 거기에 승격을 달면 임시 상한이 그대로 상시
                // 정원이 된다: 10타일 밭에 상시 5명(실측 스크린샷 — 소작 기여 40%로 얇아짐).
                // 같은 밭의 "여유" 기준은 바로 위에 이미 있다 — 타일 > C_BASE(8)×(1+인원).
                // 10타일이면 첫 한 명만 그 조건을 통과한다.
                if (open != null && m.getTenantFarm() == 0L) {
                    int streak = LAST_ASSIGNED.getOrDefault(m.getId(), 0L) == best.id
                            ? m.getTenantStreak() + 1 : 1;
                    if (streak >= promoteDays(m)) {
                        m.setTenant(best.id, streak);
                        com.evosim.mod.log.SimEvents.event(m, "상시소작", String.format(
                                "%d일 연속 출근(긴급 경유) — 구획 %d 예약석 승격", streak, best.id));
                    } else {
                        m.setTenant(0L, streak);
                    }
                }
                // 신세 — 굶던 자에게 일자리를 준 것. 1회성이지만 무겁다.
                AllegianceStore.get(level).record(m.getIndividual().id(), best.ownerId,
                        AllegianceStore.W_HIRE * AllegianceStore.rapport(m.getIndividual()), 0.0,
                        com.evosim.mod.entity.SimTime.tick(level) / 24000L);
                com.evosim.mod.log.SimEvents.event(m, "긴급고용", String.format(
                        "위급(H %.2f) — 구획 %d 즉시 배정(%d타일 · 오늘 %d명 · %.0f블록%s)",
                        m.getHolding(), best.id, best.tiles.length, assignedToPlot(best.id),
                        Math.sqrt(open != null ? od : ad), open != null ? "" : " · 정원초과"));
            }
        }
    }

    /**
     * <b>오늘의 구걸 목적지를 정한다.</b> 하루 한 번, 여기서만. goal 은 이 결과를 걷기만 한다.
     *
     * <p>점수는 {@code (저장고 − 확장예비) ÷ (거리 + K)} 이고 아는 집이면 가산이 붙는다.
     * 나눗셈이라 거리는 <b>순위를 낮출 뿐 후보를 지우지 않는다</b> — 근처에 여유 있는 집이
     * 하나도 없으면 먼 집이라도 간다. 반경으로 자르면 "주변이 다 가난하다"는 이유 하나로
     * 죽는데, 그것이야말로 구걸이 막으려는 상황이다.
     *
     * <p>빼는 것이 {@link FarmEconomy#INVEST_RESERVE} 인 것은 시혜가 <b>확장 자금을 갉지
     * 않게</b> 하기 위해서다 — 갉으면 지주가 남 먹이느라 밭을 못 넓히는 역전이 생긴다.
     *
     * @return 목적지를 잡았으면 true(그러면 호출부는 밭 배정을 건너뛴다)
     */
    private static boolean assignBeg(ServerLevel level, MimicEntity m) {
        if (m.getIndividual() == null || BEGGED_TODAY.contains(m.getId())) {
            return false; // 오늘 몫은 끝 — 호출부의 종전 경로로 떨어진다(굶어 죽게 두지는 않는다)
        }
        long me = m.getIndividual().id();
        BlockPos myHome = m.getHomePos();
        LarderStore larders = LarderStore.get(level);
        AllegianceStore ledger = AllegianceStore.get(level);

        // 가구 대표 뽑기 — 제공자 우선, 동률이면 낮은 id(결정론). 대표가 신세의 상대가 된다.
        java.util.Map<Long, BlockPos> homes = new java.util.HashMap<>();
        java.util.Map<Long, long[]> head = new java.util.HashMap<>(); // home → {제공자?1:0, id}
        for (MimicEntity o : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null && e.getHomePos() != null
                        && (e.getStage() == com.evosim.core.LifeStage.ADULT
                                || e.getStage() == com.evosim.core.LifeStage.ELDER))) {
            BlockPos h = o.getHomePos();
            if (myHome != null && myHome.equals(h)) {
                continue; // 제 집에 구걸하지 않는다
            }
            long k = h.asLong();
            homes.putIfAbsent(k, h);
            long id = o.getIndividual().id();
            long prov = o.isProviderRole() ? 1L : 0L;
            long[] cur = head.get(k);
            if (cur == null || prov > cur[0] || (prov == cur[0] && id < cur[1])) {
                head.put(k, new long[] {prov, id});
            }
        }

        BlockPos bestHome = null;
        long bestPatron = 0L;
        double bestScore = 0.0;
        double bestSurplus = 0.0;
        for (var e : homes.entrySet()) {
            BlockPos h = e.getValue();
            if (ALMS_GIVEN.getOrDefault(h.asLong(), 0) >= ALMS_HOME_CAP) {
                continue; // 오늘 이 집은 할 만큼 했다
            }
            double surplus = larders.get(h) - FarmEconomy.INVEST_RESERVE;
            if (surplus < ALMS_UNIT) {
                continue; // 제 앞가림도 빠듯한 집 — 여기 손 벌려 봐야 서로 굶는다
            }
            long[] hd = head.get(h.asLong());
            if (hd == null || hd[1] == me) {
                continue;
            }
            double d = Math.sqrt(m.blockPosition().distSqr(h));
            double score = surplus / (d + BEG_DIST_K);
            if (ledger.bondTo(me, hd[1]) > 0.0) {
                score *= BEG_KNOWN_BONUS;
            }
            // 동률은 거처 좌표로 가른다 — HashMap 순회 순서에 결과가 매달리지 않게.
            if (score > bestScore + 1e-9
                    || (Math.abs(score - bestScore) <= 1e-9 && bestHome != null
                            && h.asLong() < bestHome.asLong())) {
                bestScore = score;
                bestHome = h;
                bestPatron = hd[1];
                bestSurplus = surplus;
            }
        }
        if (bestHome == null) {
            return false; // 마을에 내줄 집이 없다 — 호출부의 종전 경로(초과 배정)로 떨어진다
        }
        BEGGED_TODAY.add(m.getId());
        m.setBegTarget(bestHome, bestPatron,
                com.evosim.mod.entity.SimTime.tick(level) + BEG_TRAVEL);
        var fl = FamilyLedger.get(level);
        var pf = fl.get(bestPatron);
        com.evosim.mod.log.SimEvents.event(m, "구걸출발", String.format(
                "일자리 없음(H %.2f) — %s 의 집 @%d,%d 로(%.0f블록 · 여유 %.1f)",
                m.getHolding(), pf != null && pf.name != null ? pf.name : "#" + bestPatron,
                bestHome.getX(), bestHome.getZ(),
                Math.sqrt(m.blockPosition().distSqr(bestHome)), bestSurplus));
        return true;
    }

    /**
     * <b>문간에서 받는다</b> — {@link MimicBegGoal} 이 도착하면 부른다.
     *
     * <p>성패와 무관하게 오늘 구걸은 여기서 끝난다({@code clearBeg}). 허탕이어도 다시 고르지
     * 않는 이유: 재선택을 허용하면 "가 보니 비었다 → 다음 집 → 또 비었다" 로 하루 종일 마을을
     * 돌며 움찔거린다. 다음 기회는 내일 새벽이다.
     */
    public static void receiveAlms(ServerLevel level, MimicEntity m) {
        BlockPos h = m.getBegHome();
        long patron = m.getBegPatron();
        m.clearBeg();
        if (h == null || m.getIndividual() == null) {
            return;
        }
        LarderStore larders = LarderStore.get(level);
        int given = ALMS_GIVEN.getOrDefault(h.asLong(), 0);
        double room = larders.get(h) - FarmEconomy.INVEST_RESERVE;
        double units = Math.min(ALMS_UNIT, Math.min(room, ALMS_HOME_CAP - given));
        var fl = FamilyLedger.get(level);
        var pf = fl.get(patron);
        String who = pf != null && pf.name != null ? pf.name : "#" + patron;
        if (units < ALMS_UNIT) {
            com.evosim.mod.log.SimEvents.event(m, "구걸", String.format(
                    "%s 의 집에서 허탕 — 여유 %.1f · 오늘 이미 %d 유닛 나감", who, room, given));
            return;
        }
        larders.set(h, larders.get(h) - units);
        m.setDayHarvest(m.getHolding() + units);
        ALMS_GIVEN.merge(h.asLong(), (int) Math.ceil(units), Integer::sum);
        long day = com.evosim.mod.entity.SimTime.tick(level) / 24000L;
        AllegianceStore ledger = AllegianceStore.get(level);
        double before = ledger.bondTo(m.getIndividual().id(), patron);
        // 구휼 가중치를 그대로 쓴다 — 굶는 자에게 먹을 것을 준 것이라 물건이 같다. 새 상수를
        // 만들면 같은 행위가 두 이름으로 갈려 균형점 계산이 두 벌이 된다.
        ledger.record(m.getIndividual().id(), patron,
                AllegianceStore.W_RELIEF * units
                        * AllegianceStore.rapport(m.getIndividual()), 0.0, day);
        double after = ledger.bondTo(m.getIndividual().id(), patron);
        com.evosim.mod.log.SimEvents.event(m, "구걸", String.format(
                "%s 에게 %.1f 받음 — 신세 %.1f→%.1f(적립 +%.2f · 체감 후) · H %.2f",
                who, units, before, after, after - before, m.getHolding()));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) {
            return;
        }
        ServerLevel level = event.getServer().overworld();
        if (com.evosim.mod.entity.SimTime.tick(level) % SCAN_INTERVAL != 0) {
            return;
        }
        assignDawn(level);
        growFarms(level); // 재투자(계정 차감)가 지대 이체보다 먼저 — 같은 밤, 남은 정수만 주인에게(R1)
        settleRent(level);
        protectTenants(level);
        emergencyHire(level); // 구휼(기존 소작) 다음 — 아직 소작이 아닌 위급자의 입구
        expireVacant(level);
        // ── 작물 관리 가속 ── 관리 중인 개체의 케어범위 합이 구획을 덮는 비율만큼, 미익은 타일의
        // 심은시각을 뒤로 당긴다(= 진행 가속). 시각을 옮기는 방식이라 세이브 형식이 그대로다.
        long nowTick = com.evosim.mod.entity.SimTime.tick(level);
        TENDING.entrySet().removeIf(e -> nowTick - e.getValue()[1] > SCAN_INTERVAL);
        java.util.Map<Long, Double> coveredBy = new java.util.HashMap<>();
        for (long[] v : TENDING.values()) {
            coveredBy.merge(v[0], v[2] / 100.0, Double::sum);
        }
        for (FarmStore.Plot p : FarmStore.get(level).all().values()) {
            double covered = coveredBy.getOrDefault(p.id, 0.0);
            if (covered <= 0.0 || p.tiles.length == 0) {
                continue;
            }
            double coverage = Math.min(1.0, covered / p.tiles.length);
            long bonus = (long) (SCAN_INTERVAL * FarmEconomy.CARE_MAX_BOOST * coverage);
            if (bonus <= 0) {
                continue;
            }
            p.careBonus += bonus; // 익음은 가상 시각(지금 + careBonus)으로 판정 — Plot.careBonus 주석
            FarmStore.get(level).setDirty();
        }
        for (FarmStore.Plot p : FarmStore.get(level).all().values()) {
            for (int i = 0; i < p.tiles.length; i++) {
                if (p.planted[i] < 0 || FarmStore.careNow(level, p) - p.planted[i] < FarmEconomy.RIPEN_TICKS) {
                    continue;
                }
                BlockPos pos = BlockPos.of(p.tiles[i]);
                if (!level.isLoaded(pos)) {
                    continue;
                }
                var st = level.getBlockState(pos);
                if (st.is(Blocks.SWEET_BERRY_BUSH) && st.getValue(SweetBerryBushBlock.AGE) < 3) {
                    level.setBlockAndUpdate(pos, st.setValue(SweetBerryBushBlock.AGE, 3));
                }
            }
            // 발자국 정비 — 다섯 스캔에 한 번(1000틱). layLogs 는 착공·단계승격 때만 돌아서,
            // 그 사이에 생긴 빈 칸(지형 붕괴·밟힘, 그리고 <b>이미 파인 채로 저장된 구세계</b>)은
            // 다음 승격까지 구덩이로 남는다. 멱등한 작업이라 반복해도 값이 변하지 않는다.
            //
            // <b>구세계 구획(beds==0)은 절대 건드리지 않는다.</b> 옛 밭은 발자국을 모르므로
            // footprint(0, rows) 가 폭 1짜리 엉뚱한 사각형이 되고, 한 번도 채워진 적 없는
            // fx/fz 좌표에 원목을 박는다 — 멀쩡한 옛 밭을 부순다. growFarms·reserveNext 가
            // 이미 같은 이유로 걸러 내고 있었는데 여기만 빠져 있었다.
            if (nowTick % 1000L == 0L && p.beds > 0) {
                layLogs(level, p);
            }
            refreshLists(level, p); // 익힌 직후에 목록을 다시 만든다 — 순서가 뒤바뀌면 한 스캔 늦는다
        }
    }

    /**
     * <b>밭이 무엇이 익었는지 스스로 세어 둔다</b> — 수확·관리의 단일 출처.
     *
     * <p>익음의 정의는 하나다: <b>블록에 열매가 달렸는가</b>. 실제로 딸 수 있고 눈에 보이는
     * 그것이다. 장부 시계(planted + careBonus)는 판정이 아니라 <b>익히는 장치</b>로만 남는다 —
     * 위 순회가 장부를 보고 블록을 익힌다. 그래서 바닐라가 먼저 익혀도 모순이 없다: 익었으면
     * 익은 목록에 들어가고, 딸 수 있고, 관리 대상이 아니다.
     */
    static void refreshLists(ServerLevel level, FarmStore.Plot p) {
        long[] ripe = new long[p.tiles.length];
        long[] unripe = new long[p.tiles.length];
        int r = 0;
        int u = 0;
        for (int i = 0; i < p.tiles.length; i++) {
            if (p.planted[i] < 0) {
                continue; // 미설치 — 어느 목록에도 없다
            }
            BlockPos pos = BlockPos.of(p.tiles[i]);
            if (!level.isLoaded(pos)) {
                continue;
            }
            var st = level.getBlockState(pos);
            if (!st.is(Blocks.SWEET_BERRY_BUSH)) {
                continue; // 덤불이 아니다(밟혀 사라짐 등) — 다음 스캔이 다시 심는다
            }
            if (st.getValue(SweetBerryBushBlock.AGE) >= 3) {
                ripe[r++] = p.tiles[i];
            } else {
                unripe[u++] = p.tiles[i];
            }
        }
        p.ripe = java.util.Arrays.copyOf(ripe, r);
        p.unripe = java.util.Arrays.copyOf(unripe, u);
        p.listTick = com.evosim.mod.entity.SimTime.tick(level);
    }

    /**
     * 한 타일이 방금 수확됐음을 목록에 즉시 반영한다.
     *
     * <p>스캔(200틱)을 기다리면 방금 딴 타일이 최대 10초간 "익은 목록"에 남아, 미믹이 같은
     * 자리를 계속 노린다 — 제자리 움찔이 된다.
     */
    static void markHarvested(ServerLevel level, FarmStore.Plot p, long tile) {
        int idx = -1;
        for (int i = 0; i < p.ripe.length; i++) {
            if (p.ripe[i] == tile) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return;
        }
        long[] nr = new long[p.ripe.length - 1];
        System.arraycopy(p.ripe, 0, nr, 0, idx);
        System.arraycopy(p.ripe, idx + 1, nr, idx, p.ripe.length - idx - 1);
        p.ripe = nr;
        long[] nu = java.util.Arrays.copyOf(p.unripe, p.unripe.length + 1);
        nu[p.unripe.length] = tile;
        p.unripe = nu;
    }
}
