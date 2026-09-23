package org.fanajing.all_spirit_continent.skill;

import org.fanajing.all_spirit_continent.skill.engine.SignatureMechanism;

import java.util.EnumSet;
import java.util.Set;

/**
 * Mob 行为签名（引擎文档 §5）。描述魂兽在战斗中的典型行为模式，
 * 用于 SkillGenerator 第 1 阶段分析 Mob 特征 → 推荐签名机制。
 * <p>
 * 由 {@link org.fanajing.all_spirit_continent.skill.profile.MobObserver} 通过监听 NeoForge 事件抽取，
 * 写入 {@link org.fanajing.all_spirit_continent.skill.profile.MobProfile#signatures}。
 * P0 阶段 MobObserver 不实装事件订阅，但本枚举与映射规则已完整建好，待 P1/P2 接入。
 * <p>
 * 本枚举位于 {@code skill} 包顶级（与 {@link SkillPrimitive} 同级），
 * 避免 skill.engine ⇄ skill.profile 的循环依赖。
 */
public enum AbilitySignature {
    /** 远程投射（发射箭矢/火球/魔法弹）→ 推荐 G 锁定目标 */
    PROJECTILE("远程投射", new SignatureMechanism[]{SignatureMechanism.G_PINNED_TARGET},
            primitiveSet(SkillPrimitive.PROJECTILE, SkillPrimitive.AOE_DAMAGE)),
    /** 群体减速/削弱（多目标负面效果）→ 推荐 H 多重目标 + O 削弱 */
    AOE_DEBUFF("群体减速/削弱",
            new SignatureMechanism[]{SignatureMechanism.H_MULTI_TARGET, SignatureMechanism.O_DEBUFF},
            primitiveSet(SkillPrimitive.SLOW, SkillPrimitive.WEAKEN, SkillPrimitive.AOE_DAMAGE, SkillPrimitive.KNOCKBACK)),
    /** 蓄力施法（长吟唱后释放）→ 推荐 J 蓄力最大 */
    CHARGED_CAST("蓄力施法", new SignatureMechanism[]{SignatureMechanism.J_OVERCHARGED},
            primitiveSet(SkillPrimitive.STUN, SkillPrimitive.BURST, SkillPrimitive.AOE_BURST)),
    /** 爆发近战（瞬时高伤害）→ 推荐 C 强化效果 */
    BURST_MELEE("爆发近战", new SignatureMechanism[]{SignatureMechanism.C_AMPLIFIED_EFFECT},
            primitiveSet(SkillPrimitive.BURST, SkillPrimitive.CHARGE, SkillPrimitive.COMBO, SkillPrimitive.BACKSTAB)),
    /** 召唤随从（生成伴生实体）→ 推荐 H 多重目标 */
    SUMMON_MINION("召唤随从", new SignatureMechanism[]{SignatureMechanism.H_MULTI_TARGET},
            primitiveSet(SkillPrimitive.SUMMON_ENTITY)),
    /** 毒气光环（持续 DOT 范围效果）→ 推荐 D 延时爆破 + E 持续时间 */
    POISON_AURA("毒气光环",
            new SignatureMechanism[]{SignatureMechanism.D_DELAYED_BLAST, SignatureMechanism.E_EXTENDED_DURATION},
            primitiveSet(SkillPrimitive.POTION, SkillPrimitive.AOE_DAMAGE)),
    /** 隐身突袭（隐身 → 攻击 → 现形）→ 推荐 M 隐身 */
    STEALTH_HIT("隐身突袭", new SignatureMechanism[]{SignatureMechanism.M_STEALTH},
            primitiveSet(SkillPrimitive.PHANTOM, SkillPrimitive.BACKSTAB, SkillPrimitive.DASH)),
    /** 自我治疗（释放治疗效果）→ 推荐 N 附加治疗 */
    HEAL_SELF("自我治疗", new SignatureMechanism[]{SignatureMechanism.N_HEAL},
            primitiveSet(SkillPrimitive.HOT, SkillPrimitive.REVIVE, SkillPrimitive.SHIELD_TRANSFER)),
    /** 自我增益（释放 buff）→ 推荐 E 持续时间 */
    BUFF_SELF("自我增益", new SignatureMechanism[]{SignatureMechanism.E_EXTENDED_DURATION},
            primitiveSet(SkillPrimitive.BUFF_STATS, SkillPrimitive.ACCELERATE, SkillPrimitive.BARRIER)),
    /** 群体嘲讽/防御（强制攻击自身）→ 推荐 K 反弹 + L 护盾 */
    TAUNT_GROUP("群体嘲讽/防御",
            new SignatureMechanism[]{SignatureMechanism.K_REFLECT, SignatureMechanism.L_BARRIER},
            primitiveSet(SkillPrimitive.TAUNT, SkillPrimitive.REFLECT, SkillPrimitive.IRON_BODY, SkillPrimitive.BARRIER)),
    /** 重击（单段高伤害）→ 推荐 B 范围放大（实际是范围） */
    HEAVY_HIT("重击", new SignatureMechanism[]{SignatureMechanism.B_EXPANDED_REACH},
            primitiveSet(SkillPrimitive.BURST, SkillPrimitive.AOE_BURST, SkillPrimitive.EXECUTE)),
    /** 连射（短间隔多发）→ 推荐 A 连续释放 */
    RAPID_FIRE("连射", new SignatureMechanism[]{SignatureMechanism.A_RAPID_CAST},
            primitiveSet(SkillPrimitive.COMBO, SkillPrimitive.PROJECTILE)),
    /** 瞬杀/瞬发（极短吟唱 + 高伤害）→ 推荐 I 瞬发 */
    INSTANT_KILL("瞬杀/瞬发", new SignatureMechanism[]{SignatureMechanism.I_INSTANT_CAST},
            primitiveSet(SkillPrimitive.BURST, SkillPrimitive.EXECUTE, SkillPrimitive.BACKSTAB)),
    /** §5.1 SOUND（受击/死亡音）→ 推荐 E 持续时间（延长声音表现窗口） */
    SOUND("声音特征", new SignatureMechanism[]{SignatureMechanism.E_EXTENDED_DURATION},
            primitiveSet(SkillPrimitive.PARTICLE)),
    /** §5.1 PARTICLE（药水/粒子派生）→ 推荐 E 持续时间 */
    PARTICLE("粒子特征", new SignatureMechanism[]{SignatureMechanism.E_EXTENDED_DURATION},
            primitiveSet(SkillPrimitive.PARTICLE));

    public final String displayName;
    public final SignatureMechanism[] suggestedMechanisms;
    public final Set<SkillPrimitive> recommendedPrimitives;

    AbilitySignature(String displayName, SignatureMechanism[] suggestedMechanisms,
                     Set<SkillPrimitive> recommendedPrimitives) {
        this.displayName = displayName;
        this.suggestedMechanisms = suggestedMechanisms;
        this.recommendedPrimitives = recommendedPrimitives;
    }

    /** 该行为签名推荐的签名机制集合（不可变副本） */
    public Set<SignatureMechanism> suggestedMechanismSet() {
        Set<SignatureMechanism> set = EnumSet.noneOf(SignatureMechanism.class);
        for (SignatureMechanism m : suggestedMechanisms) set.add(m);
        return set;
    }

    /** 工具：根据若干原语创建不可变 Set */
    private static Set<SkillPrimitive> primitiveSet(SkillPrimitive... primitives) {
        Set<SkillPrimitive> set = EnumSet.noneOf(SkillPrimitive.class);
        for (SkillPrimitive p : primitives) set.add(p);
        return java.util.Set.copyOf(set);
    }
}