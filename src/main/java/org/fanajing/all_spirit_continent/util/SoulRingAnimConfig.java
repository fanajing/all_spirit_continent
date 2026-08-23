package org.fanajing.all_spirit_continent.util;

/**
 * 开武魂动画类型配置（纯数据类，客户端滚轮切换、服务端读值、渲染器按值分支）。
 * 注：单人集成服务端与客户端同 JVM，静态字段共享；调节值仅存内存，重启丢失。
 */
public final class SoulRingAnimConfig {

    /** 动画类型数量 */
    public static final int ANIM_COUNT = 2;

    /** 动画类型 id：0 = 动画1（头部释放下移），1 = 动画2（脚底膨胀依次出现） */
    private static int currentAnimId = 0;

    private SoulRingAnimConfig() {}

    public static int getCurrentAnimId() {
        return currentAnimId;
    }

    public static void setCurrentAnimId(int id) {
        currentAnimId = ((id % ANIM_COUNT) + ANIM_COUNT) % ANIM_COUNT;
    }

    public static void next() {
        setCurrentAnimId(currentAnimId + 1);
    }

    public static void prev() {
        setCurrentAnimId(currentAnimId - 1);
    }

    /** 动画类型的显示名翻译键（语言文件 soul_anim_type_1 / soul_anim_type_2 ...） */
    public static String getDisplayKey(int id) {
        int safeId = ((id % ANIM_COUNT) + ANIM_COUNT) % ANIM_COUNT;
        return "msg.all_spirit_continent.soul_anim_type_" + (safeId + 1);
    }
}
