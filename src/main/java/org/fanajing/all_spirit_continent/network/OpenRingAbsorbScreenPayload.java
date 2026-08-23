package org.fanajing.all_spirit_continent.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.client.SoulRingAbsorbScreen;

/**
 * 服务端 → 客户端：打开魂环吸收确认窗口（蹲着右键魂环、校验通过后触发）。
 * 窗口显示怪物种类/年限/技能（开发中）/吸收概率（开发中），确认后发 ConfirmRingAbsorbPayload。
 */
public record OpenRingAbsorbScreenPayload(int ringEntityId) implements CustomPacketPayload {

    public static final Type<OpenRingAbsorbScreenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "open_ring_absorb_screen"));

    public static final StreamCodec<ByteBuf, OpenRingAbsorbScreenPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, OpenRingAbsorbScreenPayload::ringEntityId,
                    OpenRingAbsorbScreenPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端处理：打开吸收确认窗口（窗口从客户端实体同步数据读取信息） */
    public static void handleClient(final OpenRingAbsorbScreenPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft.getInstance().setScreen(new SoulRingAbsorbScreen(payload.ringEntityId()));
        });
    }
}
