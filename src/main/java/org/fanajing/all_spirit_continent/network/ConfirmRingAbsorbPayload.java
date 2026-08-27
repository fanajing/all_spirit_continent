package org.fanajing.all_spirit_continent.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.entity.SoulRingEntity;
import org.fanajing.all_spirit_continent.skill.RatingService;
import org.fanajing.all_spirit_continent.skill.SkillEntry;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：魂技感应窗口的最终决定。
 * <p>
 * action：
 * - ABSORB：绑定技能到魂环位并吸收魂环（瓶颈获环 / 非瓶颈经验吸收）
 * - REJECT：不吸收、不消耗魂环（仅当 stars>0 时对该技能评分）
 * - ROLL：强制 AI 重生成（仅本地，不入云端上传队列），窗口重新感应
 * <p>
 * stars：1-5 星（吸收/拒绝提交时同步评分；0 表示不评）。服务端 24h 防刷校验。
 */
public record ConfirmRingAbsorbPayload(int ringEntityId, String action, int stars)
        implements CustomPacketPayload {

    public static final String ACTION_ABSORB = "ABSORB";
    public static final String ACTION_REJECT = "REJECT";
    public static final String ACTION_ROLL = "ROLL";

    public static final Type<ConfirmRingAbsorbPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "confirm_ring_absorb"));

    public static final StreamCodec<ByteBuf, ConfirmRingAbsorbPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ConfirmRingAbsorbPayload::ringEntityId,
                    ByteBufCodecs.STRING_UTF8, ConfirmRingAbsorbPayload::action,
                    ByteBufCodecs.VAR_INT, ConfirmRingAbsorbPayload::stars,
                    ConfirmRingAbsorbPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 服务端处理：按动作执行吸收/拒绝/推演 */
    public static void handleServer(final ConfirmRingAbsorbPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            Entity entity = sp.serverLevel().getEntity(payload.ringEntityId());
            if (!(entity instanceof SoulRingEntity ring)) return;

            // 会话校验：感应技能必须存在（环被移除/会话过期则提示重试）
            SkillEntry session = SoulRingEntity.getSession(payload.ringEntityId(), sp.getUUID());
            if (session == null) {
                sp.sendSystemMessage(net.minecraft.network.chat.Component
                        .translatable("msg.all_spirit_continent.skill_session_expired")
                        .withStyle(net.minecraft.ChatFormatting.RED));
                return;
            }

            switch (payload.action()) {
                case ACTION_ABSORB -> {
                    // 评分 + 绑定技能到环位 + 吸收魂环
                    if (payload.stars() > 0) {
                        RatingService.rate(sp, session.uuid(), payload.stars());
                    }
                    ring.absorbWithSkill(sp, session);
                    SoulRingEntity.removeSession(payload.ringEntityId(), sp.getUUID());
                }
                case ACTION_REJECT -> {
                    // 不吸收、不消耗魂环；仅记录评分
                    if (payload.stars() > 0) {
                        RatingService.rate(sp, session.uuid(), payload.stars());
                    }
                    SoulRingEntity.removeSession(payload.ringEntityId(), sp.getUUID());
                }
                case ACTION_ROLL -> {
                    // 强制 AI 重生成（更新会话并回发新技能）
                    ring.rollSkill(sp, session);
                }
                default -> { }
            }
        });
    }
}
