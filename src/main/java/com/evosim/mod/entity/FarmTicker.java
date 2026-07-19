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

    private FarmTicker() {
    }

    /** 이 개체가 오늘 배정된 밭(없으면 0) — MimicFarmGoal 의 소작 경로 입력. */
    public static long assignedPlot(int entityId) {
        return ASSIGNED.getOrDefault(entityId, 0L);
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
        for (FarmStore.Plot plot : store.all().values()) {
            int units = (int) Math.floor(plot.account);
            if (units <= 0) {
                continue;
            }
            BlockPos home = null;
            MimicEntity ownerEnt = null;
            for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                    e -> e.isAlive() && e.getIndividual() != null
                            && e.getIndividual().id() == plot.ownerId)) {
                home = m.getHomePos();
                ownerEnt = m;
            }
            if (home == null) {
                continue; // 이월 — 다음 밤 재시도
            }
            LarderStore larder = LarderStore.get(level);
            larder.set(home, larder.get(home) + units);
            plot.account -= units;
            store.setDirty();
            // 이체가 실제로 끝난 뒤에만 기록(결과값 원칙) — 소작농화 추적의 경제 사슬 링크.
            com.evosim.mod.log.SimEvents.event(ownerEnt, "지대", String.format(
                    "구획 %d: +%d 저장고(이월 %.2f)", plot.id, units, plot.account));
        }
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
        // ① 확장
        for (FarmStore.Plot plot : new java.util.ArrayList<>(store.all().values())) {
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
            // 재투자·확장 여부는 주인의 동기가 결정(R1) — 만족·무욕 주인은 지대를 착복/정지.
            // 소작농의 만족은 무관(노동은 소작 계약의 일부, 자금은 밭 계정이라 유인 문제 없음).
            if (ownerEnt.isSatisfiedToday()
                    || com.evosim.core.Satisfaction.neverExpands(ownerEnt.getIndividual())) {
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
            // 자금: 밭 계정(지대 재투자 — R1) 우선, 부족분은 주인 저장고(생계 예비 유지) —
            // 지대가 아직 얇은 초기에도 확장이 멈추지 않게(소작 루프 v2). 단 다음 밭 자격(성숙)에
            // 도달한 지주의 저장고 예비는 다음 신규 밭 자금까지 올라간다(expandReserve — 저축 유도).
            double ownerFunds = ownerEnt.getHomePos() != null
                    ? larders.get(ownerEnt.getHomePos()) : 0.0;
            boolean eligible = nextFarmEligible(store, adults, plot.ownerId);
            // 성숙 구획은 계정의 절반만 재투자(MATURE_REINVEST_SHARE) — 잔여는 밤 정산 이체로
            // 다음 밭 종잣돈. 성숙 전엔 전액(초기 성장 경로 유지).
            int affordAccount = com.evosim.core.FarmEconomy.reinvestTiles(
                    plot.account * (eligible
                            ? com.evosim.core.FarmEconomy.MATURE_REINVEST_SHARE : 1.0));
            double reserve = com.evosim.core.FarmEconomy.expandReserve(
                    eligible, store.ownedCount(plot.ownerId));
            int affordLarder = (int) Math.floor(
                    Math.max(0.0, ownerFunds - reserve)
                            / com.evosim.core.FarmEconomy.EXPAND_COST);
            int afford = affordAccount + affordLarder;
            int k = Math.min(room, afford);
            if (k <= 0) {
                continue;
            }
            var seq = com.evosim.core.FarmLayout.layout(plot.tiles.length + k);
            int placed = 0;
            for (int i = plot.tiles.length; i < seq.size(); i++) {
                BlockPos gp = adaptiveSpot(level, store, plot.anchor,
                        seq.get(i)[0], seq.get(i)[1]);
                if (gp == null) {
                    continue; // 4방 전부 막힘 — 이 칸 스킵(비용 미지불)
                }
                level.setBlockAndUpdate(gp.below(),
                        net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());
                level.setBlockAndUpdate(gp,
                        net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 1));
                store.addTile(plot, gp, com.evosim.mod.entity.SimTime.tick(level));
                placed++;
            }
            // 공간 포화 감지 — 자금·노동은 있었는데 한 칸도 못 심음. 2일 연속이면 성숙 간주(막힌
            // 밭도 다음 밭을 연다 — 교착 방지). 심었으면 리셋.
            if (placed == 0) {
                plot.blockedDays++;
                store.setDirty();
            } else if (plot.blockedDays != 0) {
                plot.blockedDays = 0;
                store.setDirty();
            }
            if (placed > 0) {
                // 지불: 밭 계정 먼저 소진, 잔여는 주인 저장고 — 회계 합 = placed × EXPAND_COST.
                double bill = placed * com.evosim.core.FarmEconomy.EXPAND_COST;
                double fromAccount = Math.min(plot.account, bill);
                plot.account -= fromAccount;
                store.setDirty();
                double fromLarder = bill - fromAccount;
                if (fromLarder > 0 && ownerEnt.getHomePos() != null) {
                    larders.set(ownerEnt.getHomePos(), Math.max(0.0,
                            larders.get(ownerEnt.getHomePos()) - fromLarder));
                }
                grownToday.merge(grower.getId(), placed, Integer::sum);
                store.recordExpand(plot, grower.getIndividual().id(), placed,
                        com.evosim.mod.entity.SimTime.tick(level) / 24000L, hasTenant); // 밭 원장(P3): 자영/소작 귀속
                com.evosim.mod.log.SimEvents.event(grower, "밭확장", String.format(
                        "%s 구획 %d: +%d타일(총 %d) — 비용 %.0f(계정 %.0f) 소작 %d",
                        hasTenant ? "재투자" : "자영",
                        plot.id, placed, plot.tiles.length, bill, fromAccount, nTen));
            }
        }
        // ①c 죽은 타일 정비(A-3) — 블록이 사라진 타일은 무상 재식수, 구조물(천막 등)에 깔려
        //     복구 불능인 타일은 원장에서 소거. 깔린 타일이 원장에 남으면 영구 수확불능인데
        //     부족분 게시(고용 슬롯)만 부풀리는 유령 일자리가 된다(실측: 배정받고 수확 0).
        for (FarmStore.Plot plot : store.all().values()) {
            for (int i = plot.tiles.length - 1; i >= 0; i--) {
                BlockPos pos = BlockPos.of(plot.tiles[i]);
                if (!level.isLoaded(pos)) {
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
            if (m.isSatisfiedToday() || com.evosim.core.Satisfaction.neverExpands(m.getIndividual())) {
                continue; // 만족·무욕 — 신규 개간 안 함
            }
            // 독립 잠금(계층 분화 v2) — 하드게이트 없음. 잠금은 "만족의 덫": 위의 만족 게이트 +
            // 아래 자금 임계(30 = 18+12)가 서로를 배제한다. 궁핍한 평민은 자금이 없고, 자금이 모인
            // 평민은 이미 만족선(≤27.6)을 지나 만족 → 개간 동기 소멸. 동기특성×능력 소득만이 돌파.
            int owned = store.ownedCount(m.getIndividual().id());
            double cost = com.evosim.core.FarmEconomy.newFarmCost(owned);
            double funds = larders.get(m.getHomePos());
            // 가구 회계 존중(런6 실측: 배우자가 같은 저장고로 자기 명의 개간 → 왕조 다음 밭 자금
            // 누수): 가구 내 <b>주 지주</b>(최대 소유 타일)의 다음 밭 몫을 예비에 가산하되, 자신이
            // 주 지주면 가산 없음 — 상호 가산으로 부부 지주가 서로를 차단하던 부메랑(2차 실측:
            // 리엄 문턱 66) 제거. 가구 자금은 주 왕조 몫 먼저, 부속 개간은 그 뒤(우선순위 산술).
            double reserve = com.evosim.core.FarmEconomy.INVEST_RESERVE;
            {
                long headId = 0L;
                int headTiles = -1;
                for (MimicEntity h : adults) {
                    if (h.getHomePos() != null && h.getHomePos().equals(m.getHomePos())) {
                        int t = store.ownedTiles(h.getIndividual().id());
                        if (t > headTiles) {
                            headTiles = t;
                            headId = h.getIndividual().id();
                        }
                    }
                }
                if (headTiles > 0 && headId != m.getIndividual().id()) {
                    reserve += com.evosim.core.FarmEconomy.newFarmCost(
                            store.ownedCount(headId));
                }
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
            FarmStore.Plot plot = store.create(site, m.getIndividual().id());
            plot.foundedDay = com.evosim.mod.entity.SimTime.tick(level) / 24000L; // 밭 원장(P3) — 개간 게임일
            plot.tilesByFounder = 9;                        // 착공 9타일 = 부익부 대조 기준선
            for (int[] t : com.evosim.core.FarmLayout.layout(9)) { // 착공 9타일(T1) — 이후는 확장 경로
                BlockPos gp = adaptiveSpot(level, store, site, t[0], t[1]);
                if (gp == null) {
                    continue; // 막힌 칸 스킵 — 착공 부지는 findFarmSite가 회피해 대개 전부 성립
                }
                level.setBlockAndUpdate(gp.below(),
                        net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());
                level.setBlockAndUpdate(gp,
                        net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 1));
                store.addTile(plot, gp, com.evosim.mod.entity.SimTime.tick(level));
            }
            larders.set(m.getHomePos(), funds - cost);
            // 수율 G 병기 — "능력자만 독립" 검수: 개간자의 G가 낮은 사례가 잦으면 잠금 누수 신호.
            com.evosim.mod.log.SimEvents.event(m, "밭개간", String.format(
                    "신규 구획 %d(%d번째) 착공 — 비용 %.0f G%.2f", plot.id, owned + 1, cost,
                    com.evosim.core.FarmEconomy.tileYield(m.getIndividual())));
            break; // 하루 1건
        }
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
        if (np == null) {
            return false;
        }
        int permTenants = 0;
        for (MimicEntity t : adults) {
            if (t.getTenantFarm() == newest) {
                permTenants++;
            }
        }
        boolean sizeMature = np.tiles.length >= com.evosim.core.FarmEconomy.MATURE_TILES
                && permTenants >= 1;
        return sizeMature || np.blockedDays >= 1; // 막힘 성숙 2→1(2배속 — 대기 반감)
    }

    private static BlockPos adaptiveSpot(ServerLevel level, FarmStore store, BlockPos anchor,
                                         int c, int r) {
        for (int[] m : com.evosim.core.FarmLayout.mirrors(c, r)) {
            BlockPos gp = level.getHeightmapPos(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    anchor.offset(m[0], 0, m[1] * 2));
            if (!level.isLoaded(gp) || store.isFarmTile(gp)) {
                continue;
            }
            var at = level.getBlockState(gp);
            var below = level.getBlockState(gp.below());
            boolean natural = at.isAir() || at.canBeReplaced();
            boolean ground = below.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                    || below.is(net.minecraft.world.level.block.Blocks.DIRT)
                    || below.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT);
            if (natural && ground) {
                return gp;
            }
        }
        return null;
    }

    /** 신규 밭 부지 — 집 기준 8방위 20블록, 기존 밭 앵커 20·거처 12 회피(발자국 근사). 없으면 null. */
    private static BlockPos findFarmSite(ServerLevel level, FarmStore store, BlockPos home,
                                         java.util.List<MimicEntity> adults) {
        // 부지 확산(B2) — 반경 20→40→60으로 넓혀가며 8방향 탐색. 근거리가 다 차면 바깥에
        // 새 밭을 펼쳐 "수많은 밭"을 지도에 분산(통근 해제 B1이 원거리 소작을 뒷받침).
        for (int radius = 20; radius <= 60; radius += 20) {
            for (int d = 0; d < 8; d++) {
                double ang = d * Math.PI / 4.0;
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
        assignDay = -1;
        rentDay = -1;
        growDay = -1;
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
            cands.sort(java.util.Comparator
                    .comparingInt((MimicEntity m) -> m.larderComfortable() ? 1 : 0) // 빈곤 우선
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
