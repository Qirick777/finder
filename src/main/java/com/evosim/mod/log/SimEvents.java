package com.evosim.mod.log;

import com.evosim.core.LifeStage;
import com.evosim.core.Schedule;
import com.evosim.mod.entity.MimicEntity;
import net.minecraft.core.BlockPos;
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

    /** 개체 컨텍스트를 붙여 사건 기록 (id·성별·단계·좌표·시각). */
    public static void event(MimicEntity e, String type, String detail) {
        if (!enabled) {
            return;
        }
        Level lv = e.level();
        BlockPos p = e.blockPosition();
        String line = String.format("[day%d %s] %-7s #%d(%s·%s) @%d,%d,%d %s",
                lv.getGameTime() / 24000L, phaseLabel(lv.getDayTime()), type,
                e.getId(), e.isFemale() ? "여" : "남", stageLabel(e.getStage()),
                p.getX(), p.getY(), p.getZ(), detail);
        append(line);
    }

    /** 개체 없이 사건 기록 (정산·인구조사 등). */
    public static void note(Level lv, String type, String detail) {
        if (!enabled) {
            return;
        }
        String line = String.format("[day%d %s] %-7s %s",
                lv.getGameTime() / 24000L, phaseLabel(lv.getDayTime()), type, detail);
        append(line);
    }

    /** 인구 조사 — 사건이 아니라 상태 스냅샷(루프가 실제로 굴러가는지 시간축 확인). */
    public static void census(Level lv, Collection<MimicEntity> mimics) {
        if (!enabled) {
            return;
        }
        int adult = 0;
        int boy = 0;
        int infant = 0;
        int wanderer = 0;
        int minGen = Integer.MAX_VALUE;
        int maxGen = 0;
        Set<Long> homes = new HashSet<>();
        for (MimicEntity m : mimics) {
            switch (m.getStage()) {
                case ADULT -> adult++;
                case BOY -> boy++;
                case INFANT -> infant++;
            }
            if (m.isWanderer()) {
                wanderer++;
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
        int total = mimics.size();
        note(lv, "CENSUS", String.format(
                "인구=%d 성년=%d 소년=%d 유아=%d 방랑=%d 거처=%d 세대=%s",
                total, adult, boy, infant, wanderer, homes.size(),
                total == 0 ? "-" : (minGen + "~" + maxGen)));
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
            case INFANT -> "유아";
            case BOY -> "소년";
            case ADULT -> "성년";
        };
    }
}
