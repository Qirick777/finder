package com.evosim.mod.log;

import com.evosim.core.LifeStage;
import com.evosim.core.ParentingClass;
import com.evosim.core.Schedule;
import com.evosim.mod.entity.LarderStore;
import com.evosim.mod.entity.MimicEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 자연 관찰 사건 로그 (설계서 §14 관찰·측정). 사건이 <b>실제로 벌어지는 그 순간</b>에 컨텍스트(개체·좌표·
 * 시각·값)와 함께 기록한다 → "발동했다"가 아니라 "게임에서 확실히 일어났다"를 로그로 검증.
 *
 * <p>메모리 링버퍼 + 파일({@code <gamedir>/evosim-events.log})에 동시 기록. 통째로 복사해 AI 검증.
 * 기본 꺼짐(오버헤드 0), {@code /evolog on} 으로 켠다.
 */
public final class SimEvents {

    private static final int MAX_MEM = 2000;
    private static final Deque<String> MEMORY = new ArrayDeque<>();
    private static boolean enabled = false;
    private static PrintWriter writer;
    private static Path logPath;

    private SimEvents() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static Path logPath() {
        return logPath;
    }

    /** 로그 on/off. 켤 때 파일을 새로 열고 헤더 기록. */
    public static synchronized void setEnabled(boolean on, Path gameDir) {
        if (on == enabled) {
            return;
        }
        enabled = on;
        if (on) {
            try {
                logPath = gameDir.resolve("evosim-events.log");
                writer = new PrintWriter(Files.newBufferedWriter(logPath, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND));
                writer.println("=== EvoSim 관찰 로그 시작 ===");
                writer.flush();
            } catch (IOException e) {
                writer = null;
            }
        } else if (writer != null) {
            writer.println("=== 로그 종료 ===");
            writer.close();
            writer = null;
        }
    }

    /**
     * 개체 컨텍스트를 붙여 사건 기록 — 컴팩트 한 줄: 시각·유형·개체(성별/단계)·<b>보유 H 자동 포함</b>·상세·좌표.
     * 모든 개체 사건에 H가 찍히므로 식량 궤적이 공짜로 시계열이 된다(밸런싱 근거).
     */
    public static void event(MimicEntity e, String type, String detail) {
        if (!enabled) {
            return;
        }
        Level lv = e.level();
        BlockPos p = e.blockPosition();
        String line = String.format("[d%d %s] %-4s #%d(%s%s H%.2f) %s @%d,%d",
                lv.getGameTime() / 24000L, phaseLabel(lv.getDayTime()), type,
                e.getId(), e.isFemale() ? "여" : "남", stageLabel(e.getStage()),
                e.getHolding(), detail, p.getX(), p.getZ());
        append(line);
    }

    /** 개체 없이 사건 기록 (가계·인구조사 등). */
    public static void note(Level lv, String type, String detail) {
        if (!enabled) {
            return;
        }
        String line = String.format("[d%d %s] %-4s %s",
                lv.getGameTime() / 24000L, phaseLabel(lv.getDayTime()), type, detail);
        append(line);
    }

    /**
     * 가계 스냅샷 — 가족 정산 틱마다 한 줄(≈1분/가구). 저장고·구성·소지합·하루소모·이번 입출금이
     * 시간축으로 쌓여 "식량이 얼마나 늘었는지"의 근간 시계열이 된다.
     */
    public static void household(Level lv, BlockPos home, double larder, int adults, int boys,
                                 int infants, int elders, double holdingSum, double need,
                                 int deposited, int withdrawn) {
        if (!enabled) {
            return;
        }
        note(lv, "가계", String.format(
                "@%d,%d 저장고%.0f 가족%d(성%d·소%d·유%d·노%d) 소지합%.1f 하루소모%.1f 입금%d 인출%d",
                home.getX(), home.getZ(), larder, adults + boys + infants + elders, adults, boys,
                infants, elders, holdingSum, need, deposited, withdrawn));
    }

    /** 인구 조사 — 상태 스냅샷 + <b>식량 통계</b>(저장고합·소지합·위급 수). 하루 1회 시계열. */
    public static void census(Level lv, Collection<MimicEntity> mimics) {
        if (!enabled) {
            return;
        }
        int adult = 0;
        int boy = 0;
        int infant = 0;
        int old = 0;
        int wanderer = 0;
        int critical = 0;
        int careMale = 0;   // 육아 성향(무시 아님) 성년 남성 — 자연선택 관측: 세대에 걸쳐 0으로 수렴 예상
        int careFemale = 0; // 동 여성 — 유지·증가 예상(남무심·여적극 선택 가설)
        double holdSum = 0.0;
        int minGen = Integer.MAX_VALUE;
        int maxGen = 0;
        Set<Long> homes = new HashSet<>();
        for (MimicEntity m : mimics) {
            switch (m.getStage()) {
                case ADULT -> adult++;
                case BOY -> boy++;
                case INFANT -> infant++;
                case ELDER -> old++;
            }
            if (m.isWanderer()) {
                wanderer++;
            }
            if (m.getStage() == LifeStage.ADULT && m.getIndividual() != null
                    && m.getIndividual().parentingCare() != ParentingClass.NEGLECTFUL) {
                if (m.isFemale()) {
                    careFemale++;
                } else {
                    careMale++;
                }
            }
            holdSum += m.getHolding();
            if (m.isCritical()) {
                critical++;
            }
            BlockPos h = m.getHomePos();
            if (h != null) {
                homes.add(h.asLong());
            }
            if (m.getIndividual() != null) {
                int g = m.getIndividual().generation();
                minGen = Math.min(minGen, g);
                maxGen = Math.max(maxGen, g);
            }
        }
        double larderSum = 0.0;
        if (lv instanceof ServerLevel sl) {
            LarderStore store = LarderStore.get(sl);
            for (long h : homes) {
                larderSum += store.get(BlockPos.of(h));
            }
        }
        int total = mimics.size();
        note(lv, "인구", String.format(
                "총%d(성%d·소%d·유%d·노%d) 방랑%d 거처%d 세대%s | 저장고합%.0f 소지합%.1f 위급%d | 육아성향 남%d·여%d",
                total, adult, boy, infant, old, wanderer, homes.size(),
                total == 0 ? "-" : (minGen + "~" + maxGen), larderSum, holdSum, critical,
                careMale, careFemale));
    }

    public static synchronized int memorySize() {
        return MEMORY.size();
    }

    public static synchronized List<String> recent(int n) {
        List<String> all = new ArrayList<>(MEMORY);
        int from = Math.max(0, all.size() - n);
        return all.subList(from, all.size());
    }

    public static synchronized void clearMemory() {
        MEMORY.clear();
    }

    private static synchronized void append(String line) {
        MEMORY.addLast(line);
        if (MEMORY.size() > MAX_MEM) {
            MEMORY.removeFirst();
        }
        if (writer != null) {
            writer.println(line);
            writer.flush();
        }
    }

    private static String phaseLabel(long dayTime) {
        return switch (Schedule.globalPhase(dayTime)) {
            case SLEEP -> "취침";
            case WORK -> "일";
            case WANDER -> "배회";
            case NIGHT -> "밤";
        };
    }

    private static String stageLabel(LifeStage s) {
        return switch (s) {
            case INFANT -> "유";
            case BOY -> "소";
            case ADULT -> "성";
            case ELDER -> "노";
        };
    }
}
