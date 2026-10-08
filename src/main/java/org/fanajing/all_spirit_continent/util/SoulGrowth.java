package org.fanajing.all_spirit_continent.util;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.init.ModAttachments;

import java.util.List;

/**
 * 玩家成长体系 v5：血量与攻击力、魂力全部由统一的「战力年限 W」驱动。
 * <p>
 * 设计要点（完整推导见 `战力数值设计.md`）：
 * <pre>
 *   W = max(等级等效年限 lea(L), 各环年限) + SIDE_W × 其余年限之和
 *
 *   lea(L)    = 55 × 1.1357^(L-1)          // 每 20 级 ×12.7 ≈ 推进一个年限档
 *   最大生命  = 20 + 38.4 × W^0.95
 *   攻击力    = 武器 + 0.5 × W^0.85
 *   魂力上限 = 4.0 × W^0.80
 * </pre>
 * <p>
 * 为什么用 W 而不是「九个环相加」：
 * 「能否击杀」是乘性判据（击杀耗时 × 生存时间），九个环线性相加会让每个新环的边际收益
 * 超线性放大，导致战力指数爆炸。改成先合成标量 W 再取幂，边际收益有界
 * （九个等强环最多 ×1.96），爆炸被结构性消除；同时符合原作设定 ——
 * 魂师战力上限由**最强魂环**决定，不是九个环相加。
 * <p>
 * 血量指数（0.95）高于攻击指数（0.85）是刻意的：高阶魂师越来越扛得住，但杀怪不会越来越快，
 * 战斗时长因此全程稳定在 10~40 秒，不会后期秒杀。
 */
public final class SoulGrowth {

    // ===== 战力年限 W =====
    /** 1 级等效年限（正好落在十年档，1 级玩家打得动十年魂兽） */
    public static final float LEA_BASE = 55f;
    /** 每级成长率：每 20 级 ×12.7，由二分求解得出（使命中「99 级可击杀上限 ≈ 2.5×10⁷」） */
    public static final double LEA_GROW = 1.1357;
    /** 非最强项的递减权重：九个等强环最多把 W 抬到 1 + 8×0.12 = 1.96 倍 */
    public static final float SIDE_W = 0.12f;

    // ===== 玩家属性 =====
    /** 原版基础血量 */
    public static final float BASE_HEALTH = 20f;
    /** 血量系数（与 ATTACK_K 的乘积 38.4×0.5=19.2 由「可击杀上限 ≈ 战力年限」反解得出） */
    public static final float HEALTH_K = 38.4f;
    /** 血量指数（> 攻击指数，见类注释） */
    public static final double HEALTH_EXP = 0.95;
    /** 攻击系数（武器伤害由武器本身提供，不计入此） */
    public static final float ATTACK_K = 0.5f;
    /** 攻击指数 */
    public static final double ATTACK_EXP = 0.85;
    /** 魂力上限系数 */
    public static final float SOUL_POWER_K = 4.0f;
    /** 魂力上限指数（低于攻击指数 0.05 → 满池可释放次数随等级缓慢下降） */
    public static final double SOUL_POWER_EXP = 0.80;

    // ===== 魂力消耗与回复 =====
    /** 魂技消耗系数：消耗 = SKILL_COST_K × W^0.85 × 复杂度 */
    public static final float SKILL_COST_K = 0.10f;
    /** 复杂度按魂环位序递增（原作：高阶魂技消耗剧增）：第 1 环 0.6 → 第 9 环 2.2 */
    public static final float[] SKILL_COMPLEXITY =
            {0.6f, 0.8f, 1.0f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f, 2.2f};
    /** 自然回复：每 2 秒回复上限的 2%（满池约 100 秒） */
    public static final float SOUL_POWER_REGEN_PCT = 0.02f;

    /** 血量加成修饰 ID（幂等重算：先移除同 ID 旧修饰符再加新的） */
    public static final ResourceLocation HEALTH_MOD_ID =
            ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "soul_growth_health");
    /** 攻击力加成修饰 ID（幂等重算：先移除同 ID 旧修饰符再加新的） */
    public static final ResourceLocation ATTACK_MOD_ID =
            ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "soul_growth_attack");

    private SoulGrowth() {}

    // ===== 战力年限 =====

    /** 等级等效年限：等级自带的「第 10 个魂环」 */
    public static double levelEquivalentAge(int level) {
        return LEA_BASE * Math.pow(LEA_GROW, Math.max(1, level) - 1);
    }

    /**
     * 战力年限 W = max(等级等效年限, 各环年限) + SIDE_W × 其余年限之和。
     * 最强项主导、其余递减贡献，边际收益有界（9 个等强环最多 ×1.96）。
     */
    public static double effectiveYears(PlayerLevelData data) {
        double top = levelEquivalentAge(data.getLevel());
        double sum = top;
        for (int age : data.getRingAges()) {
            if (age <= 0) continue;
            if (age > top) top = age;
            sum += age;
        }
        return top + SIDE_W * Math.max(0.0, sum - top);
    }

    // ===== 血量 =====

    /** 总最大生命 = 原版基础 + 战力年限加成 */
    public static float maxHealth(PlayerLevelData data) {
        return BASE_HEALTH + HEALTH_K * (float) Math.pow(effectiveYears(data), HEALTH_EXP);
    }

    /** 血量加成（挂到 max_health 上的增量部分） */
    public static float healthBonus(PlayerLevelData data) {
        return maxHealth(data) - BASE_HEALTH;
    }

    /** 幂等应用血量加成到玩家 max_health */
    public static void applyHealth(Player player) {
        AttributeInstance attr = player.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) return;
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        attr.removeModifier(HEALTH_MOD_ID);
        float bonus = healthBonus(data);
        if (bonus > 0) {
            attr.addTransientModifier(new AttributeModifier(HEALTH_MOD_ID,
                    bonus, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    // ===== 攻击力 =====

    /** 攻击力加成（不含武器伤害，武器由物品本身提供） */
    public static float attackBonus(PlayerLevelData data) {
        return ATTACK_K * (float) Math.pow(effectiveYears(data), ATTACK_EXP);
    }

    /** 幂等应用攻击力加成到玩家 attack_damage（transient 不持久化，登录/克隆后必须重新调用） */
    public static void applyAttack(Player player) {
        AttributeInstance attr = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attr == null) return;
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        attr.removeModifier(ATTACK_MOD_ID);
        float bonus = attackBonus(data);
        if (bonus > 0) {
            attr.addTransientModifier(new AttributeModifier(ATTACK_MOD_ID,
                    bonus, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    // ===== 魂力 =====

    /** 魂力上限 = 4.0 × W^0.80 */
    public static float maxSoulPower(PlayerLevelData data) {
        return SOUL_POWER_K * (float) Math.pow(effectiveYears(data), SOUL_POWER_EXP);
    }

    /**
     * 魂技消耗 = 0.10 × W^0.85 × 复杂度。
     * 与魂力上限（W^0.80）的指数差 0.05 使满池可释放次数随等级缓慢下降，
     * 后期资源管理压力递增。
     *
     * @param ringAge 该魂技绑定的魂环年限（用于定位环位 → 复杂度）；0/未匹配时取最低复杂度
     */
    public static float skillCost(PlayerLevelData data, int ringAge) {
        return (float) (SKILL_COST_K * Math.pow(effectiveYears(data), ATTACK_EXP)
                * complexityOf(data, ringAge));
    }

    /** 复杂度：按魂环位序（第 1 环 0.6 → 第 9 环 2.2）；年限未匹配到环位时按年限量级取 */
    private static float complexityOf(PlayerLevelData data, int ringAge) {
        List<Integer> ages = data.getRingAges();
        int idx = -1;
        if (ringAge > 0) {
            for (int i = 0; i < ages.size(); i++) {
                if (ages.get(i) == ringAge) {
                    idx = i;
                    break;
                }
            }
        }
        if (idx < 0) {
            // 未绑定具体环位：按年限量级映射到 1~9 环（十年→第1环，千万年→第9环）
            idx = ringAge <= 0 ? 0
                    : Math.min(SKILL_COMPLEXITY.length - 1,
                    Math.max(0, (int) (Math.log10(Math.max(ringAge, 1)) - 1)));
        }
        return SKILL_COMPLEXITY[Math.min(idx, SKILL_COMPLEXITY.length - 1)];
    }

    /** 每 2 秒的自然回复量（满池约 100 秒） */
    public static float spiritRegenAmount(PlayerLevelData data) {
        return Math.max(1f, maxSoulPower(data) * SOUL_POWER_REGEN_PCT);
    }
}
