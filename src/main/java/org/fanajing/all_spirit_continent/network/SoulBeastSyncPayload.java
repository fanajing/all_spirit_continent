package org.fanajing.all_spirit_continent.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.client.SoulBeastClientCache;

import java.util.UUID;

/**
 * 服务端 → 客户端：同步一只魂兽的年限数据。
 * 发送时机：玩家开始跟踪某实体时（生成/走近/跨维度/区块加载）。
 * 携带 UUID 用于校验：防止实体 ID 被复用后残留数据错配到新实体。
 */
public record SoulBeastSyncPayload(int entityId, UUID uuid, int age) implements CustomPacketPayload {

    public static final Type<SoulBeastSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "soul_beast_sync"));

    public static final StreamCodec<FriendlyByteBuf, SoulBeastSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.entityId);
                buf.writeLong(payload.uuid.getMostSignificantBits());
                buf.writeLong(payload.uuid.getLeastSignificantBits());
                buf.writeVarInt(payload.age);
            },
            buf -> new SoulBeastSyncPayload(
                    buf.readVarInt(),
                    new UUID(buf.readLong(), buf.readLong()),
                    buf.readVarInt()
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端处理：写入魂兽年限缓存（渲染器按 entityId + UUID 读取） */
    public static void handleClient(final SoulBeastSyncPayload payload, final IPayloadContext context) {
        context.enqueueWork(() ->
                SoulBeastClientCache.set(payload.entityId(), payload.uuid(), payload.age()));
    }
}
