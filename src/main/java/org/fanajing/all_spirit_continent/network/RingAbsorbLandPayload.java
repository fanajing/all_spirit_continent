package org.fanajing.all_spirit_continent.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.entity.SoulRingEntity;

/**
 * 客户端 → 服务端：吸收动画「落位完成」上报（V6.2）。
 * <p>
 * 客户端在吸收动画的魂环落到脚下既定环位时上报；服务端此刻才真正把魂环加给玩家
 * （数据加环 + 魂环实体消散 + 同步 + 弹「绑定魂技」窗口）。技能会话在动画期间已备好。
 */
public record RingAbsorbLandPayload(int ringEntityId)
        implements CustomPacketPayload {

    public static final Type<RingAbsorbLandPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    All_spirit_continent.MODID, "ring_absorb_land"));

    public static final StreamCodec<ByteBuf, RingAbsorbLandPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public RingAbsorbLandPayload decode(ByteBuf buf) {
                    return new RingAbsorbLandPayload(buf.readInt());
                }

                @Override
                public void encode(ByteBuf buf, RingAbsorbLandPayload p) {
                    buf.writeInt(p.ringEntityId());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 服务端处理：魂环落位 → 真正加环并弹绑定窗口 */
    public static void handleServer(final RingAbsorbLandPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            Entity entity = sp.serverLevel().getEntity(payload.ringEntityId());
            if (!(entity instanceof SoulRingEntity ring)) {
                // 魂环已被其他参与者经验吸收消散等：中止本次吸收，不弹窗
                sp.sendSystemMessage(Component.translatable(
                        "msg.all_spirit_continent.skill_session_expired").withStyle(ChatFormatting.RED));
                return;
            }
            ring.absorbAtCinematicLand(sp);
        });
    }
}
