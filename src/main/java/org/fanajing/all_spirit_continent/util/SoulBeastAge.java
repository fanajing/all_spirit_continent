package org.fanajing.all_spirit_continent.util;

import net.minecraft.ChatFormatting;
import net.minecraft.util.RandomSource;

/**
 * 魂兽年限体系（公共工具类，服务端与客户端共用）。
 * <p>
 * 规则总览：
 *  - 年限范围 1 年 ~ 9999 万年（MAX_AGE = 99_990_000）
 *  - 年限越高生成概率越低（权重概率表）
 *  - 无攻击手段的被动生物年限相对较低：1 ~ 10 万年
 *  - 出生点 5000 格内为新手保护区：不生成十万年及以上魂兽（降级用 CAPPED_TABLE）
 *  - 年限档位（tier 0~5）与玩家魂环颜色体系（SoulRingAge）共用 6 档贴图
 *  - 年限越高：体型越大（scaleOf）、攻击力与生命值越高（statMultiplierOf，血攻同倍数保持原版比例）
 */
public final class SoulBeastAge {

    /** 魂兽年限上限：9999 万年 */
    public static final int MAX_AGE = 99_990_000;
    /** 百万年分界线（>= 此值视为百万年魂兽，受全世界数量与冷却限制） */
    public static final int MILLION_AGE = 1_000_000;
    /** 十万年分界线（>= 此值禁止生成在出生点 5000 格内） */
    public static final int HUNDRED_THOUSAND_AGE = 100_000;

    /** 年限档位（min 含 / max 含 / 权重）。权重总和为 1000 */
    private record AgeRange(int min, int max, int weight) {}

    /** 有攻击手段的生物（怪物/狼等）：1 年 ~ 9999 万年，年限越高概率越低 */
    private static final AgeRange[] HOSTILE_TABLE = {
            new AgeRange(1, 99, 450),                    // 十年（45%）
            new AgeRange(100, 999, 250),                 // 百年（25%）
            new AgeRange(1_000, 9_999, 150),             // 千年（15%）
            new AgeRange(10_000, 99_999, 80),            // 万年（8%）
            new AgeRange(100_000, 999_999, 50),          // 十万年（5%）
            new AgeRange(1_000_000, 9_999_999, 15),      // 百万年（1.5%）
            new AgeRange(10_000_000, 99_989_999, 4),     // 千万年（0.4%）
            new AgeRange(99_990_000, 99_990_000, 1)      // 9999万年（0.1%）
    };

    /** 无攻击手段的被动生物：1 ~ 10 万年，年限整体偏低 */
    private static final AgeRange[] PASSIVE_TABLE = {
            new AgeRange(1, 99, 50),                     // 十年（50%）
            new AgeRange(100, 999, 25),                  // 百年（25%）
            new AgeRange(1_000, 9_999, 12),              // 千年（12%）
            new AgeRange(10_000, 99_999, 8),             // 万年（8%）
            new AgeRange(100_000, 100_000, 5)            // 十万年（5%）
    };

    /** 出生点 5000 格内新手保护区：十万年及以上降级时使用的截断表（1 ~ 9 万年） */
    private static final AgeRange[] CAPPED_TABLE = {
            new AgeRange(1, 99, 50),
            new AgeRange(100, 999, 28),
            new AgeRange(1_000, 9_999, 15),
            new AgeRange(10_000, 99_999, 7)
    };

    private SoulBeastAge() {}

    // ===== 年限随机 =====

    /**
     * 按权重表随机一个年限档位并返回档内随机年限。
     * @param hostile 是否有攻击手段（true 用敌对表，false 用被动表）
     * @param nearSpawn 是否在出生点 5000 格内（true 时十万年及以上降级）
     */
    public static int rollAge(RandomSource random, boolean hostile, boolean nearSpawn) {
        AgeRange range = rollRange(hostile ? HOSTILE_TABLE : PASSIVE_TABLE, random);
        int age = range.min() + random.nextInt(range.max() - range.min() + 1);
        if (nearSpawn && age >= HUNDRED_THOUSAND_AGE) {
            AgeRange capped = rollRange(CAPPED_TABLE, random);
            age = capped.min() + random.nextInt(capped.max() - capped.min() + 1);
        }
        return age;
    }

    /** 权重轮盘：按权重随机选一个档位 */
    private static AgeRange rollRange(AgeRange[] table, RandomSource random) {
        int total = 0;
        for (AgeRange range : table) {
            total += range.weight();
        }
        int roll = random.nextInt(total);
        for (AgeRange range : table) {
            roll -= range.weight();
            if (roll < 0) {
                return range;
            }
        }
        return table[table.length - 1];
    }

    // ===== 档位与显示 =====

    /** 年限 → 魂环颜色档位（0~5，与 SoulRingAge 贴图一致） */
    public static int tierOf(int age) {
        if (age < 100) return 0;
        if (age < 1_000) return 1;
        if (age < 10_000) return 2;
        if (age < 100_000) return 3;
        if (age < 1_000_000) return 4;
        return 5;
    }

    /**
     * 档位 → 头顶年限文字颜色（0xAARRGGBB；alpha 必须非 0，否则 Font 渲染时
     * alpha 分量乘入颜色导致文字完全透明；万年档用浅灰保证黑夜里可读）
     */
    public static int tierColor(int tier) {
        return switch (tier) {
            case 1 -> 0xFFFFD700;   // 百年：黄
            case 2 -> 0xFFB87BFF;   // 千年：紫
            case 3 -> 0xFF8B8B8B;   // 万年：浅灰（黑色文字看不清）
            case 4 -> 0xFFFF3B3B;   // 十万年：红
            case 5 -> 0xFFFFAA00;   // 百万年及以上：金
            default -> 0xFFFFFFFF;  // 十年：白
        };
    }

    /**
     * 年限 → 聊天颜色（与 tierColor 的档位色语义一致，用于聊天栏/提示上色）：
     * 十年白 / 百年黄 / 千年浅紫 / 万年灰 / 十万年红 / 百万年金。
     */
    public static ChatFormatting tierFormatting(int age) {
        return switch (tierOf(age)) {
            case 1 -> ChatFormatting.YELLOW;        // 百年：黄
            case 2 -> ChatFormatting.LIGHT_PURPLE;  // 千年：紫
            case 3 -> ChatFormatting.GRAY;          // 万年：灰
            case 4 -> ChatFormatting.RED;           // 十万年：红
            case 5 -> ChatFormatting.GOLD;          // 百万年及以上：金
            default -> ChatFormatting.WHITE;        // 十年：白
        };
    }

    /**
     * 年限显示文本：始终显示完整年数（如 6070年、42813年、99990000年）。
     * 魂兽头顶命名牌、魂环实体、查看面板、吸收提示、调试棒年限提示等全部复用此格式。
     */
    public static String format(int age) {
        return age + "年";
    }

    /** 是否百万年及以上魂兽（受全世界数量限制） */
    public static boolean isMillion(int age) {
        return age >= MILLION_AGE;
    }

    // ===== 年限加成 =====

    /**
     * 体型缩放系数（1.0 起，随年限对数增长，每档 +0.15 明显递增）：
     * 十年约 1.16、百年约 1.30、千年约 1.45、万年约 1.60、十万年约 1.75、
     * 百万年约 1.90、千万年约 2.05、9999万年约 2.20。
     * 通过实体的 scale 属性生效，碰撞箱与模型同步放大。
     */
    public static double scaleOf(int age) {
        return 1.0 + 0.15 * Math.log10(age + 1);
    }

    /**
     * 属性倍数（血量与攻击力共用同一倍数，保持生物自身的原版血攻比例，如僵尸 20:3）：
     * max(age,1)^0.8 —— 十年约 ×6.3、百年约 ×40、千年约 ×251、万年约 ×1585、十万年 ×10000、
     * 百万年约 ×6.3万、千万年约 ×40万、9999万年约 ×250万。
     * 1 年 ×1（原版值）。僵尸（基础 20 血 / 3 攻）到 9999 万年约 5000 万血、750 万攻。
     */
    public static double statMultiplierOf(int age) {
        return Math.pow(Math.max(age, 1), 0.8);
    }
}
