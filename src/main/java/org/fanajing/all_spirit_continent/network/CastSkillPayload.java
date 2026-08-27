package org.fanajing.all_spirit_continent.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.data.PlayerSkillDataStore;
import org.fanajing.all_spirit_continent.skill.SkillData;
import org.fanajing.all_spirit_continent.skill.SkillExecutor;
import org.fanajing.all_spirit_continent.util.PlayerSkillConfig;

/**
 * 客户端 → 服务端：开武魂状态下按技能释放键（默认 R）释放指定魂环位的魂技。
 * <p>
 * 服务端权威执行：读取绑定环位的 SkillData → 冷却/精神力/武魂校验 → SkillExecutor 原语执行。
 * 若指定环位无技能，自动回退到第一个已绑定技能的环位并提示。
 */
public record CastSkillPayload(int slot) implements CustomPacketPayload {

    public static final Type<CastSkillPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "cast_skill"));

    public static final StreamCodec<ByteBuf, CastSkillPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, CastSkillPayload::slot,
                    CastSkillPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 服务端处理：校验并执行魂技 */
    public static void handleServer(final CastSkillPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            PlayerSkillConfig cfg = PlayerSkillDataStore.get(sp.serverLevel()).config(sp);

            // 解析环位：指定环无技能 → 自动回退第一个已绑定环位
            SkillData data = cfg.skillAt(payload.slot());
            if (data == null) {
                int first = cfg.firstBoundSlot();
                if (first < 0) {
                    sp.sendSystemMessage(Component
                            .translatable("msg.all_spirit_continent.skill_no_bound")
                            .withStyle(ChatFormatting.RED));
                    return;
                }
                data = cfg.skillAt(first);
                sp.sendSystemMessage(Component
                        .translatable("msg.all_spirit_continent.skill_slot_empty_switch", first)
                        .withStyle(ChatFormatting.YELLOW));
            }

            // 武魂校验 / 冷却 / 精神力由执行引擎统一处理
            switch (SkillExecutor.tryCast(sp, data)) {
                case COOLDOWN -> sp.sendSystemMessage(Component
                        .translatable("msg.all_spirit_continent.skill_cooldown")
                        .withStyle(ChatFormatting.GRAY));
                case NO_SPIRIT -> sp.sendSystemMessage(Component
                        .translatable("msg.all_spirit_continent.skill_no_spirit")
                        .withStyle(ChatFormatting.RED));
                case NO_WUHUN -> sp.sendSystemMessage(Component
                        .translatable("msg.all_spirit_continent.skill_no_wuhun")
                        .withStyle(ChatFormatting.RED));
                case SUCCESS -> { /* 原语粒子/音效已由服务端广播，无需额外反馈 */ }
            }
        });
    }
}
