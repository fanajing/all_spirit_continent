package org.fanajing.all_spirit_continent.util;

import net.minecraft.ChatFormatting;
import net.minecraft.util.RandomSource;

/**
 * 魂兽年限体系（公共工具类，服务端与客户端共用）。
 * <p>
 * 规则总览：
 *  - 年限范围 1 年 ~ 9999 万年（MAX_AGE = 99_990_000）
 *  - 年限越高生成概率越低（权重概率表）
 *  - 【V0.0.2】无攻击手段的非敌对生物：硬性封顶 1 ~ 9999 年，永远够不到万年
 *  - 出生点 5000 格内为新手保护区：不生成十万年及以上魂兽（降级用 CAPPED_TABLE）
 *  - 年限档位（tier 0~5）与玩家魂环颜色体系（SoulRingAge）共用 6 档贴图
 *  - 年限越高：体型越大（scaleOf）、生命值越高（healthMultiplierOf = 年限^0.80）、
 *    攻击力越高（attackMultiplierOf = 年限^0.95，比血量陡 0.15 → 跨档碾压感）
 */
public final class SoulBeastAge {

    /** 魂兽年限上限：9999 万年 */
    public static final int MAX_AGE = 99_990_000;
    /** 百万年分界线（>= 此值视为百万年魂兽，受全世界数量与冷却限制） */
    public static final int MILLION_AGE = 1_000_000;
    /** 十万年分界线（>= 此值禁止生成在出生点 5000 格内） */
    public static final int HUNDRED_THOUSAND_AGE = 100_000;
    /**
     * 【V0.0.2】无攻击手段的非敌对生物年限上限：9999 年。
     * <p>
     * 万年（10_000）起步需要「以战养战」，而这类生物没有攻击手段、
     * 永远无法与玩家或同阶对手产生敌对关系，缺少修炼年限的唯一途径，
     * 因此被硬性钉死在千年档封顶（最高 9999 年）。
     */
    public static final int PASSIVE_MAX_AGE = 9_999;
    /** 千年分界线（>= 此值在主世界需满足「远离出生点 / 深层 / 夜晚」才生成，否则降级） */
    public static final int THOUSAND_AGE = 1_000;

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

    /**
     * 【V0.0.2】无攻击手段的非敌对生物（鸡/牛/羊/猪/村民/美西螈等）：封顶 1 ~ 9999 年。
     * <p>
     * 已剔除原「万年 / 十万年」两档 —— 这类生物没有攻击手段、无法与玩家产生敌对关系，
     * 也就打不赢同阶对手去积累年限，够不到万年关口。权重按剔除后同比例重标定
     * （原 50/25/12 于总量 87 中占比 ≈ 57.5%/28.7%/13.8% → 取整 580/290/130）。
     */
    private static final AgeRange[] PASSIVE_TABLE = {
            new AgeRange(1, 99, 580),                    // 十年（58%）
            new AgeRange(100, 999, 290),                 // 百年（29%）
            new AgeRange(1_000, 9_999, 130)              // 千年（13%）——最高档，不含万年
    };

    /** 出生点 5000 格内新手保护区：十万年及以上降级时使用的截断表（1 ~ 9 万年） */
    private static final AgeRange[] CAPPED_TABLE = {
            new AgeRange(1, 99, 50),
            new AgeRange(100, 999, 28),
            new AgeRange(1_000, 9_999, 15),
            new AgeRange(10_000, 99_999, 7)
    };

    /**
     * 主世界安全区（近出生点 + 地表 + 白天）降级表：只出十年/百年。
     * <p>
     * rationale：按战力模型推演，1 级玩家战力年限 W=55，千年魂兽 ratio≈100 会触发**恐惧档**
     * （移速 −70%、无法攻击），且生存时间只有 1 秒 —— 新手区刷千年等于必死。
     * 百年对 1 级玩家是威压档（生存 9 秒）仍危险，但玩家有反应窗口，属于可接受的挑战。
     * 「远离出生点 / 深层 / 夜晚」三选一即解除限制 —— 三者都是玩家**主动承担风险**的行为。
     */
    private static final AgeRange[] SAFE_TABLE = {
            new AgeRange(1, 99, 70),                     // 十年（70%）
            new AgeRange(100, 999, 30)                   // 百年（30%）
    };

    private SoulBeastAge() {}

    // ===== 年限随机 =====

    /**
     * 按权重表随机一个年限档位并返回档内随机年限。
     * @param hostile 是否有攻击手段（true 用敌对表，false 用被动表）
     * @param nearSpawn 是否在出生点 5000 格内（true 时十万年及以上降级）
     * @param allowHighTier 是否允许千年及以上（主世界安全区为 false 时降级到百年以内）
     */
    public static int rollAge(RandomSource random, boolean hostile, boolean nearSpawn, boolean allowHighTier) {
        AgeRange range = rollRange(hostile ? HOSTILE_TABLE : PASSIVE_TABLE, random);
        int age = range.min() + random.nextInt(range.max() - range.min() + 1);
        // 千年及以上：主世界安全区降级（被动生物不威胁玩家生命，不在此限制内）
        if (hostile && !allowHighTier && age >= THOUSAND_AGE) {
            AgeRange safe = rollRange(SAFE_TABLE, random);
            age = safe.min() + random.nextInt(safe.max() - safe.min() + 1);
        } else if (nearSpawn && age >= HUNDRED_THOUSAND_AGE) {
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
     * 通用强度标度 max(age,1)^0.8：击杀经验（10×年限^0.8）与魂兽血量共用这一标度。
     * 十年约 ×22.9、百年约 ×144、千年约 ×908、万年约 ×5730、十万年 ×3.6万、
     * 百万年约 ×22.9万、千万年约 ×144万、9999万年约 ×251万。
     * 注：魂兽的**攻击力**不再使用本方法，见 {@link #attackMultiplierOf(int)}。
     */
    public static double statMultiplierOf(int age) {
        return Math.pow(Math.max(age, 1), 0.8);
    }

    /**
     * 血量倍数：年限^0.80（与击杀经验同标度）。
     * 僵尸（基础 20 血）：十年约 457、百年约 3,114、千年约 19,648、万年约 12.4万、
     * 十万年约 78万、百万年约 494万、千万年约 3,114万、9999万年约 5,023万。
     */
    public static double healthMultiplierOf(int age) {
        return Math.pow(Math.max(age, 1), 0.80);
    }

    /**
     * 攻击倍数：年限^0.95 —— 比血量指数陡 0.15，这是「跨档碾压 / 原著恐怖感」的来源。
     * 年限 ×10（跨一档）时血量涨 6.3 倍、攻击涨 8.9 倍，攻击多涨 1.41 倍；
     * 叠加生存判据后，跨一档的「能否击杀」判据陡降约 56 倍 —— 高一档不是打得吃力，是必死。
     * 僵尸（基础 3 攻）：十年约 123、百年约 1,204、千年约 1.07万、万年约 9.56万、
     * 十万年约 85万、百万年约 759万、千万年约 6,768万、9999万年约 1.19亿。
     */
    public static double attackMultiplierOf(int age) {
        return Math.pow(Math.max(age, 1), 0.95);
    }
}
