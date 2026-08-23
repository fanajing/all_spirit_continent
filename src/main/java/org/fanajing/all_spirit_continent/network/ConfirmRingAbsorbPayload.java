package org.fanajing.all_spirit_continent.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.entity.SoulRingEntity;

/**
 * 客户端 → 服务端：确认吸收魂环（吸收确认窗口「吸收」按钮触发）。
 * 服务端按实体 ID 重新查找魂环并执行完整校验后吸收（防伪造包）。
 */
public record ConfirmRingAbsorbPayload(int ringEntityId) implements CustomPacketPayload {

    public static final Type<ConfirmRingAbsorbPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "confirm_ring_absorb"));

    public static final StreamCodec<ByteBuf, ConfirmRingAbsorbPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ConfirmRingAbsorbPayload::ringEntityId,
                    ConfirmRingAbsorbPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 服务端处理：查找魂环实体并执行吸收（performAbsorb 内部有完整重校验） */
    public static void handleServer(final ConfirmRingAbsorbPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (!(player instanceof ServerPlayer serverPlayer)) return;
            if (serverPlayer.level().getEntity(payload.ringEntityId()) instanceof SoulRingEntity ring) {
                ring.performAbsorb(serverPlayer);
            }
        });
    }
}
