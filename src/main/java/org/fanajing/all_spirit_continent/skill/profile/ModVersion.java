package org.fanajing.all_spirit_continent.skill.profile;

import net.neoforged.fml.ModList;

/**
 * 模组版本工具（引擎文档 §6.5 版本管理）。
 * <p>
 * 画像序列化时携带当前 mod_version / mc_version 到云端，
 * 拉取时按精确版本匹配 → 否则匹配最近版本 → 都不命中则标记 {@code isStale=true}。
 */
public final class ModVersion {
    public static final String UNKNOWN = "unknown";

    private ModVersion() {
    }

    /** 当前 all_spirit_continent mod 版本（启动期获取一次缓存） */
    public static String current() {
        try {
            var mods = ModList.get().getMods();
            for (var mod : mods) {
                if ("all_spirit_continent".equals(mod.getModId())) {
                    return mod.getVersion().toString();
                }
            }
        } catch (Exception ignored) {
            // 启动早期 ModList 未就绪
        }
        return UNKNOWN;
    }
}