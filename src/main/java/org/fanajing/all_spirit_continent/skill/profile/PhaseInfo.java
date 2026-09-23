package org.fanajing.all_spirit_continent.skill.profile;

import org.fanajing.all_spirit_continent.skill.AbilitySignature;

import java.util.List;

/**
 * boss 战斗阶段信息（引擎文档 §5.3 多阶段 boss 画像，P1）。
 * <p>
 * 按 mob 血量段划分阶段（&gt;75% / 50~75% / 25~50% / &lt;25%），
 * 记录各阶段观察到的行为签名。小怪通常只在满血段出手 → 仅 1 段，
 * 长战斗 boss 在不同血量段使用不同技能（凋灵满血召唤、半血护甲破）→ 多段，
 * 天然区分 boss 与小怪。画像 {@code phases()} ≥ 2 段时视为多阶段 boss，
 * 注入生成 prompt 引导 AI 设计阶段变化机制（如低血量强化）。
 */
public record PhaseInfo(int phase, String trigger, List<String> abilities) {

    /** 血量段数（1~4） */
    public static final int MAX_PHASE = 4;

    /** 血量比例 → 阶段编号（1=开场满血 … 4=濒死狂暴） */
    public static int segmentOf(double healthRatio) {
        if (healthRatio > 0.75) return 1;
        if (healthRatio > 0.50) return 2;
        if (healthRatio > 0.25) return 3;
        return 4;
    }

    /** 阶段编号 → 触发条件描述（供 prompt 展示） */
    public static String triggerOf(int phase) {
        return switch (phase) {
            case 1 -> "战斗开始（血量>75%）";
            case 2 -> "血量降至50%~75%";
            case 3 -> "血量降至25%~50%";
            default -> "濒死（血量<25%）";
        };
    }

    /** 阶段签名展示（AbilitySignature displayName 列表 → 顿号连接） */
    public static String describe(List<AbilitySignature> signatures) {
        StringBuilder sb = new StringBuilder();
        for (AbilitySignature sig : signatures) {
            if (sb.length() > 0) sb.append("、");
            sb.append(sig.displayName);
        }
        return sb.toString();
    }
}
