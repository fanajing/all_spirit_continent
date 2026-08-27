package org.fanajing.all_spirit_continent.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.init.ModAttachments;
import org.fanajing.all_spirit_continent.network.CastSkillPayload;
import org.fanajing.all_spirit_continent.util.PlayerLevelData;

/**
 * V6.0 魂技客户端管理：
 * - 技能释放键（默认 R）：开武魂状态下对当前激活环位发送 CastSkillPayload（服务端权威执行）
 * - 环位切换键（默认 G）：在已获魂环数内循环切换激活环位，actionbar 提示当前环位
 * 激活环位只在 1..已获魂环数 范围内循环；服务端对空环自动回退到第一个已绑定技能的环位。
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = All_spirit_continent.MODID, bus = EventBusSubscriber.Bus.GAME)
public class ClientSkillManager {

    /** 当前激活的魂环位（1-9） */
    private static int activeSlot = 1;
    /** 上次记录的魂环数（用于魂环数变化后校正 activeSlot） */
    private static int lastRingCount = 0;

    private ClientSkillManager() {}

    /** 当前激活环位（供 HUD 等显示） */
    public static int activeSlot() {
        return activeSlot;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        PlayerLevelData data = mc.player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        int ringCount = Math.max(1, data.getRingCount());
        if (ringCount != lastRingCount) {
            lastRingCount = ringCount;
            if (activeSlot > ringCount) activeSlot = 1;
        }

        // 环位切换键（默认 G）：循环切换 1..ringCount
        while (ModKeyMappings.QIE_HUAN_HUAN_WEI.consumeClick()) {
            activeSlot = activeSlot % ringCount + 1;
            mc.player.displayClientMessage(
                    Component.translatable("msg.all_spirit_continent.skill_slot_selected", activeSlot),
                    true);
        }

        // 技能释放键（默认 R）：仅开武魂状态生效
        while (ModKeyMappings.SHI_FANG_JI_NENG.consumeClick()) {
            if (!SoulRingRenderer.isMartialSoulOpen()) {
                mc.player.displayClientMessage(
                        Component.translatable("msg.all_spirit_continent.skill_no_wuhun"),
                        true);
                continue;
            }
            PacketDistributor.sendToServer(new CastSkillPayload(activeSlot));
        }
    }
}
