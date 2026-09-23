package org.fanajing.all_spirit_continent.skill.engine;

import org.fanajing.all_spirit_continent.skill.SkillData;
import org.fanajing.all_spirit_continent.util.PlayerSkillConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 魂技校验器（引擎文档 §7.8）。
 * <p>
 * 多层校验：
 * <ol>
 *   <li>基础校验：名称/触发/execution+passive 至少一个非空（原语 + 参数类型由 SkillData.isStepValid 覆盖）</li>
 *   <li>9 环位硬约束：原语数/类别/禁用原语/数值范围（见 {@link RingPositionRules}）</li>
 *   <li>签名机制唯一性：玩家其他环位不占用同机制</li>
 * </ol>
 * 返回违规列表（空 = 通过）；非空时该 SkillData 被拒入池、不入本地缓存、不下载。
 * <p>
 * P0 决策：旧技能（云端条目 / 本地 json 示例）校验不过 → 直接删除（不软标注 PENDING）。
 */
public final class SkillValidator {
    private SkillValidator() {
    }

    /** 校验结果 */
    public enum Verdict {
        /** 通过：可入库 / 可被抽取 / 可绑定 */
        PASS,
        /** 拒绝：校验失败，整条丢弃（云端条目清空 + 本地 json 清空 + 玩家绑定重置） */
        REJECT
    }

    /**
     * 完整校验一条 SkillData 是否可在指定环位入库。
     *
     * @param data 待校验技能
     * @param slot 该技能被绑定的环位 1~9
     * @param cfg  玩家配置（用于签名唯一性；null 时跳过唯一性检查）
     */
    public static Verdict validate(SkillData data, int slot, PlayerSkillConfig cfg) {
        List<String> errors = collect(data, slot, cfg);
        return errors.isEmpty() ? Verdict.PASS : Verdict.REJECT;
    }

    /** 收集所有违规（用于日志展示） */
    public static List<String> collect(SkillData data, int slot, PlayerSkillConfig cfg) {
        List<String> errors = new ArrayList<>();
        // 1) 基础校验
        if (data == null) {
            errors.add("SkillData 为空");
            return errors;
        }
        if (!data.isValid()) {
            errors.add("基础校验失败：名称为空 或 execution/passive 都为空");
        }
        // 2) 环位硬约束 + 3) 签名唯一性
        try {
            Set<SignatureMechanism> used = null;
            if (cfg != null) {
                used = cfg.usedMechanisms();
                // 本环位自身已绑定的机制不算「其他环位占用」（生成流程先抽签绑定再校验候选）
                cfg.mechanismAt(slot).ifPresent(used::remove);
            }
            errors.addAll(RingPositionRules.forSlot(slot).validate(data, used));
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }
        return errors;
    }

    /**
     * 云端/本地库条目的引擎有效性（全局级，与环位无关）：
     * 必须带有合法签名机制代码（A~O）+ 基础字段有效。
     * 旧版（V6.0 及之前）条目无 signature_mechanism → 不合格，按 P0 决策直接删除。
     */
    public static boolean isEngineEntry(org.fanajing.all_spirit_continent.skill.SkillEntry entry) {
        if (entry == null || entry.skill() == null) return false;
        return entry.skill().isValid()
                && SignatureMechanism.isValidCode(entry.skill().signatureMechanism());
    }

    /** 仅基础校验（不校验环位规则）。用于云端条目初步入库过滤 */
    public static Verdict validateBasic(SkillData data) {
        if (data == null || !data.isValid()) return Verdict.REJECT;
        return Verdict.PASS;
    }
}