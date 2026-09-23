package org.fanajing.all_spirit_continent.skill.profile;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mob 实体属性快照（引擎文档 §5.3 MobProfile 子结构）。
 * <p>
 * 在 {@link MobObserver} 首次观察到某 mob 时通过 {@link AttributeInference} 抓取，
 * 用于 §5.7 属性推断战斗定位（高攻低血/高血低攻/高移速/高血高攻/飞行/火免/亡灵），
 * 并在 §5.7 规则失效时作为兜底（即使无行为签名也能从属性推测推荐原语方向）。
 * <p>
 * 字段约定：
 * <ul>
 *   <li>{@code maxHealth / attackDamage / movementSpeed / followRange}：抓自实体属性，缺失填 0</li>
 *   <li>{@code damageImmunities}：伤害类型名（如 {@code "fire"} / {@code "explosion"}），用于 §5.7 火免等</li>
 *   <li>{@code weaknesses}：弱点（便于 AI 生成反制技能方向）</li>
 *   <li>{@code extra}：其他杂项属性（如 {@code armor} / {@code knockback_resistance}）</li>
 * </ul>
 * 本类为 record + 默认值规整：构造时 null 列表自动转为空列表，方便调用方链式使用。
 */
public record EntityAttributes(
        double maxHealth,
        double attackDamage,
        double movementSpeed,
        double followRange,
        List<String> damageImmunities,
        List<String> weaknesses,
        Map<String, Double> extra
) {

    public EntityAttributes {
        damageImmunities = damageImmunities == null || damageImmunities.isEmpty()
                ? List.of() : List.copyOf(damageImmunities);
        weaknesses = weaknesses == null || weaknesses.isEmpty()
                ? List.of() : List.copyOf(weaknesses);
        extra = extra == null || extra.isEmpty() ? Map.of() : Map.copyOf(extra);
        if (maxHealth < 0) maxHealth = 0;
        if (attackDamage < 0) attackDamage = 0;
        if (movementSpeed < 0) movementSpeed = 0;
        if (followRange < 0) followRange = 0;
    }

    /** 空属性（所有字段归零） */
    public static EntityAttributes empty() {
        return new EntityAttributes(0, 0, 0, 0, List.of(), List.of(), Map.of());
    }

    /** 防御等级（如盔甲值），用于与 skill_value 反推时换算 */
    public double armor() {
        Double v = extra.get("armor");
        return v == null ? 0.0 : v;
    }

    /** 击退抗性 0~1（1 表示完全抵抗） */
    public double knockbackResistance() {
        Double v = extra.get("knockback_resistance");
        return v == null ? 0.0 : Math.max(0.0, Math.min(1.0, v));
    }

    /** 该 mob 是否对某种伤害类型免疫 */
    public boolean isImmuneTo(String damageType) {
        if (damageType == null) return false;
        String lower = damageType.toLowerCase(Locale.ROOT);
        for (String imm : damageImmunities) {
            if (imm.toLowerCase(Locale.ROOT).equals(lower)) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return "EntityAttrs[hp=" + maxHealth + " atk=" + attackDamage
                + " spd=" + movementSpeed + " range=" + followRange
                + " imm=" + damageImmunities + " weak=" + weaknesses + "]";
    }
}