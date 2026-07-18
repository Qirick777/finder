package com.evosim.mod.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 시뮬 유효 시계 (밤 스킵 v1) — <b>시뮬 로직의 모든 시간은 이 시계를 쓴다</b>.
 *
 * <p>마크 gameTime은 점프가 불가능하므로, 밤 스킵은 dayTime 점프 + 여기 누적 오프셋으로
 * 구현한다: 유효 틱 = gameTime + offset. 익음(RIPEN)·일 경계(assign/grow/rent/census)·
 * 개체 타임스탬프가 전부 유효 틱 기준이라, 스킵된 밤은 시뮬에겐 "실제로 지난 시간"이 된다.
 * 스킵이 꺼져 있으면(offset 0) 종전과 완전 동일 — 검증 스위트는 스킵 OFF 세계에서 돈다.
 *
 * <p>오프셋은 SavedData로 영속(재기동 무손실). 정적 캐시는 단일 서버 전제(이 모드의 관측
 * 환경) — 월드 로드 시 {@link Store#load}가 복원한다.
 */
public final class SimTime {

    private static volatile long offset = 0L;
    private static volatile boolean skipEnabled = false;

    private SimTime() {
    }

    /** 유효 틱 — 시뮬 로직의 표준 시계. 클라이언트/오프셋 무관 호출도 안전(원시+누적). */
    public static long tick(Level lv) {
        return lv.getGameTime() + offset;
    }

    /** 유효 일차. */
    public static long day(Level lv) {
        return tick(lv) / 24000L;
    }

    public static long offset() {
        return offset;
    }

    public static boolean skipEnabled() {
        return skipEnabled;
    }

    /** 밤 스킵 토글(관측 런 전용 — obs가 켠다. 검증 스위트는 끈 채 운용). */
    public static void setSkipEnabled(ServerLevel level, boolean on) {
        skipEnabled = on;
        Store.get(level).setDirty();
    }

    /** 스킵 적용 — dayTime 점프분을 유효 시계에 가산(NightSkipTicker 전용). */
    static void addSkip(ServerLevel level, long delta) {
        offset += delta;
        Store.get(level).setDirty();
    }

    /** 영속 저장소 — 오프셋·토글을 월드에 박제. */
    public static final class Store extends SavedData {
        private static final String KEY = "evosim_simtime";

        public static Store get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(Store::load, Store::new, KEY);
        }

        public static Store load(CompoundTag tag) {
            Store s = new Store();
            offset = tag.getLong("Offset");
            skipEnabled = tag.getBoolean("Skip");
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.putLong("Offset", offset);
            tag.putBoolean("Skip", skipEnabled);
            return tag;
        }
    }
}
