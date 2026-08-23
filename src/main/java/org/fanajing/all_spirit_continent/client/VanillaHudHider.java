package org.fanajing.all_spirit_continent.client;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.init.ModAttachments;

/**
 * 等级系统激活后，隐藏原版的血量、护甲、饱食度 HUD，
 * 因为这些信息已集成到自定义面板中。
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = All_spirit_continent.MODID, bus = EventBusSubscriber.Bus.GAME)
public class VanillaHudHider {

    private static final ResourceLocation PLAYER_HEALTH =
            ResourceLocation.fromNamespaceAndPath("minecraft", "player_health");
    private static final ResourceLocation ARMOR_LEVEL =
            ResourceLocation.fromNamespaceAndPath("minecraft", "armor_level");
    private static final ResourceLocation FOOD_LEVEL =
            ResourceLocation.fromNamespaceAndPath("minecraft", "food_level");

    @SubscribeEvent
    public static void onRenderGuiLayerPre(RenderGuiLayerEvent.Pre event) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;

        var data = mc.player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        if (!data.isActivated()) return;

        ResourceLocation name = event.getName();
        if (name.equals(PLAYER_HEALTH) || name.equals(ARMOR_LEVEL) || name.equals(FOOD_LEVEL)) {
            event.setCanceled(true);
        }
    }
}
