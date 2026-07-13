package com.evosim.mod.stage;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 검증 결과 영문 로그 — <b>cmd 콘솔</b>(ANSI 색: 성공 녹/실패 적 — 한글 깨짐 회피를 위해 영문)과
 * <b>evosim-verify.log</b> 파일(색 코드 없이 PASS/FAIL 토큰, UTF-8 append)에 동시 기록한다.
 * 포맷은 AI 원인 규명용으로 구조화: 스텝 슬러그(코드 위치 1:1)·reason 코드·경과·수치·기대식.
 */
public final class VerifyLog {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RESET = "\u001B[0m";

    private static Path file;

    private VerifyLog() {
    }

    /** 회차 시작 — 파일 경로 확정 + 타임스탬프 헤더(회차 구분). */
    public static void open(Path serverDir, String header) {
        file = serverDir.resolve("evosim-verify.log");
        String line = "[VERIFY START] "
                + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + " | " + header;
        LOGGER.info("{}{}{}", YELLOW, line, RESET);
        append(line);
    }

    /** 판정 1건 — 콘솔은 성공 녹/실패 적, 파일은 토큰 그대로(라인에 PASS/FAIL 포함돼 있어야 함). */
    public static void result(String line, boolean ok) {
        LOGGER.info("{}{}{}", ok ? GREEN : RED, line, RESET);
        append(line);
    }

    /** 중립 정보(중단·헤더 등) — 콘솔 노랑. */
    public static void info(String line) {
        LOGGER.info("{}{}{}", YELLOW, line, RESET);
        append(line);
    }

    private static void append(String s) {
        if (file == null) {
            return; // open 이전(단독 명령이 먼저 돌 때) — ensure가 경로를 잡는다
        }
        try {
            Files.writeString(file, s + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 파일 실패가 검증 자체를 막지 않게 — 콘솔 출력은 이미 나감
        }
    }

    /** 파일 미지정 상태(단독 명령 단독 실행)를 위한 경로 보증 — 헤더 없이 경로만 잡는다. */
    public static void ensure(Path serverDir) {
        if (file == null) {
            file = serverDir.resolve("evosim-verify.log");
        }
    }
}
