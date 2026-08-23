package org.fanajing.all_spirit_continent.util;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import org.fanajing.all_spirit_continent.All_spirit_continent;

/**
 * 成就系统工具。
 * 成就页（全魂大陆）与「觉醒武魂？！」成就由数据包 JSON 定义
 * （data/all_spirit_continent/advancement/），其中「觉醒武魂？！」用
 * inventory_changed 触发器在获得水晶球时自动完成。
 * 分支 1 境界成就链（魂士→极限斗罗）用 impossible 触发器 + 本类授予：
 * 《魂士》在使用完水晶球（激活武魂）时授予；《魂师》~《封号斗罗》在
 * 获得对应魂环时授予（awardRing，节点等级封顶后吸收魂环获得新魂环/调试棒）；
 * 《超级斗罗》《极限斗罗》按等级（95/99）在升级时授予（awardSoulRank）。
 */
public final class ModAdvancements {

    /** 等级成就清单：{等级阈值, 成就编号}（95 超级斗罗 / 99 极限斗罗；
     * rank_1 魂士由使用水晶球触发、rank_2~10 境界成就由 awardRing 按获得魂环授予） */
    private static final int[][] LEVEL_ADVANCEMENTS = {
            {95, 11},  // 超级斗罗：95 级
            {99, 12}   // 极限斗罗：99 级
    };

    private ModAdvancements() {}

    /** 使用完水晶球（觉醒武魂）后调用：授予《魂士》成就 */
    public static void awardSoulMaster(ServerPlayer player) {
        ServerAdvancementManager manager = player.getServer().getAdvancements();
        award(player, manager, ResourceLocation.fromNamespaceAndPath(
                All_spirit_continent.MODID, "soul_rank/rank_1"));
    }

    /**
     * 获得第 ringCount 个魂环（1~9）后调用：授予对应境界成就
     * （第1环=魂师 rank_2 … 第9环=封号斗罗 rank_10）。
     * 节点等级（10/20/.../90）封顶期间不授予，吸收魂环获得新魂环时才授予。
     */
    public static void awardRing(ServerPlayer player, int ringCount) {
        if (ringCount < 1 || ringCount > 9) return;
        ServerAdvancementManager manager = player.getServer().getAdvancements();
        award(player, manager, ResourceLocation.fromNamespaceAndPath(
                All_spirit_continent.MODID, "soul_rank/rank_" + (ringCount + 1)));
    }

    /** 等级变化后调用：授予所有已达等级阈值的等级成就（重复授予无害） */
    public static void awardSoulRank(ServerPlayer player, int level) {
        ServerAdvancementManager manager = player.getServer().getAdvancements();
        for (int[] entry : LEVEL_ADVANCEMENTS) {
            if (level >= entry[0]) {
                award(player, manager, ResourceLocation.fromNamespaceAndPath(
                        All_spirit_continent.MODID, "soul_rank/rank_" + entry[1]));
            }
        }
    }

    /** 授予指定成就的 requirement 条件（成就不存在时静默跳过） */
    private static void award(ServerPlayer player, ServerAdvancementManager manager, ResourceLocation id) {
        AdvancementHolder holder = manager.get(id);
        if (holder != null) {
            player.getAdvancements().award(holder, "requirement");
        }
    }
}
