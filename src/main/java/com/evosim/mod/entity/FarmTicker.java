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
                    double wage = Math.min(plot.account,
                            paid / workers * com.evosim.core.FarmEconomy.stewardWageMult(g, tenure));
                    int wUnits = (int) Math.floor(wage);
                    if (wUnits >= 1) {
                        larder.set(stwEnt.getHomePos(), larder.get(stwEnt.getHomePos()) + wUnits);
                        plot.account -= wUnits;
                        store.setDirty();
                        com.evosim.mod.log.SimAudit.record(
                                com.evosim.mod.log.SimAudit.Src.WAGE, wUnits);
                        com.evosim.mod.log.SimEvents.event(stwEnt, "수당", String.format(
                                "구획 %d 마름 수당 +%d (평균 %.2f × 계수 %.2f · 근속 %d일)",
                                plot.id, wUnits, paid / workers,
                                com.evosim.core.FarmEconomy.stewardWageMult(g, tenure), tenure));
                    }
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
            // 소작 수에 비례, 자금은 소작 밭이면 plot.account 만 쓴다(아래 nTen==0 분기 — 지주
            // 저장고를 건드리지 않는다). 바로 위 주석조차 "소작농의 만족은 무관"이라고 적는다.
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
            // 자금(fee 분할 E11 ②③ + 부트스트랩 예외): 소작 밭(nTen>0, 계정 소득 있음)은 <b>계정만</b>
            // 으로 — 성숙 지주의 저장고 축장을 확장이 못 갉게 격리(누수 B 차단, 격차 생전 지속).
            // 자영 밭(nTen==0)은 계정이 0이라(주인 수확은 본인 몫) 저장고가 유일 연료 — 9→24 성숙
            // 부트스트랩을 저장고로 굴린다(격리하면 신생 밭이 영구 동결). 자영 밭은 초과분 축장이
            // 없으므로 저장고를 써도 격차에 무관.
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
                    plot.account * com.evosim.core.FarmEconomy.MATURE_REINVEST_SHARE);
            double ownerFunds = 0.0;
            if (nTen == 0) { // 부트스트랩(자영) — 저장고 예비 위 잉여를 폴백 재원으로
                ownerFunds = ownerEnt.getHomePos() != null
                        ? larders.get(ownerEnt.getHomePos()) : 0.0;
                boolean eligible = nextFarmEligible(store, adults, plot.ownerId);
                double reserve = com.evosim.core.FarmEconomy.expandReserve(
                        eligible, store.ownedCount(plot.ownerId),
                        familyDailyNeed(level, ownerEnt, adults));
                afford += (int) Math.floor(Math.max(0.0, ownerFunds - reserve)
                        / com.evosim.core.FarmEconomy.EXPAND_COST);
            }
            int k = Math.min(room, afford);
            if (k <= 0) {
                continue;
            }
            // <b>점유 집합</b> 기준으로 이상 수열을 훑는다 — 개수 색인이 아니다.
            //
            // 종전에는 {@code layout(tiles.length + k)} 의 <b>i번째</b>부터 놓았다. 그러면 칸
            // 하나가 막혀 건너뛰는 순간 개수와 실제 배치가 어긋나고, 다음 날은 이미 틀어진 개수를
            // 기준으로 또 다른 칸을 계산해 구획이 이상 직사각형에서 영구히 표류한다. 게다가 막힌
            // 칸은 {@code continue} 로 <b>그냥 버려져</b> 그날 밭의 양이 줄었다.
            //
            // 이제 이상 수열을 앞에서부터 훑어 <b>아직 우리 것이 아닌</b> 칸만 후보로 삼고,
            // k개를 채울 때까지 계속 나아간다. 수열은 본래 연결적이라(열 확장 (w,r)은 (w−1,r)에,
            // 새 줄 (c,k)는 (c,k−1)에 붙는다) 막힌 칸을 건너뛰어도 몸통은 이어진 채 남는다.
            // SCAN_SLACK 만큼 더 훑으므로 막힌 칸이 있어도 배치 수가 줄지 않는다.
            var seq = com.evosim.core.FarmLayout.layout(plot.tiles.length + k + SCAN_SLACK);
            java.util.Set<Long> mine = new java.util.HashSet<>();
            for (long l : plot.tiles) {
                mine.add(l);
            }
            int placed = 0;
            for (int i = 0; i < seq.size() && placed < k; i++) {
                BlockPos gp = idealSpot(level, store, plot, seq.get(i)[0], seq.get(i)[1], adults,
                        mine);
                if (gp == null) {
                    continue; // 이미 우리 칸이거나 막힘 — 수열의 다음 이상 칸으로
                }
                level.setBlockAndUpdate(gp.below(),
                        net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());
                level.setBlockAndUpdate(gp,
                        net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 1));
                store.addTile(plot, gp, com.evosim.mod.entity.SimTime.tick(level));
                // 타일을 <b>등록한 뒤</b> 부른다 — 그래야 새 줄까지 포함한 몸통으로 판정한다.
                com.evosim.mod.entity.MimicEntity.farmTookRoad(level, ownerEnt, plot, gp);
                placed++;
            }
            // 공간 포화 감지 — 자금·노동은 있었는데 한 칸도 못 심음. 2일 연속이면 성숙 간주(막힌
            // 밭도 다음 밭을 연다 — 교착 방지). 심었으면 리셋.
            if (placed > 0) {
                redrawBorder(level, store, plot); // 상자가 커졌으면 테두리를 한 겹 물린다
            }
            if (placed == 0) {
                // <b>막히면 방향을 한 번 튼다.</b> 성장 방향이 집·다른 밭에 막히면 줄이 계단처럼
                // 짧아지는데(실측: 14구획 중 1개가 줄 길이 9→4→3→2→2, 채움 43%), 거울을 걷어낸
                // 뒤로는 반대편으로 넘어갈 수단이 없었다.
                //
                // 뒤집어도 <b>몸통은 끊기지 않는다</b>. dir 의 x 를 뒤집으면 새 칸은 앵커+(−1,…)
                // 부터 놓이는데 <b>앵커 열(c=0)은 양쪽이 공유</b>하므로 기존 타일과 맞닿는다.
                // 칸마다 뒤집던 옛 거울과 달리 구획 단위로 <b>한 번만</b> 트는 것이라, 떨어져
                // 나온 타일이 생기지 않는다. turned 로 한 번만 허용해 무한 회전을 막는다.
                if (!plot.turned) {
                    byte nd = pickDirExcept(level, store, plot.anchor, adults, plot.dir);
                    if (nd != plot.dir) {
                        com.evosim.mod.log.SimEvents.note(level, "밭방향", String.format(
                                "구획 %d 포화 — 성장 방향 %d→%d 로 전환(타일 %d)",
                                plot.id, plot.dir, nd, plot.tiles.length));
                        plot.dir = nd;
                        plot.turned = true;
                        plot.blockedDays = 0;
                        store.setDirty();
                        continue; // 다음 날 새 방향으로 다시 시도한다
                    }
                }
                plot.blockedDays++;
                store.setDirty();
            } else if (plot.blockedDays != 0) {
                plot.blockedDays = 0;
                store.setDirty();
            }
            if (placed > 0) {
                // 지불: 밭 계정 먼저 소진, 잔여는 자영 밭 한정 주인 저장고(부트스트랩). 소작 밭은
                // afford=내림(계정)이라 fromLarder=0(저장고 무손실 — 축장 보호). 회계 합 = placed×cost.
                double bill = placed * com.evosim.core.FarmEconomy.EXPAND_COST;
                double fromAccount = Math.min(plot.account, bill);
                plot.account -= fromAccount;
                double fromLarder = bill - fromAccount;
                if (fromLarder > 0 && nTen == 0 && ownerEnt.getHomePos() != null) {
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
        for (FarmStore.Plot plot : store.all().values()) {
            // 밭 <b>몸통</b>을 지나는 흙길 정비 — 사라진 덤불을 다시 심는 것과 같은 종류의 일이다.
            //
            // 확장 때만 치우면(그것도 한다) <b>밭이 성장을 멈춘 뒤</b> 갇힌 길 자국이 영영 남는다.
            // 실측: 런1 은 밭이 계속 자라 D14 이후 관통 0 이었지만, D11 에서 멈춘 런2 는 1칸이
            // 남아 있었다. 밭은 재배줄 + 고랑 구조라 길이 줄 사이에 갇힐 수 있고, 그 칸은
            // 타일이 아니어서 타일 순회로는 영영 안 잡힌다.
            com.evosim.mod.entity.MimicEntity.tidyFarmRoads(level, plot);
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
            if (funds < cost + reserve) {
                continue; // 자금(주 지주·단독 가구면 저장고≥30/39…)
            }
            if (owned > 0 && !nextFarmEligible(store, adults, m.getIndividual().id())) {
                continue; // 성숙 트리거(P6) — nextFarmEligible 참조(확장 예비 산정과 단일 출처)
            }
            BlockPos site = findFarmSite(level, store, m.getHomePos(), adults);
            if (site == null) {
                continue;
            }
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
            MimicEntity familyLord = owned == 0 ? findFamilyLord(level, store, adults, m) : null;
            long newOwnerId = familyLord != null
                    ? familyLord.getIndividual().id() : m.getIndividual().id();
            FarmStore.Plot plot = store.create(site, newOwnerId);
            plot.founderId = m.getIndividual().id(); // 원장: 창설자 = 착공 실행자(귀속과 무관)
            plot.foundedDay = com.evosim.mod.entity.SimTime.tick(level) / 24000L; // 밭 원장(P3) — 개간 게임일
            plot.tilesByFounder = 9;                        // 착공 9타일 = 부익부 대조 기준선
            // 성장 방향을 <b>여기서 한 번</b> 정한다 — 앵커 둘레 네 사분면 중 가장 넓게 트인 쪽.
            // 그 뒤로는 칸마다 뒤집지 않으므로 구획이 한쪽으로 반듯하게 자란다.
            plot.dir = pickDir(level, store, site, adults);
            java.util.Set<Long> mine0 = new java.util.HashSet<>();
            for (int[] t : com.evosim.core.FarmLayout.layout(9 + SCAN_SLACK)) {
                if (plot.tiles.length >= 9) {
                    break; // 9타일 채웠다 — 막힌 칸이 있었으면 수열을 더 훑어 벌충한 뒤 끝
                }
                BlockPos gp = idealSpot(level, store, plot, t[0], t[1], adults, mine0);
                if (gp == null) {
                    continue; // 막힌 칸 — 수열의 다음 이상 칸으로(버리지 않는다)
                }
                mine0.add(gp.asLong());
                level.setBlockAndUpdate(gp.below(),
                        net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());
                level.setBlockAndUpdate(gp,
                        net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 1));
                store.addTile(plot, gp, com.evosim.mod.entity.SimTime.tick(level));
                com.evosim.mod.entity.MimicEntity.farmTookRoad(level, m, plot, gp);
            }
            redrawBorder(level, store, plot); // 착공 즉시 경계가 보이게
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
            break; // 하루 1건
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
        if (newestPlot.blockedDays >= 1) {
            return ""; // 공간 포화 — 막힌 밭도 다음 밭을 연다(교착 방지). 성숙 2→1(2배속)
        }
        int tiles = newestPlot.tiles.length;
        if (tiles < com.evosim.core.FarmEconomy.MATURE_TILES) {
            return String.format("성숙 %d/%d타일", tiles, com.evosim.core.FarmEconomy.MATURE_TILES);
        }
        if (permTenants < 1) {
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

    /** 막힌 칸을 만났을 때 이상 수열을 더 훑는 여유분 — 이만큼이면 집 하나쯤은 우회한다. */
    private static final int SCAN_SLACK = 24;

    /** 지금 방향을 뺀 나머지 셋 중 가장 트인 쪽 — 없으면 현재 방향 그대로. */
    private static byte pickDirExcept(ServerLevel level, FarmStore store, BlockPos anchor,
                                      java.util.List<MimicEntity> adults, byte cur) {
        byte best = cur;
        int bestFree = -1;
        int off = spin(anchor);
        for (int i = 0; i < 8; i++) {
            byte d = (byte) ((i + off) % 8);
            if (d == cur) {
                continue;
            }
            int free = freeIn(level, store, anchor, adults, d);
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
                                java.util.List<MimicEntity> adults) {
        byte best = 0;
        int bestFree = -1;
        int off = spin(anchor);
        for (int i = 0; i < 8; i++) {
            byte d = (byte) ((i + off) % 8);
            int free = freeIn(level, store, anchor, adults, d);
            if (free > bestFree) {
                bestFree = free;
                best = d;
            }
        }
        return best;
    }

    /** 이 방향 7×7 격자에서 실제로 개간 가능한 칸 수. */
    private static int freeIn(ServerLevel level, FarmStore store, BlockPos anchor,
                              java.util.List<MimicEntity> adults, byte d) {
        int free = 0;
        for (int c = 0; c < 7; c++) {
            for (int r = 0; r < 7; r++) {
                BlockPos gp = level.getHeightmapPos(
                        net.minecraft.world.level.levelgen.Heightmap.Types
                                .MOTION_BLOCKING_NO_LEAVES,
                        gridOffset(anchor, d, c, r));
                if (!level.isLoaded(gp) || store.isFarmTile(gp)
                        || nearSomeHome(level, adults, gp, PLANT_CLEAR)) {
                    continue; // 방향 고르기도 실제로 심을 수 있는 칸만 세야 맞다
                }
                var at = level.getBlockState(gp);
                var below = level.getBlockState(gp.below());
                if ((at.isAir() || at.canBeReplaced() || weed(at))
                        && (below.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                        || below.is(net.minecraft.world.level.block.Blocks.DIRT)
                        || below.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT)
                        || below.is(net.minecraft.world.level.block.Blocks.DIRT_PATH))) {
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
        if (!level.isLoaded(gp) || mine.contains(gp.asLong()) || store.isFarmTile(gp)
                || nearSomeHome(level, adults, gp, PLANT_CLEAR)) {
            return null; // 새로 심는 칸은 집에서 한 발 더 물러선다 — 테두리 놓을 자리를 남긴다
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
        HomeStore reg = HomeStore.get(level);
        for (MimicEntity m : adults) {
            BlockPos h = m.getHomePos();
            if (h == null) {
                continue;
            }
            double clear = Math.max(margin, homeReach(level, reg, h) + PLANT_MARGIN);
            double dx = h.getX() - gp.getX();
            double dz = h.getZ() - gp.getZ();
            if (dx * dx + dz * dz < clear * clear) {
                return true;
            }
        }
        return false;
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

    /** 발자국 바깥에 남겨야 할 여유 — 밭 테두리 한 겹 + 지나다닐 한 칸. */
    private static final double PLANT_MARGIN = 2.0;

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
            int covered = 0;
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
                    if (streak >= com.evosim.core.FarmEconomy.PROMOTE_DAYS) {
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

    private static void emergencyHire(ServerLevel level) {
        FarmStore store = FarmStore.get(level);
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
            FarmStore.Plot best = open != null ? open : any;
            if (best != null) {
                ASSIGNED.put(m.getId(), best.id);
                com.evosim.mod.log.SimEvents.event(m, "긴급고용", String.format(
                        "위급(H %.2f) — 구획 %d 즉시 배정(%d타일 · 오늘 %d명 · %.0f블록%s)",
                        m.getHolding(), best.id, best.tiles.length, assignedToPlot(best.id),
                        Math.sqrt(open != null ? od : ad), open != null ? "" : " · 정원초과"));
            }
        }
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
        for (FarmStore.Plot p : FarmStore.get(level).all().values()) {
            for (int i = 0; i < p.tiles.length; i++) {
                if (p.planted[i] < 0 || com.evosim.mod.entity.SimTime.tick(level) - p.planted[i] < FarmEconomy.RIPEN_TICKS) {
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
        }
    }
}
