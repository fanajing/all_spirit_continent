package org.fanajing.all_spirit_continent.util;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.init.ModAttachments;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 灵魂威压 v1：把「数值碾压」从隐性变成遭遇瞬间就能感知的信号。
 * <p>
 * 数值碾压本身是隐性的 —— 玩家要打到一半、发现掉血飞快才知道打不过，这时往往已经死了。
 * 威压让年限差距在**看见魂兽的那一刻**就被感知：屏幕提示 + 移速下降 + 魂力流失，
 * 档位越高越窒息。同时它天然堵住「低等级玩家进高级维度抱大腿刷经验」的漏洞：
 * 进地狱的低级玩家会被全程威压，效率远低于正常推进。
 * <p>
 * 判据：
 * <pre>
 *   ratio = 魂兽年限 / 玩家战力年限 W
 *   ratio &lt; 2   → 0 无
 *   2 ~ 10      → 1 压迫（移速 −15%、魂力 −0.5%/s）
 *   10 ~ 100    → 2 威压（移速 −40%、攻击 −25%、魂力 −2%/s）
 *   ≥ 100       → 3 恐惧（移速 −70%、无法攻击/施放魂技、魂力 −5%/s、黑暗）
 * </pre>
 * <p>
 * 阈值取 2 / 10 / 100 的 rationale：年限档位本身是 10 倍进制 —— 「高一档」= ratio 10 起，
 * 「高两档」= ratio 100 起。机制与数值体系天然对齐，不需要额外调参。
 * 完整推导见 `战力数值设计.md` §6。
 */
public final class SoulPressure {

    // ===== 扫描参数 =====
    /** 威压作用半径（格）。32 是魂兽体型放大后仍能被玩家看见的距离 */
    public static final double RADIUS = 32.0;
    /** 扫描间隔（tick）。每 10 tick 扫一次，摊薄实体遍历开销 */
    public static final int SCAN_INTERVAL = 10;
    /** 魂力流失结算间隔（tick，20 = 每秒） */
    public static final int DRAIN_INTERVAL = 20;

    // ===== 档位效果表（下标 = 档位 0/1/2/3） =====
    /** 移速惩罚（ADD_MULTIPLIED_TOTAL，负值） */
    public static final double[] SPEED_PENALTY = {0.0, -0.15, -0.40, -0.70};
    /** 攻击力惩罚（ADD_MULTIPLIED_TOTAL，负值）。恐惧档不靠降攻，直接禁止攻击 */
    public static final double[] ATTACK_PENALTY = {0.0, 0.0, -0.25, 0.0};
    /** 每秒流失的魂力（占上限比例） */
    public static final float[] SOUL_POWER_DRAIN_PCT = {0f, 0.005f, 0.02f, 0.05f};
    /** 档位名（用于提示与调试） */
    public static final String[] TIER_NAME = {"无", "压迫", "威压", "恐惧"};
    /** 恐惧档 = 3，此档位起禁止攻击与施法 */
    public static final int FEAR_TIER = 3;

    /** 移速修饰 ID（transient：不持久化，重登由 tick 重新施加） */
    public static final ResourceLocation SPEED_MOD_ID =
            ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "soul_pressure_speed");
    /** 攻击力修饰 ID（transient） */
    public static final ResourceLocation ATTACK_MOD_ID =
            ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "soul_pressure_attack");

    /** 当前档位缓存（供攻击/施法拦截实时查询，避免每次事件都扫描实体） */
    private static final Map<UUID, Integer> CURRENT = new ConcurrentHashMap<>();
    /** 上次提示过的档位（只在档位**升高**时提示，避免边界横跳刷屏） */
    private static final Map<UUID, Integer> NOTIFIED = new ConcurrentHashMap<>();

    private SoulPressure() {}

    // ===== 判据 =====

    /**
     * 威压档位：0 无 / 1 压迫 / 2 威压 / 3 恐惧。
     *
     * @param beastAge 魂兽年限（年）
     * @param W        玩家战力年限（{@link SoulGrowth#effectiveYears}）
     */
    public static int pressureTier(int beastAge, double W) {
        if (beastAge <= 0) return 0;
        double r = beastAge / Math.max(W, 1.0);
        if (r < 2.0) return 0;
        if (r < 10.0) return 1;
        if (r < 100.0) return 2;
        return 3;
    }

    /** 玩家当前所处的最高威压档位（缓存值，随扫描更新） */
    public static int tierOf(Player player) {
        return CURRENT.getOrDefault(player.getUUID(), 0);
    }

    /** 是否处于恐惧档（无法攻击 / 无法施放魂技） */
    public static boolean isFearbound(Player player) {
        return tierOf(player) >= FEAR_TIER;
    }

    /** 登出/死亡时清理缓存，避免 Map 无限增长 */
    public static void clear(Player player) {
        CURRENT.remove(player.getUUID());
        NOTIFIED.remove(player.getUUID());
        removeModifiers(player);
    }

    // ===== 每 tick =====

    /**
     * 每 tick 调用（仅服务端）：定期扫描附近魂兽 → 施加/移除 debuff → 魂力流失 → 升档提示。
     * 创造/旁观玩家免疫（与 {@link SoulFlight} 一致，避免管理者被锁死）。
     */
    public static void tick(Player player) {
        if (player.level().isClientSide) return;
        if (player.isCreative() || player.isSpectator()) {
            if (CURRENT.remove(player.getUUID()) != null) removeModifiers(player);
            return;
        }

        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());

        // 扫描：每 SCAN_INTERVAL tick 一次，取半径内 ratio 最高的魂兽
        if (player.tickCount % SCAN_INTERVAL == 0) {
            int prev = tierOf(player);
            int tier = scanTier(player, data);
            CURRENT.put(player.getUUID(), tier);
            if (tier != prev) {
                applyModifiers(player, tier);
                if (tier > prev) notify(player, tier);
            }
            // 恐惧档持续施加黑暗（视野扭曲）；离开半径后自然过期
            if (tier >= FEAR_TIER && player.level() instanceof ServerLevel sl) {
                player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 60, 0, false, false, true));
                sl.sendParticles(ParticleTypes.SMOKE, player.getX(), player.getY() + 1.0, player.getZ(),
                        4, 0.35, 0.6, 0.35, 0.01);
            }
        }

        // 魂力流失：每秒按上限百分比扣，扣到 0 为止（不致死，但持续空池）
        int tier = tierOf(player);
        if (tier > 0 && player.tickCount % DRAIN_INTERVAL == 0) {
            float drain = SoulGrowth.maxSoulPower(data) * SOUL_POWER_DRAIN_PCT[tier];
            if (drain > 0 && data.getSoulPower() > 0) {
                data.consumeSoulPower(drain);
                All_spirit_continent.syncLevelDataToClient(player);
            }
        }
    }

    /** 扫描半径内所有魂兽，返回最高威压档位 */
    private static int scanTier(Player player, PlayerLevelData data) {
        double W = SoulGrowth.effectiveYears(data);
        AABB box = player.getBoundingBox().inflate(RADIUS);
        List<Mob> mobs = player.level().getEntitiesOfClass(Mob.class, box, e -> e.isAlive());
        int tier = 0;
        for (Mob mob : mobs) {
            if (!mob.hasData(ModAttachments.SOUL_BEAST.get())) continue;
            int age = mob.getData(ModAttachments.SOUL_BEAST.get()).age();
            if (age <= 0) continue;
            int t = pressureTier(age, W);
            if (t > tier) tier = t;
            if (tier == 3) break;   // 已是最高档，无需继续
        }
        return tier;
    }

    // ===== debuff 施加 =====

    /** 幂等施加：先移除旧修饰，再按新档位加（transient，不写存档） */
    private static void applyModifiers(Player player, int tier) {
        removeModifiers(player);
        if (tier <= 0) return;

        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null && SPEED_PENALTY[tier] != 0) {
            speed.addTransientModifier(new AttributeModifier(SPEED_MOD_ID,
                    SPEED_PENALTY[tier], AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }

        AttributeInstance atk = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (atk != null && ATTACK_PENALTY[tier] != 0) {
            atk.addTransientModifier(new AttributeModifier(ATTACK_MOD_ID,
                    ATTACK_PENALTY[tier], AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private static void removeModifiers(Player player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(SPEED_MOD_ID);
        AttributeInstance atk = player.getAttribute(Attributes.ATTACK_DAMAGE);
        if (atk != null) atk.removeModifier(ATTACK_MOD_ID);
    }

    // ===== 反馈通道 =====

    /** 升档提示：文字 + 格式色 + 音效由客户端播放（此处仅文字，避免服务端音效 API 差异） */
    private static void notify(Player player, int tier) {
        if (NOTIFIED.getOrDefault(player.getUUID(), 0) >= tier) return;
        NOTIFIED.put(player.getUUID(), tier);
        ChatFormatting style = switch (tier) {
            case 1 -> ChatFormatting.YELLOW;
            case 2 -> ChatFormatting.GOLD;
            default -> ChatFormatting.DARK_RED;
        };
        String key = "msg.all_spirit_continent.pressure_" + tier;
        player.sendSystemMessage(Component.translatable(key).withStyle(style));
    }
}
