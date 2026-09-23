package org.fanajing.all_spirit_continent.skill.profile;

import net.neoforged.fml.loading.FMLLoader;

/**
 * Minecraft 版本工具（引擎文档 §6.5 版本管理）。
 */
public final class McVersion {
    public static final String UNKNOWN = "unknown";

    private McVersion() {
    }

    public static String current() {
        // NeoForge 21.1 编译期 API 取 MC 版本可能在不同子版本位置；
        // 反射 fallback + catch-all 避免阻塞业务
        try {
            Class<?> sc = Class.forName("net.minecraft.SharedConstants");
            for (String name : new String[]{"VERSION_NAME", "VERSION_STRING", "VERSION"}) {
                try {
                    Object v = sc.getField(name).get(null);
                    if (v instanceof String s && !s.isEmpty()) return s;
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Object v = Class.forName("net.minecraft.DetectedVersion")
                    .getMethod("getCurrentVersion").invoke(null);
            Object id = v.getClass().getMethod("getId").invoke(v);
            if (id != null) return id.toString();
        } catch (Throwable ignored) {
        }
        return UNKNOWN;
    }
}