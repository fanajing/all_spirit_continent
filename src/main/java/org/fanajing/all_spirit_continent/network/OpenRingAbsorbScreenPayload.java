package org.fanajing.all_spirit_continent.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.client.RingConfirmScreen;

/**
 * 服务端 → 客户端：打开魂环·魂技感应确认窗口（不蹲右键点击魂环、校验通过后触发）。
 * <p>
 * 携带感应到的魂技完整 JSON（客户端反序列化展示名称/描述/数值预览/来源评分），
 * 窗口内「吸收」经 ConfirmRingAbsorbPayload(ABSORB) 绑定技能到环位并吸收魂环。
 */
public record OpenRingAbsorbScreenPayload(
        int ringEntityId,
        String skillJson,
        int ringNumber,
        int ringAge,
        double avgRating,
        int ratingCount,
        boolean aiGenerated,
        String uploaderName
) implements CustomPacketPayload {

    public static final Type<OpenRingAbsorbScreenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "open_ring_absorb_screen"));

    public static final StreamCodec<ByteBuf, OpenRingAbsorbScreenPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OpenRingAbsorbScreenPayload decode(ByteBuf buf) {
                    int ringEntityId = buf.readInt();
                    String skillJson = ByteBufCodecs.STRING_UTF8.decode(buf);
                    int ringNumber = buf.readInt();
                    int ringAge = buf.readInt();
                    double avgRating = buf.readDouble();
                    int ratingCount = buf.readInt();
                    boolean aiGenerated = buf.readBoolean();
                    String uploaderName = ByteBufCodecs.STRING_UTF8.decode(buf);
                    return new OpenRingAbsorbScreenPayload(
                            ringEntityId, skillJson, ringNumber, ringAge,
                            avgRating, ratingCount, aiGenerated, uploaderName);
                }

                @Override
                public void encode(ByteBuf buf, OpenRingAbsorbScreenPayload p) {
                    buf.writeInt(p.ringEntityId());
                    ByteBufCodecs.STRING_UTF8.encode(buf, p.skillJson());
                    buf.writeInt(p.ringNumber());
                    buf.writeInt(p.ringAge());
                    buf.writeDouble(p.avgRating());
                    buf.writeInt(p.ratingCount());
                    buf.writeBoolean(p.aiGenerated());
                    ByteBufCodecs.STRING_UTF8.encode(buf, p.uploaderName());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端处理：打开魂技感应确认窗口 */
    public static void handleClient(final OpenRingAbsorbScreenPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft.getInstance().setScreen(new RingConfirmScreen(
                    payload.ringEntityId(), payload.skillJson(), payload.ringNumber(),
                    payload.ringAge(), payload.avgRating(), payload.ratingCount(),
                    payload.aiGenerated(), payload.uploaderName()));
        });
    }
}
