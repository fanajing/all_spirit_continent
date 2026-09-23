package org.fanajing.all_spirit_continent.skill.engine;

import java.util.Optional;

/**
 * 魂技签名机制（引擎文档 §6）。每个魂环位绑定一种独特机制，
 * 形成 9 环位差异化体系（连续释放 / 范围放大 / 延时爆破 ...）。
 * <p>
 * 字母 A~O 共 15 种；每个机制指示该魂技的核心运作方式，并影响 AI 生成阶段的
 * 参数取值方向（cooldownScale / radiusScale / valueScale / durationScale / countScale）。
 * <p>
 * 玩家级持久化：{@code PlayerSkillConfig.ringMechanisms} 记录每个魂环位的签名机制，
 * 同武魂不同环不得重复（详见 {@link SignaturePool}）。
 */
public enum SignatureMechanism {
    A_RAPID_CAST("A", "连续释放", "短 CD 多段连击（≤3 段链式），冷却时间压缩",
            0.5, 1.0, 1.0, 1.0, 1.0),
    B_EXPANDED_REACH("B", "范围放大", "作用范围扩大 50%~100%（半径/距离参数提升）",
            1.0, 1.75, 1.0, 1.0, 1.0),
    C_AMPLIFIED_EFFECT("C", "强化效果", "数值大小提升 50%~100%（value/percent 参数放大）",
            1.0, 1.0, 1.75, 1.0, 1.0),
    D_DELAYED_BLAST("D", "延时爆破", "施法后 1~2 秒延时引爆（duration 驱动引爆阶段）",
            1.0, 1.0, 1.0, 1.5, 1.0),
    E_EXTENDED_DURATION("E", "持续时间", "持续效果时长翻倍或三倍（duration 参数放大）",
            1.0, 1.0, 1.0, 2.5, 1.0),
    F_CHANNEL_CAST("F", "引导施法", "≥0.5 秒蓄力引导（附加 cast_time 字段）",
            1.0, 1.0, 1.0, 1.0, 1.0),
    G_PINNED_TARGET("G", "锁定目标", "投射物自动寻的/弹道锁定",
            1.0, 1.0, 1.0, 1.0, 1.0),
    H_MULTI_TARGET("H", "多重目标", "目标/数量翻倍至五倍（count 参数放大）",
            1.0, 1.0, 1.0, 1.0, 3.0),
    I_INSTANT_CAST("I", "瞬发", "0 施法时间，冷却 -50%",
            0.5, 1.0, 1.0, 1.0, 1.0),
    J_OVERCHARGED("J", "蓄力最大", "蓄力上限 3 秒，蓄满增伤 100%+",
            1.0, 1.0, 1.5, 1.0, 1.0),
    K_REFLECT("K", "反弹", "反弹伤害给攻击者（反射原语联动）",
            1.0, 1.0, 1.0, 1.0, 1.0),
    L_BARRIER("L", "护盾", "附加护盾值（SHIELD_TRANSFER/BARRIER 原语增强）",
            1.0, 1.0, 1.0, 1.0, 1.0),
    M_STEALTH("M", "隐身", "施法/命中后隐身数秒",
            1.0, 1.0, 1.0, 1.0, 1.0),
    N_HEAL("N", "附加治疗", "附带治疗效果（HOT/HEAL 原语联动）",
            1.0, 1.0, 1.5, 1.0, 1.0),
    O_DEBUFF("O", "削弱", "附加负面效果（多 WEAKEN/SLOW 叠加）",
            1.0, 1.0, 1.0, 1.0, 1.0);

    public final String code;
    public final String displayName;
    public final String description;
    /** 冷却乘数（默认 1.0；A/I 为 0.5 = 冷却压缩 50%） */
    public final double cooldownScale;
    public final double radiusScale;
    public final double valueScale;
    public final double durationScale;
    public final double countScale;

    SignatureMechanism(String code, String displayName, String description,
                        double cooldownScale, double radiusScale, double valueScale,
                        double durationScale, double countScale) {
        this.code = code;
        this.displayName = displayName;
        this.description = description;
        this.cooldownScale = cooldownScale;
        this.radiusScale = radiusScale;
        this.valueScale = valueScale;
        this.durationScale = durationScale;
        this.countScale = countScale;
    }

    public static Optional<SignatureMechanism> byCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        String c = code.trim().toUpperCase();
        for (SignatureMechanism m : values()) if (m.code.equals(c)) return Optional.of(m);
        return Optional.empty();
    }

    /** 该 code 是否有效（A~O 之一） */
    public static boolean isValidCode(String code) {
        return byCode(code).isPresent();
    }
}