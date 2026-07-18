package com.evosim.mod.log;

import com.evosim.mod.entity.FamilyLedger;
import com.evosim.mod.entity.FarmStore;
import com.evosim.mod.entity.FarmTicker;
import com.evosim.mod.entity.LarderStore;
import com.evosim.mod.entity.MimicEntity;
import com.evosim.core.LifeStage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * AI 검수용 일일 감사 로그 (관측 프로토콜). 매일 1줄, <b>기계 판독용 key=value</b> 형식으로
 * 이정표 판정 필드를 전부 남긴다 — 사람용 서술 로그(SimEvents)와 달리 파서가 grep "AUDIT"
 * 한 줄이면 하루 전체를 채점할 수 있다.
 *
 * <p>소득 어큐뮬레이터는 행동 goal(채집·정원·사냥·밭)이 수확 시점에 {@link #record}로 적립,
 * 하루 1회 {@link #daily}가 스냅샷과 함께 비운다. 스냅샷 스캔은 <b>레벨 전체</b> —
 * 무인(헤드리스) 관측 런에서 플레이어 반경 스캔이 인구 0으로 찍히는 함정을 피한다.
 */
public final class SimAudit {

    /** 소득 소스 — 이정표의 "무엇으로 먹고사는가" 분해축. */
    public enum Src { GRASS, GARDEN, HUNT, FARM_SELF, FARM_TENANT, RENT, AID }

    private static final EnumMap<Src, Double> INCOME = new EnumMap<>(Src.class);
    private static int births = 0;

    private SimAudit() {
    }

    /** 소득 적립 — 수확 goal 이 수확 확정 직후 호출(로그 OFF면 무시 — 오버헤드 0 원칙). */
    public static synchronized void record(Src src, double amount) {
        if (!SimEvents.enabled()) {
            return;
        }
        INCOME.merge(src, amount, Double::sum);
    }

    /** 출산 적립 — 출산 이벤트 지점에서 호출. */
    public static synchronized void recordBirth() {
        if (!SimEvents.enabled()) {
            return;
        }
        births++;
    }

    /** 일일 감사 발행(어큐뮬레이터 리셋 포함) — SimEventTicker 가 하루 1회 호출. */
    public static void daily(ServerLevel level) {
        emit(level, true);
    }

    /**
     * 감사 1줄 발행. reset=false 면 조회 전용(/evosim audit — 어큐뮬레이터 보존).
     *
     * @return 발행한 줄(채팅 회신용)
     */
    public static synchronized String emit(ServerLevel level, boolean reset) {
        // ── 인구·상태 (레벨 전체 스캔 — 무인 관측 대응) ──
        int adult = 0;
        int adultF = 0; // 성년 여성 — 여성당 출산율(2.3 목표) 판정 분모
        int boy = 0;
        int infant = 0;
        int elder = 0;
        int critical = 0;
        int satisfied = 0;
        int tenantsPerm = 0;
        Set<Long> homes = new HashSet<>();
        // 계층별 가구 분해 — 지주(밭 보유)/소작(상시)/무밭 평민. "완만한 굶주림"은 평민 저장고
        // 추세로만 판정 가능(전체 평균은 부유한 지주가 가려버린다 — 관측 함정).
        Set<Long> ownerHomes = new HashSet<>();
        Set<Long> tenantHomes = new HashSet<>();
        java.util.List<MimicEntity> mimics = new java.util.ArrayList<>();
        for (MimicEntity m : level.getEntities(com.evosim.mod.reg.ModEntities.MIMIC.get(),
                e -> e.isAlive() && e.getIndividual() != null)) {
            mimics.add(m);
            switch (m.getStage()) {
                case ADULT -> {
                    adult++;
                    if (m.isFemale()) {
                        adultF++;
                    }
                }
                case BOY -> boy++;
                case INFANT -> infant++;
                case ELDER -> elder++;
            }
            if (m.isCritical()) {
                critical++;
            }
            if ((m.getStage() == LifeStage.ADULT || m.getStage() == LifeStage.ELDER)
                    && m.isSatisfiedToday()) {
                satisfied++;
            }
            if (m.getTenantFarm() != 0L) {
                tenantsPerm++;
                if (m.getHomePos() != null) {
                    tenantHomes.add(m.getHomePos().asLong());
                }
            }
            if (m.getHomePos() != null) {
                homes.add(m.getHomePos().asLong());
                if (FarmStore.get(level).owns(m.getIndividual().id())) {
                    ownerHomes.add(m.getHomePos().asLong());
                }
            }
        }
        tenantHomes.removeAll(ownerHomes); // 지주 우선 분류(자기 밭 소작 역설 방어)
        double larderSum = 0.0;
        double ownerLarder = 0.0;
        double tenantLarder = 0.0;
        double landlessLarder = 0.0;
        int landlessHomes = 0;
        LarderStore larders = LarderStore.get(level);
        for (long h : homes) {
            double v = larders.get(BlockPos.of(h));
            larderSum += v;
            if (ownerHomes.contains(h)) {
                ownerLarder += v;
            } else if (tenantHomes.contains(h)) {
                tenantLarder += v;
            } else {
                landlessLarder += v;
                landlessHomes++;
            }
        }
        // ── 밭·왕조 집중 ──
        FarmStore farms = FarmStore.get(level);
        int plots = 0;
        int tiles = 0;
        Map<Long, long[]> byOwner = new HashMap<>(); // ownerId → [plots, tiles]
        for (FarmStore.Plot p : farms.all().values()) {
            plots++;
            tiles += p.tiles.length;
            if (p.ownerId != 0L) {
                long[] a = byOwner.computeIfAbsent(p.ownerId, k -> new long[2]);
                a[0]++;
                a[1] += p.tiles.length;
            }
        }
        long topOwner = 0L;
        long topTiles = 0;
        long topPlots = 0;
        for (var e : byOwner.entrySet()) {
            if (e.getValue()[1] > topTiles) {
                topOwner = e.getKey();
                topTiles = e.getValue()[1];
                topPlots = e.getValue()[0];
            }
        }
        // 왕조 의존 인구 — 최대 지주의 밭에서 일하는 소작(상시+오늘 일용) + 그 가구원 + 지주 가구.
        int dynDeps = 0;
        if (topOwner != 0L) {
            Set<Long> topPlotIds = new HashSet<>();
            BlockPos ownerHome = null;
            for (FarmStore.Plot p : farms.all().values()) {
                if (p.ownerId == topOwner) {
                    topPlotIds.add(p.id);
                }
            }
            Set<Long> depHomes = new HashSet<>();
            for (MimicEntity m : mimics) {
                boolean works = topPlotIds.contains(m.getTenantFarm())
                        || topPlotIds.contains(FarmTicker.assignedPlot(m.getId()));
                if (works && m.getHomePos() != null) {
                    depHomes.add(m.getHomePos().asLong());
                }
                if (m.getIndividual().id() == topOwner && m.getHomePos() != null) {
                    ownerHome = m.getHomePos();
                }
            }
            if (ownerHome != null) {
                depHomes.add(ownerHome.asLong());
            }
            for (MimicEntity m : mimics) {
                if (m.getHomePos() != null && depHomes.contains(m.getHomePos().asLong())) {
                    dynDeps++;
                }
            }
        }
        String topName = "-";
        if (topOwner != 0L) {
            FamilyLedger.Rec r = FamilyLedger.get(level).get(topOwner);
            topName = r != null && r.name != null && !r.name.isEmpty()
                    ? r.name.replace(' ', '_') : ("id" + topOwner);
        }
        long day = level.getGameTime() / 24000L;
        String line = String.format(
                "day=%d pop=%d adult=%d adult_f=%d boy=%d infant=%d elder=%d homes=%d births=%d"
                        + " grass=%.1f garden=%.1f hunt=%.1f farm_self=%.1f farm_tenant=%.1f"
                        + " rent=%.1f aid=%.1f plots=%d tiles=%d tenants_perm=%d tenants_today=%d"
                        + " top_owner=%s top_tiles=%d top_plots=%d dyn_deps=%d"
                        + " critical=%d satisfied=%d larder_sum=%.0f larder_avg=%.1f"
                        + " homes_owner=%d homes_tenant=%d homes_landless=%d"
                        + " larder_owner=%.1f larder_tenant=%.1f larder_landless=%.1f",
                day, mimics.size(), adult, adultF, boy, infant, elder, homes.size(), births,
                INCOME.getOrDefault(Src.GRASS, 0.0), INCOME.getOrDefault(Src.GARDEN, 0.0),
                INCOME.getOrDefault(Src.HUNT, 0.0), INCOME.getOrDefault(Src.FARM_SELF, 0.0),
                INCOME.getOrDefault(Src.FARM_TENANT, 0.0), INCOME.getOrDefault(Src.RENT, 0.0),
                INCOME.getOrDefault(Src.AID, 0.0), plots, tiles, tenantsPerm,
                FarmTicker.assignedCount(), topName, topTiles, topPlots, dynDeps,
                critical, satisfied, larderSum,
                homes.isEmpty() ? 0.0 : larderSum / homes.size(),
                ownerHomes.size(), tenantHomes.size(), landlessHomes,
                ownerHomes.isEmpty() ? 0.0 : ownerLarder / ownerHomes.size(),
                tenantHomes.isEmpty() ? 0.0 : tenantLarder / tenantHomes.size(),
                landlessHomes == 0 ? 0.0 : landlessLarder / landlessHomes);
        SimEvents.note(level, "AUDIT", line);
        if (reset) {
            INCOME.clear();
            births = 0;
        }
        return line;
    }
}
