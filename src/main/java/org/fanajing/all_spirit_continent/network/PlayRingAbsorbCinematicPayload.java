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
 * 服务端 → 客户端：开始播放「吸收魂环动画」（V6.2）。
 * <p>
 * 右键点击魂环、吸收判定通过后由服务端发出。客户端据此：
 * - 隐藏地面魂环实体渲染，改为从原位置升空的「魂环灵体」
 * - 自动切第三人称并缓慢环绕相机
 * - 升空 → 飞向玩家头顶 → 武魂显形（仅环显形，不改武魂开关状态）→ 盘旋等待
 * 魂技感应完成后服务端再发 RingAbsorbSkillReadyPayload；落位由客户端上报
 * RingAbsorbLandPayload，届时服务端才真正把魂环加给玩家并弹「绑定魂技」窗口。
 */
public record PlayRingAbsorbCinematicPayload(int ringEntityId, int ringAge)
        implements CustomPacketPayload {

    public static final Type<PlayRingAbsorbCinematicPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(
                    All_spirit_continent.MODID, "play_ring_absorb_cinematic"));

    public static final StreamCodec<ByteBuf, PlayRingAbsorbCinematicPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PlayRingAbsorbCinematicPayload decode(ByteBuf buf) {
                    int ringEntityId = buf.readInt();
                    int ringAge = buf.readInt();
                    return new PlayRingAbsorbCinematicPayload(ringEntityId, ringAge);
                }

                @Override
                public void encode(ByteBuf buf, PlayRingAbsorbCinematicPayload p) {
                    buf.writeInt(p.ringEntityId());
                    buf.writeInt(p.ringAge());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端处理：启动吸收魂环动画（若已有动画在播放则忽略新指令） */
    public static void handleClient(final PlayRingAbsorbCinematicPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            RingAbsorbCinematic.start(payload.ringEntityId(), payload.ringAge());
        });
    }
}
