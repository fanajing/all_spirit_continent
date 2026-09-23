package org.fanajing.all_spirit_continent.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.entity.SoulRingEntity;
import org.fanajing.all_spirit_continent.skill.RatingService;
import org.fanajing.all_spirit_continent.skill.SkillEntry;

/**
 * 客户端 → 服务端：魂技绑定窗口的最终决定（V6.2）。
 * <p>
 * V6.2 语义变更：魂环已在吸收动画「落位瞬间」真正加给玩家并消散，此窗口只决定
 * 「是否把窗口中的魂技绑定到该魂环位」，不再负责加环/消散魂环实体（实体可能已不存在）。
 * <p>
 * action：
 * - ABSORB：把魂技绑定到已吸收的环位（环一定已吸收；无环/会话过期则提示）
 * - REJECT：拒绝本次吸收——回滚移除刚吸收的魂环并播放碎裂音效（见 SoulRingEntity.rejectAbsorbedRing），
 *   玩家回到瓶颈封顶，可重新猎杀魂兽吸收新魂环
 * - ROLL：强制 AI 重生成（仅本地，不入云端上传队列），窗口重新展示新技能
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

    /** 服务端处理：按动作执行绑定/拒绝/推演（仅凭会话与环位，不依赖魂环实体是否仍在） */
    public static void handleServer(final ConfirmRingAbsorbPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;

            // 会话校验：感应技能必须存在（会话过期则提示重试）
            SkillEntry session = SoulRingEntity.getSession(payload.ringEntityId(), sp.getUUID());
            if (session == null) {
                sp.sendSystemMessage(Component
                        .translatable("msg.all_spirit_continent.skill_session_expired")
                        .withStyle(ChatFormatting.RED));
                return;
            }

            switch (payload.action()) {
                case ACTION_ABSORB -> {
                    // 评分 + 绑定技能到已吸收的环位（V6.2：不再吸收/消散魂环实体）
                    if (payload.stars() > 0) {
                        RatingService.rate(sp, session.uuid(), payload.stars());
                    }
                    SoulRingEntity.bindSkillToRing(sp, session);
                    SoulRingEntity.removeSession(payload.ringEntityId(), sp.getUUID());
                }
                case ACTION_REJECT -> {
                    // 拒绝本次吸收：回滚移除刚吸收的魂环（碎裂消散），不再保留魂环
                    if (payload.stars() > 0) {
                        RatingService.rate(sp, session.uuid(), payload.stars());
                    }
                    SoulRingEntity.rejectAbsorbedRing(sp, payload.ringEntityId());
                }
                case ACTION_ROLL -> {
                    // 强制 AI 重生成（更新会话并回发新技能）
                    SoulRingEntity.rollSkill(sp, payload.ringEntityId(), session);
                }
                default -> { }
            }
        });
    }
}
