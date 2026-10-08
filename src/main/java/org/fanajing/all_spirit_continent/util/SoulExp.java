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
 *  - 升级所需经验 expRequired(level)：查表 XP_TABLE（98 项，由战力模型反解生成，命中 60 小时毕业）
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

    /**
     * 单次击杀/吸收的经验软上限（倍率 × 当前级升级所需）。
     * 没有它时 95 级击杀一只千万年魂兽可直接连升 3.25 级，RNG 好的玩家能比别人快一倍——
     * 这是结构性方差，不是技术差距。2.0 表示单次最多抵两级。
     */
    public static final double MAX_EXP_PER_KILL_RATIO = 2.0;

    /**
     * L 级升 L+1 级所需经验，下标 0 = 1 级（共 98 项，覆盖 1→99）。
     * <p>
     * 由 `tools/exp_curve_model.py` 按「每段目标时长 ÷ 该级实际经验产出」逐级反解生成，
     * 收敛误差 0.000%，总时长命中 60.4 小时。**不要手改** —— 改参数后重跑脚本刷新全表。
     * 用查表而非公式的原因：产出在跨阈值时会阶跃（9 级能打百年，经验/小时一夜跳 4.7 倍），
     * 任何平滑公式都会算出「8 级 5 分钟、9 级 1 分钟」的倒挂节奏。
     */
    private static final long[] XP_TABLE = {
            379L, 383L, 386L, 390L, 393L, 396L, 399L,
            402L, 405L, 735L, 740L, 744L, 748L, 751L,
            755L, 758L, 761L, 2783L, 2816L, 4784L, 4825L,
            4863L, 4900L, 4934L, 4965L, 4995L, 5022L, 5047L,
            5070L, 7643L, 7671L, 7696L, 7720L, 7741L, 23066L,
            23224L, 23368L, 23500L, 23621L, 39659L, 39805L, 39940L,
            40065L, 40179L, 40283L, 40379L, 40466L, 40545L, 40617L,
            52901L, 52975L, 53043L, 682106L, 691383L, 699991L, 707954L,
            715300L, 722059L, 728264L, 906597L, 912365L, 917689L, 922588L,
            927085L, 931204L, 934969L, 938403L, 941531L, 944375L, 1184135L,
            4913578L, 4958119L, 4999088L, 5036682L, 5071104L, 5102560L, 5131253L,
            5157384L, 5181146L, 5731779L, 5751574L, 5769681L, 5786211L, 5801274L,
            5814979L, 5827428L, 5838723L, 25821743L, 26003309L, 29430090L, 29443737L,
            29515554L, 29632329L, 29740448L, 29840236L, 29932070L, 30016365L, 30093556L,
    };

    /**
     * 升级所需经验（当前等级 → 下一级）。
     * 99 级（极限斗罗）已封顶，返回 int 上限表示不再需要经验。
     */
    public static int expRequired(int level) {
        if (level < 1) return 0;
        if (level > XP_TABLE.length) return Integer.MAX_VALUE;
        return (int) XP_TABLE[level - 1];
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
     * 升级时：重算血量/攻击力并回满血（setLevel 已回满魂力）、授予等级成就（95/99）、
     * 播放升级音效与脚底粒子、聊天提示新等级。
     * 节点等级（10/20/.../90）未获得对应魂环时封顶：吸收的经验全部作废；
     * 升级途中到达节点等级立即停止，溢出经验全部作废。
     * 99 级封顶不再升级，多余经验保留在经验条内；
     * 单次吸收连升达到 MAX_LEVELUPS_PER_ABSORB 级后，剩余经验全部作废。
     * 返回是否发生了升级。
     */
    public static boolean gainExp(Level level, ServerPlayer player, int rawGained) {
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());

        // 瓶颈封顶中（节点等级未获得对应魂环）：经验全部作废
        if (isBottleneck(data)) {
            player.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.exp_bottleneck", data.getLevel())
                            .withStyle(ChatFormatting.GOLD));
            return false;
        }

        // 单次经验软上限：95 级杀一只千万年可抵 3.25 级，这里压到最多 2 级，
        // 把「一次好运」的收益封顶，避免 RNG 造成的结构性进度方差
        int gained = (int) Math.min(rawGained, MAX_EXP_PER_KILL_RATIO * expRequired(data.getLevel()));
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

        // 魂力上限按新环加成重算（获得更高年限魂环后回满）
        data.refreshSoulPower();

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
