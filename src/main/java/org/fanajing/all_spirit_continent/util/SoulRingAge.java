package org.fanajing.all_spirit_continent.util;

import net.minecraft.resources.ResourceLocation;
import org.fanajing.all_spirit_continent.All_spirit_continent;

/**
 * 魂环年限体系（颜色档位）。
 * 贴图位于 textures/entity/，共 6 档，与斗罗大陆年限颜色对应：
 *   0 = 十年（白）  1 = 百年（黄）  2 = 千年（紫）
 *   3 = 万年（黑）  4 = 十万年（红） 5 = 百万年（金）
 * 默认全部魂环为 10 年（档位 0），调试棒「年限修改」右键 +1 循环。
 */
public final class SoulRingAge {

    /** 年限档位数量（与贴图数量一致） */
    public static final int AGE_COUNT = 6;

    /** 各档位贴图文件名（必须全小写：资源路径不允许大写字母） */
    private static final String[] TEXTURE_NAMES = {
            "soul_ring_decade.png",          // 0: 十年
            "soul_ring_century.png",         // 1: 百年
            "soul_ring_millennium.png",      // 2: 千年
            "soul_ring_decamillennium.png",  // 3: 万年
            "soul_ring_centimillennium.png", // 4: 十万年
            "soul_ring_megennium.png"        // 5: 百万年
    };

    private SoulRingAge() {}

    /** 年限 → 魂环贴图路径（内部按 tierOf 转档位，支持非整数级年限如 6070 年） */
    public static ResourceLocation getTexture(int age) {
        return getTierTexture(SoulBeastAge.tierOf(age));
    }

    /** 年限档位（0~5）→ 魂环贴图路径（魂兽渲染等按档位取贴图的场景） */
    public static ResourceLocation getTierTexture(int tier) {
        int safe = ((tier % AGE_COUNT) + AGE_COUNT) % AGE_COUNT;
        return ResourceLocation.fromNamespaceAndPath(
                All_spirit_continent.MODID, "textures/entity/" + TEXTURE_NAMES[safe]);
    }

    /** 年限 → 显示名翻译键（语言文件 ring_age_0 ~ ring_age_5，内部按 tierOf 转档位） */
    public static String getDisplayKey(int age) {
        return "msg.all_spirit_continent.ring_age_" + SoulBeastAge.tierOf(age);
    }
}
