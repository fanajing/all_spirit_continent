package org.fanajing.all_spirit_continent.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.client.RingAbsorbCinematic;

/**
 * 服务端 → 客户端：取消本次吸收魂环动画（V6.2）。
 * <p>
 * 用于魂技感应失败的场景：吸收判定通过后若 AI 生成失败，吸收流程中止，
 * 魂环不会消散（仍留在地面），客户端恢复视角与地面魂环渲染。
 */
public record RingAbsorbCancelPayload(int ringEntityId)
        implements CustomPacketPayload {

    public static final Type<RingAbsorbCancelPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    All_spirit_continent.MODID, "ring_absorb_cancel"));

    public static final StreamCodec<ByteBuf, RingAbsorbCancelPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public RingAbsorbCancelPayload decode(ByteBuf buf) {
                    return new RingAbsorbCancelPayload(buf.readInt());
                }

                @Override
                public void encode(ByteBuf buf, RingAbsorbCancelPayload p) {
                    buf.writeInt(p.ringEntityId());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端处理：终止吸收动画并恢复视角/地面魂环渲染 */
    public static void handleClient(final RingAbsorbCancelPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            RingAbsorbCinematic.cancel(payload.ringEntityId());
        });
    }
}
