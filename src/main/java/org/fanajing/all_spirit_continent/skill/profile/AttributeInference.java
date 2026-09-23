package org.fanajing.all_spirit_continent.skill.profile;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import net.minecraft.world.entity.monster.RangedAttackMob;

import java.util.ArrayList;
import java.util.List;

/**
 * 属性推断战斗定位（引擎文档 §5.7）。
 * <p>
 * 即使观察不到任何行为签名，实体属性本身也能提供定位信息：
 * 高攻低血 → 爆发型、高血低攻 → 坦克型、高移速 → 敏捷型、
 * 高血高攻 → 首领型、会飞行 → 空战型、火焰免疫 → 火系、亡灵分类 → 亡灵系。
 * <p>
 * 在 MobObserver 首次观察到某 mob 行为时调用一次，把体型/攻击模式/定位关键词
 * 写入 {@link MobProfile}；SkillGenerator 在 user prompt 中引用这些关键词。
 */
public final class AttributeInference {
    /** 爆发型：单次攻击伤害阈值 */
    private static final double HIGH_ATTACK = 8.0;
    /** 坦克型：生命阈值 */
    private static final double TANK_HEALTH = 50.0;
    /** 首领型：生命/攻击双高阈值 */
    private static final double BOSS_HEALTH = 80.0;
    /** 敏捷型：移动速度阈值（僵尸 0.23，动物 0.25，猪灵 0.35+） */
    private static final double FAST_SPEED = 0.35;

    private AttributeInference() {
    }

    /**
     * 从实体属性推断画像基础特征（体型 + 攻击模式 + 战斗定位关键词 + 实体属性快照 + 主题关键词）。
     * 不写入 ProfileCache，由调用方决定后续累加。
     * <p>
     * §5.3：填充 {@link EntityAttributes} 子结构（hp / atk / spd / range / 免疫 / 弱点）
     * §5.7：按规则写入战斗定位关键词
     * §5.3 themeKeywords：通过 {@link #themeKeywords} 抓 mob 类型分类（亡灵/飞行/火免/...）
     */
    public static MobProfile infer(Mob mob, long atTick) {
        String mobId = mob.getType().getDescriptionId();
        MobProfile base = MobProfile.empty(mobId)
                .withInferredTraits(inferSize(mob), inferPattern(mob), atTick)
                .withCombatTraits(combatTraits(mob))
                .withAttributes(captureAttributes(mob));
        // 主题关键词：§5.3 themeKeywords（去重累加）
        for (String kw : themeKeywords(mob)) {
            base = base.withThemeKeyword(kw);
        }
        return base;
    }

    /** 体型推断：碰撞箱宽度/高度 → SMALL / MEDIUM / LARGE */
    public static MobSize inferSize(Mob mob) {
        float width = mob.getBbWidth();
        float height = mob.getBbHeight();
        if (width <= 0.9F && height <= 1.4F) return MobSize.SMALL;   // 鸡/兔/蠹虫
        if (width >= 2.0F || height >= 2.5F) return MobSize.LARGE;   // 铁傀儡/恶魂/巨人系
        return MobSize.MEDIUM;
    }

    /**
     * 攻击模式推断：实现 {@link RangedAttackMob}（骷髅/女巫/恶魂等）→ RANGED；
     * 其余 → MELEE（MIXED 留给事件观察同时命中 PROJECTILE 与近战签名时修正）。
     */
    public static AttackPattern inferPattern(Mob mob) {
        if (mob instanceof RangedAttackMob) return AttackPattern.RANGED;
        return AttackPattern.MELEE;
    }

    /** 战斗定位关键词（§5.7 表格，按优先级排序） */
    public static List<String> combatTraits(Mob mob) {
        List<String> traits = new ArrayList<>();
        double health = mob.getMaxHealth();
        double attack = attr(mob, Attributes.ATTACK_DAMAGE);
        double speed = attr(mob, Attributes.MOVEMENT_SPEED);

        if (attack >= HIGH_ATTACK && health <= 20.0) {
            traits.add("爆发型（高攻低血）");
        }
        if (health >= TANK_HEALTH && attack > 0 && attack <= 4.0) {
            traits.add("坦克型（高血低攻）");
        }
        if (speed >= FAST_SPEED) {
            traits.add("敏捷型（高移速）");
        }
        if (health >= BOSS_HEALTH && attack >= HIGH_ATTACK) {
            traits.add("首领型（高血高攻）");
        }
        if (isFlying(mob)) {
            traits.add("空战型（会飞行）");
        }
        if (mob.fireImmune()) {
            traits.add("火系（火焰免疫）");
        }
        if (isUndead(mob)) {
            traits.add("亡灵系");
        }
        return traits;
    }

    private static double attr(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
        AttributeInstance instance = mob.getAttribute(attribute);
        return instance == null ? 0.0 : instance.getBaseValue();
    }

    /**
     * §5.3：抓取 mob 实体属性快照作为 EntityAttributes。
     * <ul>
     *   <li>{@code maxHealth}：{@link net.minecraft.world.entity.LivingEntity#getMaxHealth()}</li>
     *   <li>{@code attackDamage / movementSpeed}：对应 attribute base value</li>
     *   <li>{@code followRange}：{@code FOLLOW_RANGE} attribute</li>
     *   <li>{@code damageImmunities}：火焰免疫 → "fire"；未来可扩展淹死/摔落免疫等</li>
     *   <li>{@code weaknesses}：暂无内置规则，留空给玩家手动补充</li>
     *   <li>{@code extra}：{@code ARMOR} + {@code KNOCKBACK_RESISTANCE}</li>
     * </ul>
     */
    public static EntityAttributes captureAttributes(Mob mob) {
        if (mob == null) return EntityAttributes.empty();
        double hp = mob.getMaxHealth();
        double atk = attr(mob, Attributes.ATTACK_DAMAGE);
        double spd = attr(mob, Attributes.MOVEMENT_SPEED);
        double range = attr(mob, net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
        java.util.List<String> imm = new ArrayList<>();
        if (mob.fireImmune()) imm.add("fire");
        java.util.Map<String, Double> extra = new java.util.HashMap<>();
        double armor = attr(mob, net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
        if (armor > 0) extra.put("armor", armor);
        double kbr = attr(mob, net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE);
        if (kbr > 0) extra.put("knockback_resistance", kbr);
        return new EntityAttributes(hp, atk, spd, range, imm, java.util.List.of(), extra);
    }

    /**
     * §5.3 themeKeywords：从 mob 战斗特征反推主题关键词（与 combatTraits 不同——关键词更抽象，
     * 用于 SkillGenerator user prompt 中引导 AI 选契合原语方向）。
     * <ul>
     *   <li>亡灵分类 → "undead"</li>
     *   <li>会飞行 → "aerial"</li>
     *   <li>火焰免疫 → "fire"</li>
     *   <li>大型 → "boss-tier"（供 AI 推断生成高数值技能）</li>
     *   <li>远程型 → "ranged"</li>
     *   <li>近战型 → "melee"</li>
     * </ul>
     */
    public static List<String> themeKeywords(Mob mob) {
        List<String> kw = new ArrayList<>();
        if (isUndead(mob)) kw.add("undead");
        if (isFlying(mob)) kw.add("aerial");
        if (mob.fireImmune()) kw.add("fire");
        if (inferSize(mob) == MobSize.LARGE) kw.add("boss-tier");
        AttackPattern pat = inferPattern(mob);
        kw.add(pat == AttackPattern.RANGED ? "ranged" : "melee");
        return kw;
    }

    /** 飞行判定：导航系统使用飞行寻路（恶魂/末影龙/鹦鹉等） */
    private static boolean isFlying(Mob mob) {
        try {
            return mob.getNavigation() instanceof net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
        } catch (Exception e) {
            return false;
        }
    }

    /** 亡灵判定：怪物分类 + 类继承链命中已知亡灵类（僵尸/骷髅/凋灵/幻翼等） */
    private static boolean isUndead(Mob mob) {
        if (mob.getType().getCategory() != net.minecraft.world.entity.MobCategory.MONSTER) return false;
        for (Class<?> c = mob.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            switch (c.getSimpleName()) {
                case "Zombie", "AbstractSkeleton", "WitherBoss", "Phantom", "ZombifiedPiglin",
                     "Drowned", "Husk", "Stray", "ZombieVillager", "Skeleton", "WitherSkeleton":
                    return true;
                default:
            }
        }
        return false;
    }
}
