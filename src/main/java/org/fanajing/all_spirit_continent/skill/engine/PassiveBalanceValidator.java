package org.fanajing.all_spirit_continent.skill.engine;

import com.mojang.logging.LogUtils;
import org.fanajing.all_spirit_continent.skill.SkillData;
import org.slf4j.Logger;
import org.fanajing.all_spirit_continent.skill.SkillPrimitive;
import org.fanajing.all_spirit_continent.skill.SkillPrimitive.Category;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 被动平衡校验器（引擎文档 §13.6 + §3.3）。
 * <p>
 * 规范要点：
 * <ul>
 *   <li>被动白名单：ACCELERATE / BUFF_STATS / BUFF_ALL / HOT / SHIELD_TRANSFER /
 *       CLEANSE / REFLECT / BARRIER / PHANTOM / PARTICLE（§3.3）</li>
 *   <li>被动 target 必须为 SELF（§3.3）</li>
 *   <li>被动 duration 统一 200 tick（10 秒），amplifier ∈ [1, 3]（§13.6 平衡）</li>
 *   <li>禁止伤害/控制/召唤混入（§3.3）</li>
 * </ul>
 * <p>
 * <b>性质</b>：本类为<b>软校验</b>——只返回违例列表，不会单独拒入主流程。
 * 调用方可根据需要把警告转化为 Severity.WARN 或丢弃。
 * SkillFixture.loadAndReport 会单独展示 balance 偏差（与 ring 偏差区分）。
 */
public final class PassiveBalanceValidator {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** §13.6 持续时间标准值（10 秒 = 200 tick，仅作 AI Prompt 提示，不再硬校验） */
    public static final int PASSIVE_DURATION_STD = 200;

    /** §13.6 amplifier 范围 */
    public static final int PASSIVE_AMPLIFIER_MIN = 1;
    public static final int PASSIVE_AMPLIFIER_MAX = 3;

    /** §4.4 被动 percent 范围（BUFF_STATS / BUFF_ALL / ACCELERATE） */
    public static final double PERCENT_MIN = 0.1;
    public static final double PERCENT_MAX = 0.5;

    /** §3.3 被动白名单（原语名集合） */
    public static final Set<String> PASSIVE_WHITELIST = Set.of(
            "ACCELERATE", "BUFF_STATS", "BUFF_ALL", "HOT", "SHIELD_TRANSFER",
                    "CLEANSE", "REFLECT", "BARRIER", "PHANTOM", "PARTICLE");

    /** §3.3 禁止混入（伤害/控制/召唤等战斗原语） */
    public static final Set<SkillPrimitive> FORBIDDEN_IN_PASSIVE = EnumSet.of(
            SkillPrimitive.BURST, SkillPrimitive.AOE_BURST, SkillPrimitive.AOE_DAMAGE,
            SkillPrimitive.EXECUTE, SkillPrimitive.ARMOR_BREAK, SkillPrimitive.STUN,
            SkillPrimitive.SLOW, SkillPrimitive.SILENCE, SkillPrimitive.WEAKEN,
            SkillPrimitive.PULL, SkillPrimitive.TELEPORT, SkillPrimitive.SUMMON_ENTITY,
            SkillPrimitive.CHARGE, SkillPrimitive.BACKSTAB);

    private PassiveBalanceValidator() {
    }

    /**
     * 校验一条 SkillData 的 passive 部分，返回违例字符串列表（空 = 通过）。
     * 不校验 execution（主动部分）。
     *
     * @param data 待校验技能（passive 为空时返回空列表）
     * @return 违例列表；空列表表示通过
     */
    public static List<String> collect(SkillData data) {
        List<String> errors = new ArrayList<>();
        if (data == null) return errors;
        List<SkillData.ExecutionStep> passive = data.passive();
        if (passive == null || passive.isEmpty()) return errors;

        for (int i = 0; i < passive.size(); i++) {
            SkillData.ExecutionStep step = passive.get(i);
            String label = "被动[" + i + "] " + step.primitive();

            // 1) 原语名白名单
            if (!PASSIVE_WHITELIST.contains(step.primitive())) {
                errors.add(label + " 不在被动白名单（§3.3 应为 ACCELERATE / BUFF_STATS / BUFF_ALL / HOT / SHIELD_TRANSFER / CLEANSE / REFLECT / BARRIER / PHANTOM / PARTICLE）");
                continue;
            }

            // 2) 禁止战斗原语混入（再次保险，与白名单检查互补）
            SkillPrimitive prim = SkillPrimitive.byName(step.primitive());
            if (prim != null && FORBIDDEN_IN_PASSIVE.contains(prim)) {
                errors.add(label + " 为战斗原语，禁止出现在 passive 段（§3.3）");
            }

            // 3) target 必须为 SELF
            Object target = step.params().get("target");
            if (target != null && !"SELF".equalsIgnoreCase(target.toString())) {
                errors.add(label + " target 必须为 SELF（当前 " + target + "）");
            }

            // 4) duration 校验（§13.6 提议"统一 200"；但 §4.3 环位数值范围更严格，
            //    此处让位 §4.3 — PassiveBalanceValidator 不再硬校验 duration）

            // 5) amplifier 校验（仅当存在 amplifier 参数时）
            Object amp = step.params().get("amplifier");
            if (amp instanceof Number n) {
                int a = n.intValue();
                if (a < PASSIVE_AMPLIFIER_MIN || a > PASSIVE_AMPLIFIER_MAX) {
                    errors.add(String.format("%s amplifier %d 超出范围 [%d, %d]（§13.6）",
                            label, a, PASSIVE_AMPLIFIER_MIN, PASSIVE_AMPLIFIER_MAX));
                }
            }

            // 6) §4.4 被动 percent 校验：BUFF_STATS / BUFF_ALL / ACCELERATE 的 percent ∈ [0.1, 0.5]
            Object percent = step.params().get("percent");
            if (percent instanceof Number n) {
                double p = n.doubleValue();
                if (p < PERCENT_MIN || p > PERCENT_MAX) {
                    errors.add(String.format("%s percent %.2f 超出区间 [%.2f, %.2f]（§4.4 被动规则）",
                            label, p, PERCENT_MIN, PERCENT_MAX));
                }
            }
        }
        return errors;
    }

    /** 便捷方法：仅在有违规时输出 warn 日志（不抛错） */
    public static void warnIfInvalid(SkillData data, String tag) {
        List<String> errors = collect(data);
        if (!errors.isEmpty()) {
            LOGGER.warn("§13.6 被动平衡偏差 [{}] {}：",
                    tag == null ? "?" : tag,
                    data.name() == null ? "?" : data.name());
            for (String e : errors) LOGGER.warn("  {}", e);
        }
    }
}