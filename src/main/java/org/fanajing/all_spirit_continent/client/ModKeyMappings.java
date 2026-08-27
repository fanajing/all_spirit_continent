package org.fanajing.all_spirit_continent.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.fanajing.all_spirit_continent.All_spirit_continent;

/**
 * 模组按键绑定注册（客户端）。
 *  - 在按键绑定界面新开「全魂大陆」专属分类，后续所有快捷键都放这里
 *  - 开/关武魂：默认 N 键，可在原版按键绑定界面修改为任意按键
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = All_spirit_continent.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ModKeyMappings {

    /** 本模组专属按键分类（显示名走语言键） */
    public static final String CATEGORY = "key.categories.all_spirit_continent";

    /** 开/关武魂（默认 N 键） */
    public static final KeyMapping KAI_WU_HUN = new KeyMapping(
            "key.all_spirit_continent.kai_wu_hun",
            InputConstants.KEY_N,
            CATEGORY
    );

    /** V6.0 释放魂技（默认 R 键，开武魂状态下对当前激活环位生效） */
    public static final KeyMapping SHI_FANG_JI_NENG = new KeyMapping(
            "key.all_spirit_continent.cast_skill",
            InputConstants.KEY_R,
            CATEGORY
    );

    /** V6.0 切换激活魂环位（默认 G 键，在已获魂环数内循环） */
    public static final KeyMapping QIE_HUAN_HUAN_WEI = new KeyMapping(
            "key.all_spirit_continent.cycle_ring",
            InputConstants.KEY_G,
            CATEGORY
    );

    private ModKeyMappings() {}

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(KAI_WU_HUN);
        event.register(SHI_FANG_JI_NENG);
        event.register(QIE_HUAN_HUAN_WEI);
    }
}
