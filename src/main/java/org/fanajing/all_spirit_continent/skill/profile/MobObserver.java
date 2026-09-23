package org.fanajing.all_spirit_continent.skill.profile;

import net.minecraft.world.entity.Mob;
import org.fanajing.all_spirit_continent.skill.AbilitySignature;

/**
 * Mob 行为观察器（引擎文档 §5）。
 * <p>
 * 事件订阅由 {@link MobObserverEventHandler}（@EventBusSubscriber 自动注册）完成：
 * 弹射物发射 / 召唤随从 / 药水效果 / 近战与爆炸伤害 / 实体瞬移 / 受击音与死亡音 → 构造
 * {@link SignatureObservation} 后经 {@link #observe(Mob, SignatureObservation, long)} 累加到
 * {@link ProfileCache}，签名计数通过 {@link MobProfileDeduper}（§5.4）做容差去重。
 * <p>
 * 首次观察到某 mob 行为时，顺带用 {@link AttributeInference}（§5.3 + §5.7）注入
 * 体型 / 攻击模式 / 战斗定位关键词 / 实体属性快照 / 主题关键词；
 * 重复观察驱动置信度上升（§5.5，见 {@link MobProfile#confidence}）。
 * <p>
 * 兼容旧 API：{@link #observe(Mob, AbilitySignature, long)} 仍可用，内部转空 params 走 deduper。
 */
public final class MobObserver {
    private static long totalObservations = 0L;

    private MobObserver() {
    }

    /** 累计观察次数（所有 mob 的签名累加总和） */
    public static long totalObservations() {
        return totalObservations;
    }

    /**
     * 记录一条带参数的行为观察（新版主入口，服务端事件回调调用）。
     * 首次观察该 mob 时注入属性推断画像（§5.3 + §5.7），随后用 {@link MobProfileDeduper} 做容差去重。
     * <p>
     * @param phaseSegment 血量阶段（1~4，§5.3 多阶段 boss 分桶）；≤0 不记录阶段
     */
    public static void observe(Mob mob, SignatureObservation obs, long atTick) {
        if (mob == null || obs == null || mob.level().isClientSide) return;
        if (obs.abilityType() == null) return;
        String mobId = mob.getType().getDescriptionId();
        MobProfile current = ProfileCache.get(mobId);
        if (!current.observed()) {
            // 冷启动：首次观察 → 属性推断体型/攻击模式/战斗定位/属性快照/主题关键词
            current = AttributeInference.infer(mob, atTick);
        }
        double maxHealth = mob.getMaxHealth();
        int segment = maxHealth > 0 ? PhaseInfo.segmentOf(mob.getHealth() / maxHealth) : 0;

        // §5.4 容差去重：找到相似签名 → 计数 +1；找不到 → 新签名
        MobProfileDeduper.DedupResult result = MobProfileDeduper.mergeInto(
                current, java.util.List.of(obs), atTick);
        MobProfile updated = result.mergedProfile();
        // 合并阶段分桶（deduper 不做 phase 路由）
        if (segment >= 1 && segment <= PhaseInfo.MAX_PHASE && !obs.params().isEmpty()) {
            // 重新应用阶段分桶（mergeInto 已经更新签名计数，这里补 phase）
            MobProfile withPhase = ProfileCache.get(mobId).withObservedSignature(obs.abilityType(), atTick, segment);
            updated = result.mergedProfile().withObservedSignature(obs.abilityType(), atTick, segment);
        }
        // §5.1 SOUND / PARTICLE：把签名携带的 sound / particle 字符串写入画像 sounds/particles 列表
        updated = applySignatureExtras(updated, obs);

        ProfileCache.put(updated);
        totalObservations++;
    }

    /**
     * 兼容旧 API：直接传 AbilitySignature（无参数）。内部构造空 params 的 SignatureObservation 走 deduper。
     */
    public static void observe(Mob mob, AbilitySignature signature, long atTick) {
        if (mob == null || signature == null) return;
        observe(mob, SignatureObservation.of(signature), atTick);
    }

    /**
     * 直接按 mobId 注入一条行为签名（持久化载入 / 云端聚合 / 调试用，无实体引用，
     * 无血量上下文，不记录阶段分桶，不写 sounds/particles）。
     */
    public static void recordSignature(String mobId, AbilitySignature signature, long atTick) {
        if (mobId == null || signature == null) return;
        MobProfile current = ProfileCache.get(mobId);
        MobProfile updated = current.withObservedSignature(signature, atTick, 0);
        ProfileCache.put(updated);
        totalObservations++;
    }

    /** 重置全部状态（仅测试） */
    public static void reset() {
        ProfileCache.clear();
        totalObservations = 0L;
    }

    /** §5.1 SOUND / PARTICLE：把签名携带的 sound/particle 字符串写入画像 */
    private static MobProfile applySignatureExtras(MobProfile profile, SignatureObservation obs) {
        if (profile == null || obs == null) return profile;
        MobProfile p = profile;
        if (obs.abilityType() == AbilitySignature.SOUND) {
            String sound = obs.paramString("sound");
            if (sound != null) p = p.withSound(sound);
        } else if (obs.abilityType() == AbilitySignature.PARTICLE) {
            String particle = obs.paramString("particle");
            if (particle != null) p = p.withParticle(particle);
        }
        return p;
    }
}