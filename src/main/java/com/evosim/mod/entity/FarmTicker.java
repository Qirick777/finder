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
    private static final double COMMUTE = 48.0;              // 통근 상한(블록)
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
        long day = level.getGameTime() / 24000L;
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
     * 능력 게이트(growthCap — 주인 기준)로 T4 초과 차단. ② 신규 개간 — 주인 저장고가
     * newFarmCost(체증)+여유면 집 주변 빈 부지에 T1 착공(부지 없으면 건너뜀).
     */
    private static void growFarms(ServerLevel level) {
        long day = level.getGameTime() / 24000L;
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
            int cap = com.evosim.core.FarmEconomy.growthCap(ownerEnt.getIndividual());
            int labor = com.evosim.core.FarmEconomy.EXPAND_PER_DAY
                    - grownToday.getOrDefault(grower.getId(), 0); // 노동 상한은 개체 단위(R3)
            int room = Math.min(labor, cap - plot.tiles.length);
            if (room <= 0) {
                continue;
            }
            // 자금: 소작 구획은 밭 계정(지대 재투자 — R1), 자영은 종전대로 주인 저장고+생계 예비.
            double funds = hasTenant ? plot.account : larders.get(grower.getHomePos());
            int afford = hasTenant
                    ? com.evosim.core.FarmEconomy.reinvestTiles(plot.account)
                    : (int) Math.floor((funds - com.evosim.core.FarmEconomy.INVEST_RESERVE)
                            / com.evosim.core.FarmEconomy.EXPAND_COST);
            int k = Math.min(room, afford);
            if (k <= 0) {
                continue;
            }
            var seq = com.evosim.core.FarmLayout.layout(plot.tiles.length + k);
            int placed = 0;
            for (int i = plot.tiles.length; i < seq.size(); i++) {
                BlockPos gp = level.getHeightmapPos(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        plot.anchor.offset(seq.get(i)[0], 0, seq.get(i)[1] * 2));
                if (!level.isLoaded(gp)) {
                    continue;
                }
                level.setBlockAndUpdate(gp.below(),
                        net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());
                level.setBlockAndUpdate(gp,
                        net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 1));
                store.addTile(plot, gp, level.getGameTime());
                placed++;
            }
            if (placed > 0) {
                if (hasTenant) {
                    plot.account -= placed * com.evosim.core.FarmEconomy.EXPAND_COST;
                    store.setDirty();
                } else {
                    larders.set(grower.getHomePos(),
                            funds - placed * com.evosim.core.FarmEconomy.EXPAND_COST);
                }
                grownToday.merge(grower.getId(), placed, Integer::sum);
                com.evosim.mod.log.SimEvents.event(grower, "밭확장", String.format(
                        "%s 구획 %d: +%d타일(총 %d) — 비용 %.0f", hasTenant ? "재투자" : "자영",
                        plot.id, placed, plot.tiles.length,
                        placed * com.evosim.core.FarmEconomy.EXPAND_COST));
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
                        && m.blockPosition().distSqr(plot.anchor) <= COMMUTE * COMMUTE
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
        // ② 신규 개간 — 주인 단위(첫 자격자 1건/일: 폭주 제동)
        for (MimicEntity m : adults) {
            if (m.getHomePos() == null) {
                continue;
            }
            if (m.isSatisfiedToday() || com.evosim.core.Satisfaction.neverExpands(m.getIndividual())) {
                continue; // 만족·무욕 — 신규 개간 안 함
            }
            int owned = store.ownedCount(m.getIndividual().id());
            double cost = com.evosim.core.FarmEconomy.newFarmCost(owned);
            double funds = larders.get(m.getHomePos());
            if (owned == 0 && funds < cost + com.evosim.core.FarmEconomy.INVEST_RESERVE) {
                continue;
            }
            if (owned > 0) {
                // 기존 밭에 상시 소작이 있어야 신규 창설(확장권을 잃은 주인의 경로 — 설계 17)
                boolean anyTenanted = false;
                for (MimicEntity t : adults) {
                    if (t.getTenantFarm() != 0L && store.get(t.getTenantFarm()) != null
                            && store.get(t.getTenantFarm()).ownerId == m.getIndividual().id()) {
                        anyTenanted = true;
                    }
                }
                if (!anyTenanted || funds < cost + com.evosim.core.FarmEconomy.INVEST_RESERVE) {
                    continue;
                }
            }
            BlockPos site = findFarmSite(level, store, m.getHomePos(), adults);
            if (site == null) {
                continue;
            }
            FarmStore.Plot plot = store.create(site, m.getIndividual().id());
            for (int[] t : com.evosim.core.FarmLayout.layout(3)) { // 착공 3타일 — 이후는 확장 경로
                BlockPos gp = level.getHeightmapPos(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        site.offset(t[0], 0, t[1] * 2));
                level.setBlockAndUpdate(gp.below(),
                        net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());
                level.setBlockAndUpdate(gp,
                        net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH.defaultBlockState()
                                .setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 1));
                store.addTile(plot, gp, level.getGameTime());
            }
            larders.set(m.getHomePos(), funds - cost);
            com.evosim.mod.log.SimEvents.event(m, "밭개간", String.format(
                    "신규 구획 %d(%d번째) 착공 — 비용 %.0f", plot.id, owned + 1, cost));
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
                    && level.getGameTime() - p.vacantSince > com.evosim.core.FarmEconomy.VACANT_EXPIRE_TICKS) {
                store.debugRemove(p.id); // 등록·타일 색인 소거(멱등 정리 경로 재사용)
            }
        }
    }

    /** 신규 밭 부지 — 집 기준 8방위 20블록, 기존 밭 앵커 20·거처 12 회피(발자국 근사). 없으면 null. */
    private static BlockPos findFarmSite(ServerLevel level, FarmStore store, BlockPos home,
                                         java.util.List<MimicEntity> adults) {
        for (int d = 0; d < 8; d++) {
            double ang = d * Math.PI / 4.0;
            BlockPos c = home.offset((int) Math.round(Math.cos(ang) * 20), 0,
                    (int) Math.round(Math.sin(ang) * 20));
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
        return null;
    }

    /** 검증 조성 훅 — "어제 이 밭에 출근했음"을 주입(규칙 9: 조성만, 승격 결말은 실경로). */
    public static void debugSeedAssignment(int entityId, long plotId) {
        ASSIGNED.put(entityId, plotId);
    }

    /** 검증 인수 — 무대 시작 시 배정 잔재 제거(같은 자리 2회 규칙). */
    public static void clearAssignments() {
        ASSIGNED.clear();
        LAST_ASSIGNED.clear();
        assignDay = -1;
        rentDay = -1;
        growDay = -1;
    }

    /**
     * 새벽 배정 — 하루 1회(노동 시작 이후 첫 스캔): 구획별 부족분(T − 가구 ΣC, 최소 일감 게이트)을
     * 구직자(성년·비소유·저장고 비넉넉·통근 내·소유 가구 제외)로 거리순 커버. 운(익음) 무관 결정론.
     */
    private static void assignDawn(ServerLevel level) {
        long day = level.getGameTime() / 24000L;
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
                        if (m.getIndividual().id() != oid && m.getSpouseId() == oid
                                && home.equals(m.getHomePos()) && !m.isSatisfiedToday()) {
                            budget += com.evosim.core.FarmEconomy.capacity(
                                    m.getIndividual(), m.getStage());
                        }
                    }
                }
                java.util.List<FarmStore.Plot> plots = entry.getValue();
                final BlockPos h = home;
                plots.sort(java.util.Comparator
                        .comparingDouble((FarmStore.Plot p) ->
                                h == null ? 0.0 : p.anchor.distSqr(h))
                        .thenComparingLong(p -> p.id)); // 동률·무주 결정론
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
        for (FarmStore.Plot plot : store.all().values()) {
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
                if (m.blockPosition().distSqr(plot.anchor) > COMMUTE * COMMUTE) {
                    m.setTenant(0L, 0);
                    com.evosim.mod.log.SimEvents.event(m, "소작해제", "통근 초과 이주 — 관계 소멸");
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
                // 출근 관성(R2): 어제 이 구획에 출근한 자는 넉넉 필터 면제 — 하루 벌이로 넉넉해져
                // 연속일이 끊기는 승격 진동 차단(예약석과 같은 원리의 수습기 소급). 신규 구직만 빈곤 조건.
                boolean returning = LAST_ASSIGNED.getOrDefault(m.getId(), 0L) == plot.id;
                if (m.getIndividual().id() == plot.ownerId || ASSIGNED.containsKey(m.getId())
                        || (oh != null && oh.equals(m.getHomePos()))
                        || store.ownedCount(m.getIndividual().id()) > 0
                        || (m.larderComfortable() && !returning)
                        || m.blockPosition().distSqr(plot.anchor) > COMMUTE * COMMUTE) {
                    continue;
                }
                cands.add(m);
            }
            cands.sort(java.util.Comparator
                    .comparingDouble((MimicEntity m) -> m.blockPosition().distSqr(plot.anchor))
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
                com.evosim.mod.log.SimEvents.event(m, "배정", String.format(
                        "구획 %d 일용(연속 %d일)", plot.id, streak));
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
     * 보호 의무(봉건 쌍무) — 200틱 스캔: 위급(H<0.3) 상시 소작농을 영주 저장고에서 1유닛 구제.
     * 저장고가 비어 이행 불가면 관계 자동 해제(지대↔보호의 쌍무성 — 몰락 경로).
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
            if (home != null && larder >= 1.0) {
                LarderStore.get(level).set(home, larder - 1.0);
                m.addHarvest(1.0);
                com.evosim.mod.log.SimEvents.event(m, "구제", String.format(
                        "영주 저장고 1 인출 — H %.2f (구획 %d)", m.getHolding(), plot.id));
            } else {
                m.setTenant(0L, 0);
                com.evosim.mod.log.SimEvents.event(m, "소작해제", "영주 구제 불이행 — 관계 소멸");
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null) {
            return;
        }
        ServerLevel level = event.getServer().overworld();
        if (level.getGameTime() % SCAN_INTERVAL != 0) {
            return;
        }
        assignDawn(level);
        growFarms(level); // 재투자(계정 차감)가 지대 이체보다 먼저 — 같은 밤, 남은 정수만 주인에게(R1)
        settleRent(level);
        protectTenants(level);
        expireVacant(level);
        for (FarmStore.Plot p : FarmStore.get(level).all().values()) {
            for (int i = 0; i < p.tiles.length; i++) {
                if (p.planted[i] < 0 || level.getGameTime() - p.planted[i] < FarmEconomy.RIPEN_TICKS) {
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
