package org.fanajing.all_spirit_continent.skill;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.fanajing.all_spirit_continent.init.ModAttachments;
import org.fanajing.all_spirit_continent.skill.SkillData.ExecutionStep;
import org.fanajing.all_spirit_continent.util.PlayerLevelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 魂技执行引擎（服务端权威）。
 * <p>
 * 解析 SkillData.execution 中的动作原语序列，逐条映射到 Minecraft 机制：
 * 伤害/药水效果/位移/爆炸/粒子/音效/实体召唤/方块操作等。
 * 集中处理：技能冷却（服务端内存记录）、精神力消耗（PlayerLevelData）、
 * 目标解析（SELF / TARGET=准星实体）、数值平衡常量。
 */
public class SkillExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger("AllSpiritSkill");

    /** 技能冷却记录：玩家uuid:技能uuid → 上次释放的游戏 tick（每技能独立冷却，重启即失可接受） */
    private static final Map<String, Long> LAST_CAST_TICKS = new ConcurrentHashMap<>();

    /** 技能释放结果 */
    public enum CastResult {
        /** 成功释放 */
        SUCCESS,
        /** 冷却中 */
        COOLDOWN,
        /** 精神力不足 */
        NO_SPIRIT,
        /** 未开武魂（无魂环） */
        NO_WUHUN,
        /** 技能数据无效 */
        FAILED
    }

    private SkillExecutor() {
    }

    // ===== 主入口 =====

    /**
     * 尝试释放魂技：武魂校验（有魂环）→ 冷却校验 → 精神力消耗 → 执行原语序列。
     *
     * @return 释放结果（调用方据此给玩家提示）
     */
    public static CastResult tryCast(ServerPlayer caster, SkillData skill) {
        if (skill == null || !skill.isValid()) return CastResult.FAILED;

        ServerLevel level = caster.serverLevel();
        PlayerLevelData data = caster.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        if (!data.isActivated() || data.getRingCount() <= 0) return CastResult.NO_WUHUN;

        // 冷却（每技能独立记录，切换环位不共享）
        long now = level.getGameTime();
        String castKey = caster.getUUID() + ":" + skill.uuid();
        Long last = LAST_CAST_TICKS.get(castKey);
        if (last != null && now - last < skill.cooldown()) {
            return CastResult.COOLDOWN;
        }

        // 精神力消耗
        float cost = estimatedCost(skill);
        if (data.getSpiritPower() < cost) {
            return CastResult.NO_SPIRIT;
        }
        data.consumeSpiritPower(cost);

        LAST_CAST_TICKS.put(castKey, now);

        // 执行原语序列（伤害/治疗按魂环年限缩放；每个步骤独立解析目标）
        Entity aimed = aimedEntity(caster);
        double damageMult = SkillData.damageMultiplier(skill.ringAge());
        for (ExecutionStep step : skill.execution()) {
            try {
                executeStep(level, caster, step, resolveStepTarget(caster, aimed, step), damageMult);
            } catch (Exception e) {
                LOGGER.warn("执行原语 {} 出错: {}", step.primitive(), e.getMessage());
            }
        }
        return CastResult.SUCCESS;
    }

    /** 估算魂技精神力消耗：原语数 × 5 + 冷却秒数 × 0.5（保底 5） */
    public static float estimatedCost(SkillData skill) {
        int steps = skill.execution() == null ? 0 : skill.execution().size();
        float cost = 5f * Math.max(1, steps) + skill.cooldown() / 40f;
        return Math.max(5f, cost);
    }

    // ===== 目标解析 =====

    /** 敌向原语：默认作用于敌人；目标错误会把伤害/减益施加到施法者自身 */
    private static final Set<SkillPrimitive> HOSTILE_TARGET = EnumSet.of(
            SkillPrimitive.BIND, SkillPrimitive.SILENCE, SkillPrimitive.BLIND, SkillPrimitive.ROOT,
            SkillPrimitive.SLOW, SkillPrimitive.WEAKEN, SkillPrimitive.CONFUSE, SkillPrimitive.DISARM,
            SkillPrimitive.BURST, SkillPrimitive.AOE_BURST, SkillPrimitive.AOE_DAMAGE,
            SkillPrimitive.ARMOR_BREAK, SkillPrimitive.EXECUTE, SkillPrimitive.STUN,
            SkillPrimitive.CHARGE, SkillPrimitive.COMBO, SkillPrimitive.BACKSTAB,
            SkillPrimitive.PROJECTILE, SkillPrimitive.FIRE, SkillPrimitive.KNOCKBACK, SkillPrimitive.POTION);

    /** 范围系原语：无准星目标时以施法者自身为圆心释放（不会伤害自己） */
    private static final Set<SkillPrimitive> AOE_CENTER = EnumSet.of(
            SkillPrimitive.AOE_BURST, SkillPrimitive.AOE_DAMAGE);

    /** 按步骤解析生效目标（每步独立）：
     *  显式 target=SELF → 施法者自身；显式 target=TARGET → 准星敌人；
     *  省略 target → 敌向原语取准星敌人（范围系无目标时以自身为圆心，其余跳过，绝不作用自身），
     *  增益/治疗/防御/位移类默认自身。 */
    private static Entity resolveStepTarget(ServerPlayer caster, Entity aimed, ExecutionStep step) {
        SkillPrimitive p = SkillPrimitive.byName(step.primitive());
        String t = String.valueOf(step.params().get(Param.TARGET.key()));
        boolean explicitTarget = "TARGET".equalsIgnoreCase(t) || "TARGET_AREA".equalsIgnoreCase(t);
        if ("SELF".equalsIgnoreCase(t)) return caster;
        if (p != null && HOSTILE_TARGET.contains(p)) {
            if (aimed != null) return aimed;
            return AOE_CENTER.contains(p) ? caster : null;
        }
        return explicitTarget && aimed != null ? aimed : caster;
    }

    /** 射线检测施法者准星前方 8 格内第一个存活实体；无则返回 null（敌向步骤将跳过） */
    private static Entity aimedEntity(ServerPlayer caster) {
        Vec3 eye = caster.getEyePosition();
        Vec3 look = caster.getLookAngle();
        Vec3 end = eye.add(look.scale(8.0));
        AABB aabb = caster.getBoundingBox().expandTowards(look.scale(8.0)).inflate(1.0);
        var hit = ProjectileUtil.getEntityHitResult(caster, eye, end, aabb,
                e -> e instanceof LivingEntity le && le.isAlive() && e != caster, 8.0);
        return hit != null ? hit.getEntity() : null;
    }

    // ===== 单步执行 =====

    private static void executeStep(ServerLevel level, ServerPlayer caster, ExecutionStep step, Entity target, double damageMult) {
        SkillPrimitive p = SkillPrimitive.byName(step.primitive());
        if (p == null) return;
        // 敌向原语无有效目标：跳过该步骤，绝不作用到施法者自身
        if (target == null && HOSTILE_TARGET.contains(p)) return;
        Map<String, Object> params = step.params();

        switch (p) {
            // ===== 控制系 =====
            case BIND -> applyEffect(target, "slowness", dur(params, 100), amp(params, 3));
            case SILENCE -> applyEffect(target, "mining_fatigue", dur(params, 100), amp(params, 5));
            case BLIND -> applyEffect(target, "blindness", dur(params, 100), amp(params, 0));
            case ROOT -> applyEffect(target, "slowness", dur(params, 100), amp(params, 7));
            case SLOW -> applyEffect(target, "slowness", dur(params, 120), amp(params, 1));
            case WEAKEN -> applyEffect(target, "weakness", dur(params, 120), amp(params, 1));
            case CONFUSE -> applyEffect(target, "nausea", dur(params, 100), amp(params, 0));
            case DISARM -> disarm(level, caster, target, radius(params, 3.0));

            // ===== 强攻系 =====
            case BURST -> hurt(caster, target, value(params, 6.0) * 2.0 * damageMult,
                    crit(params, 0.1), dmgType(params), level);
            case AOE_BURST -> aoeDamage(level, caster, target,
                    value(params, 5.0) * 1.5 * damageMult, radius(params, 3.0), dmgType(params));
            case AOE_DAMAGE -> aoeDamage(level, caster, target,
                    value(params, 5.0) * damageMult, radius(params, 3.0), dmgType(params));
            case ARMOR_BREAK -> {
                applyEffect(target, "mining_fatigue", dur(params, 100), amp(params, 2));
                hurt(caster, target, value(params, 4.0) * 1.2 * damageMult, 0, dmgType(params), level);
            }
            case EXECUTE -> {
                double threshold = value(params, 5.0);
                if (target instanceof LivingEntity le && le.getHealth() <= threshold * 2.0) {
                    hurt(caster, target, value(params, 5.0) * 4.0 * damageMult, 0.2, dmgType(params), level);
                } else {
                    hurt(caster, target, value(params, 5.0) * damageMult, 0.1, dmgType(params), level);
                }
            }
            case STUN -> {
                applyEffect(target, "slowness", dur(params, 60), amp(params, 8));
                applyEffect(target, "mining_fatigue", dur(params, 60), amp(params, 8));
            }
            case CHARGE -> {
                Vec3 dir = target.position().subtract(caster.position()).normalize();
                teleportRelative(caster, dir, Math.min(distance(params, 6.0), 10.0));
                aoeDamage(level, caster, caster, value(params, 4.0) * damageMult, radius(params, 2.5), dmgType(params));
            }

            // ===== 敏攻系 =====
            case DASH -> teleportRelative(caster, dashDirection(caster, params), Math.min(distance(params, 5.0), 12.0));
            case COMBO -> {
                int count = Math.max(1, Math.min(intVal(params, "count", 3), 8));
                for (int i = 0; i < count; i++) {
                    hurt(caster, target, value(params, 2.5) * damageMult, 0.05, dmgType(params), level);
                }
            }
            case PHANTOM -> {
                applyEffect(caster, "invisibility", dur(params, 60), 0);
                particles(level, caster, ParticleTypes.END_ROD, 20);
            }
            case EVADE -> {
                clearHarmful(caster);
                applyEffect(caster, "invisibility", dur(params, 40), 0);
            }
            case BACKSTAB -> hurt(caster, target, value(params, 4.0) * 2.5 * damageMult, crit(params, 0.3), dmgType(params), level);
            case ACCELERATE -> applyEffect(caster, "speed", dur(params, 120), amp(params, 1));

            // ===== 辅助系 =====
            case BUFF_STATS -> buffStats(caster, target, params);
            case BUFF_ALL -> {
                applyEffect(caster, "strength", dur(params, 120), 0);
                for (ServerPlayer sp : level.getEntitiesOfClass(ServerPlayer.class,
                        box(caster, radius(params, 6.0)), e -> e.isAlive() && e != caster)) {
                    applyEffect(sp, "strength", dur(params, 120), 0);
                }
            }
            case SHIELD_TRANSFER -> applyEffect(target, "absorption", dur(params, 200), amp(params, 1));
            case CLEANSE -> clearHarmful(target);
            case HOT -> applyEffect(target, "regeneration", dur(params, 100), amp(params, 1));
            case REVIVE -> heal(target, value(params, 6.0) * 2.0 * damageMult);
            case CD_REDUCE -> { /* 冷却缩减：无状态标记，象征性净化+回复 */ }

            // ===== 防御系 =====
            case TAUNT -> taunt(level, caster, radius(params, 8.0));
            case REFLECT -> applyEffect(caster, "resistance", dur(params, 100), 1);
            case BARRIER -> applyEffect(caster, "resistance", dur(params, 100), 2);
            case IRON_BODY -> {
                applyEffect(caster, "resistance", dur(params, 100), 3);
                applyEffect(caster, "slowness", dur(params, 100), 0);
            }
            case HARDEN -> {
                applyEffect(caster, "resistance", dur(params, 100), 1);
                applyEffect(caster, "slowness", dur(params, 100), 0);
            }

            // ===== 生活系 =====
            case CROP_GROW -> growCrops(level, caster, radius(params, 4.0), growthStage(params, 1));
            case FOOD_BLESS -> {
                if (caster.getFoodData() != null) {
                    caster.getFoodData().eat(Math.max(1, (int) value(params, 4.0)), 1.0f);
                }
            }
            case HARVEST -> harvestCrops(level, caster, radius(params, 4.0));
            case BONEMEAL -> bonemealCrops(level, caster, radius(params, 4.0), count(params, 3));

            // ===== 通用 MC 机制 =====
            case SUMMON_ENTITY -> summon(level, caster, params);
            case PROJECTILE -> shootProjectile(level, caster, target, params);
            case EXPLOSION -> level.explode(caster, caster.getX(), caster.getY(), caster.getZ(),
                    (float) radius(params, 3.0), Level.ExplosionInteraction.NONE);
            case FIRE -> {
                int ticks = Math.max(20, dur(params, 100));
                if (radius(params, 0.0) > 0) {
                    for (LivingEntity e : entitiesAround(level, caster, radius(params, 3.0))) {
                        e.setRemainingFireTicks(ticks);
                    }
                } else {
                    target.setRemainingFireTicks(ticks);
                }
            }
            case SOUND -> playSound(level, caster, str(params, "sound", "minecraft:entity.generic.explode"));
            case PARTICLE -> spawnParticles(level, caster, params);
            case TELEPORT -> teleportTarget(caster, target, params);
            case PULL -> pull(level, caster, radius(params, 5.0), value(params, 0.6));
            case KNOCKBACK -> knockback(caster, target, value(params, 1.5));
            case POTION -> applyEffect(target, str(params, "effect", "minecraft:speed"),
                    dur(params, 100), amp(params, 0));
        }
    }

    // ===== 参数取值（集中数值平衡）=====

    private static double value(Map<String, Object> p, double def) {
        return num(p, Param.VALUE, def);
    }

    private static double radius(Map<String, Object> p, double def) {
        return num(p, Param.RADIUS, def);
    }

    private static int dur(Map<String, Object> p, int def) {
        return Math.max(1, (int) num(p, Param.DURATION, def));
    }

    private static int amp(Map<String, Object> p, int def) {
        return Math.max(0, (int) num(p, Param.AMPLIFIER, def));
    }

    private static double distance(Map<String, Object> p, double def) {
        return num(p, Param.DISTANCE, def);
    }

    private static int count(Map<String, Object> p, int def) {
        return Math.max(1, (int) num(p, Param.COUNT, def));
    }

    private static int growthStage(Map<String, Object> p, int def) {
        return Math.max(1, (int) num(p, Param.GROWTH_STAGE, def));
    }

    private static double crit(Map<String, Object> p, double def) {
        double c = num(p, Param.CRIT_CHANCE, def);
        return Math.max(0, Math.min(1.0, c));
    }

    private static String dmgType(Map<String, Object> p) {
        return str(p, Param.DAMAGE_TYPE.key(), "MELEE").toUpperCase();
    }

    private static double num(Map<String, Object> params, Param param, double def) {
        Object v = params.get(param.key());
        return v instanceof Number n ? n.doubleValue() : def;
    }

    private static int intVal(Map<String, Object> params, String key, int def) {
        Object v = params.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    private static String str(Map<String, Object> params, String key, String def) {
        Object v = params.get(key);
        return v instanceof String s ? s : def;
    }

    // ===== MC 机制实现 =====

    /** 伤害（含暴击） */
    private static void hurt(ServerPlayer caster, Entity target, double amount, double critChance, String type, ServerLevel level) {
        if (!(target instanceof LivingEntity le) || le.isDeadOrDying()) return;
        double finalAmount = amount;
        if (critChance > 0 && Math.random() < critChance) finalAmount *= 2.0;
        le.hurt(damageSource(caster, type, level), (float) finalAmount);
    }

    private static DamageSource damageSource(ServerPlayer caster, String type, ServerLevel level) {
        return switch (type == null ? "MELEE" : type) {
            case "FIRE" -> level.damageSources().onFire();
            case "EXPLOSION" -> level.damageSources().explosion(null, caster);
            case "MAGIC" -> level.damageSources().magic();
            default -> caster.damageSources().playerAttack(caster);
        };
    }

    /** 范围伤害（以目标或施法者为中心） */
    private static void aoeDamage(ServerLevel level, ServerPlayer caster, Entity center, double amount, double radius, String type) {
        Vec3 c = center.position();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
                AABB.ofSize(c, radius * 2, radius * 2, radius * 2), ent -> ent.isAlive() && ent != caster)) {
            e.hurt(damageSource(caster, type, level), (float) amount);
        }
        particles(level, c, ParticleTypes.CRIT, (int) (radius * 8));
    }

    /** 施加 MobEffect（按注册名，无此效果则忽略） */
    private static void applyEffect(Entity target, String effectId, int duration, int amplifier) {
        if (!(target instanceof LivingEntity le) || le.isDeadOrDying()) return;
        var holder = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.tryParse(effectId)).orElse(null);
        if (holder == null) return;
        le.addEffect(new MobEffectInstance(holder, duration, amplifier));
    }

    /** 清除负面效果 */
    private static void clearHarmful(Entity target) {
        if (!(target instanceof LivingEntity le)) return;
        for (MobEffectInstance inst : new ArrayList<>(le.getActiveEffects())) {
            if (!inst.getEffect().value().isBeneficial()) {
                le.removeEffect(inst.getEffect());
            }
        }
    }

    /** 治疗 */
    private static void heal(Entity target, double amount) {
        if (target instanceof LivingEntity le && le.isAlive()) {
            le.heal((float) amount);
        }
    }

    /** 缴械：目标主手物品掉落并清空 */
    private static void disarm(ServerLevel level, ServerPlayer caster, Entity target, double radius) {
        List<LivingEntity> targets = new ArrayList<>();
        if (target instanceof LivingEntity le && le != caster) targets.add(le);
        for (LivingEntity e : entitiesAround(level, caster, radius)) {
            if (e != caster && !targets.contains(e)) targets.add(e);
        }
        for (LivingEntity e : targets) {
            ItemStack hand = e.getMainHandItem();
            if (!hand.isEmpty()) {
                ItemEntity drop = e.spawnAtLocation(hand.copy());
                if (drop != null) drop.setPickUpDelay(30);
                e.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            }
        }
    }

    /** 嘲讽：范围内敌对生物强制攻击施法者 */
    private static void taunt(ServerLevel level, ServerPlayer caster, double radius) {
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box(caster, radius),
                m -> m.isAlive() && !m.equals(caster))) {
            mob.setTarget(caster);
        }
    }

    /** 作物催熟 */
    private static void growCrops(ServerLevel level, ServerPlayer caster, double radius, int stages) {
        for (BlockPos pos : area(caster, radius)) {
            BlockState state = level.getBlockState(pos);
            Block block = state.getBlock();
            if (block instanceof CropBlock crop && state.getValue(CropBlock.AGE) < crop.getMaxAge()) {
                int age = Math.min(crop.getMaxAge(), state.getValue(CropBlock.AGE) + stages);
                level.setBlock(pos, state.setValue(CropBlock.AGE, age), 3);
                particles(level, Vec3.atCenterOf(pos), ParticleTypes.HAPPY_VILLAGER, 3);
            }
        }
    }

    /** 收割成熟作物 */
    private static void harvestCrops(ServerLevel level, ServerPlayer caster, double radius) {
        for (BlockPos pos : area(caster, radius)) {
            BlockState state = level.getBlockState(pos);
            Block block = state.getBlock();
            if (block instanceof CropBlock crop && state.getValue(CropBlock.AGE) >= crop.getMaxAge()) {
                Block.dropResources(state, level, pos);
                level.setBlock(pos, state.setValue(CropBlock.AGE, 0), 3);
                particles(level, Vec3.atCenterOf(pos), ParticleTypes.HAPPY_VILLAGER, 5);
            }
        }
    }

    /** 骨粉催熟 */
    private static void bonemealCrops(ServerLevel level, ServerPlayer caster, double radius, int times) {
        for (BlockPos pos : area(caster, radius)) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof BonemealableBlock bonemealable
                    && bonemealable.isValidBonemealTarget(level, pos, state)) {
                for (int i = 0; i < times; i++) {
                    if (bonemealable.isBonemealSuccess(level, level.random, pos, state)) {
                        bonemealable.performBonemeal(level, level.random, pos, state);
                    }
                }
                level.levelEvent(2005, pos, 0);
            }
        }
    }

    /** 召唤实体 */
    private static void summon(ServerLevel level, ServerPlayer caster, Map<String, Object> params) {
        String id = str(params, Param.ENTITY_ID.key(), "minecraft:zombie");
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.tryParse(id));
        if (type == null) return;
        int count = count(params, 1);
        for (int i = 0; i < count; i++) {
            type.spawn(level, caster.blockPosition().offset(
                    (int) (Math.random() * 3) - 1, 1, (int) (Math.random() * 3) - 1),
                    MobSpawnType.MOB_SUMMONED);
        }
        particles(level, caster, ParticleTypes.SMOKE, 10);
    }

    /** 发射投射物 */
    private static void shootProjectile(ServerLevel level, ServerPlayer caster, Entity target, Map<String, Object> params) {
        String id = str(params, Param.PROJECTILE_TYPE.key(), "minecraft:snowball");
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.tryParse(id));
        if (type == null) return;
        Entity proj = type.spawn(level, caster.blockPosition().above(), MobSpawnType.MOB_SUMMONED);
        if (proj != null) {
            Vec3 dir = target.position().add(0, target.getBbHeight() / 2, 0)
                    .subtract(caster.getEyePosition()).normalize();
            proj.setDeltaMovement(dir.scale(Math.max(0.5, num(params, Param.SPEED, 1.5))));
        }
    }

    /** 音效 */
    private static void playSound(ServerLevel level, ServerPlayer caster, String soundId) {
        var sound = BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.tryParse(soundId));
        if (sound != null) {
            level.playSound(null, caster.blockPosition(), sound, SoundSource.PLAYERS, 1.0F, 1.0F);
        }
    }

    /** 粒子 */
    private static void spawnParticles(ServerLevel level, ServerPlayer caster, Map<String, Object> params) {
        String id = str(params, Param.PARTICLE.key(), "minecraft:flame");
        var type = BuiltInRegistries.PARTICLE_TYPE.get(ResourceLocation.tryParse(id));
        if (type == null) return;
        int count = count(params, 12);
        particles(level, caster, (net.minecraft.core.particles.ParticleOptions) type, count);
    }

    /** 传送（SELF→自身按朝向位移；TARGET→目标朝施法者位移） */
    private static void teleportTarget(ServerPlayer caster, Entity target, Map<String, Object> params) {
        double dist = Math.min(distance(params, 5.0), 12.0);
        String t = str(params, Param.TARGET.key(), "TARGET");
        if ("SELF".equalsIgnoreCase(t)) {
            teleportRelative(caster, caster.getLookAngle(), dist);
        } else {
            Vec3 dir = caster.position().subtract(target.position()).normalize();
            if (target instanceof ServerPlayer sp) {
                sp.teleportTo(sp.getX() + dir.x * dist, sp.getY(), sp.getZ() + dir.z * dist);
            } else {
                target.moveTo(target.getX() + dir.x * dist, target.getY(), target.getZ() + dir.z * dist);
            }
        }
    }

    /** 牵引：将周围实体拉向施法者 */
    private static void pull(ServerLevel level, ServerPlayer caster, double radius, double power) {
        for (LivingEntity e : entitiesAround(level, caster, radius)) {
            Vec3 to = caster.position().add(0, 0.5, 0).subtract(e.position()).normalize();
            e.setDeltaMovement(e.getDeltaMovement().add(to.scale(power)));
            e.hasImpulse = true;
        }
        particles(level, caster, ParticleTypes.WITCH, 15);
    }

    /** 击退 */
    private static void knockback(ServerPlayer caster, Entity target, double power) {
        if (target instanceof LivingEntity le) {
            le.knockback((float) power, le.getX() - caster.getX(), le.getZ() - caster.getZ());
        }
    }

    /** 属性增益：ATTACK_DAMAGE→力量 / MOVEMENT_SPEED→迅捷 / 其他→生命恢复 */
    private static void buffStats(ServerPlayer caster, Entity target, Map<String, Object> params) {
        String attribute = str(params, Param.ATTRIBUTE.key(), "ATTACK_DAMAGE").toUpperCase();
        int duration = dur(params, 120);
        switch (attribute) {
            case "MOVEMENT_SPEED" -> applyEffect(target, "speed", duration, 1);
            case "MAX_HEALTH" -> {
                applyEffect(target, "health_boost", duration, 1);
                applyEffect(target, "regeneration", duration, 0);
            }
            default -> applyEffect(target, "strength", duration, 1);
        }
    }

    // ===== 通用辅助 =====

    private static void teleportRelative(ServerPlayer player, Vec3 dir, double dist) {
        player.teleportTo(player.getX() + dir.x * dist, player.getY(), player.getZ() + dir.z * dist);
    }

    private static Vec3 dashDirection(ServerPlayer caster, Map<String, Object> params) {
        String dir = str(params, Param.DIRECTION.key(), "FORWARD").toUpperCase();
        return switch (dir) {
            case "BACKWARD" -> caster.getLookAngle().scale(-1);
            case "UP" -> new Vec3(0, 1, 0);
            case "TOWARD_TARGET" -> {
                Entity t = aimedEntity(caster);
                yield t != null
                        ? t.position().subtract(caster.position()).normalize()
                        : caster.getLookAngle();
            }
            default -> caster.getLookAngle();
        };
    }

    private static void particles(ServerLevel level, ServerPlayer caster, net.minecraft.core.particles.ParticleOptions type, int count) {
        particles(level, caster.position(), type, count);
    }

    private static void particles(ServerLevel level, Vec3 pos, net.minecraft.core.particles.ParticleOptions type, int count) {
        level.sendParticles(type, pos.x, pos.y + 1.0, pos.z, count, 0.3, 0.3, 0.3, 0.1);
    }

    private static AABB box(Entity center, double radius) {
        return AABB.ofSize(center.position(), radius * 2, radius * 2, radius * 2);
    }

    private static List<LivingEntity> entitiesAround(ServerLevel level, Entity center, double radius) {
        return level.getEntitiesOfClass(LivingEntity.class, box(center, radius),
                e -> e.isAlive() && e != center);
    }

    private static Iterable<BlockPos> area(Entity center, double radius) {
        int r = (int) Math.ceil(radius);
        return BlockPos.betweenClosed(
                center.blockPosition().offset(-r, -1, -r),
                center.blockPosition().offset(r, 2, r));
    }
}
