package org.fanajing.all_spirit_continent.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.item.ModItems;
import org.fanajing.all_spirit_continent.item.TiaoShiBangItem;
import org.fanajing.all_spirit_continent.util.DebugStickMode;

/**
 * 客户端 → 服务端：同步调试棒当前模式。
 * 客户端滚轮切换模式后发送，服务端将模式写入物品 NBT（服务端权威）。
 */
public record SetDebugModePayload(String modeId) implements CustomPacketPayload {

    public static final Type<SetDebugModePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "set_debug_mode"));

    public static final StreamCodec<ByteBuf, SetDebugModePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, SetDebugModePayload::modeId,
                    SetDebugModePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 服务端处理：校验手持调试棒后写入模式 NBT */
    public static void handleServer(final SetDebugModePayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player == null) return;
            ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (!stack.is(ModItems.TIAOSHI_BANG.get())) return;
            DebugStickMode mode = DebugStickMode.fromName(payload.modeId);
            TiaoShiBangItem.setMode(stack, mode);
        });
    }
}
