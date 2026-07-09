package com.evosim.mod.reg;

import com.evosim.mod.EvoSimMod;
import com.evosim.mod.block.MimicHearthBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 블록 등록 — 거처 모닥불. */
public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, EvoSimMod.MODID);

    public static final RegistryObject<Block> MIMIC_HEARTH = BLOCKS.register("mimic_hearth",
            () -> new MimicHearthBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.PODZOL)
                    .strength(2.0F)
                    .sound(SoundType.WOOD)
                    .noOcclusion()
                    .lightLevel(s -> s.getValue(MimicHearthBlock.LIT) ? 15 : 0)));

    private ModBlocks() {
    }
}
