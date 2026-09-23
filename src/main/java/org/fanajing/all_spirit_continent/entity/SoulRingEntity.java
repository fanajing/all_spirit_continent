package org.fanajing.all_spirit_continent.entity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.cloud.CloudSyncService;
import org.fanajing.all_spirit_continent.data.PlayerSkillDataStore;
import org.fanajing.all_spirit_continent.init.ModAttachments;
import org.fanajing.all_spirit_continent.network.OpenRingAbsorbScreenPayload;
import org.fanajing.all_spirit_continent.network.PlayRingAbsorbCinematicPayload;
import org.fanajing.all_spirit_continent.network.RingAbsorbCancelPayload;
import org.fanajing.all_spirit_continent.network.RingAbsorbSkillReadyPayload;
import org.fanajing.all_spirit_continent.skill.PoolSelector;
import org.fanajing.all_spirit_continent.skill.SkillData;
import org.fanajing.all_spirit_continent.skill.SkillEntry;
import org.fanajing.all_spirit_continent.skill.engine.SignatureMechanism;
import org.fanajing.all_spirit_continent.skill.engine.SkillGenerator;
import org.fanajing.all_spirit_continent.skill.engine.SkillValidator;
import org.fanajing.all_spirit_continent.util.PlayerLevelData;
import org.fanajing.all_spirit_continent.util.PlayerSkillConfig;
import org.fanajing.all_spirit_continent.util.SoulBeastAge;
import org.fanajing.all_spirit_continent.util.SoulExp;
import org.fanajing.all_spirit_continent.util.SoulGrowth;
import org.fanajing.all_spirit_continent.util.SoulRingLayout;
import org.fanajing.all_spirit_continent.util.TitleSystem;
import org.fanajing.all_spirit_continent.util.UploaderAnonymizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 魂环实体：击杀魂兽后原地生成的环状实体（不是掉落物）。
 *  - 年限通过 synchedData 同步到客户端，渲染器按档位选颜色贴图
 *  - 环上方年限文字用原版命名牌机制显示（customName），渲染器复用 renderNameTag
 *  - 不可被拾取
 *  - 经验吸收：击杀参与者蹲着（潜行）右键魂环 → 魂环逸散为经验值（瓶颈封顶时经验作废，不直接获得新魂环）；
 *    多人参与击杀则平分（每位参与者各吸收一份 基础经验×惩罚÷人数），全部吸收完后魂环消散
 *  - 瓶颈获取魂环：节点等级（10/20/.../90）未获得对应魂环时，不蹲右键进入「吸收流程」：
 *    V6.2 起先判定吸收（当前恒为 100%，占位，后续替换为真实算法）→ 播放「吸收魂环动画」
 *    （升空 → 飞向玩家头顶 → 武魂显形 → 盘旋等待魂技就绪 → 落位），动画期间并行感应魂技；
 *    客户端在动画落位瞬间上报 → 服务端此刻才真正把魂环加给玩家（不升级、不结算经验），
 *    魂环实体消散，随后弹出「绑定魂技」窗口（窗口只决定魂技是否绑定；
 *    点「拒绝」则回滚：移除刚吸收的魂环并播放碎裂音效，见 {@link #rejectAbsorbedRing}）
 *  - 持久化：存档读档后魂环实体与参与者/已吸收记录保留
 */
public class SoulRingEntity extends Entity {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** 魂环年限（年），同步数据 */
    private static final EntityDataAccessor<Integer> DATA_AGE =
            SynchedEntityData.defineId(SoulRingEntity.class, EntityDataSerializers.INT);

    /** 掉落来源怪物类型的本地化键（如 entity.minecraft.slime），同步数据；空字符串 = 未知 */
    private static final EntityDataAccessor<String> DATA_SOURCE_TYPE =
            SynchedEntityData.defineId(SoulRingEntity.class, EntityDataSerializers.STRING);

    /** 魂技组合键的血量档位（如 "100_999"，由魂兽年限档位推导），同步数据；空字符串 = 未知 */
    private static final EntityDataAccessor<String> DATA_HEALTH_SEGMENT =
            SynchedEntityData.defineId(SoulRingEntity.class, EntityDataSerializers.STRING);

    /** 掉落来源魂兽被击杀时的最大生命值（AI 数值规划提示用），同步数据；0 = 未知 */
    private static final EntityDataAccessor<Float> DATA_SOURCE_MAX_HEALTH =
            SynchedEntityData.defineId(SoulRingEntity.class, EntityDataSerializers.FLOAT);

    /** 击杀参与者 UUID 列表（服务端权威，死亡时由魂兽系统写入，NBT 持久化） */
    private List<UUID> participants = new ArrayList<>();
    /** 已吸收经验的玩家 UUID 列表（NBT 持久化，全部参与者吸收完后魂环消散） */
    private List<UUID> absorbed = new ArrayList<>();

    /** 魂技感应会话：ringId + ":" + playerUuid → 感应到的技能（GUI 确认时取用，环消散自然失效） */
    private static final Map<String, SkillEntry> SENSING_SESSIONS = new ConcurrentHashMap<>();
    /** V6.2 会话来源标记：ringId + ":" + playerUuid → 该感应技能是否 AI 推演生成（区分展示来源） */
    private static final Map<String, Boolean> SESSION_AI = new ConcurrentHashMap<>();
    /** 正在 AI 生成中的玩家（单玩家并发限制 1，防刷接口） */
    private static final Set<UUID> GENERATING = new ConcurrentHashMap<>().newKeySet();
    /** V6.2 吸收动画流程锁：玩家 UUID → 进行中的吸收流程（含超时时间）。
     *  右键判定通过 → 播放吸收动画 → 落位真正加环 期间，阻止同玩家对其它魂环重复触发；
     *  落位吸收完成 / 魂技感应失败取消 / 魂环消散 / 超时 / 玩家断开 时清理。 */
    private static final Map<UUID, Absorbing> ABSORBING = new ConcurrentHashMap<>();

    /**
     * 吸收流程锁状态：目标魂环实体 id + 流程超时时间（毫秒，默认 30 分钟）。
     * 超时后该锁失效，玩家可重新右键开始新的吸收流程（防御客户端中途断开/卡死）。
     */
    private record Absorbing(int ringEntityId, long expiryMillis) {
        static Absorbing of(int ringEntityId) {
            return new Absorbing(ringEntityId, System.currentTimeMillis() + ABSORB_TIMEOUT_MS);
        }
    }

    /** 吸收流程整体超时上限（毫秒）：动画等待魂技就绪无上限，但流程不能无限挂起 */
    private static final long ABSORB_TIMEOUT_MS = 30L * 60 * 1000;

    public SoulRingEntity(EntityType<?> type, Level level) {
        super(type, level);
        LOGGER.info("[SoulRing] constructed side={}", level.isClientSide ? "client" : "server");
    }

    @Override
    public void onRemovedFromLevel() {
        // V6.2：魂环消散/卸载时清理指向本环的吸收流程锁（环没了，流程自然作废）
        clearAbsorbingFor(getId());
        super.onRemovedFromLevel();
        LOGGER.info("[SoulRing] removed reason={}", getRemovalReason());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_AGE, 0);
        builder.define(DATA_SOURCE_TYPE, "");
        builder.define(DATA_HEALTH_SEGMENT, "");
        builder.define(DATA_SOURCE_MAX_HEALTH, 0F);
    }

    // ===== 魂技感应会话 =====

    /** 取玩家对该魂环的感应技能；无会话返回 null */
    public static SkillEntry getSession(int ringEntityId, UUID playerUuid) {
        return SENSING_SESSIONS.get(sessionKey(ringEntityId, playerUuid));
    }

    /** 记录玩家对该魂环的感应技能（默认按非 AI 推演处理） */
    public static void putSession(int ringEntityId, UUID playerUuid, SkillEntry entry) {
        putSession(ringEntityId, playerUuid, entry, false);
    }

    /** 记录玩家对该魂环的感应技能，并标记来源是否 AI 推演（区分窗口展示来源） */
    public static void putSession(int ringEntityId, UUID playerUuid, SkillEntry entry, boolean aiGenerated) {
        String key = sessionKey(ringEntityId, playerUuid);
        if (entry == null) {
            SESSION_AI.remove(key);
            return;
        }
        SENSING_SESSIONS.put(key, entry);
        if (aiGenerated) {
            SESSION_AI.put(key, Boolean.TRUE);
        } else {
            SESSION_AI.remove(key);
        }
    }

    /** 该会话是否由 AI 推演生成（三池抽取命中为 false） */
    public static boolean isAiSession(int ringEntityId, UUID playerUuid) {
        return Boolean.TRUE.equals(SESSION_AI.get(sessionKey(ringEntityId, playerUuid)));
    }

    /** 移除玩家对该魂环的感应技能（吸收/拒绝后调用） */
    public static void removeSession(int ringEntityId, UUID playerUuid) {
        String key = sessionKey(ringEntityId, playerUuid);
        SENSING_SESSIONS.remove(key);
        SESSION_AI.remove(key);
    }

    private static String sessionKey(int ringEntityId, UUID playerUuid) {
        return ringEntityId + ":" + playerUuid;
    }

    /** 由魂兽年限推导魂技组合键血量档位（保证云端键稳定） */
    public static String healthSegmentOf(int age) {
        return switch (SoulBeastAge.tierOf(age)) {
            case 0 -> "1_99";
            case 1 -> "100_999";
            case 2 -> "1000_9999";
            case 3 -> "10000_99999";
            case 4 -> "100000_999999";
            default -> "1000000_99990000";
        };
    }

    /** 魂技组合键血量档位；未写入返回空字符串 */
    public String getHealthSegment() {
        return entityData.get(DATA_HEALTH_SEGMENT);
    }

    /** 写入血量档位（魂兽死亡生成魂环时由年限档位推导，服务端） */
    public void setHealthSegment(String segment) {
        entityData.set(DATA_HEALTH_SEGMENT, segment == null ? "" : segment);
    }

    /** 魂环可被准星瞄准（默认 Entity.isPickable 返回 false，右键交互将永远无法命中） */
    @Override
    public boolean isPickable() {
        return true;
    }

    /**
     * 扩大右键瞄准半径（只影响准星命中判定 inflate，不改碰撞箱/物理/渲染）：
     * 默认碰撞箱 0.5×0.1 太扁，瞄准手感差。
     */
    @Override
    public float getPickRadius() {
        return 0.6F;
    }

    /** 魂环年限 */
    public int getRingAge() {
        return entityData.get(DATA_AGE);
    }

    public void setRingAge(int age) {
        entityData.set(DATA_AGE, age);
        // 头顶年限文字：命名牌机制（原版渲染管线，可靠显示）
        setCustomName(Component.literal(SoulBeastAge.format(age)));
        setCustomNameVisible(true);
    }

    /** 掉落来源怪物类型的本地化键（如 entity.minecraft.slime），空字符串 = 未知 */
    public String getSourceType() {
        return entityData.get(DATA_SOURCE_TYPE);
    }

    /** 写入掉落来源怪物类型本地化键（魂兽死亡生成魂环时调用，服务端） */
    public void setSourceType(String descriptionId) {
        entityData.set(DATA_SOURCE_TYPE, descriptionId == null ? "" : descriptionId);
    }

    /** 掉落来源魂兽被击杀时的最大生命值；0 = 未知（AI 数值规划提示用） */
    public float getSourceMaxHealth() {
        return entityData.get(DATA_SOURCE_MAX_HEALTH);
    }

    /** 写入掉落来源魂兽被击杀时的最大生命值（魂兽死亡生成魂环时调用，服务端） */
    public void setSourceMaxHealth(float health) {
        entityData.set(DATA_SOURCE_MAX_HEALTH, health);
    }

    // ===== 击杀参与者与已吸收记录 =====

    /** 写入击杀参与者列表（魂兽死亡时调用，服务端） */
    public void setParticipants(List<UUID> uuids) {
        this.participants = new ArrayList<>(uuids);
    }

    public boolean isParticipant(UUID uuid) {
        return participants.contains(uuid);
    }

    public int getParticipantCount() {
        return participants.size();
    }

    /** 标记该玩家已吸收经验（重复吸收被拦截） */
    public void markAbsorbed(UUID uuid) {
        if (!absorbed.contains(uuid)) {
            absorbed.add(uuid);
        }
    }

    public boolean isAbsorbed(UUID uuid) {
        return absorbed.contains(uuid);
    }

    /** 全部参与者都已吸收完 → 魂环可以消散 */
    public boolean isFullyAbsorbed() {
        return !participants.isEmpty() && absorbed.containsAll(participants);
    }

    // ===== 右键交互：蹲着右键直接逸散为经验；不蹲右键进入「吸收魂环」动画流程（V6.2） =====

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        // 客户端：直接返回成功（摆臂），逻辑由服务端权威处理
        if (player.level().isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.FAIL;

        // 蹲着（潜行）右键：魂环直接逸散为经验（瓶颈封顶时经验作废，不直接获得新魂环；
        // 校验失败时提示）
        if (player.isShiftKeyDown()) {
            return performExpAbsorb(serverPlayer) ? InteractionResult.CONSUME : InteractionResult.FAIL;
        }

        // 不蹲右键：V6.2 吸收流程（校验 → 吸收判定 → 吸收动画 + 并行感应魂技 → 落位真正加环 → 绑定窗口）
        openSensing(serverPlayer);
        return InteractionResult.CONSUME;
    }

    // ===== V6.2 吸收流程：判定 → 吸收动画 + 并行感应魂技 → 落位加环 → 绑定窗口 =====

    /**
     * 吸收流程入口（服务端，不蹲右键点击魂环触发）：
     * 基础校验 → 瓶颈校验 → 吸收判定（V6.2 吸收概率机制占位，当前恒为 100%）→
     * 动画流程锁/组合键/黑名单校验 → 通知客户端播放「吸收魂环动画」→ 动画期间并行感应魂技
     * （本地三池抽取命中立即就绪；无候选异步 AI 生成，默认入品鉴池）。
     * <p>
     * 客户端动画「落位瞬间」上报 RingAbsorbLandPayload → 服务端在落位时真正加环并弹
     * 「绑定魂技」窗口（见 {@link #absorbAtCinematicLand}）。
     */
    public void openSensing(ServerPlayer serverPlayer) {
        if (level().isClientSide) return;
        if (!validateAbsorb(serverPlayer)) return;

        // ⑤ 瓶颈校验：只有达到瓶颈节点（10/20/.../90）才能吸收新的魂环与魂技，
        //    未达瓶颈仅可蹲着右键吸收经验，感应/吸收均被拒绝
        PlayerLevelData lvl = serverPlayer.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        if (!SoulExp.isBottleneck(lvl)) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.skill_need_bottleneck")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        // ⑥ 吸收判定：先判定「能否吸收这枚魂环」再播放吸收动画。
        //    V6.2 测试版恒为 100%（吸收概率算法后续实装：按年限/魂师承受力/成功率等设计）
        if (!passAbsorbChance(lvl, getRingAge())) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.absorb_chance_failed")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        // ⑦ 吸收动画流程锁：同玩家对其它魂环的流程进行中 → 拒绝（防双开动画与重复感应）；
        //    对同一魂环重开则放行（断线/死亡回来后重新开始）
        if (isAbsorbBlocked(serverPlayer.getUUID())) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.absorb_in_progress")
                            .withStyle(ChatFormatting.GRAY));
            return;
        }

        PlayerSkillConfig cfg = PlayerSkillDataStore.get(serverPlayer.serverLevel()).config(serverPlayer);
        String wuhun = cfg.wuhun() == null || cfg.wuhun().isEmpty() ? PlayerSkillConfig.DEFAULT_WUHUN : cfg.wuhun();
        String mobId = getSourceType().isEmpty() ? "unknown" : getSourceType();
        String segment = getHealthSegment().isEmpty() ? "0_0" : getHealthSegment();

        // 黑名单组合：直接拒绝并提示「已被大陆法则封印」
        if (CloudSyncService.isBlacklisted(wuhun, mobId, segment)) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.skill_blacklisted")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        // ⑧ 记录流程锁，通知客户端开始播放吸收魂环动画
        ABSORBING.put(serverPlayer.getUUID(), Absorbing.of(getId()));
        PacketDistributor.sendToPlayer(serverPlayer,
                new PlayRingAbsorbCinematicPayload(getId(), getRingAge()));

        // ⑨ 动画期间并行感应魂技（就绪后通知客户端落位，不再立即弹窗）
        senseForCinematic(serverPlayer, cfg, wuhun, mobId, segment);
    }

    /** 吸收判定（V6.2 吸收概率机制占位）：测试版恒定为 100%，后续替换为真实概率算法 */
    private static boolean passAbsorbChance(PlayerLevelData data, int ringAge) {
        return true;
    }

    /** 该玩家是否被「吸收动画流程锁」挡住：对其它魂环的流程进行中视为挡住；同一魂环放行（自愈） */
    private boolean isAbsorbBlocked(UUID playerUuid) {
        Absorbing state = ABSORBING.get(playerUuid);
        if (state == null) return false;
        if (System.currentTimeMillis() > state.expiryMillis()) {
            ABSORBING.remove(playerUuid); // 流程整体超时自愈（防御客户端中断/卡死）
            return false;
        }
        return state.ringEntityId() != getId();
    }

    /**
     * 动画期间感应魂技（不直接弹窗）：
     * 本地三池抽取命中（需通过引擎环位校验）→ 存会话并通知「技能就绪」；
     * 无候选 → 魂技生成引擎异步生成（单玩家并发 1），成功默认入品鉴池后同样存会话并通知「技能就绪」。
     */
    private void senseForCinematic(ServerPlayer serverPlayer, PlayerSkillConfig cfg,
                                   String wuhun, String mobId, String segment) {
        // 目标环位 = 当前环数 + 1（动画落位后才真正加环）
        PlayerLevelData lvl = serverPlayer.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        int targetSlot = Math.min(9, Math.max(1, lvl.getRingCount() + 1));

        // 本地缓存三池抽取（PENDING 已剔除；无签名机制的旧条目已在同步层删除；
        // 原语数/类别/数值不匹配目标环位的条目跳过，命中累加下载量）
        List<SkillEntry> poolCandidates = new ArrayList<>();
        for (SkillEntry e : CloudSyncService.findSkills(wuhun, mobId, segment)) {
            if (SkillValidator.validate(e.skill(), targetSlot, cfg) == SkillValidator.Verdict.PASS) {
                poolCandidates.add(e);
            }
        }
        SkillEntry picked = PoolSelector.pickWithDownload(poolCandidates);
        if (picked != null) {
            putSession(getId(), serverPlayer.getUUID(), picked, false);
            notifySkillReady(serverPlayer);
            return;
        }

        // 无候选 → 生成引擎异步生成（单玩家并发 1，避免接口滥用）
        if (!GENERATING.add(serverPlayer.getUUID())) {
            // 双保险：正常被流程锁挡住，此处仅清理并取消
            ABSORBING.remove(serverPlayer.getUUID());
            PacketDistributor.sendToPlayer(serverPlayer, new RingAbsorbCancelPayload(getId()));
            serverPlayer.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.skill_generating")
                            .withStyle(ChatFormatting.GRAY));
            return;
        }
        SkillGenerator.generate(new SkillGenerator.Request(
                        serverPlayer, cfg, wuhun, mobId, segment,
                        targetSlot, getRingAge(), getSourceMaxHealth()))
                .whenComplete((opt, ex) -> {
            GENERATING.remove(serverPlayer.getUUID());
            serverPlayer.server.execute(() -> {
                if (!serverPlayer.isAlive()) {
                    // 玩家掉线/死亡：清除流程锁，魂环仍留地面可重新吸收
                    ABSORBING.remove(serverPlayer.getUUID());
                    return;
                }
                if (ex != null || opt.isEmpty() || !opt.get().skill().isValid()) {
                    // 感应失败 → 取消动画（魂环不消散，留待玩家再次右键重试），清理流程锁
                    ABSORBING.remove(serverPlayer.getUUID());
                    serverPlayer.sendSystemMessage(
                            Component.translatable("msg.all_spirit_continent.skill_gen_failed")
                                    .withStyle(ChatFormatting.RED));
                    PacketDistributor.sendToPlayer(serverPlayer, new RingAbsorbCancelPayload(getId()));
                    return;
                }
                SkillEntry entry = SkillEntry.fromAi(opt.get().skill());
                // 上传时写入魂环实体实际年限（skill_data.ring_age 驱动伤害缩放，后台可识别年限分布）
                entry = entry.withSkill(entry.skill().withRingAge(getRingAge()));
                // 魂兽来自非原版 mod → 自动标记依赖，防止污染未安装该 mod 的玩家库
                entry = autoRequireMod(entry);
                // 上传者：游戏 ID 明文（玩家下载时展示来源）+ 匿名哈希（后台统计/封禁，改名也可追踪）
                entry = new SkillEntry(entry.skill(), entry.pool(), entry.stats(),
                        UploaderAnonymizer.hash(serverPlayer.getUUID()),
                        serverPlayer.getGameProfile().getName());
                CloudSyncService.queueSkill(entry); // 入本地库，共享阵营排队写回云端
                putSession(getId(), serverPlayer.getUUID(), entry, true);
                notifySkillReady(serverPlayer);
            });
        });
    }

    /** 通知客户端：魂技已感应就绪，动画可进入「头顶→脚下」落位阶段 */
    private void notifySkillReady(ServerPlayer serverPlayer) {
        PacketDistributor.sendToPlayer(serverPlayer, new RingAbsorbSkillReadyPayload(getId()));
    }

    /**
     * 根据魂兽来源自动标记依赖 mod：mob_id 形如 entity.twilightforest.naga，
     * 命名空间非 minecraft 即视为依赖该 mod，只有安装它的玩家才会下载/抽取到。
     */
    private static SkillEntry autoRequireMod(SkillEntry entry) {
        String mobId = entry.skill().mobId();
        if (mobId == null || !mobId.startsWith("entity.")) return entry;
        String ns = mobId.substring("entity.".length());
        int dot = ns.indexOf('.');
        ns = dot > 0 ? ns.substring(0, dot) : ns;
        if (ns.isEmpty() || "minecraft".equals(ns)) return entry;
        List<String> reqs = new ArrayList<>();
        if (entry.skill().requiresMods() != null) reqs.addAll(entry.skill().requiresMods());
        if (!reqs.contains(ns)) reqs.add(ns);
        return entry.withSkill(entry.skill().withRequiresMods(reqs));
    }

    /**
     * 绑定魂技到已吸收的魂环（V6.2 吸收窗口「吸收」按钮经 ConfirmRingAbsorbPayload 触发）：
     * 魂环在动画落位时已真正加给玩家，此方法只把窗口中的魂技绑定到该环位，不再加环/消散。
     * 窗口点「拒绝」则是回滚移除刚吸收的魂环（见 {@link #rejectAbsorbedRing}），两动作互斥。
     */
    public static void bindSkillToRing(ServerPlayer serverPlayer, SkillEntry entry) {
        PlayerLevelData data = serverPlayer.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        int slot = data.getRingCount(); // 已吸收的环位（1-based 与环序号一致）
        if (slot <= 0 || entry == null) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.skill_session_expired")
                            .withStyle(ChatFormatting.RED));
            return;
        }
        // 绑定技能时从玩家数据读取该环位实际年限（落位时已写入；驱动伤害缩放）
        int age = data.getRingAge(slot - 1);
        if (age <= 0) age = 10;
        SkillEntry bound = entry.withSkill(entry.skill().withRingAge(age));
        PlayerSkillDataStore store = PlayerSkillDataStore.get(serverPlayer.serverLevel());
        store.bindSkill(serverPlayer, slot, bound.skill());
        // 签名机制回放：三池条目在此刻绑定玩家级持有状态（AI 生成路径生成时已抽签绑定，此处幂等）
        SignatureMechanism.byCode(bound.skill().signatureMechanism())
                .ifPresent(m -> store.bindMechanism(serverPlayer, slot, m));
        serverPlayer.sendSystemMessage(
                Component.translatable("msg.all_spirit_continent.skill_bound", slot, bound.skill().name())
                        .withStyle(ChatFormatting.GOLD));
    }

    /**
     * 拒绝本次吸收（绑定窗口「拒绝」按钮 → ConfirmRingAbsorbPayload.ACTION_REJECT）。
     * <p>
     * V6.2 起魂环已在动画落位瞬间真正加给玩家（见 {@link #absorbAtCinematicLand}），窗口弹出时魂环
     * 已经入账——因此「拒绝」必须回滚：移除刚吸收的魂环（年限归零，玩家回到瓶颈封顶可再次猎杀吸收）、
     * 退还该环位重刷机会、同步客户端、在玩家脚底播放魂环碎裂音效与粒子；若魂环实体仍在世界上
     * （多人其它参与者未吸收完）则同时撤销本玩家的「已吸收」标记，使其可对该魂环重新右键吸收。
     */
    public static void rejectAbsorbedRing(ServerPlayer serverPlayer, int ringEntityId) {
        PlayerLevelData data = serverPlayer.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        int slot = data.getRingCount(); // 刚吸收的魂环：落位加环后立即弹窗，它必为最外层环位
        if (slot <= 0) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.skill_session_expired")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        // ===== 数据回滚：移除刚吸收的魂环 =====
        data.setRingAge(slot - 1, 0);      // 年限归零 = 该环消失（回到瓶颈封顶，可继续猎杀魂兽）
        data.unmarkRerolled(slot);         // 该环位消耗的重刷机会一并退还
        data.setMaxSpiritPower(SoulGrowth.maxSpiritPower(data)); // 上限随环减少重算，魂力值保持现状
        // 该环位抽得的签名机制一并解绑（下次吸收重新抽签）
        PlayerSkillDataStore.get(serverPlayer.serverLevel()).unbindMechanism(serverPlayer, slot);
        SoulGrowth.applyHealth(serverPlayer);
        SoulGrowth.applyAttack(serverPlayer);
        if (serverPlayer.getHealth() > serverPlayer.getMaxHealth()) {
            serverPlayer.setHealth(serverPlayer.getMaxHealth());
        }
        All_spirit_continent.syncLevelDataToClient(serverPlayer);

        // 多人同环：魂环实体若仍在（其它参与者未吸收完）→ 撤销本玩家「已吸收」标记，可重新右键吸收；
        // 单人场景实体通常已在落位时消散，无需处理
        if (serverPlayer.level().getEntity(ringEntityId) instanceof SoulRingEntity ring) {
            ring.absorbed.remove(serverPlayer.getUUID());
        }

        // ===== 碎裂音效 + 粒子（玩家脚底，最外层魂环半径处，与调试棒移除魂环同观感）=====
        Level level = serverPlayer.level();
        double x = serverPlayer.getX();
        double y = serverPlayer.getY() + 0.15;
        double z = serverPlayer.getZ();
        level.playSound(null, x, y, z, SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.0F, 1.2F);
        double outerRadius = SoulRingLayout.getScaledRadius(slot - 1);
        for (int i = 0; i < 24; i++) {
            double angle = Math.PI * 2 * i / 24;
            level.addParticle(ParticleTypes.POOF,
                    x + Math.cos(angle) * outerRadius, y, z + Math.sin(angle) * outerRadius,
                    0, 0.05, 0);
        }

        // 清除会话并提示
        removeSession(ringEntityId, serverPlayer.getUUID());
        serverPlayer.sendSystemMessage(
                Component.translatable("msg.all_spirit_continent.skill_rejected", slot)
                        .withStyle(ChatFormatting.RED));
    }

    /**
     * 吸收动画「落位瞬间」真正加环（客户端落位上报 RingAbsorbLandPayload 后触发，服务端权威）：
     * 再校验一次（魂环可能已被其它参与者经验吸收消散等）→ 数据上加环 + 魂环实体消散 + 同步
     * → 弹「绑定魂技」窗口。技能会话在动画期间已备好（客户端只在「就绪」后才落位）。
     */
    public boolean absorbAtCinematicLand(ServerPlayer serverPlayer) {
        if (level().isClientSide) return false;
        UUID uuid = serverPlayer.getUUID();

        // 流程锁校验：无锁 / 锁的是其它环 / 整体超时 → 本次落位作废（防御旧动画/误触发）
        Absorbing state = ABSORBING.get(uuid);
        if (state == null || state.ringEntityId() != getId()
                || System.currentTimeMillis() > state.expiryMillis()) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.skill_session_expired")
                            .withStyle(ChatFormatting.RED));
            return false;
        }

        // 落位前再全量校验一次（激活/参与者/已吸收/满级）
        if (!validateAbsorb(serverPlayer)) {
            ABSORBING.remove(uuid);
            return false;
        }
        PlayerLevelData data = serverPlayer.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        if (!SoulExp.isBottleneck(data)) {
            ABSORBING.remove(uuid);
            serverPlayer.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.skill_need_bottleneck")
                            .withStyle(ChatFormatting.RED));
            return false;
        }

        // 技能会话必须已就绪（防御异常时序：技能没就绪不允许落位吸收）
        SkillEntry session = getSession(getId(), uuid);
        if (session == null) {
            ABSORBING.remove(uuid);
            PacketDistributor.sendToPlayer(serverPlayer, new RingAbsorbCancelPayload(getId()));
            serverPlayer.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.skill_session_expired")
                            .withStyle(ChatFormatting.RED));
            return false;
        }
        boolean ai = isAiSession(getId(), uuid);
        int age = getRingAge();
        // 会话技能补写实际年限（三池云端条目通常无年限，落位时以本环实际年限为准）
        session = session.withSkill(session.skill().withRingAge(age));
        putSession(getId(), uuid, session, ai);

        // ===== 落位瞬间真正加环（年限继承魂环实体年限，不升级、不结算经验，封顶解除）=====
        SoulExp.absorbRing(level(), serverPlayer, age);
        markAbsorbed(uuid);
        if (isFullyAbsorbed()) {
            discard();
        }
        playAbsorbEffects();
        All_spirit_continent.syncLevelDataToClient(serverPlayer);

        // 吸收成功提示：第 N 魂环已获得（同步后重取环数，确保读到加环后的值）
        PlayerLevelData synced = serverPlayer.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        serverPlayer.sendSystemMessage(
                Component.translatable("msg.all_spirit_continent.ring_absorbed",
                                Math.max(1, synced.getRingCount()), SoulBeastAge.format(age))
                        .withStyle(ChatFormatting.GOLD));

        // 流程锁解除；弹「绑定魂技」窗口（环已吸收，窗口只决定魂技是否绑定）
        ABSORBING.remove(uuid);
        openScreen(serverPlayer, getId(), session, ai);
        return true;
    }

    /**
     * 自行推演（重刷）：强制 AI 重生成（仅本地，不入云端上传队列），更新会话并回发新技能。每玩家每环位仅一次。
     * V6.2：魂环可能在落位时已消散，源信息从会话技能自带字段恢复（mob_id/wuhun/segment/年限）。
     */
    public static void rollSkill(ServerPlayer serverPlayer, int ringEntityId, SkillEntry oldEntry) {
        if (serverPlayer.level().isClientSide) return;
        // 重刷机会校验：仅当已出现魂技但玩家不满意时消耗机会，每玩家每环位一次
        PlayerLevelData lvl = serverPlayer.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        int slot = lvl.getRingCount(); // 已吸收的环位（1-based）
        if (oldEntry == null || slot <= 0 || lvl.hasRerolled(slot)) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.skill_reroll_used")
                            .withStyle(ChatFormatting.RED));
            return;
        }
        PlayerSkillConfig cfg = PlayerSkillDataStore.get(serverPlayer.serverLevel()).config(serverPlayer);
        SkillData base = oldEntry.skill();
        String wuhun = base.wuhun() == null || base.wuhun().isEmpty()
                ? PlayerSkillConfig.DEFAULT_WUHUN : base.wuhun();
        String mobId = base.mobId() == null || base.mobId().isEmpty() ? "unknown" : base.mobId();
        String segment = base.mobHealthSegment() == null || base.mobHealthSegment().isEmpty()
                ? "0_0" : base.mobHealthSegment();
        int age = base.ringAge() > 0 ? base.ringAge() : Math.max(10, lvl.getRingAge(slot - 1));
        float sourceMaxHealth = 0F; // 魂环实体可能已消散，数值规划提示退化为未知

        if (!GENERATING.add(serverPlayer.getUUID())) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.skill_generating")
                            .withStyle(ChatFormatting.GRAY));
            return;
        }
        // 生成引擎：同环重刷保持已抽签名机制（机制是玩家级持久资产，不随重刷变化）
        SkillGenerator.generate(new SkillGenerator.Request(
                        serverPlayer, cfg, wuhun, mobId, segment, slot, age, sourceMaxHealth))
                .whenComplete((opt, ex) -> {
            GENERATING.remove(serverPlayer.getUUID());
            serverPlayer.server.execute(() -> {
                if (!serverPlayer.isAlive()) return;
                if (ex != null || opt.isEmpty() || !opt.get().skill().isValid()) {
                    serverPlayer.sendSystemMessage(
                            Component.translatable("msg.all_spirit_continent.skill_gen_failed")
                                    .withStyle(ChatFormatting.RED));
                    return;
                }
                SkillEntry entry = SkillEntry.fromAi(opt.get().skill()); // ROLL 仅本地：不 queueSkill
                entry = entry.withSkill(entry.skill().withRingAge(age));
                lvl.markRerolled(slot); // 重刷成功即消耗该环位的一次机会
                putSession(ringEntityId, serverPlayer.getUUID(), entry, true);
                openScreen(serverPlayer, ringEntityId, entry, true);
            });
        });
    }

    /** 开窗：向玩家发送魂技信息（V6.2 落位加环后 / 自行推演后调用；窗口仅决定魂技绑定） */
    private static void openScreen(ServerPlayer serverPlayer, int ringEntityId,
                                   SkillEntry entry, boolean aiGenerated) {
        PlayerLevelData data = serverPlayer.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        int ringNumber = Math.max(1, data.getRingCount()); // 已吸收的第 N 魂环
        int ringAge = data.getRingAge(ringNumber - 1);
        if (ringAge <= 0) ringAge = 10;
        PacketDistributor.sendToPlayer(serverPlayer, new OpenRingAbsorbScreenPayload(
                ringEntityId,
                entry.skill().toJson().toString(),
                ringNumber,
                ringAge,
                entry.stats().avg(),
                entry.stats().ratingCount(),
                aiGenerated,
                entry.uploaderName()
        ));
    }

    /** 吸收前基础校验（激活/参与者/已吸收/满级），失败自动提示并返回 false */
    private boolean validateAbsorb(ServerPlayer serverPlayer) {
        PlayerLevelData data = serverPlayer.getData(ModAttachments.PLAYER_LEVEL_DATA.get());

        // ① 觉醒校验：等级系统未激活无法吸收
        if (!data.isActivated()) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.exp_not_activated")
                            .withStyle(ChatFormatting.RED));
            return false;
        }
        // ② 参与者校验：只有参与击杀的玩家才能吸收
        if (!isParticipant(serverPlayer.getUUID())) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.exp_not_participant")
                            .withStyle(ChatFormatting.RED));
            return false;
        }
        // ③ 已吸收校验：每人只能吸收一次（多人平分）
        if (isAbsorbed(serverPlayer.getUUID())) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.exp_absorbed")
                            .withStyle(ChatFormatting.RED));
            return false;
        }
        // ④ 满级校验：极限斗罗（99级）无法再获得经验
        if (data.getLevel() >= TitleSystem.JI_XIAN_LEVEL) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.exp_full")
                            .withStyle(ChatFormatting.RED));
            return false;
        }
        return true;
    }

    /**
     * 魂环消散/移除时清理所有指向本环的吸收流程锁：
     * 其它参与者经验吸收完 → 环消散 → 指向它的吸收流程一并作废，玩家可对新魂环重新右键。
     */
    private static void clearAbsorbingFor(int ringEntityId) {
        ABSORBING.entrySet().removeIf(e -> e.getValue().ringEntityId() == ringEntityId);
    }

    /** 玩家登出/断开时清理其吸收流程锁（动画中断，环仍留地面，重进后对同一环可重新右键） */
    public static void clearAbsorbingForPlayer(UUID playerUuid) {
        ABSORBING.remove(playerUuid);
    }

    /**
     * 执行经验吸收（服务端，蹲着右键触发）：
     * 完整校验（激活/参与者/已吸收/满级，失败提示）后结算经验
     * （基础经验×越级惩罚÷人数，瓶颈封顶时经验逸散作废）+ 音效粒子 + 提示 + 同步。
     * V6.2 起环位吸收走动画流程（openSensing → absorbAtCinematicLand），此方法只处理经验。
     * 返回是否真正执行了吸收。
     */
    public boolean performExpAbsorb(ServerPlayer serverPlayer) {
        PlayerLevelData data = serverPlayer.getData(ModAttachments.PLAYER_LEVEL_DATA.get());

        // ①-④ 基础校验（激活/参与者/已吸收/满级），失败自动提示
        if (!validateAbsorb(serverPlayer)) return false;

        // ⑤ 计算经验：基础经验 × 越级惩罚 ÷ 参与人数（多人平分，最低 1 点）
        int age = getRingAge();
        double base = SoulExp.baseExp(age);
        double pen = SoulExp.penalty(data.getLevel(), age);
        int gained = Math.max(1, (int) Math.round(base * pen / Math.max(1, participants.size())));

        // ⑥ 加经验并处理升级（瓶颈封顶时经验逸散作废）
        SoulExp.gainExp(level(), serverPlayer, gained);
        boolean dissipated = SoulExp.isBottleneck(data);

        // ⑦ 标记已吸收；全部参与者吸收完后魂环消散
        markAbsorbed(serverPlayer.getUUID());
        if (isFullyAbsorbed()) {
            discard();
        }

        // ⑧ 吸收音效 + 粒子（魂环位置）
        playAbsorbEffects();

        // ⑨ 提示：瓶颈封顶经验逸散 / 越级惩罚折扣 / 正常获得经验
        if (dissipated) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.exp_dissipated")
                            .withStyle(ChatFormatting.GRAY));
        } else if (pen < 1.0) {
            serverPlayer.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.exp_absorb_penalty",
                                    (int) Math.round(pen * 100), SoulExp.formatExp(gained))
                            .withStyle(ChatFormatting.GREEN));
        } else {
            serverPlayer.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.exp_absorb",
                                    SoulExp.formatExp(gained))
                            .withStyle(ChatFormatting.GREEN));
        }

        // ⑩ 同步经验/等级到客户端（魂环渲染）
        All_spirit_continent.syncLevelDataToClient(serverPlayer);
        return true;
    }

    /** 吸收魂环时的音效与粒子（魂环位置，经验吸收与瓶颈获取魂环共用） */
    private void playAbsorbEffects() {
        double x = getX();
        double y = getY() + 0.3;
        double z = getZ();
        level().playSound(null, x, y, z,
                SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0F, 1.0F);
        for (int i = 0; i < 20; i++) {
            double angle = level().random.nextDouble() * Math.PI * 2;
            double r = level().random.nextDouble() * 0.5;
            level().addParticle(ParticleTypes.ENCHANT,
                    x + Math.cos(angle) * r, y, z + Math.sin(angle) * r,
                    0, 0.15, 0);
        }
    }

    // ===== 持久化 =====

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("RingAge", getRingAge());
        tag.putString("SourceType", getSourceType());
        tag.putString("HealthSegment", getHealthSegment());
        tag.putFloat("SourceMaxHealth", getSourceMaxHealth());
        tag.put("Participants", toUuidList(participants));
        tag.put("Absorbed", toUuidList(absorbed));
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setRingAge(tag.getInt("RingAge"));
        setSourceType(tag.getString("SourceType"));
        setHealthSegment(tag.getString("HealthSegment"));
        if (tag.contains("SourceMaxHealth")) {
            setSourceMaxHealth(tag.getFloat("SourceMaxHealth"));
        }
        participants = fromUuidList(tag.getList("Participants", Tag.TAG_STRING));
        absorbed = fromUuidList(tag.getList("Absorbed", Tag.TAG_STRING));
    }

    /** UUID 列表 → NBT 字符串列表 */
    private static ListTag toUuidList(List<UUID> list) {
        ListTag tag = new ListTag();
        for (UUID uuid : list) {
            tag.add(StringTag.valueOf(uuid.toString()));
        }
        return tag;
    }

    /** NBT 字符串列表 → UUID 列表（非法条目跳过） */
    private static List<UUID> fromUuidList(ListTag tag) {
        List<UUID> list = new ArrayList<>();
        for (Tag t : tag) {
            try {
                list.add(UUID.fromString(t.getAsString()));
            } catch (IllegalArgumentException ignored) {
                // 坏数据跳过
            }
        }
        return list;
    }
}
