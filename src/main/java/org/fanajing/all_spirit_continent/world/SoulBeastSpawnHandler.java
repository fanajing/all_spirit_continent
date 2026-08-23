package org.fanajing.all_spirit_continent.world;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import com.mojang.logging.LogUtils;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.config.SoulRingConfig;
import org.fanajing.all_spirit_continent.entity.ModEntities;
import org.fanajing.all_spirit_continent.entity.SoulRingEntity;
import org.fanajing.all_spirit_continent.init.ModAttachments;
import org.fanajing.all_spirit_continent.network.SoulBeastSyncPayload;
import org.fanajing.all_spirit_continent.util.SoulBeastAge;
import org.fanajing.all_spirit_continent.util.SoulBeastData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 魂兽系统服务端处理器：
 *  - 魂兽化拦截（EntityJoinLevelEvent）：自定义配置 > Boss 百万年 > 默认概率分配（敌我分表 + 新手保护降级）
 *  - 年限生效（applySoulBeast）：体型/攻击力/生命值加成 + 头顶命名牌（名称+年限）+ 百万年防消失与计数
 *  - 客户端同步（PlayerEvent.StartTracking）：玩家开始跟踪魂兽时下发年限
 *  - 击杀掉落（LivingDamageEvent.Post + LivingDropsEvent）：仅玩家参与击杀才生成魂环实体（后续版本右键吸收）
 *  - 百万年规则（LivingDeathEvent + ServerTickEvent）：死亡进冷却，冷却结束后自动补刷
 */
@EventBusSubscriber(modid = All_spirit_continent.MODID, bus = EventBusSubscriber.Bus.GAME)
public class SoulBeastSpawnHandler {

    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    /** 出生点新手保护半径（格）：范围内不生成十万年及以上魂兽 */
    public static final double SPAWN_PROTECT_RANGE = 5000.0;

    /** 属性修饰 ID */
    private static final ResourceLocation SCALE_MOD_ID =
            ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "soul_beast_scale");
    private static final ResourceLocation ATTACK_MOD_ID =
            ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "soul_beast_attack");
    private static final ResourceLocation HEALTH_MOD_ID =
            ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "soul_beast_health");

    /** Boss 生物：固定百万年魂环（其他 mod 的 boss 可在自定义魂环配置中指定） */
    private static final Set<EntityType<?>> BOSS_TYPES = Set.of(
            EntityType.ENDER_DRAGON, EntityType.WITHER, EntityType.WARDEN);
    /** Boss 魂环年限：固定百万年档 */
    private static final int BOSS_AGE = 1_000_000;

    /** 玩家最近伤害魂兽的时间记录（魂兽 UUID → 玩家 UUID → 伤害游戏 tick），用于判定「参与击杀」 */
    private static final Map<UUID, Map<UUID, Long>> LAST_PLAYER_DAMAGE = new ConcurrentHashMap<>();
    /** 死亡前 30 秒内有玩家伤害过即视为参与击杀（掉落魂环，并计入魂环吸收参与者） */
    private static final long PLAYER_KILL_WINDOW_TICKS = 20L * 30;

    /** 百万年魂兽冷却结束后主动补刷的候选敌对生物（各维度通用） */
    private static final EntityType<?>[] MILLION_CANDIDATES = {
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER,
            EntityType.CREEPER, EntityType.WITCH, EntityType.ENDERMAN
    };

    // ===== 1. 实体加入世界：自然生成拦截分配年限 =====

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        // 已魂兽化（存档读档/维度切换等）：重新幂等应用体型/攻击力/生命值，
        // 让旧存档生物同步最新曲线（不重新随机年限、不重复计数百万年）
        if (mob.hasData(ModAttachments.SOUL_BEAST.get())) {
            int age = mob.getData(ModAttachments.SOUL_BEAST.get()).age();
            if (age > 0) {
                applyModifiers(mob, age);
                applyNameTag(mob, age);
            }
            return;
        }

        // ① 自定义魂环配置（整合包作者）优先级最高：指定生物（含其他 mod 生物）
        //    直接按配置年限魂兽化，无视生成方式过滤与新手保护（配置匹配即生效）
        SoulRingConfig.Override override = SoulRingConfig.find(mob.getType());
        if (override != null) {
            applySoulBeast(mob, override.roll(level.random), level);
            return;
        }

        // ② Boss（末影龙/凋零/监守者）：固定百万年魂环，无视生成方式与新手保护
        if (isBossType(mob.getType())) {
            applySoulBeast(mob, BOSS_AGE, level);
            return;
        }

        // ③ 默认逻辑：自然/区块/事件生成（含村庄铁傀儡）魂兽化；铁傀儡玩家建造（COMMAND）也纳入；
        // 刷怪笼/刷怪蛋/结构/命令生成的其他生物不魂兽化，防止刷怪塔刷魂环
        MobSpawnType type = mob.getSpawnType();
        boolean naturalSpawn = type == MobSpawnType.NATURAL
                || type == MobSpawnType.CHUNK_GENERATION
                || type == MobSpawnType.EVENT;
        boolean golemSpawn = mob.getType() == EntityType.IRON_GOLEM
                && type != MobSpawnType.SPAWN_EGG;
        if (!naturalSpawn && !golemSpawn) return;

        // 有攻击手段 → 敌对年限表（1 ~ 9999万年）；无攻击手段 → 被动年限表（1 ~ 10万年）
        boolean hostile = mob.getAttribute(Attributes.ATTACK_DAMAGE) != null;
        boolean nearSpawn = isNearSpawn(level, mob.blockPosition());

        int age = SoulBeastAge.rollAge(level.random, hostile, nearSpawn);

        // 百万年魂兽：全世界最多 2 只且冷却期内不生成，超限降级为十万年档
        if (SoulBeastAge.isMillion(age)) {
            SoulBeastWorldData data = SoulBeastWorldData.get(level);
            if (!data.canSpawnMillion(level.getGameTime())) {
                age = 100_000 + level.random.nextInt(900_000);
            }
        }

        applySoulBeast(mob, age, level);
    }

    /** 是否 Boss 生物（固定百万年魂环） */
    private static boolean isBossType(EntityType<?> type) {
        return BOSS_TYPES.contains(type);
    }

    /** 是否在世界出生点 5000 格（新手保护区）内 */
    private static boolean isNearSpawn(ServerLevel level, BlockPos pos) {
        BlockPos spawn = level.getSharedSpawnPos();
        double dx = pos.getX() - spawn.getX();
        double dz = pos.getZ() - spawn.getZ();
        return dx * dx + dz * dz < SPAWN_PROTECT_RANGE * SPAWN_PROTECT_RANGE;
    }

    /**
     * 将生物变成魂兽：写入年限附件、按年限放大体型（scale 属性）、
     * 加攻击力与生命值、百万年魂兽设置防消失并更新全局计数。
     */
    public static void applySoulBeast(Mob mob, int age, ServerLevel level) {
        mob.setData(ModAttachments.SOUL_BEAST.get(), new SoulBeastData(age));
        applyModifiers(mob, age);
        applyNameTag(mob, age);

        // 百万年魂兽：不自然消失 + 全局计数
        if (SoulBeastAge.isMillion(age)) {
            mob.setPersistenceRequired();
            SoulBeastWorldData.get(level).onMillionSpawned();
        }
    }

    /**
     * 头顶名称：魂兽名称 + 年限（如「僵尸 987年」），原版命名牌机制（customName + customNameVisible），
     * 由原版生物渲染器自动绘制在头顶，管线可靠；读档后重设保持幂等。
     * 名称用 EntityType.getDescription()（translatable 组件），客户端渲染时按客户端语言本地化；
     * 不能取 getName（会被 customName 覆盖导致叠加）。
     */
    private static void applyNameTag(Mob mob, int age) {
        Component name = mob.getType().getDescription().copy().append(" " + SoulBeastAge.format(age));
        mob.setCustomName(name);
        mob.setCustomNameVisible(true);
    }

    /**
     * 幂等应用年限加成（体型 + 攻击力 + 生命值）：先移除旧修饰符再加新的，
     * 避免重复叠加；存档读档的旧魂兽也会重新应用，保证曲线更新后老生物同步生效。
     */
    private static void applyModifiers(Mob mob, int age) {
        // 体型：年限越高越大（碰撞箱与模型同步缩放）
        AttributeInstance scaleAttr = mob.getAttribute(Attributes.SCALE);
        if (scaleAttr != null) {
            scaleAttr.removeModifier(SCALE_MOD_ID);
            scaleAttr.addPermanentModifier(new AttributeModifier(SCALE_MOD_ID,
                    SoulBeastAge.scaleOf(age) - 1.0, AttributeModifier.Operation.ADD_VALUE));
        }

        // 攻击力：年限越高越强（乘算倍数，基于生物原本攻击力）
        AttributeInstance attackAttr = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attackAttr != null) {
            attackAttr.removeModifier(ATTACK_MOD_ID);
            attackAttr.addPermanentModifier(new AttributeModifier(ATTACK_MOD_ID,
                    SoulBeastAge.statMultiplierOf(age) - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }

        // 生命值：年限越高血越厚（乘算倍数，基于生物原本生命值）；应用后回满血
        AttributeInstance healthAttr = mob.getAttribute(Attributes.MAX_HEALTH);
        if (healthAttr != null) {
            healthAttr.removeModifier(HEALTH_MOD_ID);
            healthAttr.addPermanentModifier(new AttributeModifier(HEALTH_MOD_ID,
                    SoulBeastAge.statMultiplierOf(age) - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            mob.setHealth(mob.getMaxHealth());
        }

        LOGGER.info("[SoulBeast-apply] {} age={} mult={} attack={} maxHealth={}",
                mob.getType(), age, String.format("%.1f", SoulBeastAge.statMultiplierOf(age)),
                attackAttr == null ? "N/A" : String.format("%.1f", attackAttr.getValue()),
                String.format("%.1f", mob.getMaxHealth()));
    }

    // ===== 2. 客户端同步：玩家开始跟踪魂兽时下发年限 =====

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Entity target = event.getTarget();
        if (!(target instanceof LivingEntity living)) return;
        if (!living.hasData(ModAttachments.SOUL_BEAST.get())) return;

        SoulBeastData data = living.getData(ModAttachments.SOUL_BEAST.get());
        PacketDistributor.sendToPlayer(player,
                new SoulBeastSyncPayload(target.getId(), target.getUUID(), data.age()));
    }

    // ===== 3. 击杀掉落：仅玩家参与击杀才生成魂环实体（不可拾取，后续版本右键吸收） =====

    /** 记录每个玩家伤害魂兽的时间（服务端），死亡时筛选「参与击杀」的玩家列表 */
    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Post event) {
        if (event.getEntity().level().isClientSide()) return;
        LivingEntity entity = event.getEntity();
        if (!entity.hasData(ModAttachments.SOUL_BEAST.get())) return;
        if (event.getSource().getEntity() instanceof Player player) {
            LAST_PLAYER_DAMAGE
                    .computeIfAbsent(entity.getUUID(), k -> new ConcurrentHashMap<>())
                    .put(player.getUUID(), entity.level().getGameTime());
        }
    }

    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        LivingEntity entity = event.getEntity();
        if (!entity.hasData(ModAttachments.SOUL_BEAST.get())) return;

        // 玩家未参与击杀（如铁傀儡/环境/其他生物击杀）不掉落魂环；
        // 筛选死亡前 30 秒内伤害过魂兽的所有玩家作为击杀参与者（多人参与可平分吸收经验）
        Map<UUID, Long> damagers = LAST_PLAYER_DAMAGE.remove(entity.getUUID());
        if (damagers == null || damagers.isEmpty()) {
            LOGGER.info("[SoulRing-drop] {} died but no player damagers recorded — no ring", entity.getType());
            return;
        }
        long now = entity.level().getGameTime();
        List<UUID> participants = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : damagers.entrySet()) {
            if (now - entry.getValue() <= PLAYER_KILL_WINDOW_TICKS) {
                participants.add(entry.getKey());
            }
        }
        if (participants.isEmpty()) {
            LOGGER.info("[SoulRing-drop] {} damagers all outside {} ticks window — no ring", entity.getType(), PLAYER_KILL_WINDOW_TICKS);
            return;
        }

        int age = entity.getData(ModAttachments.SOUL_BEAST.get()).age();
        if (age <= 0) return;

        // 原地生成魂环实体：年限同步到客户端（渲染器按档位选颜色贴图），参与者列表写入魂环；
        // 怪物类型本地化键写入魂环（吸收确认窗口显示怪物种类）
        SoulRingEntity ring = new SoulRingEntity(ModEntities.SOUL_RING_ENTITY.get(), entity.level());
        ring.setRingAge(age);
        ring.setSourceType(entity.getType().getDescriptionId());
        ring.setParticipants(participants);
        ring.setPos(entity.getX(), entity.getY() + 0.1, entity.getZ());
        entity.level().addFreshEntity(ring);
        LOGGER.info("[SoulRing-drop] spawned ring at ({}, {}, {}) age={} participants={}",
                ring.getX(), ring.getY(), ring.getZ(), age, participants.size());
    }

    // ===== 4. 百万年魂兽死亡：进入冷却 =====

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;
        LivingEntity entity = event.getEntity();
        if (!entity.hasData(ModAttachments.SOUL_BEAST.get())) return;

        int age = entity.getData(ModAttachments.SOUL_BEAST.get()).age();
        if (SoulBeastAge.isMillion(age)) {
            SoulBeastWorldData.get(level).onMillionDeath(level.getGameTime());
        }
    }

    // ===== 5. 冷却结束后自动补刷百万年魂兽（主世界，玩家附近） =====

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();
        // 每 5 秒（100 tick）检查一次
        if (level.getGameTime() % 100 != 0) return;

        SoulBeastWorldData data = SoulBeastWorldData.get(level);
        if (!data.canSpawnMillion(level.getGameTime())) return;
        trySpawnMillionBeast(level);
    }

    /** 在随机在线玩家附近（48~96 格）尝试补刷一只百万年魂兽 */
    private static void trySpawnMillionBeast(ServerLevel level) {
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        ServerPlayer player = players.get(level.random.nextInt(players.size()));
        // 新手保护：玩家仍处于出生点 5000 格内时不刷（换其他在线玩家碰运气）
        if (isNearSpawn(level, player.blockPosition())) return;

        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            double dist = 48 + level.random.nextDouble() * 48;
            int x = (int) (player.getX() + Math.cos(angle) * dist);
            int z = (int) (player.getZ() + Math.sin(angle) * dist);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);

            EntityType<?> type = MILLION_CANDIDATES[level.random.nextInt(MILLION_CANDIDATES.length)];
            if (!SpawnPlacements.isSpawnPositionOk(type, level, pos)) {
                continue;
            }

            Entity spawned = type.create(level);
            if (!(spawned instanceof Mob mob)) return;

            mob.moveTo(x + 0.5, y, z + 0.5, level.random.nextFloat() * 360F, 0F);
            level.addFreshEntity(mob);

            // 补刷的百万年魂兽年限在百万 ~ 9999万年之间随机
            int age = SoulBeastAge.MILLION_AGE
                    + level.random.nextInt(SoulBeastAge.MAX_AGE - SoulBeastAge.MILLION_AGE + 1);
            applySoulBeast(mob, age, level);
            return;
        }
    }
}
