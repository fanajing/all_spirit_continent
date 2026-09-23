package org.fanajing.all_spirit_continent.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Unbreakable;
import org.fanajing.all_spirit_continent.data.PlayerSkillDataStore;
import org.fanajing.all_spirit_continent.init.ModAttachments;
import org.fanajing.all_spirit_continent.item.MartialSoulHammerItem;
import org.fanajing.all_spirit_continent.item.ModItems;
import org.fanajing.all_spirit_continent.skill.SkillData;

import java.util.List;

/**
 * 武魂模式（开环）物品栏替换系统。
 * <p>
 * 机制：开武魂时保存玩家原主物品栏（36 格）→ 清空 → 槽位 0 发放昊天锤（攻击力=魂环加成）；
 * 关武魂时清理魂器栏（杂物掉落、昊天锤移除）→ 恢复原物品栏。整个过程中玩家 Inventory
 * 对象不变（只交换内容），因此打开的容器 UI 始终安全。
 * <p>
 * 防卡 bug 多层防护：
 *  - 昊天锤 onDroppedByPlayer=false（无法 Q 丢出）、canFitInsideContainer=false（无法入容器）
 *  - 容器点击拦截：任何涉及昊天锤且目标不是玩家自身主物品栏的操作一律取消
 *  - 兜底 tick：每 20 tick 校验昊天锤必须在槽位 0（丢失自动补发 / 多把自动清除 / 错位自动归位）
 *  - 死亡/登出强制关闭武魂恢复物品栏，防止原物品丢失或被吞
 */
public final class MartialSoulInventoryHandler {
    /** 昊天锤锁定槽位（主物品栏 0 号） */
    public static final int HAMMER_SLOT = 0;
    /** 兜底检查间隔（tick） */
    private static final int TICK_INTERVAL = 20;
    /** 昊天锤攻击速度修饰（对齐斧头手感，最终攻速约 1.6） */
    private static final float HAMMER_ATTACK_SPEED = -2.4F;

    private MartialSoulInventoryHandler() {
    }

    // ===== 状态查询 =====

    public static boolean isOpen(Player player) {
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        return data != null && data.isMartialSoulOpen();
    }

    // ===== 开/关武魂 =====

    /** 开武魂：保存原物品栏 → 清空 → 槽位0发放昊天锤 */
    public static void open(ServerPlayer player) {
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        if (data == null || !data.isActivated() || data.getRingCount() <= 0) return;
        if (data.isMartialSoulOpen()) return;
        Inventory inv = player.getInventory();
        InventorySave save = player.getData(ModAttachments.INVENTORY_SAVE.get());
        if (save == null) return;
        // 1. 清理异常恢复残留的锤子，再保存原主物品栏
        for (int i = 0; i < InventorySave.MAIN_SLOTS; i++) {
            if (inv.getItem(i).getItem() instanceof MartialSoulHammerItem) {
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        save.saveFrom(inv);
        // 2. 清空
        for (int i = 0; i < InventorySave.MAIN_SLOTS; i++) {
            inv.setItem(i, ItemStack.EMPTY);
        }
        // 3. 槽位0发放昊天锤
        inv.setItem(HAMMER_SLOT, createSoulHammer(player));
        data.setMartialSoulOpen(true);
        // 4. 移除全局魂环攻击加成（由昊天锤体现，避免叠加）
        removeGlobalAttack(player);
        syncContainer(player);
    }

    /** 关武魂：清理魂器栏 → 恢复原物品栏 */
    public static void close(ServerPlayer player) {
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        if (data == null || !data.isMartialSoulOpen()) return;
        // 0. 先强制关闭外部容器：触发 Close 事件回收可能流入容器的昊天锤（防复制）
        player.closeContainer();
        Inventory inv = player.getInventory();
        InventorySave save = player.getData(ModAttachments.INVENTORY_SAVE.get());
        // 1. 清理魂器栏：昊天锤直接移除，其余杂物掉落到脚下（防吞物品）
        for (int i = 0; i < InventorySave.MAIN_SLOTS; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.getItem() instanceof MartialSoulHammerItem) {
                inv.setItem(i, ItemStack.EMPTY);
                continue;
            }
            ItemStack drop = s.copy();
            player.drop(drop, true, false);
            inv.setItem(i, ItemStack.EMPTY);
        }
        // 2. 恢复原物品栏（快照自动清空）
        if (save != null) {
            save.restoreTo(inv);
        }
        data.setMartialSoulOpen(false);
        // 3. 恢复全局魂环攻击加成
        SoulGrowth.applyAttack(player);
        syncContainer(player);
    }

    /** 强制关闭（死亡/登出前调用）：仅武魂开启时才动作，保证原物品栏安全归还 */
    public static void forceClose(ServerPlayer player) {
        if (!isOpen(player)) return;
        close(player);
    }

    // ===== 每 tick 兜底 =====

    /** 服务端每 tick 调用（内部节流）：确保昊天锤锁定槽位0；武魂关闭时清除任何残留锤子 */
    public static void tick(ServerPlayer player) {
        if (isOpen(player)) {
            if (player.tickCount % TICK_INTERVAL != 0) return;
            ensureHammer(player);
            // 顺带移除可能被其他逻辑重新加上的全局攻击加成
            removeGlobalAttack(player);
        } else if (player.tickCount % TICK_INTERVAL == 0) {
            clearStrayHammers(player);
        }
    }

    /** 容器关闭时回收意外流入容器的昊天锤（防复制兜底） */
    public static void recoverFromContainer(ServerPlayer player, AbstractContainerMenu menu) {
        ItemStack hammer = ItemStack.EMPTY;
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory) continue; // 玩家自身槽位跳过
            ItemStack s = slot.getItem();
            if (s.getItem() instanceof MartialSoulHammerItem) {
                hammer = s.copy();
                slot.set(ItemStack.EMPTY);
                break; // 理论上只会有一把
            }
        }
        if (hammer.isEmpty()) return;
        Inventory inv = player.getInventory();
        ItemStack slot0 = inv.getItem(HAMMER_SLOT);
        if (!slot0.isEmpty()) {
            player.drop(slot0, true, false); // 槽位0被占用则把占位物掉落
        }
        inv.setItem(HAMMER_SLOT, hammer);
        syncContainer(player);
    }

    /**
     * 同步攻击力状态（魂环/等级变化、登录/克隆/换维度后调用）：
     * 武魂开启 → 只保留昊天锤的攻击加成（移除全局）；关闭 → 恢复全局加成。
     */
    public static void syncAttackState(Player player) {
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        if (data == null) return;
        if (data.isMartialSoulOpen()) {
            removeGlobalAttack(player);
            if (player instanceof ServerPlayer sp) {
                // 顺带刷新昊天锤组件（魂环可能已变化）
                Inventory inv = sp.getInventory();
                for (int i = 0; i < InventorySave.MAIN_SLOTS; i++) {
                    ItemStack s = inv.getItem(i);
                    if (s.getItem() instanceof MartialSoulHammerItem) {
                        refreshSoulData(s, player);
                    }
                }
            }
        } else {
            SoulGrowth.applyAttack(player);
        }
    }

    // ===== 昊天锤 =====

    /** 按当前魂环生成昊天锤（攻击力 = 魂环总攻击加成） */
    public static ItemStack createSoulHammer(Player player) {
        ItemStack stack = new ItemStack(ModItems.MARTIAL_SOUL_HAMMER.get());
        refreshSoulData(stack, player);
        return stack;
    }

    /**
     * 刷新昊天锤组件：
     *  1. ATTRIBUTE_MODIFIERS —— 攻击属性（攻击力 = 魂环总攻击加成）
     *  2. UNBREAKABLE —— 原版的不可破坏（魂器不消耗耐久）
     *  3. CUSTOM_DATA 数据快照 —— 供客户端介绍面板展示（攻击力/等级/封号/每环年限/魂技/魂兽）
     * 所有发放与刷新入口统一走这里，保证显示与实际属性一致。
     */
    public static void refreshSoulData(ItemStack stack, Player player) {
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS, buildAttackModifiers(player));
        // 不可破坏：true 表示在提示里显示「不可破坏」标签
        stack.set(DataComponents.UNBREAKABLE, new Unbreakable(true));

        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        CompoundTag tag = new CompoundTag();
        if (data != null) {
            tag.putFloat("Attack", Math.max(1f, SoulGrowth.ringsAttackBonus(data)));
            tag.putInt("Level", data.getLevel());
            String title = data.getTitle();
            if (title != null && !title.isEmpty()) {
                tag.putString("Title", title);
            }
            for (int i = 0; i < SoulRingLayout.MAX_RINGS; i++) {
                tag.putInt("RingAge" + i, data.getRingAge(i));
            }

            // 魂技与魂兽名称快照（服务端读取 SavedData）
            if (player instanceof ServerPlayer sp) {
                PlayerSkillConfig cfg = PlayerSkillDataStore.get((ServerLevel) sp.level()).config(sp);
                for (int i = 0; i < SoulRingLayout.MAX_RINGS; i++) {
                    SkillData skill = cfg.skillAt(i + 1);
                    if (skill == null) continue;
                    String name = skill.name();
                    if (name != null && !name.isEmpty()) {
                        tag.putString("SkillName" + i, name);
                    }
                    String mobId = skill.mobId();
                    if (mobId != null && !mobId.isEmpty()) {
                        // 存储魂兽实体翻译键，客户端可按当前语言显示名称。
                        // 兼容两种格式：已带 "entity." 前缀的翻译键（如 entity.minecraft.zombie），
                        // 或纯资源路径（如 minecraft:zombie）。
                        String beastKey;
                        if (mobId.startsWith("entity.")) {
                            beastKey = mobId;
                        } else {
                            beastKey = EntityType.byString(mobId)
                                    .map(EntityType::getDescriptionId)
                                    .orElse("entity." + mobId.replace(':', '.'));
                        }
                        tag.putString("BeastKey" + i, beastKey);
                    }
                }
            }
        } else {
            tag.putFloat("Attack", 1f);
        }
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static ItemAttributeModifiers buildAttackModifiers(Player player) {
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        float attack = data == null ? 1f : Math.max(1f, SoulGrowth.ringsAttackBonus(data));
        return new ItemAttributeModifiers(List.of(
                new ItemAttributeModifiers.Entry(Attributes.ATTACK_DAMAGE,
                        new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID, attack, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND),
                new ItemAttributeModifiers.Entry(Attributes.ATTACK_SPEED,
                        new AttributeModifier(Item.BASE_ATTACK_SPEED_ID, HAMMER_ATTACK_SPEED, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
        ), false);
    }

    // ===== 内部工具 =====

    private static void removeGlobalAttack(Player player) {
        AttributeInstance attr = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attr != null) {
            attr.removeModifier(SoulGrowth.ATTACK_MOD_ID);
        }
    }

    /** 兜底校验：昊天锤必须存在、唯一且锁定在槽位0；丢失自动补发，错位自动归位，多把自动清除 */
    private static void ensureHammer(ServerPlayer player) {
        Inventory inv = player.getInventory();
        int firstHammerSlot = -1;
        for (int i = 0; i < InventorySave.MAIN_SLOTS; i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof MartialSoulHammerItem) {
                if (firstHammerSlot == -1) {
                    firstHammerSlot = i;
                } else {
                    inv.setItem(i, ItemStack.EMPTY); // 多余的锤子（复制防护）移除
                }
            }
        }
        if (firstHammerSlot == -1) {
            // 丢失：槽位0补发（防止某途径把锤子移走导致的丢失）
            inv.setItem(HAMMER_SLOT, createSoulHammer(player));
        } else if (firstHammerSlot != HAMMER_SLOT) {
            // 错位：与槽位0互换，强制归位
            ItemStack hammer = inv.getItem(firstHammerSlot);
            ItemStack slot0 = inv.getItem(HAMMER_SLOT);
            inv.setItem(firstHammerSlot, slot0);
            inv.setItem(HAMMER_SLOT, hammer);
        }
        // 刷新攻击力组件与介绍面板数据（魂环年限可能已变化）
        ItemStack hammer = inv.getItem(HAMMER_SLOT);
        if (hammer.getItem() instanceof MartialSoulHammerItem) {
            refreshSoulData(hammer, player);
        }
        syncContainer(player);
    }

    /** 武魂关闭状态下清除玩家物品栏中的任何残留昊天锤（异常恢复兜底，防锤子流落到背包） */
    private static void clearStrayHammers(ServerPlayer player) {
        Inventory inv = player.getInventory();
        boolean dirty = false;
        for (int i = 0; i < InventorySave.MAIN_SLOTS; i++) {
            if (inv.getItem(i).getItem() instanceof MartialSoulHammerItem) {
                inv.setItem(i, ItemStack.EMPTY);
                dirty = true;
            }
        }
        if (dirty) syncContainer(player);
    }

    /** 同步物品栏到客户端；登出/死亡等连接异常时静默忽略 */
    private static void syncContainer(ServerPlayer player) {
        try {
            player.containerMenu.broadcastChanges();
        } catch (Exception e) {
            // 玩家可能已断开连接，忽略
        }
    }
}
