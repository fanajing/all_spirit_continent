package org.fanajing.all_spirit_continent.skill.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.fanajing.all_spirit_continent.cloud.OssClient;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 画像云端众包聚合（引擎文档 §6，P1 + §6.3 / §6.4 / §6.5 / §6.6）。
 * <p>
 * 无专用后端时的 OSS 简化协议（与 public_skills.json 同风格）：
 * <ul>
 *   <li>云端对象：{@code profiles/mob_profiles.json}（全量画像数组）</li>
 *   <li>拉取（启动时）：云端画像与本地缓存按 §6.3 聚合规则合并 →
 *       签名计数取 max、战斗定位/主题/声音/粒子关键词取并集、contributorCount 累加</li>
 *   <li>推送（周期/关服）：本地全量画像写回云端（读-合-写，与技能库同竞态模型）</li>
 * </ul>
 *
 * <h4>§6.5 版本管理</h4>
 * <ul>
 *   <li>拉取后按 modVersion 精确比较：相同 → isStale=false</li>
 *   <li>缺失精确版本 → 选取 modVersion 相近的（按版本号字符串 Levenshtein 距离），
 *       fallback 任意版本时 isStale=true</li>
 * </ul>
 *
 * <h4>§6.6 环境隔离</h4>
 * <ul>
 *   <li>profile.environmentHash 不同 → 视为不同整合包画像，本地仅累加同 environmentHash 的</li>
 *   <li>未知（unknown）hash 视为与本端环境一致（容错）</li>
 * </ul>
 *
 * <h4>§6.4 批量上传</h4>
 * <ul>
 *   <li>{@link #pushAllAsync()}：30 分钟节流、忙锁防交错，一次性推送全部本地画像</li>
 *   <li>玩家手动上传（{@code /douluo upload}）也可调用本方法批量同步，避免每条单独 scheduleFlush</li>
 * </ul>
 */
public final class ProfileCloudSync {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CLOUD_KEY = "profiles/mob_profiles.json";
    /** 推送节流（毫秒）：30 分钟内不重复上传 */
    private static final long PUSH_THROTTLE_MS = 30L * 60 * 1000;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static volatile long lastPushAt = 0L;

    private ProfileCloudSync() {
    }

    /**
     * 拉取云端画像并按 §6.3 / §6.5 / §6.6 规则合并进本地缓存（启动时异步执行）。
     * <ol>
     *     <li>按 {@code (mobId, environmentHash)} 分组：相同组合 → 严格合并；不同环境 → 跳过</li>
     *     <li>同环境内：先按 modVersion 精确匹配 → 找不到则选最近版本（且打 stale）</li>
     *     <li>合并：signatureCounts 取 max / 关键词取并集 / contributorCount 累加 / isStale 取或</li>
     *   </ol>
     */
    public static CompletableFuture<Void> pullAndMergeAsync() {
        if (!BUSY.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        OssClient oss = OssClient.fromConfig();
        if (!oss.isConfigured()) {
            BUSY.set(false);
            return CompletableFuture.completedFuture(null);
        }
        return oss.get(CLOUD_KEY)
                .whenComplete((r, e) -> BUSY.set(false))
                .thenAccept(body -> {
                    if (body.isEmpty()) return;
                    try {
                        JsonArray arr = JsonParser.parseString(body.get()).getAsJsonArray();
                        Map<String, List<MobProfile>> remoteByKey = groupByEnv(arr);
                        int merged = 0;
                        int stale = 0;
                        int skipped = 0;
                        for (var entry : remoteByKey.entrySet()) {
                            MobProfile remote = pickBestMatch(entry.getValue());
                            if (remote == null) continue;
                            MobProfile local = ProfileCache.get(remote.mobId());
                            // §6.6：环境完全不同的画像不强行合并（即使 mobId 一致也只跳过云端远端）
                            if (!EnvironmentHash.matches(local.environmentHash(), remote.environmentHash())) {
                                skipped++;
                                continue;
                            }
                            MobProfile mergedProfile = local.merge(remote);
                            ProfileCache.put(mergedProfile);
                            merged++;
                            if (mergedProfile.isStale()) stale++;
                        }
                        if (merged > 0) {
                            LOGGER.info("云端画像聚合完成（§6.3）：合并 {} 个 mob 画像（其中 {} 个打 stale，{} 个跨环境跳过）",
                                    merged, stale, skipped);
                        } else if (skipped > 0) {
                            LOGGER.info("云端画像拉取完成：{} 个 mob 来自不同整合包，未合并（§6.6 环境隔离）", skipped);
                        }
                    } catch (Exception e2) {
                        LOGGER.warn("云端画像解析失败（忽略）: {}", e2.getClass().getSimpleName());
                    }
                })
                .exceptionally(e -> {
                    LOGGER.warn("云端画像拉取失败: {}", e.getClass().getSimpleName());
                    return null;
                });
    }

    /**
     * 本地画像全量推送云端（§6.4 批量上传：30 分钟节流 + 忙锁防止与拉取交错）。
     * OSS 未配置写权限时立即返回。
     */
    public static CompletableFuture<Void> pushAllAsync() {
        OssClient oss = OssClient.fromConfig();
        if (!oss.canWrite()) return CompletableFuture.completedFuture(null);
        long now = System.currentTimeMillis();
        if (now - lastPushAt < PUSH_THROTTLE_MS) return CompletableFuture.completedFuture(null);
        if (!BUSY.compareAndSet(false, true)) return CompletableFuture.completedFuture(null);
        lastPushAt = now;

        JsonArray arr = new JsonArray();
        for (MobProfile profile : ProfileCache.all()) {
            arr.add(ProfileStore.toJson(profile));
        }
        String content = GSON.toJson(arr);
        return oss.put(CLOUD_KEY, content)
                .whenComplete((ok, e) -> BUSY.set(false))
                .thenAccept(ok -> {
                    if (Boolean.TRUE.equals(ok)) {
                        LOGGER.info("云端画像推送完成（§6.4 批量上传）: {} 个 mob 画像", arr.size());
                    }
                });
    }

    /**
     * §6.5：从同 (mobId, environmentHash) 分组的多个候选中挑选最佳：
     * 1) 精确 modVersion 匹配本端 → not stale
     * 2) 否则挑选版本字符串最相近的 → 标 stale
     */
    private static MobProfile pickBestMatch(List<MobProfile> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        String myVer = ModVersion.current();
        MobProfile exact = null;
        MobProfile nearest = candidates.get(0);
        int bestDistance = Integer.MAX_VALUE;
        for (MobProfile c : candidates) {
            if (c.modVersion().equals(myVer)) {
                exact = c;
                break;
            }
            int d = levenshtein(c.modVersion(), myVer);
            if (d < bestDistance) {
                bestDistance = d;
                nearest = c;
            }
        }
        if (exact != null) return exact;
        // 没找到精确版本 → 选 nearest 但打 stale 标记
        return nearest.markStale();
    }

    /** §6.6：按 (mobId, environmentHash) 分组云端数据 */
    private static Map<String, List<MobProfile>> groupByEnv(JsonArray arr) {
        Map<String, List<MobProfile>> out = new HashMap<>();
        for (var el : arr) {
            if (!el.isJsonObject()) continue;
            MobProfile p = ProfileStore.fromJson(el.getAsJsonObject());
            if (p == null) continue;
            String key = p.mobId() + "@" + p.environmentHash();
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        return out;
    }

    /** Levenshtein 距离（用于版本号相似度比较，最大 32 字符预算） */
    private static int levenshtein(String a, String b) {
        if (a == null || b == null) return Integer.MAX_VALUE;
        if (a.equals(b)) return 0;
        int maxLen = 32;
        if (a.length() > maxLen) a = a.substring(0, maxLen);
        if (b.length() > maxLen) b = b.substring(0, maxLen);
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    /** §6.5 工具：按 modVersion 倒序排序（最新优先，便于查 stale 候选） */
    static List<MobProfile> sortedByVersion(List<MobProfile> profiles) {
        List<MobProfile> copy = new ArrayList<>(profiles);
        copy.sort(Comparator.comparing(MobProfile::modVersion).reversed());
        return copy;
    }

    /** §6.3 调试：转 JSON 输出聚合上下文 */
    public static String debugSnapshot() {
        JsonArray arr = new JsonArray();
        for (MobProfile p : ProfileCache.all()) {
            JsonObject o = ProfileStore.toJson(p);
            o.addProperty("_debug_contributor_count", p.contributorCount());
            o.addProperty("_debug_is_stale", p.isStale());
            arr.add(o);
        }
        return GSON.toJson(arr);
    }
}