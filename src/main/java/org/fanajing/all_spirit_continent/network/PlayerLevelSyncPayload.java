package org.fanajing.all_spirit_continent.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.init.ModAttachments;
import org.fanajing.all_spirit_continent.util.PlayerLevelData;
import org.fanajing.all_spirit_continent.util.SoulRingLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 → 客户端：同步玩家等级系统数据。
 * 在玩家登录时由服务端发送，客户端接收后写入 Attachment。
 */
public record PlayerLevelSyncPayload(boolean activated, int level, int exp, float spiritPower, float maxSpiritPower,
                                     String title, List<Integer> ringAges)
        implements CustomPacketPayload {

    public static final Type<PlayerLevelSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "player_level_sync"));

    /**
     * 手动编解码：固定写 10 个环年限 VarInt，避免集合 codec 在跨版本间的行为差异。
     * 编码顺序 = 解码顺序：activated, level, exp, spiritPower, maxSpiritPower, title, ringAges[10]。
     */
    public static final StreamCodec<ByteBuf, PlayerLevelSyncPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PlayerLevelSyncPayload decode(ByteBuf buf) {
                    boolean activated = ByteBufCodecs.BOOL.decode(buf);
                    int level = ByteBufCodecs.INT.decode(buf);
                    int exp = ByteBufCodecs.INT.decode(buf);
                    float spiritPower = ByteBufCodecs.FLOAT.decode(buf);
                    float maxSpiritPower = ByteBufCodecs.FLOAT.decode(buf);
                    String title = ByteBufCodecs.STRING_UTF8.decode(buf);
                    List<Integer> ages = new ArrayList<>(SoulRingLayout.MAX_RINGS);
                    for (int i = 0; i < SoulRingLayout.MAX_RINGS; i++) {
                        ages.add(ByteBufCodecs.VAR_INT.decode(buf));
                    }
                    return new PlayerLevelSyncPayload(activated, level, exp, spiritPower, maxSpiritPower, title, ages);
                }

                @Override
                public void encode(ByteBuf buf, PlayerLevelSyncPayload payload) {
                    ByteBufCodecs.BOOL.encode(buf, payload.activated);
                    ByteBufCodecs.INT.encode(buf, payload.level);
                    ByteBufCodecs.INT.encode(buf, payload.exp);
                    ByteBufCodecs.FLOAT.encode(buf, payload.spiritPower);
                    ByteBufCodecs.FLOAT.encode(buf, payload.maxSpiritPower);
                    ByteBufCodecs.STRING_UTF8.encode(buf, payload.title);
                    for (int i = 0; i < SoulRingLayout.MAX_RINGS; i++) {
                        ByteBufCodecs.VAR_INT.encode(buf,
                                i < payload.ringAges.size() ? payload.ringAges.get(i) : 0);
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端处理：将接收到的数据写入玩家 Attachment */
    public static void handleClient(final PlayerLevelSyncPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (player == null) return;
            PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
            data.setActivated(payload.activated);
            data.setLevel(payload.level);
            data.setExp(payload.exp);
            data.setMaxSpiritPower(payload.maxSpiritPower);
            data.setSpiritPower(payload.spiritPower);
            data.setTitle(payload.title);
            data.setRingAges(payload.ringAges);
        });
    }
}
