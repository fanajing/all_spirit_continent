package org.fanajing.all_spirit_continent.skill.profile;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 环境哈希工具（引擎文档 §6.6）。
 * <p>
 * 同一个怪物在不同整合包/Mod 组合下可能表现不同，因此云端画像必须按「环境」分组存储。
 * <p>
 * 哈希输入：当前已加载的 Mod 列表（按 modid 排序），加上当前 Minecraft 版本与 NeoForge 版本，
 * 输出 16 字节 SHA-1 → 32 字符十六进制。同一整合包环境不同机器计算结果应一致。
 * <p>
 * 用途：
 * <ul>
 *   <li>{@link MobProfile#environmentHash}：画像来源整合包指纹</li>
 *   <li>{@link MobProfile#isStale}：跨版本拉取时被标记为「可能过期」</li>
 *   <li>{@link ProfileCloudSync}：按环境隔离存储画像</li>
 * </ul>
 */
public final class EnvironmentHash {
    private EnvironmentHash() {
    }

    /** 未知/默认整合包环境（首次启动未生成时使用） */
    public static final String UNKNOWN = "unknown";

    private static volatile String cachedHash = null;

    /** 当前整合包环境哈希（启动时计算一次缓存） */
    public static String current() {
        String h = cachedHash;
        if (h != null) return h;
        synchronized (EnvironmentHash.class) {
            h = cachedHash;
            if (h != null) return h;
            h = compute();
            cachedHash = h;
            return h;
        }
    }

    /**
     * 强制重算（用于单元测试或热加载 Mod 后刷新缓存）；
     * 生产环境一般通过 {@link #current()} 懒计算一次即可。
     */
    public static String recompute() {
        synchronized (EnvironmentHash.class) {
            cachedHash = compute();
            return cachedHash;
        }
    }

    /** 实际计算 SHA-1（按 modid 排序的已加载 Mod 列表 + MC 版本 + NeoForge 版本） */
    private static String compute() {
        try {
            List<String> lines = new ArrayList<>();
            for (var mod : ModList.get().getMods()) {
                lines.add(mod.getModId() + "@" + mod.getVersion().toString());
            }
            // 加 MC 版本与 NeoForge 版本作为弱盐（不同 MC 版本大版本互不污染）
            lines.add("mc=" + McVersion.current());
            try {
                Object v = Class.forName("net.neoforged.fml.loading.FMLLoader")
                        .getMethod("getNeoForgeVersion").invoke(null);
                if (v != null) lines.add("loader=" + v);
            } catch (Throwable ignored) {
                // launcher info not available in every env
            }
            lines.sort(Comparator.naturalOrder());
            String joined = String.join("\n", lines);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(joined.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return UNKNOWN;
        }
    }

    /** 是否两个哈希相等（兼容「unknown」视为相同） */
    public static boolean matches(String a, String b) {
        if (a == null || b == null) return false;
        if (UNKNOWN.equals(a) || UNKNOWN.equals(b)) return true;  // 容错：未计算 → 视为同环境
        return a.equals(b);
    }
}