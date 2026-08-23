package org.fanajing.all_spirit_continent.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.client.SoulRingRenderer;

/**
 * 服务端 → 客户端：通知客户端播放「开武魂」动画（ringCount > 0）或关闭武魂（ringCount == 0）。
 * animType 指定动画类型（0=动画1 头部释放，1=动画2 脚底膨胀）。
 */
public record OpenSoulAnimationPayload(int ringCount, int animType) implements CustomPacketPayload {

    public static final Type<OpenSoulAnimationPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "open_soul_animation"));

    public static final StreamCodec<ByteBuf, OpenSoulAnimationPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, OpenSoulAnimationPayload::ringCount,
                    ByteBufCodecs.VAR_INT, OpenSoulAnimationPayload::animType,
                    OpenSoulAnimationPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端处理：ringCount > 0 按 animType 播放开武魂动画；ringCount == 0 关闭武魂（魂环隐藏） */
    public static void handleClient(final OpenSoulAnimationPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (payload.ringCount() > 0) {
                SoulRingRenderer.startAnimation(payload.ringCount(), payload.animType());
            } else {
                SoulRingRenderer.stopAnimation();
            }
        });
    }
}
