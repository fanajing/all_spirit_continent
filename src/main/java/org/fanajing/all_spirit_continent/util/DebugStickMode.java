package org.fanajing.all_spirit_continent.util;

import net.minecraft.network.chat.Component;

/**
 * 调试棒功能模式枚举。
 * 玩家手持调试棒 + 潜行 + 滚轮可以循环切换模式，
 * 不同模式下右键触发不同的调试功能。
 * 后期可在此处继续扩展新模式（如获取武魂、获取魂技等）。
 */
public enum DebugStickMode {
    /** 无功能（默认） */
    NONE("none"),
    /** 获取魂环模式：右键获得一个魂环（等级+10，最高100级） */
    SOUL_RING("soul_ring"),
    /** 移除魂环模式：右键移除一个魂环（等级-10，始终从最外层往内减少） */
    REMOVE_SOUL_RING("remove_soul_ring"),
    /** 调节环大小模式：滚轮微调最大魂环半径，Alt+长按右键导出大小配方到桌面 */
    RESIZE_RING("resize_ring"),
    /** 开武魂模式：右键播放开武魂动画（魂环从头部依次释放到脚下） */
    KAI_WU_HUN("kai_wu_hun"),
    /** 动画选项模式：滚轮切换开武魂动画类型（动画1/动画2） */
    SOUL_ANIM("soul_anim"),
    /** 年限修改模式：右键魂环年限+1档（换一次颜色），最高档后回到十年 */
    RING_AGE("ring_age"),
    /** 增减等级模式：右键等级+1，Alt+右键等级-1（1~99） */
    LEVEL("level"),
    /** 查看魂环年限模式：右键列出自己 9 个魂环的年限（无魂环显示无） */
    CHECK_RING_AGE("check_ring_age");

    private final String id;

    DebugStickMode(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    /** 模式显示名称（本地化） */
    public Component getDisplayName() {
        return Component.translatable("mode.all_spirit_continent." + id);
    }

    /** 按名称查找模式，找不到时返回 NONE */
    public static DebugStickMode fromName(String name) {
        for (DebugStickMode mode : values()) {
            if (mode.id.equals(name)) {
                return mode;
            }
        }
        return NONE;
    }

    /** 切换到下一个模式（循环） */
    public DebugStickMode next() {
        DebugStickMode[] modes = values();
        return modes[(ordinal() + 1) % modes.length];
    }

    /** 切换到上一个模式（循环） */
    public DebugStickMode prev() {
        DebugStickMode[] modes = values();
        return modes[(ordinal() - 1 + modes.length) % modes.length];
    }
}
