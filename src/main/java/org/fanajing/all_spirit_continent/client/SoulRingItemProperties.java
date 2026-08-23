package org.fanajing.all_spirit_continent.client;

import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.item.ModItems;
import org.fanajing.all_spirit_continent.item.SoulRingItem;
import org.fanajing.all_spirit_continent.util.SoulBeastAge;

/**
 * 魂环物品动态贴图（纯客户端）。
 * 按物品 NBT 中的年限档位注册 ring_tier override（0~5），
 * 物品贴图自动显示对应年限颜色（十年白/百年黄/千年紫/万年黑/十万年红/百万年金）。
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = All_spirit_continent.MODID, bus = EventBusSubscriber.Bus.MOD)
public class SoulRingItemProperties {

    public static final ResourceLocation RING_TIER_PROPERTY =
            ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "ring_tier");

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemProperties.register(
                ModItems.SOUL_RING.get(),
                RING_TIER_PROPERTY,
                (stack, level, entity, seed) -> SoulBeastAge.tierOf(SoulRingItem.getRingAge(stack))
        ));
    }
}
