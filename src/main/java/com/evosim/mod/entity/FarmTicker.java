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
            for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                    e -> e.isAlive() && e.getIndividual() != null
                            && e.getIndividual().id() == plot.ownerId)) {
                home = m.getHomePos();
            }
            if (home == null) {
                continue; // 이월 — 다음 밤 재시도
            }
            LarderStore larder = LarderStore.get(level);
            larder.set(home, larder.get(home) + units);
            plot.account -= units;
            store.setDirty();
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
        java.util.List<MimicEntity> adults = level.getEntities(
                com.evosim.mod.reg.ModEntities.MIMIC.get(),
                m -> m.isAlive() && m.getIndividual() != null
                        && (m.getStage() == com.evosim.core.LifeStage.ADULT
                                || m.getStage() == com.evosim.core.LifeStage.ELDER));
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
            int cap = com.evosim.core.FarmEconomy.growthCap(ownerEnt.getIndividual());
            int room = Math.min(com.evosim.core.FarmEconomy.EXPAND_PER_DAY,
                    cap - plot.tiles.length);
            if (room <= 0) {
                continue;
            }
            double funds = larders.get(grower.getHomePos());
            int afford = (int) Math.floor((funds - com.evosim.core.FarmEconomy.INVEST_RESERVE)
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
                larders.set(grower.getHomePos(),
                        funds - placed * com.evosim.core.FarmEconomy.EXPAND_COST);
                com.evosim.mod.log.SimEvents.event(grower, "밭확장", String.format(
                        "%s 구획 %d: +%d타일(총 %d) — 비용 %.0f", hasTenant ? "소작권" : "자영",
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
            if (p.ownerId == 0L && p.vacantSince >= 0
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
        LAST_ASSIGNED.clear();
        LAST_ASSIGNED.putAll(ASSIGNED);
        ASSIGNED.clear();
        FarmStore store = FarmStore.get(level);
        java.util.List<MimicEntity> adults = level.getEntities(
                com.evosim.mod.reg.ModEntities.MIMIC.get(),
                m -> m.isAlive() && m.getIndividual() != null
                        && (m.getStage() == com.evosim.core.LifeStage.ADULT
                                || m.getStage() == com.evosim.core.LifeStage.ELDER));
        for (FarmStore.Plot plot : store.all().values()) {
            // 가구 ΣC: 소유자 + 같은 거처 성년(생계 우선이면 가장 제외는 larderComfortable 로 근사)
            int ownCap = 0;
            BlockPos ownerHome = null;
            for (MimicEntity m : adults) {
                if (m.getIndividual().id() == plot.ownerId) {
                    ownerHome = m.getHomePos();
                    ownCap += com.evosim.core.FarmEconomy.capacity(m.getIndividual(), m.getStage());
                }
            }
            if (ownerHome != null) {
                for (MimicEntity m : adults) {
                    if (m.getIndividual().id() != plot.ownerId && ownerHome.equals(m.getHomePos())) {
                        ownCap += com.evosim.core.FarmEconomy.capacity(m.getIndividual(), m.getStage());
                    }
                }
            }
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
                if (m.getIndividual().id() == plot.ownerId || ASSIGNED.containsKey(m.getId())
                        || (oh != null && oh.equals(m.getHomePos()))
                        || store.ownedCount(m.getIndividual().id()) > 0
                        || m.larderComfortable()
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
        settleRent(level);
        growFarms(level);
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
