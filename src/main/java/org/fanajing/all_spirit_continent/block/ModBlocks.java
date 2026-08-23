package org.fanajing.all_spirit_continent.block;

import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.fanajing.all_spirit_continent.All_spirit_continent;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(All_spirit_continent.MODID);

    // ===== 示例方块（后续替换为你的方块） =====
    // public static final DeferredBlock<Block> EXAMPLE = BLOCKS.registerSimpleBlock("example",
    //         BlockBehaviour.Properties.of().strength(3.0f));

    // ===== 在此处注册你的方块 =====
    // 普通方块:
    // public static final DeferredBlock<Block> XXX = BLOCKS.registerSimpleBlock("xxx",
    //         BlockBehaviour.Properties.of().strength(3.0F).requiresCorrectToolForDrops());
    //
    // 矿石:
    // public static final DeferredBlock<DropExperienceBlock> XXX = BLOCKS.register("xxx_ore",
    //         () -> new DropExperienceBlock(ConstantInt.of(3),
    //                 BlockBehaviour.Properties.of().strength(3.0F).requiresCorrectToolForDrops()));
}
