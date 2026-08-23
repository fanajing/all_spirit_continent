package org.fanajing.all_spirit_continent.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.item.ModItems;
import org.fanajing.all_spirit_continent.item.TiaoShiBangItem;
import org.fanajing.all_spirit_continent.util.DebugStickMode;

/**
 * 客户端 → 服务端：增减等级（调试棒增减等级模式右键 +1 / Alt+右键 -1 触发）。
 * 服务端校验手持调试棒、当前为增减等级模式且 delta 为 ±1 后调用 TiaoShiBangItem.adjustLevel。
 */
public record AdjustLevelPayload(int delta) implements CustomPacketPayload {

    public static final Type<AdjustLevelPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "adjust_level"));

    public static final StreamCodec<ByteBuf, AdjustLevelPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, AdjustLevelPayload::delta,
                    AdjustLevelPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 服务端处理：校验手持调试棒、增减等级模式与 delta 范围后执行等级调整 */
    public static void handleServer(final AdjustLevelPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (payload.delta() != 1 && payload.delta() != -1) return;
            ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (!stack.is(ModItems.TIAOSHI_BANG.get())) return;
            if (TiaoShiBangItem.getMode(stack) != DebugStickMode.LEVEL) return;
            TiaoShiBangItem.adjustLevel(player.level(), player, payload.delta());
        });
    }
}
