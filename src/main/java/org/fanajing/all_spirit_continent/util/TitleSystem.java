package org.fanajing.all_spirit_continent.util;

import net.minecraft.network.chat.Component;

/**
 * 称号系统：根据玩家等级、已获魂环数与自定义封号计算当前称号。
 *
 * 境界划分（已获魂环数 / 称号）：
 *   0 环 魂士（1-10 级，节点封顶未获得魂环时仍为魂士）
 *   1 环 魂师（10 级获得第 1 环后晋升，至 20 级获得第 2 环）
 *   2 环 大魂师   3 环 魂尊   4 环 魂宗   5 环 魂王
 *   6 环 魂帝   7 环 魂圣   8 环 魂斗罗
 *   9 环 封号斗罗（90 级获得第 9 环后晋升；自定义封号后称为 xx斗罗）
 *   91-94 封号斗罗（可自定义封号，未自定义则称「封号斗罗」）
 *   95-98 超级斗罗（自定义封号后称为 xx斗罗（超级））
 *   99    极限斗罗（自定义封号后称为 xx斗罗（极限））
 *   100   神祇（未完成，当前版本最高 99 级无法达到）
 */
public final class TitleSystem {
    /** 封号斗罗最低等级（可自定义封号） */
    public static final int FENG_HAO_LEVEL = 91;
    /** 魂帝最低等级（61 级起解锁精神力飞行） */
    public static final int SOUL_EMPEROR_LEVEL = 61;
    /** 超级斗罗最低等级 */
    public static final int CHAO_JI_LEVEL = 95;
    /** 极限斗罗等级 */
    public static final int JI_XIAN_LEVEL = 99;
    /** 神祇等级（未完成） */
    public static final int SHEN_ZHI_LEVEL = 100;

    private TitleSystem() {}

    /** 计算当前称号；等级小于 1（未激活异常）时返回空文本。
     * ringCount = 已获魂环数（境界由魂环决定：0 环=魂士 … 9 环=封号斗罗，
     * 91 级可自定义封号、95 超级斗罗、99 极限斗罗按等级覆盖） */
    public static Component getTitle(int level, int ringCount, String customTitle) {
        if (level < 1) return Component.empty();
        String fh = customTitle == null ? "" : customTitle;

        if (level >= SHEN_ZHI_LEVEL) {
            // 神祇：目前标注（未完成），暂不支持自定义
            return Component.translatable("title.all_spirit_continent.shen_zhi");
        }
        if (level >= JI_XIAN_LEVEL) {
            // 极限斗罗：自定义封号 → xx斗罗（极限）
            return fh.isEmpty()
                    ? Component.translatable("title.all_spirit_continent.ji_xian_dou_luo")
                    : Component.literal(fh)
                            .append(Component.translatable("title.all_spirit_continent.dou_luo"))
                            .append(Component.translatable("title.all_spirit_continent.limit_mark"));
        }
        if (level >= CHAO_JI_LEVEL) {
            // 超级斗罗：自定义封号 → xx斗罗（超级）
            return fh.isEmpty()
                    ? Component.translatable("title.all_spirit_continent.chao_ji_dou_luo")
                    : Component.literal(fh)
                            .append(Component.translatable("title.all_spirit_continent.dou_luo"))
                            .append(Component.translatable("title.all_spirit_continent.super_mark"));
        }
        if (level >= FENG_HAO_LEVEL || ringCount >= 9) {
            // 封号斗罗：91 级起或已获第 9 环；自定义封号 → xx斗罗
            return fh.isEmpty()
                    ? Component.translatable("title.all_spirit_continent.feng_hao_dou_luo")
                    : Component.literal(fh).append(Component.translatable("title.all_spirit_continent.dou_luo"));
        }

        // 91 级以下：境界由已获魂环数决定（tier_1 魂士 … tier_9 魂斗罗）
        int tier = Math.min(ringCount + 1, 9);
        return Component.translatable("title.all_spirit_continent.tier_" + Math.max(1, tier));
    }
}
