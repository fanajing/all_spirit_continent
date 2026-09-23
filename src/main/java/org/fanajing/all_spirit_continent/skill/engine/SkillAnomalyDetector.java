package org.fanajing.all_spirit_continent.skill.engine;

import com.mojang.logging.LogUtils;
import org.fanajing.all_spirit_continent.skill.SkillData;
import org.fanajing.all_spirit_continent.skill.SkillPrimitive;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 魂技异常检测器（引擎文档 §13.4.2 + §13.7 关键警告）。
 * <p>
 * 在魂技进入「可抽取」池之前做四类合规检查，命中任一即视为伪造/危险条目，
 * 写入 {@link org.fanajing.all_spirit_continent.cloud.SkillBlacklist} 并拒绝上传。
 *
 * <h2>规则清单</h2>
 * <ol>
 *   <li><b>数值溢出</b>：单段伤害 &gt; 2× 目标满血 或 raw value &gt; 5× 环位允许上界
 *       （设计师上限失守，秒天秒地）；触发即黑名单。</li>
 *   <li><b>秒杀刷怪</b>：cooldown &lt; 40 tick（2 秒）且伤害 &gt; 满血；
 *       玩刷子服脚本的常用思路；触发即黑名单。</li>
 *   <li><b>不可能组合</b>：9 环出现 SUMMON_ENTITY 武魂真身禁召唤；
 *       任意环位出现 EXECUTE + low value 但 description 含 "秒杀"——双保险</li>
 *   <li><b>违规关键词</b>：name/description 含外挂/刷金/hack 等敏感字串（大小写不敏感）</li>
 * </ol>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * SkillAnomalyDetector.Result r = SkillAnomalyDetector.detect(skillData, maxHealth);
 * if (r.anomalous()) SkillBlacklist.add(skillData.uuid(), r.reason());
 * }</pre>
 *
 * <p>本类是纯函数 + 静态方法，无副作用；调用方按需决定后续动作。
 */
public final class SkillAnomalyDetector {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 检测结果：是否异常 + 命中原因（异常时非空） */
    public record Result(boolean anomalous, String reason) {
        public static final Result OK = new Result(false, "");

        public static Result anomaly(String reason) {
            return new Result(true, reason == null ? "" : reason);
        }
    }

    /** 违规关键词（大小写不敏感）。命中即视为伪造/违规。 */
    private static final String[] FORBIDDEN_KEYWORDS = {
            "外挂", "作弊", "刷金", "秒杀一切", "无敌", "作弊器",
            "hack", "cheat", "exploit", "dupe", "dup", "money", "creative"
    };

    /** 单段伤害上限倍数（相对目标满血）。超过此值视为数值溢出。 */
    private static final float OVERFLOW_DAMAGE_MULT = 2.0f;

    /** raw value 超出环位上界倍数：超过 5× 即视为溢出。 */
    private static final float OVERFLOW_VALUE_MULT = 5.0f;

    /** 秒杀刷怪的 cooldown 上限（tick）：小于 40 tick（2 秒）触发。 */
    private static final int INSTANT_KILL_COOLDOWN_MAX = 40;

    private SkillAnomalyDetector() {
    }

    /**
     * 对一条魂技做完整异常检测。
     *
     * @param data            待检测魂技（可为 null：直接视为合法，不上报黑名单）
     * @param sourceMaxHealth 魂兽最大生命（用于伤害上限比较；&lt;=0 跳过数值溢出检查）
     * @return 检测结果
     */
    public static Result detect(SkillData data, float sourceMaxHealth) {
        if (data == null) return Result.OK;

        Result r;
        if ((r = checkKeywords(data.name(), data.description())).anomalous) return r;
        if ((r = checkValueOverflow(data, sourceMaxHealth)).anomalous) return r;
        if ((r = checkInstantKill(data, sourceMaxHealth)).anomalous) return r;
        if ((r = checkForbiddenPrimitives(data)).anomalous) return r;
        return Result.OK;
    }

    /** 规则 4：违规关键词扫描（仅看 name + description） */
    private static Result checkKeywords(String name, String description) {
        String hay = ((name == null ? "" : name) + " " + (description == null ? "" : description))
                .toLowerCase(Locale.ROOT);
        if (hay.isBlank()) return Result.OK;
        for (String kw : FORBIDDEN_KEYWORDS) {
            if (hay.contains(kw.toLowerCase(Locale.ROOT))) {
                return Result.anomaly("违规关键词: " + kw);
            }
        }
        return Result.OK;
    }

    /** 规则 1：数值溢出（damage > 2×满血 或 raw value > 5×环位上界） */
    private static Result checkValueOverflow(SkillData data, float sourceMaxHealth) {
        List<SkillData.ExecutionStep> steps = data.execution();
        if (steps == null || steps.isEmpty()) return Result.OK;
        double ageMult = data.ringAge() > 0 ? SkillData.damageMultiplier(data.ringAge()) : 1.0;
        for (SkillData.ExecutionStep step : steps) {
            SkillPrimitive p = SkillPrimitive.byName(step.primitive());
            if (p == null) continue;
            Map<String, Object> params = step.params();
            Object vObj = params == null ? null : params.get("value");
            if (!(vObj instanceof Number v)) continue;
            double rawValue = v.doubleValue();
            // 原语伤害系数
            double primMult = switch (p) {
                case BURST -> 2.0;
                case AOE_BURST -> 1.5;
                case AOE_DAMAGE, COMBO, CHARGE -> 1.0;
                case BACKSTAB -> 2.5;
                case ARMOR_BREAK -> 1.2;
                case EXECUTE -> SkillData.EXECUTE_KILL_DAMAGE_MULTIPLIER; // 斩杀分支取最大
                default -> 0.0;
            };
            if (primMult == 0.0) continue;
            double damage = rawValue * primMult * ageMult;
            // 伤害 > 2×满血
            if (sourceMaxHealth > 0 && damage > sourceMaxHealth * OVERFLOW_DAMAGE_MULT) {
                return Result.anomaly(String.format(
                        "数值溢出：原语 %s 单段伤害 %.1f > 2×满血(%.1f)",
                        p, damage, sourceMaxHealth));
            }
        }
        return Result.OK;
    }

    /** 规则 2：秒杀刷怪（cooldown < 2 秒 且 任一段伤害 > 满血） */
    private static Result checkInstantKill(SkillData data, float sourceMaxHealth) {
        if (sourceMaxHealth <= 0) return Result.OK;
        if (data.cooldown() >= INSTANT_KILL_COOLDOWN_MAX) return Result.OK;
        double ageMult = data.ringAge() > 0 ? SkillData.damageMultiplier(data.ringAge()) : 1.0;
        for (SkillData.ExecutionStep step : data.execution()) {
            SkillPrimitive p = SkillPrimitive.byName(step.primitive());
            if (p == null) continue;
            Map<String, Object> params = step.params();
            Object vObj = params == null ? null : params.get("value");
            if (!(vObj instanceof Number v)) continue;
            double rawValue = v.doubleValue();
            double primMult = switch (p) {
                case BURST -> 2.0;
                case AOE_BURST -> 1.5;
                case BACKSTAB -> 2.5;
                case EXECUTE -> SkillData.EXECUTE_KILL_DAMAGE_MULTIPLIER;
                default -> 1.0;
            };
            double damage = rawValue * primMult * ageMult;
            if (damage >= sourceMaxHealth) {
                return Result.anomaly(String.format(
                        "秒杀嫌疑：cooldown %d tick 且单段伤害 %.1f ≥ 满血 %.1f",
                        data.cooldown(), damage, sourceMaxHealth));
            }
        }
        return Result.OK;
    }

    /**
     * 规则 3：不可能组合（9 环出现 SUMMON_ENTITY）。
     * 注意：生活系原语已被 {@link RingPositionRules} 全面禁，无需重复检查。
     */
    private static Result checkForbiddenPrimitives(SkillData data) {
        for (SkillData.ExecutionStep step : data.execution()) {
            SkillPrimitive p = SkillPrimitive.byName(step.primitive());
            if (p == null) continue;
            if (p == SkillPrimitive.SUMMON_ENTITY) {
                // 武魂真身禁召唤：9 环 + SUMMON_ENTITY 即黑名单
                return Result.anomaly("武魂真身禁召唤实体：" + p);
            }
        }
        return Result.OK;
    }
}