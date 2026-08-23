package org.fanajing.all_spirit_continent.util;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.init.ModAttachments;

/**
 * 玩家成长体系：血量与精神力随等级提升，魂环按对应年限魂兽的血量/精神力量级加成（9 环独立累加）。
 * <p>
 * 公式总览：
 *  - 血量 = 20 基础 + 等级加成（每级 +2）+ 魂环年限加成
 *  - 魂环年限加成（每环独立，与魂兽血攻同一曲线 statMultiplierOf = 年限^0.8）：
 *    血量基准 20 × 年限^0.8 —— 十年≈126 / 百年≈800 / 千年≈5030 / 万年≈3.2万 / 十万年=20万 / 百万年=126万
 *  - 攻击力 = 原版基础 + 魂环年限加成（基准 2 × 年限^0.8，为血量基准的 1/10，百万年≈12.6万）
 *  - 精神力上限 = 100 + (等级 - 1) × 10 + 魂环年限加成（基准 1 × 年限^0.8，百万年≈6.3万）
 *  - 支持非整数级魂环（如 6070 年）：年限直接代入公式，无需离散档位表
 */
public final class SoulGrowth {

    /** 玩家基础血量（原版 20 点） */
    public static final float BASE_HEALTH = 20f;
    /** 每级血量加成（等级 - 1 为加成等级数，99 级共 +196） */
    public static final float HEALTH_PER_LEVEL = 2f;
    /** 精神力基础上限（1 级） */
    public static final float BASE_SPIRIT = 100f;
    /** 每级精神力上限成长 */
    public static final float SPIRIT_PER_LEVEL = 10f;

    /** 魂环血量加成基准（对齐僵尸/玩家基础血量 20，与魂兽血攻曲线同指数） */
    public static final float RING_BASE_HEALTH = 20f;
    /** 魂环攻击力加成基准（血量基准的 1/10，与魂兽血攻比例同量级，百万年环 ≈ 12.6万攻击） */
    public static final float RING_BASE_ATTACK = 2f;
    /** 魂环精神力加成基准（相对血量缩减 100 倍，百万年环 ≈ 6.3万） */
    public static final float RING_BASE_SPIRIT = 1f;

    /** 血量加成修饰 ID（幂等重算：先移除同 ID 旧修饰符再加新的） */
    public static final ResourceLocation HEALTH_MOD_ID =
            ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "soul_growth_health");
    /** 攻击力加成修饰 ID（幂等重算：先移除同 ID 旧修饰符再加新的） */
    public static final ResourceLocation ATTACK_MOD_ID =
            ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "soul_growth_attack");

    private SoulGrowth() {}

    // ===== 血量 =====

    /** 等级 → 等级血量加成（1 级无加成） */
    public static float levelHealthBonus(int level) {
        return (level - 1) * HEALTH_PER_LEVEL;
    }

    /** 单环血量加成 = 基准 × 年限^0.8（无环/0 年 → 0） */
    public static float ringHealthBonus(int age) {
        if (age <= 0) return 0;
        return RING_BASE_HEALTH * (float) SoulBeastAge.statMultiplierOf(age);
    }

    /** 已获魂环的总血量加成（9 环独立累加，未获得的环不计算） */
    public static float ringsHealthBonus(PlayerLevelData data) {
        int ringCount = Math.min(data.getRingCount(), SoulRingLayout.MAX_RINGS);
        float sum = 0;
        for (int i = 0; i < ringCount; i++) {
            sum += ringHealthBonus(data.getRingAge(i));
        }
        return sum;
    }

    /** 总血量加成 = 等级加成 + 魂环年限加成 */
    public static float totalHealthBonus(PlayerLevelData data) {
        return levelHealthBonus(data.getLevel()) + ringsHealthBonus(data);
    }

    /**
     * 幂等应用血量加成到玩家 max_health：先移除同 ID 旧修饰符再按当前等级/魂环重算。
     * 登录、换维度、死亡重生后都要重新调用（transient 修饰符不随 NBT 持久化）。
     */
    public static void applyHealth(Player player) {
        AttributeInstance attr = player.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) return;
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        attr.removeModifier(HEALTH_MOD_ID);
        float bonus = totalHealthBonus(data);
        if (bonus > 0) {
            attr.addTransientModifier(new AttributeModifier(HEALTH_MOD_ID,
                    bonus, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    // ===== 攻击力 =====

    /** 单环攻击力加成 = 基准 × 年限^0.8（无环/0 年 → 0） */
    public static float ringAttackBonus(int age) {
        if (age <= 0) return 0;
        return RING_BASE_ATTACK * (float) SoulBeastAge.statMultiplierOf(age);
    }

    /** 已获魂环的总攻击力加成（9 环独立累加，未获得的环不计算） */
    public static float ringsAttackBonus(PlayerLevelData data) {
        int ringCount = Math.min(data.getRingCount(), SoulRingLayout.MAX_RINGS);
        float sum = 0;
        for (int i = 0; i < ringCount; i++) {
            sum += ringAttackBonus(data.getRingAge(i));
        }
        return sum;
    }

    /** 幂等应用攻击力加成到玩家 attack_damage（transient 不持久化，登录/克隆后必须重新调用） */
    public static void applyAttack(Player player) {
        AttributeInstance attr = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attr == null) return;
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        attr.removeModifier(ATTACK_MOD_ID);
        float bonus = ringsAttackBonus(data);
        if (bonus > 0) {
            attr.addTransientModifier(new AttributeModifier(ATTACK_MOD_ID,
                    bonus, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    // ===== 精神力 =====

    /** 单环精神力加成 = 基准 × 年限^0.8（无环/0 年 → 0） */
    public static float ringSpiritBonus(int age) {
        if (age <= 0) return 0;
        return RING_BASE_SPIRIT * (float) SoulBeastAge.statMultiplierOf(age);
    }

    /** 精神力上限 = 等级成长 + 已获魂环年限加成（9 环独立累加） */
    public static float maxSpiritPower(PlayerLevelData data) {
        float max = BASE_SPIRIT + (data.getLevel() - 1) * SPIRIT_PER_LEVEL;
        int ringCount = Math.min(data.getRingCount(), SoulRingLayout.MAX_RINGS);
        for (int i = 0; i < ringCount; i++) {
            max += ringSpiritBonus(data.getRingAge(i));
        }
        return max;
    }
}
