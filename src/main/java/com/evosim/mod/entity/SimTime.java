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

    /**
     * 오프셋을 복원해 둔 서버 — 아직 안 했으면 {@code null}.
     *
     * <p><b>이것이 없으면 재기동 직후 시계가 과거로 돌아간다.</b> {@link Store#load}는 SavedData가
     * <b>처음 접근될 때만</b> 불리는데, {@link #tick}은 Store 를 건드리지 않고 정적 {@link #offset}
     * 만 읽는다. 그래서 재기동 후 아무도 Store 를 열지 않으면 offset 이 0 인 채로 날이 흐르고,
     * 그러다 누군가 Store 를 여는 순간 날짜가 통째로 점프한다.
     *
     * <p>실측(오프셋 238828 = 9.95일이 쌓인 월드): Store 를 열기 전 D15 · 거주24 · 빈집인데거주 0,
     * {@code evosim skip} 한 줄로 Store 를 연 뒤 D25 · 거주0 · <b>빈집인데거주 24</b>. 날짜가 10일
     * 점프하면서 모든 거처가 "3일 넘게 거주 신호 없음"이 되어 한꺼번에 빈집으로 뒤집혔다.
     *
     * <p>빈집은 눈에 띈 하나일 뿐이다 — 익음·일 경계(배정·성장·지대·인구조사)·개체 나이와 근속이
     * 전부 같은 시계를 쓰므로 함께 어긋났다.
     *
     * <p>서버 인스턴스로 열쇠를 삼는 이유: 한 JVM 에서 월드를 갈아 끼우면(단일 플레이 재접속)
     * 새 {@code MinecraftServer} 가 생기므로, 별도 종료 이벤트 없이도 다음 월드에서 다시 복원된다.
     */
    private static volatile Object loadedFor = null;

    private SimTime() {
    }

    /** 유효 틱 — 시뮬 로직의 표준 시계. 클라이언트/오프셋 무관 호출도 안전(원시+누적). */
    public static long tick(Level lv) {
        if (lv instanceof ServerLevel sl && loadedFor != sl.getServer()) {
            ensureLoaded(sl);
        }
        return lv.getGameTime() + offset;
    }

    private static synchronized void ensureLoaded(ServerLevel sl) {
        if (loadedFor == sl.getServer()) {
            return;
        }
        loadedFor = sl.getServer(); // 먼저 세운다 — Store.get 안에서 tick 이 다시 불려도 안 돈다
        offset = 0L;                // 저장이 없는 새 월드면 0 이 맞다
        skipEnabled = false;
        Store.get(sl);              // 저장이 있으면 load() 가 위 둘을 덮어쓴다
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
