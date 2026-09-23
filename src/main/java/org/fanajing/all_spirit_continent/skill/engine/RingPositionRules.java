package org.fanajing.all_spirit_continent.skill.engine;

import org.fanajing.all_spirit_continent.skill.SkillData;
import org.fanajing.all_spirit_continent.skill.SkillPrimitive;
import org.fanajing.all_spirit_continent.skill.SkillPrimitive.Category;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 9 环位硬约束规则表（引擎文档 §4.3）。
 * <p>
 * 每个环位（1~9）有：
 * <ul>
 *   <li>原语数范围、允许的类别、必需要的类别数（多样性）</li>
 *   <li>禁用的原语（少量）</li>
 *   <li>cooldown / value / radius / duration 数值范围</li>
 *   <li>禁用的签名机制</li>
 * </ul>
 * 环位规则是 {@link SkillValidator} 的核心数据源：所有 SkillData 必须通过
 * {@code forSlot(slot).validate(data, playerUsedMechanisms)} 校验。
 * <p>
 * 校验返回的违规列表是空集合时视为通过；非空时整条技能被拒绝入池。
 */
public final class RingPositionRules {
    private RingPositionRules() {
    }

    /**
     * 单个环位的硬约束规格。
     * <p>
     * forbiddenSignatures 与数值范围按环位难度递进：
     * 1~2 环禁止高级签名（D 延时爆破 / J 蓄力最大 / F 引导），3~4 环只禁 J，
     * 5 环起完全放开所有签名机制。
     */
    public record RuleSpec(
            int slot,
            String tierName,
            int primitiveCountMin,
            int primitiveCountMax,
            Set<Category> allowedCategories,
            int minCategoriesRequired,
            Set<SkillPrimitive> forbiddenPrimitives,
            int cooldownMin,
            int cooldownMax,
            double valueMin,
            double valueMax,
            double radiusMin,
            double radiusMax,
            int durationMin,
            int durationMax,
            Set<SignatureMechanism> forbiddenSignatures
    ) {
        /**
         * 校验一条 SkillData 是否符合该环位硬约束。
         *
         * @param data 待校验的技能
         * @param playerUsedMechanisms 玩家其他魂环位已占用的签名机制（用于唯一性校验；null 跳过唯一性）
         * @return 违规列表（空 = 通过）
         */
        public List<String> validate(SkillData data, Set<SignatureMechanism> playerUsedMechanisms) {
            List<String> errors = new ArrayList<>();
            if (data == null) {
                errors.add("SkillData 为空");
                return errors;
            }

            // 1) 原语数范围
            int execCount = data.execution() == null ? 0 : data.execution().size();
            if (execCount < primitiveCountMin || execCount > primitiveCountMax) {
                errors.add(String.format("环位 %d 原语数 %d 超出范围 [%d, %d]",
                        slot, execCount, primitiveCountMin, primitiveCountMax));
            }

            // 2) 禁用原语 + 类别范围 + 累计类别集合
            Set<Category> seen = EnumSet.noneOf(Category.class);
            if (data.execution() != null) {
                for (SkillData.ExecutionStep step : data.execution()) {
                    SkillPrimitive p = SkillPrimitive.byName(step.primitive());
                    if (p == null) {
                        errors.add("未知原语: " + step.primitive());
                        continue;
                    }
                    if (forbiddenPrimitives.contains(p)) {
                        errors.add("环位 " + slot + " 禁止原语 " + p.name());
                    }
                    if (!allowedCategories.contains(p.category())) {
                        errors.add("环位 " + slot + " 不允许类别 " + p.category().key()
                                + "（原语 " + p.name() + "）");
                    }
                    seen.add(p.category());
                }
            }
            if (minCategoriesRequired > 0 && !seen.isEmpty() && seen.size() < minCategoriesRequired) {
                errors.add(String.format("环位 %d 至少覆盖 %d 个类别，实际 %d 个",
                        slot, minCategoriesRequired, seen.size()));
            }

            // 3) cooldown 范围
            if (data.cooldown() < cooldownMin || data.cooldown() > cooldownMax) {
                errors.add(String.format("环位 %d 冷却 %d 超出范围 [%d, %d]",
                        slot, data.cooldown(), cooldownMin, cooldownMax));
            }

            // 4) value / radius / duration 数值范围（首个出现该参数的步骤即报）
            for (SkillData.ExecutionStep step : data.execution()) {
                Object v = step.params().get("value");
                if (v instanceof Number n) {
                    if (n.doubleValue() < valueMin || n.doubleValue() > valueMax) {
                        errors.add(String.format("环位 %d value %.2f 超出范围 [%.2f, %.2f]",
                                slot, n.doubleValue(), valueMin, valueMax));
                    }
                    break;
                }
            }
            for (SkillData.ExecutionStep step : data.execution()) {
                Object r = step.params().get("radius");
                if (r instanceof Number n) {
                    if (n.doubleValue() < radiusMin || n.doubleValue() > radiusMax) {
                        errors.add(String.format("环位 %d radius %.2f 超出范围 [%.2f, %.2f]",
                                slot, n.doubleValue(), radiusMin, radiusMax));
                    }
                    break;
                }
            }
            for (SkillData.ExecutionStep step : data.execution()) {
                Object d = step.params().get("duration");
                if (d instanceof Number n) {
                    if (n.intValue() < durationMin || n.intValue() > durationMax) {
                        errors.add(String.format("环位 %d duration %d 超出范围 [%d, %d]",
                                slot, n.intValue(), durationMin, durationMax));
                    }
                    break;
                }
            }

            // 6) 签名机制：必填 + 禁用 + 玩家唯一性
            String mechanismCode = data.signatureMechanism();
            if (mechanismCode == null || mechanismCode.isBlank()) {
                errors.add("环位 " + slot + " 必须绑定签名机制（A~O 之一）");
            } else {
                SignatureMechanism m = SignatureMechanism.byCode(mechanismCode).orElse(null);
                if (m == null) {
                    errors.add("无效签名机制代码: " + mechanismCode);
                } else {
                    if (forbiddenSignatures.contains(m)) {
                        errors.add("环位 " + slot + " 禁止签名机制 " + m.displayName);
                    }
                    if (playerUsedMechanisms != null && playerUsedMechanisms.contains(m)) {
                        errors.add("签名机制 " + m.displayName + " 已被玩家其他魂环占用");
                    }
                }
            }
            return errors;
        }
    }

    // ===== 9 环位硬约束（按引擎文档 §4.3 数值范围 + 难度递增）=====

    private static final List<RuleSpec> RULES = List.of(
            // 1 环：十年·第一魂环 - 1 原语，简单控制
            new RuleSpec(1, "十年·第一魂环", 1, 1,
                    EnumSet.of(Category.CONTROL, Category.GENERAL),
                    1,
                    EnumSet.of(SkillPrimitive.EXECUTE, SkillPrimitive.REVIVE,
                            SkillPrimitive.TELEPORT, SkillPrimitive.PULL,
                            SkillPrimitive.CHARGE, SkillPrimitive.BACKSTAB,
                            SkillPrimitive.STUN, SkillPrimitive.ARMOR_BREAK),
                    40, 100, 2.0, 5.0, 1.0, 3.0, 20, 100,
                    EnumSet.of(SignatureMechanism.D_DELAYED_BLAST,
                            SignatureMechanism.J_OVERCHARGED,
                            SignatureMechanism.F_CHANNEL_CAST,
                            SignatureMechanism.H_MULTI_TARGET,
                            SignatureMechanism.K_REFLECT,
                            SignatureMechanism.L_BARRIER)),

            // 2 环：百年·第二魂环 - 1 原语，强攻
            new RuleSpec(2, "百年·第二魂环", 1, 1,
                    EnumSet.of(Category.STRONG),
                    1,
                    EnumSet.of(SkillPrimitive.EXECUTE, SkillPrimitive.REVIVE,
                            SkillPrimitive.CROP_GROW, SkillPrimitive.FOOD_BLESS,
                            SkillPrimitive.HARVEST, SkillPrimitive.BONEMEAL,
                            SkillPrimitive.BUFF_ALL, SkillPrimitive.PULL,
                            SkillPrimitive.TELEPORT),
                    60, 160, 4.0, 10.0, 2.0, 4.0, 40, 160,
                    EnumSet.of(SignatureMechanism.D_DELAYED_BLAST,
                            SignatureMechanism.J_OVERCHARGED,
                            SignatureMechanism.F_CHANNEL_CAST,
                            SignatureMechanism.E_EXTENDED_DURATION,
                            SignatureMechanism.H_MULTI_TARGET)),

            // 3 环：百年·第三魂环 - 2 原语，混合
            new RuleSpec(3, "百年·第三魂环", 2, 2,
                    EnumSet.of(Category.CONTROL, Category.STRONG, Category.SUPPORT),
                    1,
                    EnumSet.of(SkillPrimitive.EXECUTE, SkillPrimitive.REVIVE,
                            SkillPrimitive.CROP_GROW, SkillPrimitive.FOOD_BLESS,
                            SkillPrimitive.HARVEST, SkillPrimitive.BONEMEAL,
                            SkillPrimitive.TELEPORT),
                    100, 240, 6.0, 14.0, 2.0, 5.0, 60, 240,
                    EnumSet.of(SignatureMechanism.J_OVERCHARGED)),

            // 4 环：千年·第四魂环 - 2 原语，混合（强制多样性 ≥2）
            new RuleSpec(4, "千年·第四魂环", 2, 2,
                    EnumSet.of(Category.STRONG, Category.AGILE, Category.SUPPORT),
                    2,
                    EnumSet.of(SkillPrimitive.REVIVE, SkillPrimitive.CROP_GROW,
                            SkillPrimitive.FOOD_BLESS, SkillPrimitive.HARVEST,
                            SkillPrimitive.BONEMEAL, SkillPrimitive.TELEPORT),
                    160, 380, 10.0, 22.0, 3.0, 6.0, 100, 380,
                    EnumSet.noneOf(SignatureMechanism.class)),

            // 5 环：千年·第五魂环 - 3 原语，连携
            new RuleSpec(5, "千年·第五魂环", 3, 3,
                    EnumSet.of(Category.STRONG, Category.AGILE, Category.SUPPORT, Category.CONTROL),
                    2,
                    EnumSet.of(SkillPrimitive.REVIVE, SkillPrimitive.CROP_GROW,
                            SkillPrimitive.FOOD_BLESS, SkillPrimitive.HARVEST,
                            SkillPrimitive.BONEMEAL),
                    240, 540, 16.0, 32.0, 3.0, 7.0, 160, 540,
                    EnumSet.noneOf(SignatureMechanism.class)),

            // 6 环：万年·第六魂环 - 3 原语，连携
            new RuleSpec(6, "万年·第六魂环", 3, 3,
                    EnumSet.of(Category.STRONG, Category.AGILE, Category.SUPPORT, Category.DEFENSE),
                    2,
                    EnumSet.of(SkillPrimitive.REVIVE, SkillPrimitive.CROP_GROW,
                            SkillPrimitive.FOOD_BLESS, SkillPrimitive.HARVEST,
                            SkillPrimitive.BONEMEAL),
                    360, 780, 24.0, 46.0, 4.0, 8.0, 240, 780,
                    EnumSet.noneOf(SignatureMechanism.class)),

            // 7 环：万年·第七魂环 - 4 原语，复合（强制多样性 ≥3）
            new RuleSpec(7, "万年·第七魂环", 4, 4,
                    EnumSet.of(Category.STRONG, Category.AGILE, Category.SUPPORT,
                            Category.DEFENSE, Category.CONTROL),
                    3,
                    EnumSet.of(SkillPrimitive.CROP_GROW, SkillPrimitive.FOOD_BLESS,
                            SkillPrimitive.HARVEST, SkillPrimitive.BONEMEAL),
                    540, 1100, 36.0, 68.0, 4.0, 10.0, 360, 1100,
                    EnumSet.noneOf(SignatureMechanism.class)),

            // 8 环：万年·第八魂环 - 4 原语，复合
            new RuleSpec(8, "万年·第八魂环", 4, 4,
                    EnumSet.of(Category.STRONG, Category.AGILE, Category.SUPPORT,
                            Category.DEFENSE, Category.GENERAL),
                    3,
                    EnumSet.of(SkillPrimitive.CROP_GROW, SkillPrimitive.FOOD_BLESS,
                            SkillPrimitive.HARVEST, SkillPrimitive.BONEMEAL),
                    800, 1600, 52.0, 96.0, 5.0, 12.0, 540, 1600,
                    EnumSet.noneOf(SignatureMechanism.class)),

            // 9 环：十万年·第九魂环 - 5 原语，终极（武魂真身）
            // §13.7.6：武魂真身禁止召唤实体，必须是自身爆发
            // §13.7.3：生活系原语不应在战斗技能中出现
            new RuleSpec(9, "十万年·第九魂环", 5, 5,
                    EnumSet.allOf(Category.class),
                    3,
                    EnumSet.of(SkillPrimitive.SUMMON_ENTITY,
                            SkillPrimitive.CROP_GROW, SkillPrimitive.FOOD_BLESS,
                            SkillPrimitive.HARVEST, SkillPrimitive.BONEMEAL),
                    1200, 2400, 76.0, 140.0, 5.0, 15.0, 800, 2400,
                    EnumSet.noneOf(SignatureMechanism.class))
    );

    /** 按环位取规则（1~9）；非法环位抛 IAE */
    public static RuleSpec forSlot(int slot) {
        if (slot < 1 || slot > 9) {
            throw new IllegalArgumentException("环位 " + slot + " 非法（应为 1~9）");
        }
        return RULES.get(slot - 1);
    }

    /** 最大环位（9） */
    public static int maxSlot() {
        return RULES.size();
    }
}