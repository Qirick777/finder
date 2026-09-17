package com.evosim.mod.entity;

import net.minecraft.server.level.ServerLevel;

/**
 * <b>직역 신분</b> — 밭을 매개로 한 자리. 전부 <b>파생값</b>이고 저장하지 않는다({@link SocialRank} 와 같은 원칙).
 *
 * <p>{@link SocialRank} 는 소유와 예속으로 4계층(지배·상위·평민·천민)을 가른다. 그런데 그 축만으로는
 * <b>무토지인 마름과 소작이 둘 다 천민</b>으로 묶여 구별되지 않는다(SocialRank 주석에 이미 기록된 한계다).
 * 이 enum 은 "밭에서 무엇을 하는 자인가"를 가른다 — 두 축은 겹치지 않고 서로를 대신하지도 않는다.
 *
 * <h3>농노는 신분이 아니라 결과다</h3>
 * 무토지라고 농노가 아니다. <b>빚을 진 채 예속이 이어질 때</b> 농노다. 그래서 흉년 한 번에 떨어지지 않고
 * ({@link SocialRank#BOUND_DAYS} 일 연속이 필요하다), 능력 있는 자는 상환이 빨라 며칠 만에 빠져나온다.
 * 특성이 "너는 농노다"라고 찍는 것이 아니라, 특성이 만든 수확과 지출의 차이가 그를 끌고 간다.
 *
 * <h3>유생 — 소작은 하되 농노는 아니다</h3>
 * 학위자는 가난해도 예속으로 세지 않는다. 역사적으로 표준이었다: 조선의 잔반은 제 손으로 농사를 지어도
 * 신분은 양반이었고, 중세 유럽에서도 같은 영주의 땅을 갈면서 자유 소작과 농노가 신분으로 갈렸다.
 */
public enum TenantStatus {

    /** 제 밭을 가진 자 — 신분 논의 밖이다. */
    LANDOWNER("지주"),
    /** 마름·감독관 — 무토지지만 남의 밭을 맡은 자. 예속으로 세지 않는다. */
    MANAGER("관리"),
    /** 학위자 — 소작을 하되 예속되지 않는다(잔반). */
    SCHOLAR("유생"),
    /** 빚을 진 채 예속이 이어진 자. */
    SERF("농노"),
    /** 나머지 무토지 — 지대만 내고 떠날 수 있다. */
    FREE("자유소작");

    private final String label;

    TenantStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isSerf() {
        return this == SERF;
    }

    /** 이 신분이 예속 일수를 세는 대상인가 — 지주·관리·유생은 세지 않는다. */
    public boolean countsBondage() {
        return this == SERF || this == FREE;
    }

    /**
     * 판정 — 순서가 곧 우선순위다(지주 → 관리 → 유생 → 농노 → 자유소작).
     * 저장하지 않으므로 부르는 쪽이 필요할 때마다 부른다(밤 정산·배정·지대 계산).
     */
    public static TenantStatus of(ServerLevel level, MimicEntity m) {
        if (m == null || m.getIndividual() == null) {
            return FREE;
        }
        long id = m.getIndividual().id();
        FarmStore fs = FarmStore.get(level);
        if (fs.ownedCount(id) > 0) {
            return LANDOWNER;
        }
        if (fs.stewardOf(id) != 0L || fs.overseerOf(id) != 0L) {
            return MANAGER;
        }
        if (m.getDegree() >= com.evosim.core.Degree.BACHELOR) {
            return SCHOLAR;
        }
        // <b>벗어나는 길</b>(명세 §6) — 학교와 군역. 나머지 둘(상환·개간)은 조건이 저절로 깨진다:
        // 빚을 다 갚으면 owed 가 0 이 되고, 제 밭을 얻으면 위에서 지주로 빠진다.
        // 군역만 면제다 — 복무 중에는 농노가 아니다(역사적으로 군복무가 면천의 길이었다).
        // <b>학력 면제는 뺐다</b>(사용자 지시): 학력은 등교 일수를 3까지 자른 값이라 이틀만 다녀도
        // 중급이 되어 신분을 공짜로 사 주는 문이었다. 학교는 이제 면제가 아니라 상환 가속으로 값을
        // 한다(Tribute.repayShare — 학력 등급당 +3%).
        if (FarmTicker.isSoldier(m)) {
            return FREE;
        }
        AllegianceStore lg = AllegianceStore.get(level);
        if (lg.owedOf(id) > 0.0 && lg.boundDays(id) >= SocialRank.BOUND_DAYS) {
            return SERF;
        }
        return FREE;
    }

    /** 배정 순번 — 작을수록 먼저 불려 나간다. 농노가 부역으로 앞서고 유생이 맨 뒤다. */
    public int workOrder() {
        return switch (this) {
            case SERF -> 0;
            case FREE -> 1;
            case LANDOWNER, MANAGER -> 2;
            case SCHOLAR -> 3;
        };
    }
}
