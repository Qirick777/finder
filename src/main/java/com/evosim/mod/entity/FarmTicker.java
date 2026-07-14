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

    private FarmTicker() {
    }

    /** 이 개체가 오늘 배정된 밭(없으면 0) — MimicFarmGoal 의 소작 경로 입력. */
    public static long assignedPlot(int entityId) {
        return ASSIGNED.getOrDefault(entityId, 0L);
    }

    /** 검증 인수 — 무대 시작 시 배정 잔재 제거(같은 자리 2회 규칙). */
    public static void clearAssignments() {
        ASSIGNED.clear();
        assignDay = -1;
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
            if (need <= 0) {
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
            int covered = 0;
            for (MimicEntity m : cands) {
                if (covered >= need) {
                    break;
                }
                ASSIGNED.put(m.getId(), plot.id);
                covered += com.evosim.core.FarmEconomy.capacity(m.getIndividual(), m.getStage());
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
