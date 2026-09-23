package org.fanajing.all_spirit_continent.skill.engine;

import net.minecraft.util.RandomSource;
import org.fanajing.all_spirit_continent.util.PlayerSkillConfig;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * 魂技签名机制池（引擎文档 §6）。
 * <p>
 * 提供：
 * <ul>
 *   <li>{@link #availableFor(int)}：按环位过滤禁用签名（来自 RingPositionRules.forbiddenSignatures）</li>
 *   <li>{@link #availableForPlayer(int, PlayerSkillConfig)}：进一步过滤玩家已占用签名（玩家级唯一性）</li>
 *   <li>{@link #draw(int, PlayerSkillConfig, RandomSource)}：随机抽签（不绑定）</li>
 *   <li>{@link #drawAndBind(int, PlayerSkillConfig, RandomSource)}：抽签并绑定到环位</li>
 * </ul>
 * 持久化层在 {@link PlayerSkillConfig#ringMechanisms}，本类不直接读写存档。
 */
public final class SignaturePool {
    private SignaturePool() {
    }

    /** 该环位理论上可选的签名集合（去掉 RingPositionRules 禁用的） */
    public static Set<SignatureMechanism> availableFor(int slot) {
        Set<SignatureMechanism> all = EnumSet.allOf(SignatureMechanism.class);
        all.removeAll(RingPositionRules.forSlot(slot).forbiddenSignatures());
        return all;
    }

    /** 该环位玩家当前可用的签名集（理论集 - 玩家已占用） */
    public static Set<SignatureMechanism> availableForPlayer(int slot, PlayerSkillConfig cfg) {
        Set<SignatureMechanism> available = availableFor(slot);
        if (cfg != null) available.removeAll(cfg.usedMechanisms());
        return available;
    }

    /**
     * 随机抽取一个签名机制（仅返回，不修改 cfg）。
     * 无可选签名时返回 {@code null}（=15 个机制全被该环位禁用 + 占用，应提升魂环品质重抽或报错）。
     */
    public static SignatureMechanism draw(int slot, PlayerSkillConfig cfg, RandomSource random) {
        Set<SignatureMechanism> available = availableForPlayer(slot, cfg);
        if (available.isEmpty()) return null;
        SignatureMechanism[] arr = available.toArray(new SignatureMechanism[0]);
        return arr[random.nextInt(arr.length)];
    }

    /**
     * 随机抽取并绑定到环位。
     * 绑定失败（环位非法 / 抽签失败）返回 empty；调用方需将 cfg 标脏。
     */
    public static Optional<SignatureMechanism> drawAndBind(int slot, PlayerSkillConfig cfg, RandomSource random) {
        SignatureMechanism m = draw(slot, cfg, random);
        if (m == null) return Optional.empty();
        if (cfg == null || !cfg.bindMechanism(slot, m)) return Optional.empty();
        return Optional.of(m);
    }

    /**
     * 显式绑定一个签名（用于从云端拉取已绑定机制的魂技时回放玩家级持有状态）。
     * 不随机抽取；签名机制不在可用集（禁用/已占用）时返回 false。
     */
    public static boolean bindExplicit(int slot, SignatureMechanism mechanism, PlayerSkillConfig cfg) {
        if (mechanism == null || cfg == null) return false;
        if (!availableFor(slot).contains(mechanism)) return false;
        return cfg.bindMechanism(slot, mechanism);
    }
}