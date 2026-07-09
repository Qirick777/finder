package com.evosim.mod.item;

import net.minecraft.world.item.ItemStack;

/** 검사봉 모드 (설계서 §14 관찰) — 스크롤(쉬프트+스크롤)로 순환. ItemStack NBT "Mode"에 저장. */
public enum ScannerMode {
    TRAIT("특성"),
    MATE("짝"),
    HOME("거처"),
    INVENTORY("가족 인벤토리");

    private final String label;

    ScannerMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static ScannerMode of(ItemStack stack) {
        int i = stack.getOrCreateTag().getInt("Mode");
        ScannerMode[] all = values();
        return all[Math.floorMod(i, all.length)];
    }

    public void writeTo(ItemStack stack) {
        stack.getOrCreateTag().putInt("Mode", ordinal());
    }

    public static ScannerMode cycle(ScannerMode cur, int dir) {
        ScannerMode[] all = values();
        return all[Math.floorMod(cur.ordinal() + dir, all.length)];
    }
}
