package org.fanajing.all_spirit_continent.skill.profile;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.skill.AbilitySignature;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mob 行为观察事件订阅器（引擎文档 §5.1）。
 * <p>
 * NeoForge GAME bus 事件 → {@link AbilitySignature} + {@link SignatureObservation} 映射：
 * <ul>
 *   <li>{@code EntityJoinLevelEvent}（弹射物入场）→ PROJECTILE，params: {@code projectile_type}</li>
 *   <li>{@code EntityJoinLevelEvent}（mob 召唤的非弹射物实体）→ SUMMON_MINION，params: {@code entity_id}</li>
 *   <li>{@code MobEffectEvent.Added}：mob 给目标施加负面效果 → AOE_DEBUFF；
 *       mob 自身获得效果（喝药/光环）→ 正面 BUFF_SELF / 负面 POISON_AURA，
 *       params: {@code effect}@{@code amplifier} / {@code particle}</li>
 *   <li>{@code LivingIncomingDamageEvent}：mob 近战伤害 → 单次 ≥ 8 点 HEAVY_HIT，否则 BURST_MELEE，
 *       params: {@code damage_type}</li>
 *   <li>{@code ExplosionEvent.Start}：mob 引发的爆炸 → HEAVY_HIT，params: {@code radius}</li>
 *   <li>{@code EntityTeleportEvent}：mob 瞬移（末影人等）→ STEALTH_HIT，params: {@code distance}</li>
 *   <li>{@code LivingHurtEvent.Post}（受击音）→ SOUND，params: {@code hurt_sound}</li>
 *   <li>{@code LivingDeathEvent}（死亡音）→ SOUND，params: {@code death_sound}</li>
 *   <li>{@code MobEffectEvent.Added}（效果/粒子）→ PARTICLE，params: {@code effect}</li>
 * </ul>
 * 关键原则（§5.1）：只记录玩家附近（32 格）的行为，只在事件触发时记录，不每 tick 扫描；
 * 同一 mob 同一签名 100 tick 内节流去重，防止近战高频刷计数。
 * 画像 key 统一为 {@link net.minecraft.world.entity.EntityType#getDescriptionId()}
 * （如 "entity.minecraft.zombie"），与魂环/生成引擎的 mobId 一致。
 */
@EventBusSubscriber(modid = All_spirit_continent.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class MobObserverEventHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 观察半径（格）：仅当 32 格内有存活玩家才记录 */
    private static final double OBSERVE_RANGE = 32.0;
    /** 同一 mob + 同一签名的节流窗口（tick）：5 秒内重复行为不重复计数 */
    private static final long THROTTLE_TICKS = 100L;

    private static final Map<String, Long> THROTTLE = new ConcurrentHashMap<>();

    private MobObserverEventHandler() {
    }

    /**
     * 反射调用 LivingEntity 的 protected 声音相关方法（getHurtSound / getDeathSound）。
     * 失败时静默返回 null（不抛异常，避免污染观察日志）。
     */
    private static SoundEvent invokeProtectedSound(Class<?> owner, LivingEntity entity, String method,
                                                   Class<?>[] paramTypes, Object[] args) {
        try {
            java.lang.reflect.Method m = owner.getDeclaredMethod(method, paramTypes);
            m.setAccessible(true);
            return (SoundEvent) m.invoke(entity, args);
        } catch (Exception e) {
            return null;
        }
    }

    // ===== 弹射物 → 远程投射 + mob 召唤非弹射实体 → 召唤随从 =====

    @SubscribeEvent
    public static void onProjectileJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof Projectile projectile)) return;
        // NeoForge 21.1 的 Entity#getOwner 不通用，但 Projectile#getOwner 是 public
        if (!(projectile.getOwner() instanceof Mob mob)) return;
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType());
        String typeId = key != null ? key.toString() : projectile.getType().getDescriptionId();
        SignatureObservation obs = SignatureObservation.of(
                AbilitySignature.PROJECTILE, typeId,
                new HashMap<>(java.util.Map.of("projectile_type", typeId)),
                event.getLevel().getGameTime());
        record(mob, obs);
    }

    // ===== 药水效果 → 群体削弱 / 自我增益 / 毒气光环 / 粒子 =====

    @SubscribeEvent
    public static void onMobEffectAdded(MobEffectEvent.Added event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide) return;
        boolean beneficial = event.getEffectInstance().getEffect().value().isBeneficial();
        var instance = event.getEffectInstance();
        // getEffect() 返回 Holder<MobEffect>，用 value() 取 final 再查 registry
        var effectHolder = instance.getEffect();
        var effect = effectHolder.value();
        String effectKey = BuiltInRegistries.MOB_EFFECT.getKey(effect).toString();
        int amplifier = instance.getAmplifier();
        long now = target.level().getGameTime();

        if (event.getEffectSource() instanceof Mob caster) {
            // mob 给目标施加效果：负面 → 群体削弱；正面（给同伴 buff）暂不记录
            if (!beneficial) {
                String sig = effectKey + "@" + amplifier;
                SignatureObservation obs = SignatureObservation.of(
                        AbilitySignature.AOE_DEBUFF, sig,
                        new HashMap<>(java.util.Map.of(
                                "effect", effectKey,
                                "amplifier", amplifier)),
                        now);
                record(caster, obs);
            }
            return;
        }
        // mob 自身获得效果（药水/天生光环）：正面 → 自我增益；负面 → 毒气光环
        if (target instanceof Mob self) {
            AbilitySignature type = beneficial ? AbilitySignature.BUFF_SELF : AbilitySignature.POISON_AURA;
            String sig = effectKey + "@" + amplifier;
            Map<String, Object> params = new HashMap<>();
            params.put("effect", effectKey);
            params.put("amplifier", amplifier);
            params.put("duration", instance.getDuration());
            SignatureObservation obs = SignatureObservation.of(type, sig, params, now);
            record(self, obs);

            // §5.1 ParticleEvent：用 effect id 作为粒子 key（不同药水粒子不同）
            String particleKey = "effect_" + effectKey;
            SignatureObservation particleObs = SignatureObservation.of(
                    AbilitySignature.PARTICLE, particleKey,
                    new HashMap<>(java.util.Map.of(
                            "particle", particleKey,
                            "amplifier", amplifier)),
                    now);
            record(self, particleObs);
        }
    }

    // ===== 近战伤害 → 爆发近战 / 重击 =====

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide) return;
        if (!(event.getSource().getDirectEntity() instanceof Mob attacker)) return;
        if (target == attacker) return;
        // 单次伤害 ≥ 8 → 重击；否则常规近战连击
        boolean heavy = event.getAmount() >= 8.0F;
        AbilitySignature type = heavy ? AbilitySignature.HEAVY_HIT : AbilitySignature.BURST_MELEE;
        String damageType = event.getSource().type().msgId();
        SignatureObservation obs = SignatureObservation.of(
                type, damageType,
                new HashMap<>(java.util.Map.of(
                        "damage_type", damageType,
                        "value", event.getAmount())),
                target.level().getGameTime());
        record(attacker, obs);
    }

    // ===== 爆炸 → 重击 =====

    @SubscribeEvent
    public static void onExplosionStart(ExplosionEvent.Start event) {
        if (event.getExplosion().getDirectSourceEntity() instanceof Mob mob) {
            float radius = event.getExplosion().radius();
            SignatureObservation obs = SignatureObservation.of(
                    AbilitySignature.HEAVY_HIT, "explosion",
                    new HashMap<>(java.util.Map.of(
                            "damage_type", "explosion",
                            "radius", radius)),
                    mob.level().getGameTime());
            record(mob, obs);
        }
    }

    // ===== 瞬移 → 隐身突袭 =====

    @SubscribeEvent
    public static void onTeleport(EntityTeleportEvent event) {
        var entity = event.getEntity();
        if (entity.level().isClientSide) return;
        if (entity instanceof Mob mob) {
            double distance = event.getTarget() != null
                    ? Math.sqrt(event.getTarget().distanceToSqr(entity.position()))
                    : 0.0;
            SignatureObservation obs = SignatureObservation.of(
                    AbilitySignature.STEALTH_HIT, "teleport",
                    new HashMap<>(java.util.Map.of("distance", distance)),
                    entity.level().getGameTime());
            record(mob, obs);
        }
    }

    // ===== 受击音 → SOUND =====

    @SubscribeEvent
    public static void onLivingDamagePre(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Pre event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) return;
        if (!(entity instanceof Mob mob)) return;
        var level = mob.level();
        if (!level.hasNearbyAlivePlayer(mob.getX(), mob.getY(), mob.getZ(), OBSERVE_RANGE)) return;
        // LivingEntity#getHurtSound / getDeathSound 在 NeoForge 21.1 都是 protected，反射访问
        SoundEvent hurtSound = invokeProtectedSound(LivingEntity.class, mob, "getHurtSound",
                new Class<?>[]{net.minecraft.world.damagesource.DamageSource.class},
                new Object[]{event.getSource()});
        if (hurtSound == null) return;
        ResourceLocation key = BuiltInRegistries.SOUND_EVENT.getKey(hurtSound);
        if (key == null) return;
        String keyStr = key.toString();
        SignatureObservation obs = SignatureObservation.of(
                AbilitySignature.SOUND, keyStr,
                new HashMap<>(java.util.Map.of("sound", keyStr)),
                level.getGameTime());
        recordSignature(mob, keyStr, obs);
    }

    // ===== 死亡音 → SOUND =====

    @SubscribeEvent
    public static void onLivingDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) return;
        if (!(entity instanceof Mob mob)) return;
        var level = mob.level();
        if (!level.hasNearbyAlivePlayer(mob.getX(), mob.getY(), mob.getZ(), OBSERVE_RANGE)) return;
        // LivingEntity.getDeathSound() 是 protected，反射访问
        SoundEvent deathSound = invokeProtectedSound(LivingEntity.class, mob, "getDeathSound",
                new Class<?>[0], new Object[0]);
        if (deathSound == null) return;
        ResourceLocation key = BuiltInRegistries.SOUND_EVENT.getKey(deathSound);
        if (key == null) return;
        String keyStr = key.toString();
        SignatureObservation obs = SignatureObservation.of(
                AbilitySignature.SOUND, keyStr,
                new HashMap<>(java.util.Map.of("sound", keyStr)),
                level.getGameTime());
        recordSignature(mob, keyStr, obs);
    }

    // ===== 记录核心（32 格玩家检查 + 节流）=====

    private static void record(Mob mob, SignatureObservation obs) {
        var level = mob.level();
        if (level.isClientSide) return;
        // 只记录玩家附近的行为，避免无人战斗的全图观察刷屏
        if (!level.hasNearbyAlivePlayer(mob.getX(), mob.getY(), mob.getZ(), OBSERVE_RANGE)) return;

        String mobId = mob.getType().getDescriptionId();
        String throttleKey = mobId + '#' + obs.abilityType().name() + '#' + obs.signature();
        long now = level.getGameTime();
        Long last = THROTTLE.get(throttleKey);
        if (last != null && now - last < THROTTLE_TICKS) return;
        THROTTLE.put(throttleKey, now);

        try {
            MobObserver.observe(mob, obs, now);
        } catch (Exception e) {
            LOGGER.debug("行为观察记录失败 {}#{}: {}", mobId, obs.abilityType(), e.getClass().getSimpleName());
        }
    }

    /**
     * SOUND 专用记录（节流粒度更细，避免每次受击都重置同一 throttle）：
     * 节流键为 mobId + SOUND，不带具体声音 key。
     */
    private static void recordSignature(Mob mob, String soundKey, SignatureObservation obs) {
        var level = mob.level();
        String mobId = mob.getType().getDescriptionId();
        String throttleKey = mobId + '#' + AbilitySignature.SOUND.name();
        long now = level.getGameTime();
        Long last = THROTTLE.get(throttleKey);
        if (last != null && now - last < THROTTLE_TICKS) return;
        THROTTLE.put(throttleKey, now);
        try {
            MobObserver.observe(mob, obs, now);
        } catch (Exception e) {
            LOGGER.debug("声音观察失败 {}: {}", mobId, e.getClass().getSimpleName());
        }
    }
}