package org.fanajing.all_spirit_continent.skill.engine;

import com.mojang.logging.LogUtils;
import org.fanajing.all_spirit_continent.skill.Param;
import org.fanajing.all_spirit_continent.skill.SkillData;
import org.fanajing.all_spirit_continent.skill.SkillPrimitive;
import org.fanajing.all_spirit_continent.skill.SkillPrimitive.Category;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MVP 兜底技能构建器（引擎文档 §8.2 第 4 层降级）。
 * <p>
 * 当 AI 重试（{@link SkillGenerator#MAX_RETRY_PER_CANDIDATE} 次）和本地规则修复
 * （{@link LocalRepair}）都失败时，本类生成一个保底技能——不依赖 AI、不依赖网络、
 * 不依赖 mob 画像，100% 能通过引擎校验，确保玩家在 AI 服务异常时仍有技能可用。
 * <p>
 * 设计原则：
 * <ol>
 *   <li>执行步骤数 = 1（环位 primitiveCountMin），规避原语数硬约束</li>
 *   <li>按签名机制 + 环位 allowedCategories 选一个稳定可用的原语（AOE_DAMAGE / BIND / BUFF_STATS 等）</li>
 *   <li>数值取环位范围中位（已确保在硬范围内）</li>
 *   <li>被动固定为单条 BUFF_STATS（小幅攻击/属性增益）</li>
 *   <li>技能名以 "兜底·" 前缀，让玩家可识别</li>
 * </ol>
 * 兜底技能不写本地缓存、不上传云端；仅供本次吸收使用。
 */
public final class FallbackSkillBuilder {
    private static final Logger LOGGER = LogUtils.getLogger();

    private FallbackSkillBuilder() {
    }

    /**
     * 生成 MVP 兜底技能。
     *
     * @param slot      环位 1~9
     * @param wuhun     武魂名（用于命名 "兜底·<武魂>·<原语中文>"）
     * @param mobId     魂兽注册名（用于 SkillData.mobId，不参与逻辑）
     * @param segment   魂兽生命段标签（用于 SkillData.mobHealthSegment，不参与逻辑）
     * @param mechanism 已抽签绑定的签名机制（用于挑选默认原语）
     * @param ringAge   魂环年限（用于伤害缩放）
     * @return 一定通过引擎校验的保底技能（除非环位非法抛 IAE）
     */
    public static SkillData build(int slot, String wuhun, String mobId, String segment,
                                   SignatureMechanism mechanism, int ringAge) {
        RingPositionRules.RuleSpec rule = RingPositionRules.forSlot(slot);
        SkillPrimitive prim = pickFallbackPrimitive(rule, mechanism);
        Map<String, Object> params = buildFallbackParams(prim, rule);

        List<SkillData.ExecutionStep> execution = List.of(
                new SkillData.ExecutionStep(prim.name(), params));
        // 被动：单条 BUFF_STATS（小幅攻击增益，常驻增益 10s）
        Map<String, Object> passiveParams = new HashMap<>();
        passiveParams.put("target", "SELF");
        passiveParams.put("attribute", "ATTACK_DAMAGE");
        passiveParams.put("percent", 0.1);
        passiveParams.put("duration", 200);
        List<SkillData.ExecutionStep> passive = List.of(
                new SkillData.ExecutionStep("BUFF_STATS", passiveParams));

        SkillData skill = new SkillData(
                UUID.randomUUID().toString(),
                "兜底·" + (wuhun == null || wuhun.isEmpty() ? "武魂" : wuhun) + "·" + primChineseName(prim),
                primChineseName(prim) + "的保底效果",
                Math.max(SkillData.MIN_COOLDOWN, (rule.cooldownMin() + rule.cooldownMax()) / 2),
                "RIGHT_CLICK",
                execution, passive,
                wuhun == null ? "" : wuhun,
                mobId == null ? "" : mobId,
                segment == null ? "" : segment,
                Math.max(0, ringAge),
                List.of(),
                mechanism != null ? mechanism.code : "");
        LOGGER.warn("生成 MVP 兜底技能: 环位 {} / 原语 {} / 签名 {}",
                slot, prim.name(), mechanism != null ? mechanism.code : "?");
        return skill;
    }

    /**
     * 兜底原语选择策略：
     * <ol>
     *   <li>签名机制偏好（§7.4） → 选偏好表中第一个该环位 allowedCategories 内的原语</li>
     *   <li>否则选环位 allowedCategories 的第一个原语</li>
     *   <li>最后兜底：AOE_DAMAGE（绝大多数环位允许）</li>
     * </ol>
     */
    private static SkillPrimitive pickFallbackPrimitive(RingPositionRules.RuleSpec rule,
                                                          SignatureMechanism mechanism) {
        SkillPrimitive[] prefs = preferencesFor(mechanism);
        for (SkillPrimitive p : prefs) {
            if (!rule.allowedCategories().contains(p.category())) continue;
            if (rule.forbiddenPrimitives().contains(p)) continue;
            return p;
        }
        // 兜底：环位允许类别中的第一个非禁用原语
        for (Category c : rule.allowedCategories()) {
            for (SkillPrimitive p : SkillPrimitive.values()) {
                if (p.category() != c) continue;
                if (rule.forbiddenPrimitives().contains(p)) continue;
                return p;
            }
        }
        // 终极兜底：AOE_DAMAGE（几乎所有环位允许）
        return SkillPrimitive.AOE_DAMAGE;
    }

    private static SkillPrimitive[] preferencesFor(SignatureMechanism m) {
        if (m == null) return new SkillPrimitive[0];
        return switch (m.code) {
            case "A" -> new SkillPrimitive[]{SkillPrimitive.COMBO, SkillPrimitive.BURST};
            case "B" -> new SkillPrimitive[]{SkillPrimitive.AOE_DAMAGE, SkillPrimitive.AOE_BURST};
            case "C" -> new SkillPrimitive[]{SkillPrimitive.BURST};
            case "D" -> new SkillPrimitive[]{SkillPrimitive.BIND};
            case "E" -> new SkillPrimitive[]{SkillPrimitive.SLOW};
            case "F" -> new SkillPrimitive[]{SkillPrimitive.STUN};
            case "G" -> new SkillPrimitive[]{SkillPrimitive.PROJECTILE};
            case "H" -> new SkillPrimitive[]{SkillPrimitive.AOE_BURST};
            case "I" -> new SkillPrimitive[]{SkillPrimitive.BURST};
            case "J" -> new SkillPrimitive[]{SkillPrimitive.STUN};
            case "K" -> new SkillPrimitive[]{SkillPrimitive.REFLECT};
            case "L" -> new SkillPrimitive[]{SkillPrimitive.BARRIER};
            case "M" -> new SkillPrimitive[]{SkillPrimitive.PHANTOM};
            case "N" -> new SkillPrimitive[]{SkillPrimitive.HOT};
            case "O" -> new SkillPrimitive[]{SkillPrimitive.WEAKEN};
            default -> new SkillPrimitive[0];
        };
    }

    /**
     * 默认参数：取环位范围中位 + target 规则 + 必要 Param 全部填上。
     */
    private static Map<String, Object> buildFallbackParams(SkillPrimitive prim, RingPositionRules.RuleSpec rule) {
        Map<String, Object> params = new HashMap<>();
        String wantedTarget = targetRuleFor(prim);
        for (Param p : prim.allowedParams()) {
            switch (p) {
                case TARGET -> params.put(p.key(), wantedTarget != null ? wantedTarget : "TARGET");
                case VALUE -> params.put(p.key(), mid(rule.valueMin(), rule.valueMax()));
                case RADIUS -> params.put(p.key(), mid(rule.radiusMin(), rule.radiusMax()));
                case DURATION -> params.put(p.key(), (int) mid(rule.durationMin(), rule.durationMax()));
                case COUNT -> params.put(p.key(), 1);
                case AMPLIFIER -> params.put(p.key(), 1);
                case PERCENT -> params.put(p.key(), 0.3);
                case SPEED -> params.put(p.key(), 1.0);
                case DISTANCE -> params.put(p.key(), 4.0);
                case DAMAGE_TYPE -> params.put(p.key(), "MELEE");
                case DIRECTION -> params.put(p.key(), "FORWARD");
                case ATTRIBUTE -> params.put(p.key(), "ATTACK_DAMAGE");
                case CRIT_CHANCE -> params.put(p.key(), 0.0);
                default -> { /* 留给原语自行兜底 */ }
            }
        }
        return params;
    }

    private static String targetRuleFor(SkillPrimitive prim) {
        return switch (prim.category()) {
            case STRONG, CONTROL -> "TARGET";
            case AGILE, SUPPORT, DEFENSE -> "SELF";
            case GENERAL, LIFE -> "TARGET";
        };
    }

    private static double mid(double min, double max) {
        return (min + max) / 2.0;
    }

    /**
     * 原语 → 中文显示名（兜底命名用，避免翻译键缺失崩溃）。
     */
    private static String primChineseName(SkillPrimitive prim) {
        return switch (prim) {
            case BIND -> "束缚";
            case SILENCE -> "沉默";
            case BLIND -> "致盲";
            case ROOT -> "定身";
            case SLOW -> "减速";
            case WEAKEN -> "虚弱";
            case CONFUSE -> "混乱";
            case DISARM -> "缴械";
            case BURST -> "爆发";
            case AOE_BURST -> "范围爆发";
            case AOE_DAMAGE -> "范围伤害";
            case ARMOR_BREAK -> "破甲";
            case EXECUTE -> "处决";
            case STUN -> "眩晕";
            case CHARGE -> "冲锋";
            case DASH -> "突进";
            case COMBO -> "连击";
            case PHANTOM -> "幻影";
            case EVADE -> "闪避";
            case BACKSTAB -> "背刺";
            case ACCELERATE -> "加速";
            case BUFF_STATS -> "属性增益";
            case BUFF_ALL -> "全体增益";
            case SHIELD_TRANSFER -> "护盾";
            case CLEANSE -> "净化";
            case HOT -> "持续治疗";
            case REVIVE -> "复苏";
            case CD_REDUCE -> "冷却缩减";
            case TAUNT -> "嘲讽";
            case REFLECT -> "反弹";
            case BARRIER -> "屏障";
            case IRON_BODY -> "铁身";
            case HARDEN -> "硬化";
            case CROP_GROW -> "催熟";
            case FOOD_BLESS -> "赐食";
            case HARVEST -> "收割";
            case BONEMEAL -> "骨粉";
            case SUMMON_ENTITY -> "召唤";
            case PROJECTILE -> "投射";
            case EXPLOSION -> "爆炸";
            case FIRE -> "点燃";
            case SOUND -> "音效";
            case PARTICLE -> "粒子";
            case TELEPORT -> "传送";
            case PULL -> "牵引";
            case KNOCKBACK -> "击退";
            case POTION -> "药水";
        };
    }
}