package org.fanajing.all_spirit_continent.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.init.ModAttachments;
import org.fanajing.all_spirit_continent.network.OpenSoulAnimationPayload;
import org.fanajing.all_spirit_continent.util.DebugStickMode;
import org.fanajing.all_spirit_continent.util.ModAdvancements;
import org.fanajing.all_spirit_continent.util.PlayerLevelData;
import org.fanajing.all_spirit_continent.util.SoulBeastAge;
import org.fanajing.all_spirit_continent.util.SoulGrowth;
import org.fanajing.all_spirit_continent.util.SoulRingAnimConfig;
import org.fanajing.all_spirit_continent.util.SoulRingLayout;
import org.fanajing.all_spirit_continent.util.TitleSystem;

import java.util.List;

/**
 * 调试棒：模组的核心调试工具。
 * 玩家手持调试棒 + 潜行 + 滚轮可切换功能模式（模式保存在物品 NBT 中），
 * 不同模式下右键触发对应功能。
 *
 * 当前模式：
 *  - NONE（无）：无任何功能
 *  - SOUL_RING（获取魂环）：右键获得一个魂环（等级+10，最高99级）
 *  - REMOVE_SOUL_RING（移除魂环）：右键移除一个魂环（等级-10，始终从最外层往内减少）
 *  - RING_AGE（年限修改）：右键只修改最大魂环的年限+1档（换颜色），最高档后回到十年
 *  - LEVEL（增减等级）：右键等级+1（回满血），Alt+右键等级-1（1~99，不增减魂环，经网络包执行）
 *  - CHECK_RING_AGE（查看魂环年限）：右键列出自己 9 个魂环的年限（无魂环显示无）
 *
 * 魂环效果与等级关联：每10级一个魂环，最高9个（99级，神祇100级未完成）。
 */
public class TiaoShiBangItem extends Item {
    /** NBT 中保存当前模式的键名 */
    public static final String MODE_TAG = "DebugMode";

    /** 每个魂环对应的等级跨度 */
    public static final int LEVELS_PER_RING = 10;
    /** 魂环数量上限（神祇境界未完成，暂限 9 环） */
    public static final int MAX_RINGS = 9;
    /** 最高等级（神祇 100 级未完成，暂限 99 级） */
    public static final int MAX_LEVEL = 99;

    public TiaoShiBangItem(Properties properties) {
        super(properties);
    }

    // ===== 悬浮介绍（tooltip）：列出全部模式与操作方式 =====

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.all_spirit_continent.tiaoshibang.use")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.all_spirit_continent.tiaoshibang.switch_mode")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.empty());
        for (DebugStickMode mode : DebugStickMode.values()) {
            tooltip.add(Component.translatable("tooltip.all_spirit_continent.mode." + mode.getId())
                    .withStyle(ChatFormatting.AQUA));
        }
        tooltip.add(Component.empty());
        tooltip.add(Component.translatable("tooltip.all_spirit_continent.tiaoshibang.inspect")
                .withStyle(ChatFormatting.GRAY));
    }

    // ===== 模式读写（物品 NBT） =====

    /** 读取调试棒当前模式 */
    public static DebugStickMode getMode(ItemStack stack) {
        CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        return DebugStickMode.fromName(data.copyTag().getString(MODE_TAG));
    }

    /** 写入调试棒当前模式 */
    public static void setMode(ItemStack stack, DebugStickMode mode) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY,
                data -> data.update(tag -> tag.putString(MODE_TAG, mode.getId())));
    }

    // ===== 右键交互 =====

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            DebugStickMode mode = getMode(stack);
            switch (mode) {
                case SOUL_RING -> grantSoulRing(level, player);
                case REMOVE_SOUL_RING -> removeSoulRing(level, player);
                case KAI_WU_HUN -> openMartialSoul(level, player);
                case RING_AGE -> increaseRingAge(level, player);
                case CHECK_RING_AGE -> checkRingAges(player);
                case NONE, RESIZE_RING, LEVEL -> { /* 无右键功能（LEVEL 由客户端按键监听发包处理） */ }
            }
        }
        return InteractionResultHolder.consume(stack);
    }

    /**
     * 查看魂环年限：右键在聊天栏列出自己 9 个魂环的年限
     * （每环一行，按年限档位上色；没有魂环显示「无」）。
     */
    private void checkRingAges(Player player) {
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());

        // 标题行 + 9 个环位（无环显示「无」），用换行拼成一条多行消息
        MutableComponent message =
                Component.translatable("msg.all_spirit_continent.ring_age_list")
                        .withStyle(ChatFormatting.GOLD);
        for (int i = 0; i < MAX_RINGS; i++) {
            int age = data.getRingAge(i);
            MutableComponent line;
            if (age > 0) {
                line = Component.translatable("msg.all_spirit_continent.ring_age_item",
                                i + 1, SoulBeastAge.format(age))
                        .withStyle(SoulBeastAge.tierFormatting(age));
            } else {
                line = Component.translatable("msg.all_spirit_continent.ring_age_item",
                                i + 1, Component.translatable("msg.all_spirit_continent.ring_age_none"))
                        .withStyle(ChatFormatting.GRAY);
            }
            message.append(Component.literal("\n")).append(line);
        }
        player.sendSystemMessage(message);
    }

    /** 移除一个魂环：清除最外层魂环（年限归零）并等级 -10（最低1级） */
    private void removeSoulRing(Level level, Player player) {
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());

        int ringCountBefore = data.getRingCount();
        if (ringCountBefore <= 0) {
            player.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.soul_ring_none")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        // 清除最外层魂环年限 + 等级 -10（最低保持1级）
        data.setRingAge(ringCountBefore - 1, 0);
        data.setLevel(Math.max(1, data.getLevel() - LEVELS_PER_RING));
        int ringCount = data.getRingCount();

        // 重算血量/攻击力加成（等级下降、魂环减少），不回满
        SoulGrowth.applyHealth(player);
        SoulGrowth.applyAttack(player);

        // 同步最新等级到客户端（客户端据此渲染魂环）
        All_spirit_continent.syncLevelDataToClient(player);

        // === 音效：魂环碎裂 ===
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.0F, 1.2F);

        // === 粒子特效：在最外层魂环半径处碎裂消散 ===
        // 半径由公共布局 SoulRingLayout 计算（含每环独立调节覆盖），与客户端渲染保持一致
        double x = player.getX();
        double y = player.getY() + 0.15;
        double z = player.getZ();
        double outerRadius = SoulRingLayout.getScaledRadius(ringCountBefore - 1);
        for (int i = 0; i < 24; i++) {
            double angle = Math.PI * 2 * i / 24;
            level.addParticle(ParticleTypes.POOF,
                    x + Math.cos(angle) * outerRadius, y, z + Math.sin(angle) * outerRadius,
                    0, 0.05, 0);
        }

        // 提示
        player.sendSystemMessage(
                Component.translatable("msg.all_spirit_continent.soul_ring_remove", ringCount)
                        .withStyle(ChatFormatting.GRAY));
    }

    /**
     * 开武魂（服务端统一逻辑，右键《开武魂》模式与 N 键共用）：
     * 检查魂环数 → 通知客户端播放释放动画 + 音效 + 提示。
     */
    public static void openMartialSoul(Level level, Player player) {
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        int ringCount = data.getRingCount();

        if (ringCount <= 0) {
            player.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.kai_wu_hun_none")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        // 服务端 → 客户端：播放开武魂动画（携带当前选中的动画类型）
        PacketDistributor.sendToPlayer(
                (net.minecraft.server.level.ServerPlayer) player,
                new OpenSoulAnimationPayload(ringCount, SoulRingAnimConfig.getCurrentAnimId()));

        // === 音效 ===
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.0F, 1.0F);

        // 提示
        player.sendSystemMessage(
                Component.translatable("msg.all_spirit_continent.kai_wu_hun_ok", ringCount)
                        .withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    /**
     * 关武魂（服务端统一逻辑）：通知客户端隐藏魂环 + 音效 + 提示。
     * 关闭不需要检查魂环数（没开武魂时重复关闭也只会收到提示）。
     */
    public static void closeMartialSoul(Level level, Player player) {
        PacketDistributor.sendToPlayer(
                (net.minecraft.server.level.ServerPlayer) player,
                new OpenSoulAnimationPayload(0, 0));

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.0F, 1.0F);

        player.sendSystemMessage(
                Component.translatable("msg.all_spirit_continent.kai_wu_hun_closed")
                        .withStyle(ChatFormatting.GRAY));
    }

    /**
     * 年限修改：只修改当前最外层（最大）魂环的年限 +1 级
     * （十年→百年→千年→万年→十万年→百万年→回到十年，年限 ×10 递进），
     * 与「调节环大小」一样每环独立，其他环的颜色不受影响。没有魂环时修改第 1 环（预览）。
     */
    private void increaseRingAge(Level level, Player player) {
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());

        // 调节目标：当前最外层环；没有魂环时调节第 1 环（预览）
        int ringCount = data.getRingCount();
        int targetIndex = Math.max(ringCount - 1, 0);

        // 整数级年限阶梯：十年 → 百年 → … → 百万年 → 回十年
        int[] steps = {10, 100, 1000, 10000, 100000, 1000000};
        int current = data.getRingAge(targetIndex);
        int nextAge = steps[0];
        for (int step : steps) {
            if (step > current) {
                nextAge = step;
                break;
            }
        }
        data.setRingAge(targetIndex, nextAge);

        // 重算精神力上限与血量/攻击力加成（魂环年限变化），吸收更高年限魂环后回满
        data.refreshSpiritPower();
        SoulGrowth.applyHealth(player);
        SoulGrowth.applyAttack(player);
        player.setHealth(player.getMaxHealth());

        // 同步最新年限到客户端（客户端据此选择魂环贴图）
        All_spirit_continent.syncLevelDataToClient(player);

        // === 音效 ===
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.6F, 1.4F);

        // 提示（含环序号与当前年限）
        player.sendSystemMessage(
                Component.translatable("msg.all_spirit_continent.ring_age", targetIndex + 1,
                        SoulBeastAge.format(nextAge))
                        .withStyle(ChatFormatting.AQUA));
    }

    /**
     * 增减等级（服务端，增减等级模式右键 +1 与 Alt+右键 -1 共用，均由
     * AdjustLevelPayload 网络包触发）：直接修改等级数值（不增减魂环），
     * 1~99 内；重算血量/攻击力加成（等级变化），升级回满血；同步客户端 + 音效 + 提示。
     */
    public static void adjustLevel(Level level, Player player, int delta) {
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());

        int newLevel = Math.clamp(data.getLevel() + delta, 1, MAX_LEVEL);
        if (newLevel == data.getLevel()) {
            player.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.level_limit")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        data.setLevel(newLevel);

        // 重算血量/攻击力加成（等级变化）；升级回满血
        SoulGrowth.applyHealth(player);
        SoulGrowth.applyAttack(player);
        if (delta > 0) {
            player.setHealth(player.getMaxHealth());
        }

        // 同步最新等级到客户端
        All_spirit_continent.syncLevelDataToClient(player);

        // === 音效 ===
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                delta > 0 ? SoundEvents.PLAYER_LEVELUP : SoundEvents.UI_BUTTON_CLICK.value(),
                SoundSource.PLAYERS, 1.0F, 1.0F);

        // 提示（当前等级）
        player.sendSystemMessage(
                Component.translatable(delta > 0
                        ? "msg.all_spirit_continent.level_up"
                        : "msg.all_spirit_continent.level_down", newLevel)
                        .withStyle(delta > 0 ? ChatFormatting.AQUA : ChatFormatting.GRAY));
    }

    /**
     * 获取一个魂环：等级 +10 并按新等级补齐对应魂环（每 10 级一个，最高 9 环）；
     * 90 级（9 环满）后改为每次 +1 级修为（91~99 逐级提升，无新魂环），
     * 并同步到客户端；踏入封号斗罗（91级）时提示自定义封号。
     */
    private void grantSoulRing(Level level, Player player) {
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());

        if (data.getLevel() >= MAX_LEVEL) {
            player.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.soul_ring_full")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        int oldLevel = data.getLevel();
        int ringCountBefore = data.getRingCount();
        // 9 环未满时：等级 +10（每 10 级一个魂环）；9 环满后每次 +1 级修为
        if (ringCountBefore < MAX_RINGS) {
            data.setLevel(Math.min(data.getLevel() + LEVELS_PER_RING, MAX_LEVEL));
        } else {
            data.setLevel(Math.min(data.getLevel() + 1, MAX_LEVEL));
        }

        // 按新等级补齐缺失魂环（+10 跨过一个节点，新环默认十年，直接从下一环位补）
        boolean ringGained = false;
        int needed = Math.min(data.getLevel() / LEVELS_PER_RING, MAX_RINGS);
        for (int i = ringCountBefore; i < needed; i++) {
            data.setRingAge(i, 10);
            ModAdvancements.awardRing((ServerPlayer) player, i + 1);
            ringGained = true;
        }
        int ringCount = data.getRingCount();

        // 获得新环后重算精神力上限（含新环加成）与血量/攻击力加成，升级后回满血
        if (ringGained) {
            data.refreshSpiritPower();
        }
        SoulGrowth.applyHealth(player);
        SoulGrowth.applyAttack(player);
        player.setHealth(player.getMaxHealth());

        // 同步最新等级到客户端（客户端据此渲染魂环）
        All_spirit_continent.syncLevelDataToClient(player);

        // 成就：获得新魂环 → 境界成就（第 N 环，补齐循环内已授予）；等级成就（95/99）按新等级授予
        ModAdvancements.awardSoulRank((ServerPlayer) player, data.getLevel());

        // === 音效 ===
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.0F);

        // === 粒子特效：脚底一圈魔法粒子 ===
        double x = player.getX();
        double y = player.getY() + 0.15;
        double z = player.getZ();
        for (int i = 0; i < 24; i++) {
            double angle = Math.PI * 2 * i / 24;
            double r = 0.7;
            level.addParticle(ParticleTypes.END_ROD,
                    x + Math.cos(angle) * r, y, z + Math.sin(angle) * r,
                    0, 0.08, 0);
        }
        for (int i = 0; i < 12; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            double r = level.random.nextDouble() * 0.7;
            level.addParticle(ParticleTypes.ENCHANT,
                    x + Math.cos(angle) * r, y, z + Math.sin(angle) * r,
                    0, 0.2, 0);
        }

        // 提示（获得魂环 / 修为提升两种文案）
        if (ringGained) {
            player.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.soul_ring_grant", ringCount)
                            .withStyle(ChatFormatting.LIGHT_PURPLE));
        } else {
            player.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.soul_power_up", data.getLevel())
                            .withStyle(ChatFormatting.AQUA));
        }

        // 刚踏入封号斗罗境界（91级以上）且没有封号时，提示自定义封号
        if (oldLevel < TitleSystem.FENG_HAO_LEVEL
                && data.getLevel() >= TitleSystem.FENG_HAO_LEVEL
                && data.getTitle().isEmpty()) {
            player.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.fh_reached")
                            .withStyle(ChatFormatting.GOLD));
        }
    }
}
