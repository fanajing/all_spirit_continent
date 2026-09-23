package org.fanajing.all_spirit_continent.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.client.RingAbsorbCinematic;
import org.fanajing.all_spirit_continent.client.RingConfirmScreen;

/**
 * 服务端 → 客户端：打开魂环·魂技绑定窗口（V6.2 在吸收动画「落位加环」后触发）。
 * <p>
 * 携带感应到的魂技完整 JSON（客户端反序列化展示名称/描述/数值预览/来源评分），
 * 窗口内「绑定」经 ConfirmRingAbsorbPayload(ABSORB) 把魂技固化到已吸收的环位。
 * 窗口到达即代表魂环已吸收完毕，客户端据此结束吸收动画。
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

    /** 客户端处理：魂环已吸收完毕 → 结束吸收动画并打开「魂技绑定」窗口 */
    public static void handleClient(final OpenRingAbsorbScreenPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            // V6.2：窗口到达即代表动画落位加环完成，结束动画（环显形关闭、相机还原）
            RingAbsorbCinematic.onAbsorbWindowOpened(payload.ringEntityId());
            // 若是「自行推演」刷新：标记旧窗口为被替换，避免其 removed() 误发自动拒绝
            if (Minecraft.getInstance().screen instanceof RingConfirmScreen current) {
                current.markRefreshed();
            }
            Minecraft.getInstance().setScreen(new RingConfirmScreen(
                    payload.ringEntityId(), payload.skillJson(), payload.ringNumber(),
                    payload.ringAge(), payload.avgRating(), payload.ratingCount(),
                    payload.aiGenerated(), payload.uploaderName()));
        });
    }
}
