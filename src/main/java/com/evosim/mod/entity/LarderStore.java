package com.evosim.mod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * 거처 저장고 L (식량 경제 v2). homePos → 잔량(double)을 월드 단위로 영속한다.
 *
 * <p>규칙: 입출금은 <b>정수 단위로만</b> 일어나고(순수 {@link com.evosim.core.FoodEconomy#settleHome}이
 * 보장), 시작값도 정수 올림이라 L은 항상 정수를 유지한다(B-3 불변식). 부패 없음 — 순수 완충.
 * 값 계산은 전부 순수 코어의 몫이고, 이 클래스는 보관·영속만 담당한다.
 */
public class LarderStore extends SavedData {

    private static final String KEY = "evosim_larder";

    private final Map<Long, Double> larders = new HashMap<>();

    public static LarderStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(LarderStore::load, LarderStore::new, KEY);
    }

    public static LarderStore load(CompoundTag tag) {
        LarderStore store = new LarderStore();
        ListTag list = tag.getList("Larders", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            store.larders.put(e.getLong("Pos"), e.getDouble("Amount"));
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<Long, Double> e : larders.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putLong("Pos", e.getKey());
            c.putDouble("Amount", e.getValue());
            list.add(c);
        }
        tag.put("Larders", list);
        return tag;
    }

    /** 저장고 잔량 — 항목 없으면 0(신설 가구는 getOrInit로 시작값을 깐다). */
    public double get(BlockPos home) {
        return larders.getOrDefault(home.asLong(), 0.0);
    }

    /** 잔량 조회 — 항목이 없으면 시작값(init)으로 개설하고 그 값을 반환(콜드스타트, B-3). */
    public double getOrInit(BlockPos home, double init) {
        Double cur = larders.get(home.asLong());
        if (cur != null) {
            return cur;
        }
        larders.put(home.asLong(), init);
        setDirty();
        return init;
    }

    public void set(BlockPos home, double value) {
        larders.put(home.asLong(), Math.max(0.0, value));
        setDirty();
    }

    /** 거처 소멸 시 항목 제거(고아 엔트리 청소). */
    public void remove(BlockPos home) {
        if (larders.remove(home.asLong()) != null) {
            setDirty();
        }
    }
}
