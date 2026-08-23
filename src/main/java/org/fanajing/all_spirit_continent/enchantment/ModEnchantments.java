package org.fanajing.all_spirit_continent.enchantment;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.enchantment.Enchantment;
import org.fanajing.all_spirit_continent.All_spirit_continent;

/**
 * 模组附魔（数据驱动注册）。
 * 1.21.1 附魔是动态注册表（由数据包 data/&lt;modid&gt;/enchantment/*.json 加载），
 * NeoForge 的 RegisterEvent 只对静态注册表（BuiltInRegistries）发出，
 * DeferredRegister 对附魔静默无效 —— 实际定义见
 * data/all_spirit_continent/enchantment/soul_vision.json。
 * 代码中需要附魔 Holder 时通过注册表 lookup 按 SOUL_VISION_KEY 获取。
 */
public class ModEnchantments {

    /**
     * 魂视：只能附在头盔上，玩家穿戴后准星指向魂兽即可看到详细信息面板。
     */
    public static final ResourceKey<Enchantment> SOUL_VISION_KEY =
            ResourceKey.create(Registries.ENCHANTMENT,
                    ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "soul_vision"));
}
