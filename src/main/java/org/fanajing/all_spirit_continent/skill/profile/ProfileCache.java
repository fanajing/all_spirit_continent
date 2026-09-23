package org.fanajing.all_spirit_continent.skill.profile;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mob 画像本地缓存（进程级 ConcurrentHashMap）。
 * <p>
 * 接口：
 * <ul>
 *   <li>{@link #get(String)}：按 mobId 读取画像；未观察到时返回 {@link MobProfile#empty(String)} 占位</li>
 *   <li>{@link #put(MobProfile)}：写入/覆盖画像（仅在 observed=true 时）</li>
 *   <li>{@link #containsObserved(String)}：是否已有真实观察数据</li>
 *   <li>{@link #clear()}：清空缓存（测试用）</li>
 * </ul>
 * 当前（P0）不持久化到磁盘；P1 可加 saveToJson/loadFromJson 走 mod 配置目录。
 */
public final class ProfileCache {
    private static final Map<String, MobProfile> CACHE = new ConcurrentHashMap<>();

    private ProfileCache() {
    }

    /**
     * 按 mobId 读取画像。未观察到时返回 {@link MobProfile#empty(String)} 占位（never null）。
     */
    public static MobProfile get(String mobId) {
        if (mobId == null || mobId.isBlank()) return MobProfile.empty("");
        String key = mobId.toLowerCase(Locale.ROOT);
        MobProfile p = CACHE.get(key);
        return p != null ? p : MobProfile.empty(key);
    }

    /** 写入/覆盖画像。空画像（observed=false）会被忽略，避免污染缓存 */
    public static void put(MobProfile profile) {
        if (profile == null || !profile.observed()) return;
        CACHE.put(profile.mobId().toLowerCase(Locale.ROOT), profile);
    }

    /** 是否有真实观察数据（区别于空占位） */
    public static boolean containsObserved(String mobId) {
        if (mobId == null) return false;
        MobProfile p = CACHE.get(mobId.toLowerCase(Locale.ROOT));
        return p != null && p.observed();
    }

    /** 读取 Optional（不返回占位） */
    public static Optional<MobProfile> getIfObserved(String mobId) {
        if (mobId == null) return Optional.empty();
        MobProfile p = CACHE.get(mobId.toLowerCase(Locale.ROOT));
        return p != null && p.observed() ? Optional.of(p) : Optional.empty();
    }

    /** 清空缓存（仅测试使用） */
    public static void clear() {
        CACHE.clear();
    }

    /** 当前已观察 mob 数（用于调试/统计） */
    public static int observedCount() {
        return CACHE.size();
    }

    /** 全部已观察画像快照（持久化保存用，与缓存隔离） */
    public static java.util.Collection<MobProfile> all() {
        return List.copyOf(CACHE.values());
    }

    /** 写入/合并画像（云端聚合用）：与已有画像 merge 而非覆盖 */
    public static void merge(MobProfile profile) {
        if (profile == null || !profile.observed()) return;
        CACHE.merge(profile.mobId().toLowerCase(Locale.ROOT), profile, MobProfile::merge);
    }
}