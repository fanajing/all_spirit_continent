package org.fanajing.all_spirit_continent.skill.engine;

import com.mojang.logging.LogUtils;
import org.fanajing.all_spirit_continent.skill.SkillData;
import org.fanajing.all_spirit_continent.skill.SkillPrimitive;
import org.fanajing.all_spirit_continent.skill.SkillPrimitive.Category;
import org.fanajing.all_spirit_continent.skill.Param;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 本地规则修复器（引擎文档 §8.2 第 3 层降级）。
 * <p>
 * 当 AI 重试（{@link SkillGenerator#MAX_RETRY_PER_CANDIDATE} 次）仍不能产出
 * 通过 {@link SkillValidator} 校验的技能时，本类对 AI 原始输出做"机械级"修复——
 * 不依赖网络、不调用 AI，只按 9 环位硬约束 + 原语规则做数值夹紧、target 修正、
 * 禁用原语删除、参数补全等。
 * <p>
 * 修复策略（保守优先）：
 * <ol>
 *   <li>删除所有禁止原语（含原语类别不允许的）；原语数不足时按"补默认值"策略填齐，
 *       超出时按 execution 顺序截断</li>
 *   <li>数值夹紧：cooldown / value / radius / duration 全部 clamp 到环位硬范围</li>
 *   <li>target 修正：伤害/减益/控制类强制 TARGET；增益/防御/位移类强制 SELF；
 *       AOE 类按中心点规则</li>
 *   <li>必填参数补全：按原语声明的 required 字段（当前约定 target 必填）</li>
 *   <li>多余参数删除：原语不允许的参数静默丢弃</li>
 * </ol>
 * 修复返回 {@link Result#FIXED}；修复后仍不过校验则调用方继续走 MVP 兜底层。
 */
public final class LocalRepair {
    private static final Logger LOGGER = LogUtils.getLogger();

    private LocalRepair() {
    }

    /** 修复结果 */
    public enum Result {
        /** 已是合法技能，无需修复 */
        ALREADY_VALID,
        /** 已成功修复（修改了部分字段） */
        FIXED,
        /** 修复失败（结构性破坏：无可用原语 / 必要字段缺失） */
        UNREPAIRABLE
    }

    /** 单次修复结果汇总 */
    public record Outcome(Result result, SkillData data, List<String> repairs) {
        public boolean ok() {
            return data != null && (result == Result.FIXED || result == Result.ALREADY_VALID);
        }
    }

    /**
     * 对一条 AI 输出的技能做本地规则修复。
     *
     * @param data          AI 原始输出（可能含各种违规）
     * @param rule          该环位硬约束规格
     * @param mechanism     已抽签绑定的签名机制（用于补默写签名机制字段）
     * @return 修复结果（data 可能为 null）
     */
    public static Outcome repair(SkillData data, RingPositionRules.RuleSpec rule, SignatureMechanism mechanism) {
        if (data == null) {
            return new Outcome(Result.UNREPAIRABLE, null, List.of("SkillData 为空"));
        }
        List<String> repairs = new ArrayList<>();

        // ===== 步骤 1：清理 execution 步骤（删除禁用原语 / 未知原语 / 多余参数）=====
        List<SkillData.ExecutionStep> execSteps = new ArrayList<>();
        if (data.execution() != null) {
            for (SkillData.ExecutionStep step : data.execution()) {
                SkillData.ExecutionStep cleaned = cleanStep(step, rule, repairs);
                if (cleaned != null) execSteps.add(cleaned);
            }
        }

        // ===== 步骤 2：execution 步骤数 = 范围（补齐 / 截断）=====
        if (execSteps.size() < rule.primitiveCountMin()) {
            int need = rule.primitiveCountMin() - execSteps.size();
            for (int i = 0; i < need; i++) {
                SkillData.ExecutionStep filler = defaultPrimitiveFor(rule, mechanism, repairs);
                if (filler != null) execSteps.add(filler);
                else break;  // 无法补 → 留给上层兜底
            }
        }
        if (execSteps.size() > rule.primitiveCountMax()) {
            int cut = execSteps.size() - rule.primitiveCountMax();
            for (int i = 0; i < cut; i++) execSteps.remove(execSteps.size() - 1);
            repairs.add("execution 步骤超出环位上限，截断末尾 " + cut + " 步");
        }

        if (execSteps.isEmpty()) {
            // 完全无可用原语 → 无法修复
            repairs.add("execution 清空后无可用原语，标记 UNREPAIRABLE");
            return new Outcome(Result.UNREPAIRABLE, null, repairs);
        }

        // ===== 步骤 3：补齐类别多样性（minCategoriesRequired）=====
        Set<Category> seen = EnumSet.noneOf(Category.class);
        for (SkillData.ExecutionStep s : execSteps) {
            SkillPrimitive p = SkillPrimitive.byName(s.primitive());
            if (p != null) seen.add(p.category());
        }
        if (!seen.isEmpty() && seen.size() < rule.minCategoriesRequired()) {
            int need = rule.minCategoriesRequired() - seen.size();
            for (int i = 0; i < need && execSteps.size() < rule.primitiveCountMax(); i++) {
                SkillData.ExecutionStep filler = diversePrimitiveFor(rule, seen, repairs);
                if (filler != null) execSteps.add(filler);
            }
        }

        // ===== 步骤 4：cooldown 夹紧 =====
        int cooldown = data.cooldown();
        if (cooldown < rule.cooldownMin() || cooldown > rule.cooldownMax()) {
            int clamped = Math.max(rule.cooldownMin(), Math.min(rule.cooldownMax(), cooldown));
            repairs.add(String.format(Locale.ROOT, "冷却 %d → %d 夹紧到 [%d, %d]",
                    cooldown, clamped, rule.cooldownMin(), rule.cooldownMax()));
            cooldown = clamped;
        }

        // ===== 步骤 5：value / radius / duration 夹紧 + target 修正（已合在 cleanStep）=====
        // 此处仅对"首个有 value 的步骤"做夹紧语义（与 RingPositionRules 一致）
        for (SkillData.ExecutionStep step : execSteps) {
            Object v = step.params().get("value");
            if (v instanceof Number n) {
                double dv = n.doubleValue();
                if (dv < rule.valueMin() || dv > rule.valueMax()) {
                    double clamped = Math.max(rule.valueMin(), Math.min(rule.valueMax(), dv));
                    Map<String, Object> newParams = new HashMap<>(step.params());
                    newParams.put("value", clampDouble(clamped));
                    int idx = execSteps.indexOf(step);
                    execSteps.set(idx, new SkillData.ExecutionStep(step.primitive(), newParams));
                    repairs.add(String.format(Locale.ROOT, "value %.2f → %.2f 夹紧到 [%.2f, %.2f]",
                            dv, clamped, rule.valueMin(), rule.valueMax()));
                }
                break;
            }
        }
        for (SkillData.ExecutionStep step : execSteps) {
            Object r = step.params().get("radius");
            if (r instanceof Number n) {
                double dr = n.doubleValue();
                if (dr < rule.radiusMin() || dr > rule.radiusMax()) {
                    double clamped = Math.max(rule.radiusMin(), Math.min(rule.radiusMax(), dr));
                    Map<String, Object> newParams = new HashMap<>(step.params());
                    newParams.put("radius", clampDouble(clamped));
                    int idx = execSteps.indexOf(step);
                    execSteps.set(idx, new SkillData.ExecutionStep(step.primitive(), newParams));
                    repairs.add(String.format(Locale.ROOT, "radius %.2f → %.2f 夹紧",
                            dr, clamped));
                }
                break;
            }
        }
        for (SkillData.ExecutionStep step : execSteps) {
            Object d = step.params().get("duration");
            if (d instanceof Number n) {
                int di = n.intValue();
                if (di < rule.durationMin() || di > rule.durationMax()) {
                    int clamped = Math.max(rule.durationMin(), Math.min(rule.durationMax(), di));
                    Map<String, Object> newParams = new HashMap<>(step.params());
                    newParams.put("duration", clamped);
                    int idx = execSteps.indexOf(step);
                    execSteps.set(idx, new SkillData.ExecutionStep(step.primitive(), newParams));
                    repairs.add(String.format(Locale.ROOT, "duration %d → %d 夹紧",
                            di, clamped));
                }
                break;
            }
        }

        // ===== 步骤 6：被动白名单修正（被动只接受白名单内原语）=====
        List<SkillData.ExecutionStep> passive = new ArrayList<>();
        if (data.passive() != null) {
            for (SkillData.ExecutionStep step : data.passive()) {
                SkillData.ExecutionStep cleaned = cleanPassive(step, repairs);
                if (cleaned != null) passive.add(cleaned);
            }
        }

        // ===== 步骤 7：构造修复后 SkillData =====
        SkillData repaired = new SkillData(
                data.uuid(), data.name(), data.description(),
                cooldown, data.trigger(),
                execSteps, passive,
                data.wuhun(), data.mobId(), data.mobHealthSegment(),
                data.ringAge(), data.requiresMods(),
                data.signatureMechanism() != null && !data.signatureMechanism().isEmpty()
                        ? data.signatureMechanism()
                        : (mechanism != null ? mechanism.code : ""));

        // 二次校验：若仍不通过则标记 UNREPAIRABLE
        List<String> postErrors = RingPositionRules.forSlot(rule.slot()).validate(
                repaired, null);  // 不强制做签名唯一性（本地修复场景玩家已绑定机制）
        if (!postErrors.isEmpty()) {
            repairs.add("二次校验仍不通过: " + postErrors);
            return new Outcome(Result.UNREPAIRABLE, repaired, repairs);
        }
        return new Outcome(repairs.isEmpty() ? Result.ALREADY_VALID : Result.FIXED, repaired, repairs);
    }

    /**
     * 清理单条步骤：删除未知原语 / 删除禁用原语 / 删除原语不允许的参数 / 修正 target / 补必填参数。
     * 返回 null 表示该步骤被完全丢弃。
     */
    private static SkillData.ExecutionStep cleanStep(SkillData.ExecutionStep step,
                                                       RingPositionRules.RuleSpec rule,
                                                       List<String> repairs) {
        SkillPrimitive prim = SkillPrimitive.byName(step.primitive());
        if (prim == null) {
            repairs.add("丢弃未知原语: " + step.primitive());
            return null;
        }
        if (rule.forbiddenPrimitives().contains(prim)) {
            repairs.add("丢弃环位禁用原语: " + prim.name());
            return null;
        }
        if (!rule.allowedCategories().contains(prim.category())) {
            repairs.add("丢弃不允许类别原语: " + prim.name() + " (" + prim.category().key() + ")");
            return null;
        }
        Map<String, Object> cleaned = new HashMap<>();
        // 仅保留原语允许的参数
        for (Map.Entry<String, Object> e : step.params().entrySet()) {
            String key = e.getKey();
            Param param = Param.byKey(key);
            if (param == null) {
                repairs.add("丢弃未注册参数: " + prim.name() + "." + key);
                continue;
            }
            if (!prim.allows(param)) {
                repairs.add("丢弃原语不允许参数: " + prim.name() + "." + key);
                continue;
            }
            cleaned.put(key, e.getValue());
        }
        // target 修正
        String target = (String) cleaned.get("target");
        String wanted = targetRuleFor(prim);
        if (wanted != null) {
            if (target == null || !target.equals(wanted)) {
                cleaned.put("target", wanted);
                repairs.add("修正 target: " + prim.name() + " → " + wanted);
            }
        }
        SkillData.ExecutionStep result = new SkillData.ExecutionStep(prim.name(), cleaned);
        return result;
    }

    /**
     * target 规则：伤害/减益/控制类 → TARGET；增益/治疗/防御/位移类 → SELF。
     * 与 SkillPrimitive 系别分类保持一致（§7.5 target 规则）。
     */
    private static String targetRuleFor(SkillPrimitive prim) {
        return switch (prim.category()) {
            case STRONG, CONTROL -> "TARGET";
            case AGILE, SUPPORT, DEFENSE -> "SELF";
            case GENERAL, LIFE -> null;  // 由原语定义决定（PROJECTILE/FIRE/EXPLOSION 等使用 TARGET，POTION/SUMMON_ENTITY 灵活）
        };
    }

    /**
     * 被动步骤清理：仅保留被动白名单原语 + target 强制 SELF。
     */
    private static SkillData.ExecutionStep cleanPassive(SkillData.ExecutionStep step, List<String> repairs) {
        SkillPrimitive prim = SkillPrimitive.byName(step.primitive());
        if (prim == null) {
            repairs.add("被动丢弃未知原语: " + step.primitive());
            return null;
        }
        if (!SkillPrimitive.isPassiveAllowed(prim)) {
            repairs.add("被动丢弃非白名单原语: " + prim.name());
            return null;
        }
        Map<String, Object> cleaned = new HashMap<>();
        for (Map.Entry<String, Object> e : step.params().entrySet()) {
            Param param = Param.byKey(e.getKey());
            if (param == null || !prim.allows(param)) {
                continue;
            }
            cleaned.put(e.getKey(), e.getValue());
        }
        if (!"SELF".equals(cleaned.get("target"))) {
            cleaned.put("target", "SELF");
            repairs.add("被动 target 强制 SELF: " + prim.name());
        }
        return new SkillData.ExecutionStep(prim.name(), cleaned);
    }

    /**
     * 缺失原语数的默认填充策略：按 signature_mechanism 选默认原语；
     * 找不到时退回到环位 allowedCategories 的第一个原语。
     */
    private static SkillData.ExecutionStep defaultPrimitiveFor(RingPositionRules.RuleSpec rule,
                                                                 SignatureMechanism mechanism,
                                                                 List<String> repairs) {
        SkillPrimitive prim = pickPrimitiveForMechanism(mechanism, rule);
        if (prim == null) {
            for (Category c : rule.allowedCategories()) {
                for (SkillPrimitive p : SkillPrimitive.values()) {
                    if (p.category() == c && !rule.forbiddenPrimitives().contains(p)) {
                        prim = p;
                        break;
                    }
                }
                if (prim != null) break;
            }
        }
        if (prim == null) return null;
        Map<String, Object> params = defaultParams(prim, rule);
        repairs.add("补默认原语: " + prim.name());
        return new SkillData.ExecutionStep(prim.name(), params);
    }

    /**
     * 多样性补充：选一个环位允许的、未在已有类别集合中的类别原语。
     */
    private static SkillData.ExecutionStep diversePrimitiveFor(RingPositionRules.RuleSpec rule,
                                                                Set<Category> seen,
                                                                List<String> repairs) {
        for (Category c : rule.allowedCategories()) {
            if (seen.contains(c)) continue;
            for (SkillPrimitive p : SkillPrimitive.values()) {
                if (p.category() != c) continue;
                if (rule.forbiddenPrimitives().contains(p)) continue;
                Map<String, Object> params = defaultParams(p, rule);
                seen.add(c);
                repairs.add("补多样性原语: " + p.name() + " (" + c.key() + ")");
                return new SkillData.ExecutionStep(p.name(), params);
            }
        }
        return null;
    }

    /**
     * 签名机制 → 默认原语（§7.4 偏好表）：A/B/C/D/E/F/G/H/I/J/K/L/M/N/O。
     */
    private static SkillPrimitive pickPrimitiveForMechanism(SignatureMechanism m, RingPositionRules.RuleSpec rule) {
        if (m == null) return null;
        SkillPrimitive[] prefs = switch (m.code) {
            case "A" -> new SkillPrimitive[]{SkillPrimitive.COMBO, SkillPrimitive.BURST};
            case "B" -> new SkillPrimitive[]{SkillPrimitive.AOE_DAMAGE, SkillPrimitive.AOE_BURST};
            case "C" -> new SkillPrimitive[]{SkillPrimitive.BURST, SkillPrimitive.ARMOR_BREAK};
            case "D" -> new SkillPrimitive[]{SkillPrimitive.BIND, SkillPrimitive.SLOW};
            case "E" -> new SkillPrimitive[]{SkillPrimitive.SLOW, SkillPrimitive.WEAKEN};
            case "F" -> new SkillPrimitive[]{SkillPrimitive.STUN, SkillPrimitive.BURST};
            case "G" -> new SkillPrimitive[]{SkillPrimitive.PROJECTILE, SkillPrimitive.BURST};
            case "H" -> new SkillPrimitive[]{SkillPrimitive.AOE_BURST, SkillPrimitive.AOE_DAMAGE};
            case "I" -> new SkillPrimitive[]{SkillPrimitive.BURST, SkillPrimitive.AOE_DAMAGE};
            case "J" -> new SkillPrimitive[]{SkillPrimitive.STUN, SkillPrimitive.BURST};
            case "K" -> new SkillPrimitive[]{SkillPrimitive.REFLECT, SkillPrimitive.TAUNT};
            case "L" -> new SkillPrimitive[]{SkillPrimitive.BARRIER, SkillPrimitive.SHIELD_TRANSFER};
            case "M" -> new SkillPrimitive[]{SkillPrimitive.PHANTOM, SkillPrimitive.EVADE};
            case "N" -> new SkillPrimitive[]{SkillPrimitive.HOT, SkillPrimitive.SHIELD_TRANSFER};
            case "O" -> new SkillPrimitive[]{SkillPrimitive.WEAKEN, SkillPrimitive.SLOW};
            default -> new SkillPrimitive[0];
        };
        for (SkillPrimitive p : prefs) {
            if (!rule.allowedCategories().contains(p.category())) continue;
            if (rule.forbiddenPrimitives().contains(p)) continue;
            return p;
        }
        return null;
    }

    /**
     * 默认参数表：按原语允许的 Param 填上合理默认值（落在环位数值范围内）。
     */
    private static Map<String, Object> defaultParams(SkillPrimitive prim, RingPositionRules.RuleSpec rule) {
        Map<String, Object> params = new HashMap<>();
        for (Param p : prim.allowedParams()) {
            switch (p) {
                case TARGET -> params.put(p.key(), targetRuleFor(prim) != null ? targetRuleFor(prim) : "TARGET");
                case VALUE -> params.put(p.key(), mid(rule.valueMin(), rule.valueMax()));
                case RADIUS -> params.put(p.key(), mid(rule.radiusMin(), rule.radiusMax()));
                case DURATION -> params.put(p.key(), (int) (mid(rule.durationMin(), rule.durationMax())));
                case COUNT -> params.put(p.key(), 1);
                case AMPLIFIER -> params.put(p.key(), 1);
                case PERCENT -> params.put(p.key(), 0.3);
                case SPEED -> params.put(p.key(), 1.0);
                case DISTANCE -> params.put(p.key(), 4.0);
                case DAMAGE_TYPE -> params.put(p.key(), "MELEE");
                case DIRECTION -> params.put(p.key(), "FORWARD");
                case ATTRIBUTE -> params.put(p.key(), "ATTACK_DAMAGE");
                default -> { /* 留给原语自行兜底 */ }
            }
        }
        return params;
    }

    private static double mid(double min, double max) {
        return (min + max) / 2.0;
    }

    private static double clampDouble(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return (double) (long) v;
        return v;
    }
}