package org.fanajing.all_spirit_continent.skill.engine;

import org.fanajing.all_spirit_continent.skill.Param;
import org.fanajing.all_spirit_continent.skill.SkillData;
import org.fanajing.all_spirit_continent.skill.SkillPrimitive;

/**
 * 数值反推器（引擎文档 §7.5）。根据环位 + 签名机制，
 * 反推 AI 生成阶段应当使用的数值范围（cooldown / value / radius / duration）。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>数值范围的下限/上限完全来自 {@link RingPositionRules}（9 环位硬约束）</li>
 *   <li>推荐值 = 范围中位数 × 签名机制 scale，再 clamp 回 [min, max]</li>
 *   <li>输出供 SkillGenerator 在 AI prompt 中拼接，AI 在范围内自由取值</li>
 * </ul>
 */
public final class ValueCalculator {
    private ValueCalculator() {
    }

    /** 数值类型 */
    public enum ValueKind {
        COOLDOWN,    // 冷却（tick，整数）
        VALUE,       // 伤害/治疗量
        RADIUS,      // 半径/距离
        DURATION     // 持续时间（tick，整数）
    }

    /** 数值范围（min/max 严格边界，recommended 是 clamp 后推荐值） */
    public record ValueRange(double min, double max, double recommended) {
        public int minInt() { return (int) Math.round(min); }
        public int maxInt() { return (int) Math.round(max); }
        public int recInt() { return (int) Math.round(recommended); }

        /** 用于 AI prompt 的紧凑字符串："[40~100] 推荐 70" */
        public String toPromptString() {
            if (max - min >= 1000) {
                return String.format("[%.0f~%.0f] 推荐 %.0f", min, max, recommended);
            }
            return String.format("[%s~%s] 推荐 %s",
                    trim(min), trim(max), trim(recommended));
        }

        private static String trim(double v) {
            if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
            return String.format(java.util.Locale.ROOT, "%.2f", v);
        }
    }

    /**
     * 计算 AI 在某环位生成某种数值时的取值范围。
     *
     * @param slot      环位 1~9
     * @param mechanism 签名机制（null = 不应用签名机制乘数）
     * @param kind      数值类型
     */
    public static ValueRange range(int slot, SignatureMechanism mechanism, ValueKind kind) {
        RingPositionRules.RuleSpec rule = RingPositionRules.forSlot(slot);
        return switch (kind) {
            case COOLDOWN -> compute(rule.cooldownMin(), rule.cooldownMax(),
                    mechanism == null ? 1.0 : mechanism.cooldownScale);
            case VALUE -> compute(rule.valueMin(), rule.valueMax(),
                    mechanism == null ? 1.0 : mechanism.valueScale);
            case RADIUS -> compute(rule.radiusMin(), rule.radiusMax(),
                    mechanism == null ? 1.0 : mechanism.radiusScale);
            case DURATION -> compute(rule.durationMin(), rule.durationMax(),
                    mechanism == null ? 1.0 : mechanism.durationScale);
        };
    }

    /** 按环位计算（不带签名机制） */
    public static ValueRange range(int slot, ValueKind kind) {
        return range(slot, null, kind);
    }

    /**
     * 数值调整后的推荐值：min/max 不动，recommended = clamp(中位数 × scale, min, max)。
     * 缩放系数 ≤0 的（理论上不应出现）当 1.0 处理。
     */
    private static ValueRange compute(double min, double max, double scale) {
        if (scale <= 0) scale = 1.0;
        double mid = (min + max) / 2.0;
        double scaled = mid * scale;
        double clamped = Math.max(min, Math.min(max, scaled));
        return new ValueRange(min, max, clamped);
    }

    /**
     * 生成 AI prompt 中关于数值的指引文本（4 种数值各一行）。
     * 用于 SkillGenerator 阶段 1 分析阶段输出"数值约束摘要"。
     */
    public static String fullHint(int slot, SignatureMechanism mechanism) {
        StringBuilder sb = new StringBuilder();
        sb.append("冷却:").append(range(slot, mechanism, ValueKind.COOLDOWN).toPromptString()).append("; ");
        sb.append("value:").append(range(slot, mechanism, ValueKind.VALUE).toPromptString()).append("; ");
        sb.append("radius:").append(range(slot, mechanism, ValueKind.RADIUS).toPromptString()).append("; ");
        sb.append("duration:").append(range(slot, mechanism, ValueKind.DURATION).toPromptString());
        return sb.toString();
    }

    /**
     * 缩放冷却值：某些签名机制（A/I）会把 CD ×0.5。
     * 仅在 SkillGenerator 已生成 cooldown 后微调用，作为生成后规整步骤。
     */
    public static int applyCooldownScale(int raw, SignatureMechanism mechanism) {
        if (mechanism == null || mechanism.cooldownScale <= 0) return raw;
        return Math.max(SkillData.MIN_COOLDOWN,
                (int) Math.round(raw * mechanism.cooldownScale));
    }

    /**
     * 阻止魔法：星数 1-5 映射到冷却下调百分比。
     * 来自 RatingStats.applyRating，不在本类内（避免依赖环位）。
     */
    public static double ratingCooldownDiscount(int rating) {
        return switch (rating) {
            case 5 -> 0.20;
            case 4 -> 0.10;
            case 3 -> 0.0;
            case 2 -> -0.10;
            case 1 -> -0.20;
            default -> 0.0;
        };
    }

    // ===== §8.3 数值反推（calibrate）=====

    /** 原语伤害系数（§8.3 + §7.5）：AI 在 prompt 中也按这套系数反推 */
    public static final java.util.Map<String, Double> PRIMITIVE_COEFFICIENT = java.util.Map.of(
            "BURST", 2.0,
            "AOE_BURST", 1.5,
            "AOE_DAMAGE", 1.0,
            "COMBO", 1.0,
            "BACKSTAB", 2.5,
            "ARMOR_BREAK", 1.2,
            "EXECUTE", 1.0,         // 普通分支；斩杀分支由 EXECUTE_KILL_DAMAGE_MULTIPLIER（4.0）另行处理
            "CHARGE", 1.0
    );

    /** 年限倍率（§8.3）：十年 ×1，每升一档 ×2。1~9 环位用同一映射（与 SkillData.damageMultiplier 一致） */
    public static double yearMultiplier(int ringAge) {
        if (ringAge <= 10) return 1.0;
        if (ringAge <= 100) return 2.0;
        if (ringAge <= 1000) return 4.0;
        if (ringAge <= 10000) return 8.0;
        if (ringAge <= 100000) return 16.0;
        return 32.0;
    }

    /**
     * 环位 → 单体目标伤害比例（§8.3 + §7.5）。
     * 1~3 环：8%（启动技保守）；4~6 环：12%（成长期主力）；7~9 环：15%（终极）。
     * <p>
     * 范围技能（AOE_*）在调用方按一半比例取，参见 {@link #AOE_DAMAGE_RATIO_DIVISOR}。
     */
    public static double damageRatio(int slot) {
        return switch (slot) {
            case 1, 2, 3 -> 0.08;
            case 4, 5, 6 -> 0.12;
            case 7, 8, 9 -> 0.15;
            default -> 0.10;
        };
    }

    /** 范围原语按此系数折算（AOE_DAMAGE / AOE_BURST 单体伤害比例 / 该系数） */
    public static final double AOE_DAMAGE_RATIO_DIVISOR = 2.5;

    /**
     * 反推校正（§8.3）：
     * 根据魂兽最大生命 × 环位伤害比例，反推每个含 value 的步骤应当值，
     * 并 clamp 到环位硬范围 [valueMin, valueMax]。
     *
     * @param data          待校正的技能（不修改原对象，返回副本）
     * @param mobMaxHealth  魂兽最大生命（≤0 视为无画像数据，跳过校正）
     * @param ringAge       魂环年限（用于 yearMultiplier）
     * @return 校正后的 SkillData 副本；mobMaxHealth ≤0 时返回原 data（不变）
     */
    public static SkillData calibrate(SkillData data, double mobMaxHealth, int ringAge) {
        if (data == null) return null;
        if (mobMaxHealth <= 0) return data;
        int slot = data.ringAge() > 0 ? inferSlotFromRingAge(data.ringAge()) : inferSlotFromCooldown(data.cooldown());
        if (slot < 1 || slot > 9) return data;

        RingPositionRules.RuleSpec rule = RingPositionRules.forSlot(slot);
        double baseRatio = damageRatio(slot);
        double yearMul = yearMultiplier(ringAge);
        java.util.List<SkillData.ExecutionStep> steps = data.execution();
        if (steps == null || steps.isEmpty()) return data;

        java.util.List<SkillData.ExecutionStep> calibrated = new java.util.ArrayList<>();
        boolean touched = false;
        for (SkillData.ExecutionStep step : steps) {
            SkillData.ExecutionStep updated = calibrateStep(step, mobMaxHealth, baseRatio, yearMul, rule);
            if (updated != step) touched = true;
            calibrated.add(updated);
        }
        if (!touched) return data;

        return new SkillData(data.uuid(), data.name(), data.description(),
                data.cooldown(), data.trigger(),
                calibrated, data.passive(),
                data.wuhun(), data.mobId(), data.mobHealthSegment(),
                data.ringAge(), data.requiresMods(), data.signatureMechanism());
    }

    /** 反推单步 value（不做 clamp 的辅助，由 calibrate 调用） */
    private static SkillData.ExecutionStep calibrateStep(SkillData.ExecutionStep step,
                                                          double mobMaxHealth,
                                                          double baseRatio,
                                                          double yearMul,
                                                          RingPositionRules.RuleSpec rule) {
        Object v = step.params().get("value");
        if (!(v instanceof Number rawVal)) return step;
        SkillPrimitive prim = SkillPrimitive.byName(step.primitive());
        if (prim == null) return step;

        double coefficient = PRIMITIVE_COEFFICIENT.getOrDefault(prim.name(), 1.0);
        double ratio = prim.name().startsWith("AOE_") ? baseRatio / AOE_DAMAGE_RATIO_DIVISOR : baseRatio;
        double targetDamage = mobMaxHealth * ratio;
        double raw = targetDamage / Math.max(coefficient, 0.01) / Math.max(yearMul, 0.01);
        double clamped = Math.max(rule.valueMin(), Math.min(rule.valueMax(), raw));

        // EXECUTE 的 value 语义需单独处理（§8.3 注释）：斩杀阈值 = value × 2；
        // 推荐 value ≈ 满血 1%~2%。此处若原 value 已远超 2%，保持原值避免破坏 AI 意图。
        if ("EXECUTE".equals(prim.name())) {
            double executeThreshold = mobMaxHealth * 0.02;  // 斩杀线 ≈ 满血 2%
            if (clamped > executeThreshold * 2) {
                // 原始 AI 设定已偏离「满血 1~2%」语义；仅在 ringAge <= 0 时校正
                if (clamped > executeThreshold * 5) {
                    clamped = executeThreshold;
                }
            }
        }

        if (clamped == rawVal.doubleValue()) return step;

        java.util.Map<String, Object> newParams = new java.util.HashMap<>(step.params());
        // 整数友好：能整除就存整数
        newParams.put("value", (clamped == Math.floor(clamped)) ? (double) (long) clamped : clamped);
        return new SkillData.ExecutionStep(step.primitive(), newParams);
    }

    /** 用 ringAge 反推 slot（仅用于 calibrate 内部决策；调用方应优先用真实 slot） */
    private static int inferSlotFromRingAge(int ringAge) {
        if (ringAge <= 10) return 1;
        if (ringAge <= 100) return 2;
        if (ringAge <= 1000) return 4;
        if (ringAge <= 10000) return 7;
        if (ringAge <= 100000) return 9;
        return 9;
    }

    /** 用 cooldown 反推 slot（ringAge 缺失场景的兜底） */
    private static int inferSlotFromCooldown(int cooldown) {
        if (cooldown < 60) return 1;
        if (cooldown < 120) return 2;
        if (cooldown < 200) return 3;
        if (cooldown < 320) return 4;
        if (cooldown < 500) return 5;
        if (cooldown < 700) return 6;
        if (cooldown < 900) return 7;
        if (cooldown < 1300) return 8;
        return 9;
    }
}