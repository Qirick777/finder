package com.evosim.mod.entity;

import com.evosim.mod.EvoSimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashSet;
import java.util.Set;

/**
 * 정원 결정론 성장 (관측 모드 전용) — 무인 서버에서는 청크 랜덤틱이 돌지 않아 가구 정원
 * 관목이 영원히 age=1에 고착된다(실측: 3일 고착, 가속 테스트 무반응). 밭의 결정론 익음
 * 타이머(FarmTicker)와 같은 철학으로, 정원 관목을 <b>바닐라 기대 성장률 등가 확률</b>로
 * 전진시킨다: 스테이지당 기대 6827틱(randomTickSpeed 3 × 성공 1/5 역산) — 실측 캘리브레이션
 * (3.1회/그루/일)의 근거였던 유인 서버 성장률을 재현한다.
 *
 * <p><b>관측 모드(SimTime.skipEnabled) 게이트</b> — 플레이어가 있는 실월드는 바닐라 랜덤틱이
 * 살아 있으므로 여기가 돌면 이중 성장이 된다. 밤 스킵의 점프분은 {@link #catchUp}이 소급
 * 적용(스킵된 밤에도 관목은 자란 것으로 — 유인 월드와 등가).
 */
@Mod.EventBusSubscriber(modid = EvoSimMod.MODID)
public final class GardenTicker {

    private static final int SCAN_INTERVAL = 200;
    private static final int STAGE_TICKS = 3840; // 기준 월드 실측 재현: 그루당 3.125회/일 =
    // 주기 7680틱(2스테이지). 바닐라 이론치(6827)로는 실측(3.1회/그루/일)의 57%에 그쳐
    // 정원 5.0/가구 캘리브레이션이 깨졌다(시도3 실측: 2.5/가구 → 개간·굶주림 관문 연쇄 실패).
    private static final int HOME_RADIUS = 8;    // 정원 탐색 반경(거처 기준)

    private GardenTicker() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !SimTime.skipEnabled()) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getTickCount() % SCAN_INTERVAL != 0) {
            return;
        }
        ServerLevel level = server.overworld();
        grow(level, (float) SCAN_INTERVAL / STAGE_TICKS, 0);
    }

    /** 밤 스킵 소급 성장 — 점프한 delta 틱만큼 기대 스테이지(delta/6827)를 적용. */
    public static void catchUp(ServerLevel level, long delta) {
        int whole = (int) (delta / STAGE_TICKS);
        float frac = (float) (delta % STAGE_TICKS) / STAGE_TICKS;
        grow(level, frac, whole);
    }

    /** 전 가구 정원 스캔 — age<3 관목을 확정 whole 스테이지 + 확률 p 로 1스테이지 전진. */
    private static void grow(ServerLevel level, float p, int whole) {
        FarmStore farms = FarmStore.get(level);
        Set<Long> homes = new HashSet<>();
        for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getHomePos() != null)) {
            homes.add(m.getHomePos().asLong());
        }
        for (long h : homes) {
            BlockPos home = BlockPos.of(h);
            for (BlockPos pos : BlockPos.betweenClosed(
                    home.offset(-HOME_RADIUS, -2, -HOME_RADIUS),
                    home.offset(HOME_RADIUS, 2, HOME_RADIUS))) {
                BlockState st = level.getBlockState(pos);
                if (!st.is(Blocks.SWEET_BERRY_BUSH) || farms.isFarmTile(pos)) {
                    continue; // 밭 타일은 FarmTicker 결정론 익음이 담당(이중 성장 금지)
                }
                int age = st.getValue(SweetBerryBushBlock.AGE);
                if (age >= 3) {
                    continue;
                }
                int adv = whole + (level.random.nextFloat() < p ? 1 : 0);
                if (adv > 0) {
                    level.setBlockAndUpdate(pos, st.setValue(
                            SweetBerryBushBlock.AGE, Math.min(3, age + adv)));
                }
            }
        }
    }
}
