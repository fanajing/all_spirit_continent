package org.fanajing.all_spirit_continent.util;

/**
 * 魂环布局计算（公共工具类）。
 * 客户端渲染（SoulRingRenderer）与服务端移除粒子（TiaoShiBangItem）
 * 共用此处的半径表，保证视觉一致、避免硬编码不同步。
 *
 * 布局规则：
 *  - 每环默认半径由 DEFAULT_RADII 精确指定（玩家在游戏内调节并导出的配方）
 *  - 调试棒「调节环大小」模式可临时覆盖某环半径（仅存内存，重启失效）
 */
public final class SoulRingLayout {
    /** 魂环数量上限（与等级系统一致） */
    public static final int MAX_RINGS = 10;

    /**
     * 每环默认半径表（格）：第 i+1 环的默认半径 = DEFAULT_RADII[i]。
     * 2026-08-14 由玩家在游戏内调节并导出的配方（soul_ring_recipe.json）正式设定。
     */
    private static final float[] DEFAULT_RADII = {
            1.10F, 1.85F, 2.70F, 3.85F, 5.60F,
            7.95F, 11.10F, 15.95F, 21.90F, 30.75F
    };

    private SoulRingLayout() {}

    // ===== 每环独立调节覆盖（调试棒「调节环大小」模式） =====

    /**
     * 每环独立调节覆盖：RING_OVERRIDES[index] 为第 index+1 环的调节半径（格），
     * null 表示该环使用默认布局。玩家获取新魂环后只调新环，旧环的调节值保留。
     * 注：单人集成服务端与客户端同 JVM，静态字段共享，移除粒子也能读到。
     */
    private static final Float[] RING_OVERRIDES = new Float[MAX_RINGS];

    public static Float getOverride(int index) {
        if (index < 0 || index >= RING_OVERRIDES.length) return null;
        return RING_OVERRIDES[index];
    }

    public static void setOverride(int index, Float value) {
        if (index < 0 || index >= RING_OVERRIDES.length) return;
        RING_OVERRIDES[index] = value;
    }

    /** 调节范围：最小/最大半径（格） */
    public static final float MIN_RADIUS = 0.5F;
    public static final float MAX_RADIUS = 50.0F;

    // ===== 半径计算 =====

    /** 计算第 index（从 0 开始）个魂环的默认半径（格）；越界时取最接近的有效值 */
    public static float getRadius(int index) {
        if (index < 0) return DEFAULT_RADII[0];
        if (index >= DEFAULT_RADII.length) return DEFAULT_RADII[DEFAULT_RADII.length - 1];
        return DEFAULT_RADII[index];
    }

    /**
     * 计算第 index（从 0 开始）个魂环的最终半径（格）：
     * 该环有临时调节覆盖值时用调节值，否则用 DEFAULT_RADII 的默认值。
     */
    public static float getScaledRadius(int index) {
        Float override = getOverride(index);
        return override != null ? override : getRadius(index);
    }
}
