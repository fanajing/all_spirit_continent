package org.fanajing.all_spirit_continent.util;

import com.mojang.serialization.Codec;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 武魂模式物品栏快照：保存玩家开武魂前的原主物品栏（36 格）。
 * 作为玩家附件（attachment）持久化，随玩家存档落盘，防止异常退出导致原物品栏丢失。
 */
public class InventorySave {
    /** 主物品栏槽位数（快捷栏 9 + 背包 27） */
    public static final int MAIN_SLOTS = 36;

    public static final Codec<InventorySave> CODEC = ItemStack.OPTIONAL_CODEC.listOf()
            .xmap(InventorySave::fromList, InventorySave::toList);

    private final ItemStack[] slots = new ItemStack[MAIN_SLOTS];

    public InventorySave() {
        for (int i = 0; i < MAIN_SLOTS; i++) {
            slots[i] = ItemStack.EMPTY;
        }
    }

    private static InventorySave fromList(List<ItemStack> list) {
        InventorySave save = new InventorySave();
        for (int i = 0; i < list.size() && i < MAIN_SLOTS; i++) {
            ItemStack s = list.get(i);
            if (s != null && !s.isEmpty()) {
                save.slots[i] = s.copy();
            }
        }
        return save;
    }

    private static List<ItemStack> toList(InventorySave save) {
        List<ItemStack> list = new ArrayList<>(MAIN_SLOTS);
        for (ItemStack s : save.slots) {
            list.add(s == null ? ItemStack.EMPTY : s);
        }
        return list;
    }

    /** 是否有保存过内容（= 武魂开启时用于区分模式） */
    public boolean hasSaved() {
        for (ItemStack s : slots) {
            if (s != null && !s.isEmpty()) return true;
        }
        return false;
    }

    /** 保存当前主物品栏（复制副本） */
    public void saveFrom(Inventory inventory) {
        for (int i = 0; i < MAIN_SLOTS; i++) {
            slots[i] = inventory.getItem(i).copy();
        }
    }

    /** 恢复到主物品栏并清空快照 */
    public void restoreTo(Inventory inventory) {
        for (int i = 0; i < MAIN_SLOTS; i++) {
            inventory.setItem(i, slots[i]);
            slots[i] = ItemStack.EMPTY;
        }
    }

    /** 清空快照 */
    public void clear() {
        for (int i = 0; i < MAIN_SLOTS; i++) {
            slots[i] = ItemStack.EMPTY;
        }
    }
}
