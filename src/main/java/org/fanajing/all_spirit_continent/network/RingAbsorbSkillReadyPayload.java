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
 * 服务端 → 客户端：通知「魂技已感应完毕」（V6.2）。
 * <p>
 * 服务端在吸收动画期间并行完成本地三池抽取或云端 AI 生成后发送。
 * 客户端收到后：若魂环已盘旋满最短时间则开始「头顶→脚下」落位；
 * 若还未盘旋够则等到最短盘旋时间结束后自动落位（动画时长自适应 AI 延迟）。
 */
public record RingAbsorbSkillReadyPayload(int ringEntityId)
        implements CustomPacketPayload {

    public static final Type<RingAbsorbSkillReadyPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    All_spirit_continent.MODID, "ring_absorb_skill_ready"));

    public static final StreamCodec<ByteBuf, RingAbsorbSkillReadyPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public RingAbsorbSkillReadyPayload decode(ByteBuf buf) {
                    return new RingAbsorbSkillReadyPayload(buf.readInt());
                }

                @Override
                public void encode(ByteBuf buf, RingAbsorbSkillReadyPayload p) {
                    buf.writeInt(p.ringEntityId());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端处理：标记魂技就绪，触发落位阶段 */
    public static void handleClient(final RingAbsorbSkillReadyPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            RingAbsorbCinematic.skillReady(payload.ringEntityId());
        });
    }
}
