package org.fanajing.all_spirit_continent.init;

import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.enchantment.ModEnchantments;
import org.fanajing.all_spirit_continent.item.ModItems;

public class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, All_spirit_continent.MODID);

    // ===== 全魂大陆主创造标签页 =====
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ALL_SPIRIT_CONTINENT_TAB =
            CREATIVE_MODE_TABS.register("all_spirit_continent_tab",
                    () -> CreativeModeTab.builder()
                            .title(Component.translatable("itemGroup.all_spirit_continent"))
                            .icon(() -> new ItemStack(ModItems.SHUIJINGQIU.get()))
                            .displayItems((params, output) -> {
                                output.accept(ModItems.SHUIJINGQIU.get());
                                output.accept(ModItems.TIAOSHI_BANG.get());
                                output.accept(ModItems.SOUL_RING.get());
                                // 魂视附魔书：从附魔注册表 lookup 取 Holder（数据驱动注册表）
                                output.accept(EnchantedBookItem.createForEnchantment(new EnchantmentInstance(
                                        params.holders().lookupOrThrow(Registries.ENCHANTMENT)
                                                .getOrThrow(ModEnchantments.SOUL_VISION_KEY), 1)));
                            })
                            .build());

    // ===== 如果需要更多标签页，在此添加 =====
    // public static final DeferredHolder<CreativeModeTab, CreativeModeTab> BLOCKS_TAB = ...
}
