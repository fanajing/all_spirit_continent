package org.fanajing.all_spirit_continent.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.entity.ModEntities;

/**
 * 客户端实体渲染器注册中心（纯客户端）。
 * 魂环实体：SoulRingEntityRenderer（地面旋转魂环 + 环上方年限文字）。
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = All_spirit_continent.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ClientEntityRenderers {

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.SOUL_RING_ENTITY.get(), SoulRingEntityRenderer::new);
    }
}
