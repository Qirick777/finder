package com.evosim.core;

/**
 * 특성 카테고리 (설계서 §2).
 * 개체는 카테고리별로 최대 3개의 발현 특성을 가진다 → 기본 9개.
 */
public enum Category {
    DISPOSITION, // 성향 — 대립쌍(반발)
    PHYSICAL,    // 신체 — 대립쌍(반발)
    PREFERENCE   // 선호 — 반발 없는 독립 가점(익숙함/다양성 축만 예외)
}
