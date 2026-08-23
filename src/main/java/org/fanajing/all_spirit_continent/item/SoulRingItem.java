package org.fanajing.all_spirit_continent.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import org.fanajing.all_spirit_continent.util.SoulBeastAge;

import java.util.List;

/**
 * 魂环掉落物：击杀魂兽后原地掉落，标注《魂兽名字》《魂环年限》。
 * NBT（CUSTOM_DATA）：SoulBeastName = 魂兽显示名，SoulRingAge = 魂兽年限（年）。
 * 物品贴图按年限档位（ring_tier override）显示对应颜色。
 */
public class SoulRingItem extends Item {

    public static final String TAG_BEAST_NAME = "SoulBeastName";
    public static final String TAG_RING_AGE = "SoulRingAge";

    public SoulRingItem(Properties properties) {
        super(properties);
    }

    /** 读取魂兽名字（无则空串） */
    public static String getBeastName(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.getString(TAG_BEAST_NAME);
    }

    /** 读取魂环年限（无则 0） */
    public static int getRingAge(ItemStack stack) {
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.getInt(TAG_RING_AGE);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        int age = getRingAge(stack);
        if (age <= 0) return;

        String name = getBeastName(stack);
        if (!name.isEmpty()) {
            tooltip.add(Component.translatable("item.all_spirit_continent.soul_ring.beast", name)
                    .withStyle(ChatFormatting.YELLOW));
        }
        tooltip.add(Component.translatable("item.all_spirit_continent.soul_ring.age",
                        SoulBeastAge.format(age))
                .withStyle(ChatFormatting.GOLD));
    }
}
