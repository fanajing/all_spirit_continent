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
import org.fanajing.all_spirit_continent.item.TiaoShiBangItem;

/**
 * 客户端 → 服务端：玩家按下「开/关武魂」按键。
 * open=true 请求开启武魂，open=false 请求关闭武魂；
 * 具体开/关逻辑在服务端统一处理（校验魂环数、下发动画、音效与提示）。
 */
public record KaiWuHunPayload(boolean open) implements CustomPacketPayload {

    public static final Type<KaiWuHunPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "kai_wu_hun"));

    public static final StreamCodec<ByteBuf, KaiWuHunPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, KaiWuHunPayload::open,
                    KaiWuHunPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 服务端处理：转发给开关武魂统一逻辑（按键触发，无需手持调试棒） */
    public static void handleServer(final KaiWuHunPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (!(player instanceof ServerPlayer serverPlayer)) return;
            if (payload.open()) {
                TiaoShiBangItem.openMartialSoul(serverPlayer.level(), serverPlayer);
            } else {
                TiaoShiBangItem.closeMartialSoul(serverPlayer.level(), serverPlayer);
            }
        });
    }
}
