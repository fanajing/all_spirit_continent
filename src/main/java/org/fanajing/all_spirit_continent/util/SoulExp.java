package org.fanajing.all_spirit_continent.util;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import org.fanajing.all_spirit_continent.init.ModAttachments;

/**
 * 经验系统（公共工具类，服务端使用）：
 *  - 升级所需经验 expRequired(level) = 100 × level^1.8（1 级升 2 级 100，99 级约 39 万；
 *    指数高于魂兽经验曲线，越后期每级所需越多，长期玩法）
 *  - 魂环基础经验 baseExp(age) = 10 × 年限^0.8（与魂兽血攻曲线 statMultiplierOf 同指数；
 *    十年≈63 / 百年≈398 / 千年≈2512 / 万年≈15849 / 十万年≈10万 / 百万年≈63万）
 *  - 越级惩罚：玩家等级档位（level/10，封顶 5 = 百万年）高于魂兽年限档位时，每高一档经验 ×0.8
 *    （如 30 级杀千年魂兽 ×80%，40 级杀千年 ×64%，防止高级玩家刷低级魂兽）
 *  - 多人参与击杀平分：每位参与者吸收时获得 基础经验 × 惩罚 ÷ 参与人数
 *  - 单次吸收最多连升 MAX_LEVELUPS_PER_ABSORB = 5 级（防高等级带低等级刷百万年直升几十级），
 *    升满 5 级后剩余经验全部作废
 *  - 节点等级（10/20/.../90）封顶：到达节点等级立即封顶，溢出经验全部作废；
 *    封顶期间经验条显示 0/1（魂环进度），吸收魂环获得新魂环（不升级）后恢复
 *  - 99 级（极限斗罗）封顶：满级后无法再吸收经验
 */
public final class SoulExp {

    private SoulExp() {}

    /** 单次吸收最多连续升级数（超过部分经验作废，防止低等级玩家抱大腿刷百万年直升几十级） */
    public static final int MAX_LEVELUPS_PER_ABSORB = 5;

    /** 升级所需经验（当前等级 → 下一级） */
    public static int expRequired(int level) {
        return (int) Math.round(100 * Math.pow(level, 1.8));
    }

    /** 等级是否处于节点（10/20/.../90）：节点等级未获得对应魂环时封顶，溢出经验作废 */
    public static boolean isBreakpoint(int level) {
        return level >= 10 && level <= 90 && level % 10 == 0;
    }

    /** 是否处于瓶颈封顶：节点等级且尚未获得对应魂环（已获环数 < 等级/10，如 10 级 0 环、20 级 1 环） */
    public static boolean isBottleneck(PlayerLevelData data) {
        return isBreakpoint(data.getLevel()) && data.getRingCount() < data.getLevel() / 10;
    }

    /** 魂环基础经验 = 10 × 年限^0.8（无环/0 年 → 0） */
    public static double baseExp(int age) {
        if (age <= 0) return 0;
        return 10 * SoulBeastAge.statMultiplierOf(age);
    }

    /**
     * 越级惩罚系数：玩家等级档位（level/10，封顶百万年档）高于魂兽年限档位时，
     * 每高一档 ×0.8；不高于时无惩罚（1.0）。
     * 例：30 级（档位 3）杀千年（档位 2）→ 0.8；30 级杀万年（档位 3）→ 1.0。
     */
    public static double penalty(int playerLevel, int age) {
        int playerTier = Math.min(playerLevel / 10, SoulRingAge.AGE_COUNT - 1);
        int beastTier = SoulBeastAge.tierOf(age);
        int diff = playerTier - beastTier;
        if (diff <= 0) return 1.0;
        return Math.pow(0.8, diff);
    }

    /** 经验数值显示：1 万及以上显示为「X.X万」 */
    public static String formatExp(int value) {
        if (value >= 10000) {
            return String.format("%.1f万", value / 10000.0);
        }
        return String.valueOf(value);
    }

    /**
     * 给玩家加经验并处理连续升级（服务端，吸收魂环后调用）。
     * 升级时：重算血量/攻击力并回满血（setLevel 已回满精神力）、授予等级成就（95/99）、
     * 播放升级音效与脚底粒子、聊天提示新等级。
     * 节点等级（10/20/.../90）未获得对应魂环时封顶：吸收的经验全部作废；
     * 升级途中到达节点等级立即停止，溢出经验全部作废。
     * 99 级封顶不再升级，多余经验保留在经验条内；
     * 单次吸收连升达到 MAX_LEVELUPS_PER_ABSORB 级后，剩余经验全部作废。
     * 返回是否发生了升级。
     */
    public static boolean gainExp(Level level, ServerPlayer player, int gained) {
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());

        // 瓶颈封顶中（节点等级未获得对应魂环）：经验全部作废
        if (isBottleneck(data)) {
            player.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.exp_bottleneck", data.getLevel())
                            .withStyle(ChatFormatting.GOLD));
            return false;
        }

        data.addExp(gained);

        boolean leveled = false;
        int levelUps = 0;
        while (data.getLevel() < TitleSystem.JI_XIAN_LEVEL
                && levelUps < MAX_LEVELUPS_PER_ABSORB
                && data.getExp() >= expRequired(data.getLevel())) {
            data.addExp(-expRequired(data.getLevel()));
            data.setLevel(data.getLevel() + 1);
            leveled = true;
            levelUps++;

            // 升到节点等级（10/20/.../90）：立即封顶，溢出经验全部作废
            if (isBottleneck(data)) {
                int wasted = data.getExp();
                if (wasted > 0) {
                    data.setExp(0);
                    player.sendSystemMessage(
                            Component.translatable("msg.all_spirit_continent.exp_wasted",
                                            data.getLevel(), SoulExp.formatExp(wasted))
                                    .withStyle(ChatFormatting.GOLD));
                }
                break;
            }
        }

        // 连续升级达到上限：剩余经验全部作废（防止百万年魂环让低等级玩家直升几十级）
        if (levelUps >= MAX_LEVELUPS_PER_ABSORB && data.getExp() > 0) {
            int wasted = data.getExp();
            data.setExp(0);
            player.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.exp_capped",
                                    MAX_LEVELUPS_PER_ABSORB, SoulExp.formatExp(wasted))
                            .withStyle(ChatFormatting.GOLD));
        }

        if (leveled) {
            // 升级副作用：重算血量/攻击力加成并回满血 + 升级音效与脚底粒子
            applyLevelUpEffects(level, player);

            // 成就：等级成就（超级斗罗 95 / 极限斗罗 99；境界成就由获得魂环时授予）
            ModAdvancements.awardSoulRank(player, data.getLevel());

            // 提示
            player.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.soul_power_up", data.getLevel())
                            .withStyle(ChatFormatting.AQUA));
        }

        // 处于瓶颈封顶：提示需要吸收魂环才能继续升级
        if (isBottleneck(data)) {
            player.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.exp_bottleneck", data.getLevel())
                            .withStyle(ChatFormatting.GOLD));
        }
        return leveled;
    }

    /**
     * 瓶颈节点吸收魂环实体：获得新魂环（年限 = 魂环实体年限，魂兽多少年魂环就是多少年）——
     * 不升级、不消耗经验。获得魂环后封顶解除，经验条恢复，继续攒经验升级。
     * 返回是否发生了吸收。
     */
    public static boolean absorbRing(Level level, ServerPlayer player, int ringAge) {
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        int lv = data.getLevel();
        if (!isBottleneck(data)) return false;

        // 获得新魂环：年限继承吸收的魂环（魂兽是多少年，魂环就是多少年；年限缺失兜底十年）
        int ringIndex = lv / 10 - 1;
        int age = ringAge > 0 ? ringAge : 10;
        if (data.getRingAge(ringIndex) <= 0) {
            data.setRingAge(ringIndex, age);
        }

        // 精神力上限按新环加成重算（获得更高年限魂环后回满）
        data.refreshSpiritPower();

        // 成就：获得第 N 个魂环 → 对应境界成就（魂师=第1环 … 封号斗罗=第9环）
        ModAdvancements.awardRing(player, ringIndex + 1);

        // 升级副作用：重算血量/攻击力加成并回满血 + 音效与脚底粒子
        applyLevelUpEffects(level, player);

        // 提示：获得新魂环（含年限）+ 新称号（等级不变）
        player.sendSystemMessage(
                Component.translatable("msg.all_spirit_continent.exp_breakthrough",
                                ringIndex + 1, SoulBeastAge.format(age),
                                TitleSystem.getTitle(lv, data.getRingCount(), data.getTitle()))
                        .withStyle(ChatFormatting.GOLD));
        return true;
    }

    /** 升级/突破共用的副作用：重算血量/攻击力并回满血 + 升级音效 + 脚底一圈魔法粒子 */
    private static void applyLevelUpEffects(Level level, ServerPlayer player) {
        SoulGrowth.applyHealth(player);
        SoulGrowth.applyAttack(player);
        player.setHealth(player.getMaxHealth());

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.0F);

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
    }
}
