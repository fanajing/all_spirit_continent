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
import org.fanajing.all_spirit_continent.init.ModAttachments;
import org.fanajing.all_spirit_continent.network.OpenRingAbsorbScreenPayload;
import org.fanajing.all_spirit_continent.util.PlayerLevelData;
import org.fanajing.all_spirit_continent.util.SoulBeastAge;
import org.fanajing.all_spirit_continent.util.SoulExp;
import org.fanajing.all_spirit_continent.util.TitleSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 魂环实体：击杀魂兽后原地生成的环状实体（不是掉落物）。
 *  - 年限通过 synchedData 同步到客户端，渲染器按档位选颜色贴图
 *  - 环上方年限文字用原版命名牌机制显示（customName），渲染器复用 renderNameTag
 *  - 不可被拾取
 *  - 经验吸收：击杀参与者蹲着（潜行）右键魂环 → 魂环逸散为经验值（瓶颈封顶时经验作废，不直接获得新魂环）；
 *    多人参与击杀则平分（每位参与者各吸收一份 基础经验×惩罚÷人数），全部吸收完后魂环消散
 *  - 瓶颈获取魂环：节点等级（10/20/.../90）未获得对应魂环时，仅能不蹲右键弹窗确认「吸收」获得新魂环
 *    （年限继承魂环实体年限，不升级、不结算经验），获得后封顶解除
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

    /** 击杀参与者 UUID 列表（服务端权威，死亡时由魂兽系统写入，NBT 持久化） */
    private List<UUID> participants = new ArrayList<>();
    /** 已吸收经验的玩家 UUID 列表（NBT 持久化，全部参与者吸收完后魂环消散） */
    private List<UUID> absorbed = new ArrayList<>();

    public SoulRingEntity(EntityType<?> type, Level level) {
        super(type, level);
        LOGGER.info("[SoulRing] constructed side={}", level.isClientSide ? "client" : "server");
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        LOGGER.info("[SoulRing] removed reason={}", getRemovalReason());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_AGE, 0);
        builder.define(DATA_SOURCE_TYPE, "");
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

    // ===== 右键交互：蹲着右键直接逸散为经验；不蹲右键弹出吸收确认窗口 =====

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        // 客户端：直接返回成功（摆臂），逻辑由服务端权威处理
        if (player.level().isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.FAIL;

        // 蹲着（潜行）右键：魂环直接逸散为经验（瓶颈封顶时经验作废，不直接获得新魂环；
        // 校验失败时提示）
        if (player.isShiftKeyDown()) {
            return performAbsorb(serverPlayer, false) ? InteractionResult.CONSUME : InteractionResult.FAIL;
        }

        // 不蹲右键：弹出吸收确认窗口（怪物种类/年限/技能/吸收概率），点「吸收」才执行
        PacketDistributor.sendToPlayer(serverPlayer, new OpenRingAbsorbScreenPayload(getId()));
        return InteractionResult.CONSUME;
    }

    /**
     * 执行吸收（服务端，吸收确认窗口「吸收」按钮经 ConfirmRingAbsorbPayload 触发）：
     * 完整校验后瓶颈节点可获得新魂环（年限继承魂环实体年限）或经验吸收。
     */
    public boolean performAbsorb(ServerPlayer serverPlayer) {
        return performAbsorb(serverPlayer, true);
    }

    /**
     * 执行吸收（服务端）：蹲着右键直接触发（allowRingGain=false，瓶颈时经验逸散、不获得新魂环），
     * 或吸收确认窗口「吸收」按钮经 ConfirmRingAbsorbPayload 触发（allowRingGain=true）。
     * 完整校验（激活/参与者/已吸收/满级，失败提示）后：
     * ⑤ 瓶颈节点获取魂环分支（仅确认吸收，不升级不结算经验）或
     * ⑥-⑪ 经验吸收（基础经验×越级惩罚÷人数，瓶颈封顶时经验逸散作废）+ 音效粒子 + 提示 + 同步。
     * 返回是否真正执行了吸收。
     */
    public boolean performAbsorb(ServerPlayer serverPlayer, boolean allowRingGain) {
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

        // ⑤ 瓶颈获取魂环分支：节点等级（10/20/.../90）未获得对应魂环
        //    → 吸收魂环直接获得新魂环（年限继承魂环实体年限，不升级、不结算经验，封顶解除）；
        //    仅弹窗确认吸收（allowRingGain）生效，蹲着右键此分支被跳过（经验直接逸散）
        if (allowRingGain && SoulExp.isBottleneck(data)) {
            SoulExp.absorbRing(level(), serverPlayer, getRingAge());
            markAbsorbed(serverPlayer.getUUID());
            if (isFullyAbsorbed()) {
                discard();
            }
            playAbsorbEffects();
            All_spirit_continent.syncLevelDataToClient(serverPlayer);
            return true;
        }

        // ⑥ 计算经验：基础经验 × 越级惩罚 ÷ 参与人数（多人平分，最低 1 点）
        int age = getRingAge();
        double base = SoulExp.baseExp(age);
        double pen = SoulExp.penalty(data.getLevel(), age);
        int gained = Math.max(1, (int) Math.round(base * pen / Math.max(1, participants.size())));

        // ⑦ 加经验并处理升级（瓶颈封顶时经验逸散作废）
        SoulExp.gainExp(level(), serverPlayer, gained);
        boolean dissipated = SoulExp.isBottleneck(data);

        // ⑧ 标记已吸收；全部参与者吸收完后魂环消散
        markAbsorbed(serverPlayer.getUUID());
        if (isFullyAbsorbed()) {
            discard();
        }

        // ⑨ 吸收音效 + 粒子（魂环位置）
        playAbsorbEffects();

        // ⑩ 提示：瓶颈封顶经验逸散 / 越级惩罚折扣 / 正常获得经验
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

        // ⑪ 同步经验/等级到客户端（魂环渲染）
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
        tag.put("Participants", toUuidList(participants));
        tag.put("Absorbed", toUuidList(absorbed));
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setRingAge(tag.getInt("RingAge"));
        setSourceType(tag.getString("SourceType"));
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
