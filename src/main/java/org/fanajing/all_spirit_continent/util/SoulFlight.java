package org.fanajing.all_spirit_continent.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.init.ModAttachments;

/**
 * 魂帝飞行：魂帝境界（61 级）起解锁魂力飞行。
 * <p>
 * 规则：
 *  - 已激活的魂帝（61 级+）获得飞行能力（mayfly），创造/旁观玩家不干预
 *  - 真正飞行中每秒消耗 2 点魂力（每 20 tick 结算一次）
 *  - 魂力每 2 秒回复 1 点（已激活玩家，不超上限）
 *  - 魂力不足 2 点时强制落地并提示
 */
public final class SoulFlight {

    /** 飞行消耗结算间隔（tick，20 = 每秒） */
    public static final int COST_INTERVAL_TICKS = 20;
    /** 每次结算消耗的魂力（即每秒 2 点） */
    public static final float COST_PER_INTERVAL = 2f;
    /** 魂力回复间隔（tick，40 = 每 2 秒） */
    public static final int REGEN_INTERVAL_TICKS = 40;
    /**
     * 每次回复的魂力比例（每 2 秒回复上限的 2%，满池约 100 秒）。
     * 原来固定 1 点/2 秒在后期池子上百万时等于不回复，魂力不再是一个资源。
     * 具体数值由 {@link SoulGrowth#spiritRegenAmount} 按上限百分比算出。
     */
    @Deprecated
    public static final float REGEN_AMOUNT = 1f;

    private SoulFlight() {}

    /**
     * 每 tick 调用（仅服务端）：维持/收回飞行能力 + 飞行消耗 + 魂力回复。
     * 能力状态变化时才调用 onUpdateAbilities 同步客户端，避免每 tick 发包。
     */
    public static void tick(Player player) {
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        Abilities abilities = player.getAbilities();

        // 创造/旁观：原版自由飞行，不干预
        if (player.isCreative() || player.isSpectator()) return;

        boolean soulEmperor = data.isActivated() && data.getLevel() >= TitleSystem.SOUL_EMPEROR_LEVEL;

        // 飞行能力开关（仅在状态变化时同步）
        if (abilities.mayfly != soulEmperor) {
            abilities.mayfly = soulEmperor;
            if (!soulEmperor) abilities.flying = false;
            player.onUpdateAbilities();
            if (soulEmperor) {
                player.sendSystemMessage(
                        Component.translatable("msg.all_spirit_continent.flight_unlock")
                                .withStyle(ChatFormatting.AQUA));
            }
        }

        // 飞行消耗：真正飞行中才消耗；魂力不足时强制落地
        if (soulEmperor && abilities.flying && player.tickCount % COST_INTERVAL_TICKS == 0) {
            if (data.getSoulPower() < COST_PER_INTERVAL) {
                abilities.flying = false;
                player.onUpdateAbilities();
                player.sendSystemMessage(
                        Component.translatable("msg.all_spirit_continent.flight_no_spirit")
                                .withStyle(ChatFormatting.RED));
            } else {
                data.consumeSoulPower(COST_PER_INTERVAL);
            }
        }

        // 魂力回复：已激活玩家每 2 秒回复上限的 2%（满池约 100 秒），数值变化时同步 HUD。
        // 百分比回复保证后期池子膨胀后回复速度同步缩放，魂力始终是有效资源。
        if (data.isActivated() && player.tickCount % REGEN_INTERVAL_TICKS == 0) {
            float before = data.getSoulPower();
            data.restoreSoulPower(SoulGrowth.spiritRegenAmount(data));
            if (data.getSoulPower() != before) {
                All_spirit_continent.syncLevelDataToClient(player);
            }
        }
    }
}
