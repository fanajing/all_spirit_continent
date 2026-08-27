package org.fanajing.all_spirit_continent.cloud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import org.fanajing.all_spirit_continent.config.CloudConfig;
import org.fanajing.all_spirit_continent.skill.RatingStats;
import org.fanajing.all_spirit_continent.skill.SkillEntry;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 云端同步服务：OSS 上的 public_skills.json / blacklist.json / version.json 与本地缓存的双向同步。
 * <ul>
 *   <li>启动：读本地离线快照 → 异步拉取 version.json 比对 → 全量拉取魂技库与黑名单</li>
 *   <li>定时：按配置间隔增量拉取；如有本地改动（评分/上传）则合并写回 OSS（读-改-写回防覆盖）</li>
 *   <li>查询：内存缓存优先，离线可用；PENDING 条目不参与抽取（由调用方过滤）</li>
 * </ul>
 * 所有网络 I/O 走单线程 daemon 执行器，绝不阻塞主线程。
 */
public class CloudSyncService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final String FILE_PUBLIC_SKILLS = "public_skills.json";
    public static final String FILE_BLACKLIST = "blacklist.json";
    public static final String FILE_VERSION = "version.json";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ASC-CloudSync");
        t.setDaemon(true);
        return t;
    });

    // ===== 内存缓存（volatile 引用替换保证并发安全）=====
    private static volatile List<SkillEntry> cachedSkills = List.of();
    private static volatile Set<String> cachedBlacklist = Set.of();
    private static volatile long cachedVersion = -1;
    /** 本地改动集（uuid → 改动后条目），写回成功即清空 */
    private static final Map<String, SkillEntry> localOverrides = new ConcurrentHashMap<>();
    /** 初始同步是否完成（供感应流程判断是否可查云端数据） */
    private static volatile boolean ready = false;

    private static volatile ServerLevel currentLevel;
    private static volatile ScheduledFuture<?> syncTask;
    private static volatile ScheduledExecutorService scheduler;
    /** 延迟合并写回任务（本地改动后 10 秒内触发一次，多次改动合并为一次） */
    private static volatile ScheduledFuture<?> pendingFlush;
    /** 最近一次写回结果（供 /douluo status 诊断显示） */
    private static volatile String lastWriteStatus = "尚未尝试（有本地改动后自动触发）";

    private CloudSyncService() {
    }

    // ===== 生命周期（主类调用）=====

    public static void onServerStarted(ServerLevel level) {
        currentLevel = level;
        // 先读本地离线快照，保证服务端启动即可离线查询
        EXECUTOR.submit(() -> {
            loadSnapshot();
            pullAll();
        });
        int interval = Math.max(30, CloudConfig.SYNC_INTERVAL_SECONDS.get());
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ASC-CloudScheduler");
            t.setDaemon(true);
            return t;
        });
        syncTask = scheduler.scheduleWithFixedDelay(CloudSyncService::syncCycle,
                interval, interval, TimeUnit.SECONDS);
    }

    public static void onServerStopped() {
        if (syncTask != null) syncTask.cancel(false);
        if (scheduler != null) scheduler.shutdownNow();
        // 停止前尽力写回
        try {
            EXECUTOR.submit(CloudSyncService::flush).get(15, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        currentLevel = null;
    }

    // ===== 拉取 =====

    private static void syncCycle() {
        pullIncremental();
        if (!localOverrides.isEmpty()) flush();
    }

    private static void pullAll() {
        OssClient oss = OssClient.fromConfig();
        if (!oss.isConfigured()) {
            LOGGER.warn("OSS 未配置，跳过云端拉取（使用本地快照）");
            ready = true;
            return;
        }
        try {
            // version.json
            Optional<String> versionOpt = oss.get(FILE_VERSION).get(20, TimeUnit.SECONDS);
            long version = versionOpt.flatMap(CloudSyncService::parseVersion).orElse(-1L);
            if (versionOpt.isPresent()) {
                cachedVersion = version;
            }
            // public_skills.json
            Optional<String> skillsOpt = oss.get(FILE_PUBLIC_SKILLS).get(20, TimeUnit.SECONDS);
            skillsOpt.ifPresent(body -> {
                // 按本机已安装 mod 过滤：依赖未安装 mod 的魂技不下载（内存与本地快照都只保留可用条目）
                List<SkillEntry> list = parseSkills(body, true);
                cachedSkills = list;
                saveSnapshotFile(FILE_PUBLIC_SKILLS, serializeEntries(list));
                LOGGER.info("云端魂技库已加载: {} 条", list.size());
            });
            // blacklist.json
            Optional<String> blackOpt = oss.get(FILE_BLACKLIST).get(20, TimeUnit.SECONDS);
            blackOpt.ifPresent(body -> {
                cachedBlacklist = parseBlacklist(body);
                saveSnapshotFile(FILE_BLACKLIST, body);
                LOGGER.info("云端黑名单已加载: {} 条", cachedBlacklist.size());
            });
            ready = true;
        } catch (Exception e) {
            LOGGER.warn("云端拉取失败: {}", e.getClass().getSimpleName());
            ready = true;
        }
    }

    private static void pullIncremental() {
        if (currentLevel == null) return;
        OssClient oss = OssClient.fromConfig();
        if (!oss.isConfigured()) return;
        try {
            Optional<String> versionOpt = oss.get(FILE_VERSION).get(20, TimeUnit.SECONDS);
            long version = versionOpt.flatMap(CloudSyncService::parseVersion).orElse(-1L);
            if (versionOpt.isEmpty()) return;
            if (version == cachedVersion) return; // 无变化
            cachedVersion = version;
            Optional<String> skillsOpt = oss.get(FILE_PUBLIC_SKILLS).get(20, TimeUnit.SECONDS);
            skillsOpt.ifPresent(body -> {
                // 按本机已安装 mod 过滤（本地改动由本机产生，视为可用，不过滤）
                List<SkillEntry> list = parseSkills(body, true);
                cachedSkills = applyOverrides(list);
                saveSnapshotFile(FILE_PUBLIC_SKILLS, serializeEntries(cachedSkills));
                LOGGER.info("云端魂技库增量同步完成: {} 条", cachedSkills.size());
            });
            Optional<String> blackOpt = oss.get(FILE_BLACKLIST).get(20, TimeUnit.SECONDS);
            blackOpt.ifPresent(body -> {
                cachedBlacklist = parseBlacklist(body);
                saveSnapshotFile(FILE_BLACKLIST, body);
            });
        } catch (Exception e) {
            LOGGER.warn("云端增量同步失败: {}", e.getClass().getSimpleName());
        }
    }

    /** 云端列表 + 本地改动集 合并 */
    private static List<SkillEntry> applyOverrides(List<SkillEntry> cloudList) {
        if (localOverrides.isEmpty()) return cloudList;
        Map<String, SkillEntry> merged = new HashMap<>();
        for (SkillEntry e : cloudList) merged.put(e.uuid(), e);
        merged.putAll(localOverrides);
        return List.copyOf(merged.values());
    }

    /** 写回：读最新云端 → 合并本地改动 → PUT（读-改-写回降低并发覆盖） */
    private static void flush() {
        if (currentLevel == null || localOverrides.isEmpty()) return;
        OssClient oss = OssClient.fromConfig();
        if (!oss.canWrite()) {
            LOGGER.warn("无法写回云端：未配置 OSS AccessKey（{} 条本地改动保留在内存）", localOverrides.size());
            lastWriteStatus = "失败：未配置 OSS AccessKey（请用环境变量 ASC_OSS_ACCESS_KEY_ID/SECRET 或 config 注入）";
            return;
        }
        try {
            Map<String, SkillEntry> merged = new HashMap<>();
            Optional<String> cloud = oss.get(FILE_PUBLIC_SKILLS).get(20, TimeUnit.SECONDS);
            if (cloud.isPresent()) {
                // 写回合并必须看全量（不过滤），否则会把未安装 mod 的条目从云端覆盖丢失
                for (SkillEntry e : parseSkills(cloud.get(), false)) {
                    merged.put(e.uuid(), e);
                }
            }
            merged.putAll(localOverrides);

            long nowTicks = currentLevel.getGameTime();
            JsonArray array = new JsonArray();
            for (SkillEntry e : merged.values()) {
                // 重算池（评分变化驱动升降级）
                String pool = e.stats().determinePool(nowTicks);
                if (!pool.equals(e.pool())) {
                    e = new SkillEntry(e.skill(), pool, e.stats(), e.uploaderHash(), e.uploaderName());
                }
                array.add(e.toCloudJson());
            }
            String body = GSON.toJson(array);
            boolean ok = oss.put(FILE_PUBLIC_SKILLS, body).get(30, TimeUnit.SECONDS);
            if (ok) {
                localOverrides.clear();
                // 写回云端的是全量；本地内存与快照仍按本机已安装 mod 过滤
                List<SkillEntry> filtered = filterByInstalledMods(List.copyOf(merged.values()));
                cachedSkills = filtered;
                saveSnapshotFile(FILE_PUBLIC_SKILLS, serializeEntries(filtered));
                LOGGER.info("云端写回成功: {} 条改动已同步", merged.size());
                lastWriteStatus = "成功（" + merged.size() + " 条已同步）";
            } else {
                lastWriteStatus = "失败：OSS PUT 被拒绝（HTTP 非 200/204，详见日志）";
            }
        } catch (Exception e) {
            LOGGER.warn("云端写回失败: {}", e.getClass().getSimpleName());
            lastWriteStatus = "失败：" + e.getClass().getSimpleName() + "（详见日志）";
        }
    }

    /** 当前 OSS 是否可写（供 status 诊断） */
    public static boolean ossWritable() {
        return OssClient.fromConfig().canWrite();
    }

    /** 最近一次写回结果（供 status 诊断） */
    public static String lastWriteStatus() {
        return lastWriteStatus;
    }

    // ===== 查询接口 =====

    public static boolean isReady() {
        return ready;
    }

    /** 是否命中黑名单组合（武魂|怪物|血量档位） */
    public static boolean isBlacklisted(String wuhun, String mobId, String mobHealthSegment) {
        String key = wuhun + "|" + mobId + "|" + mobHealthSegment;
        return cachedBlacklist.contains(key);
    }

    /** 精确匹配组合键的可用技能（剔除 PENDING），本地改动优先 */
    public static List<SkillEntry> findSkills(String wuhun, String mobId, String mobHealthSegment) {
        List<SkillEntry> result = new ArrayList<>();
        for (SkillEntry e : mergedSkills()) {
            if (e.isPending()) continue;
            if (matches(e.skill().wuhun(), e.skill().mobId(), e.skill().mobHealthSegment(),
                    wuhun, mobId, mobHealthSegment)) {
                result.add(e);
            }
        }
        return result;
    }

    /** 按 uuid 查找本地技能（含 PENDING，含本地改动） */
    public static Optional<SkillEntry> findById(String uuid) {
        SkillEntry override = localOverrides.get(uuid);
        if (override != null) return Optional.of(override);
        for (SkillEntry e : cachedSkills) {
            if (e.uuid().equals(uuid)) return Optional.of(e);
        }
        return Optional.empty();
    }

    /** 当前可用技能总数（调试/验证用） */
    public static int cachedSkillCount() {
        return cachedSkills.size();
    }

    // ===== 本地改动（上传/评分）=====

    /** 新增/更新一条魂技（上传/推演结果入本地库并排队写回） */
    public static void queueSkill(SkillEntry entry) {
        if (entry == null) return;
        localOverrides.put(entry.uuid(), entry);
        scheduleFlush();
    }

    /** 更新某条魂技的评分统计（评分/撤回后调用），池随评分重算 */
    public static void queueStatsUpdate(String uuid, RatingStats stats) {
        SkillEntry existing = findById(uuid).orElse(null);
        if (existing == null) return;
        long nowTicks = currentLevel != null ? currentLevel.getGameTime() : 0;
        String pool = stats.determinePool(nowTicks);
        SkillEntry updated = new SkillEntry(existing.skill(), pool, stats, existing.uploaderHash(), existing.uploaderName());
        localOverrides.put(uuid, updated);
        scheduleFlush();
    }

    /**
     * 延迟合并写回：本地有改动后 10 秒内触发一次 flush，
     * 玩家评分/上传/推演后不必等待 300 秒同步周期即可上传云端。
     */
    private static void scheduleFlush() {
        ScheduledExecutorService s = scheduler;
        if (s == null || s.isShutdown()) return;
        if (pendingFlush != null) pendingFlush.cancel(false);
        pendingFlush = s.schedule(CloudSyncService::flush, 10, TimeUnit.SECONDS);
    }

    // ===== 工具 =====

    private static List<SkillEntry> mergedSkills() {
        if (localOverrides.isEmpty()) return cachedSkills;
        return applyOverrides(cachedSkills);
    }

    private static boolean matches(String a, String b, String c, String wuhun, String mobId, String segment) {
        return (wuhun == null || wuhun.isEmpty() || a.equals(wuhun))
                && (mobId == null || mobId.isEmpty() || b.equals(mobId))
                && (segment == null || segment.isEmpty() || c.equals(segment));
    }

    /** 解析云端条目；filterByInstalledMods=true 时丢弃「依赖未安装 mod」的条目 */
    private static List<SkillEntry> parseSkills(String body, boolean filterByInstalledMods) {
        List<SkillEntry> list = new ArrayList<>();
        try {
            JsonElement el = JsonParser.parseString(body);
            if (el.isJsonArray()) {
                for (JsonElement e : el.getAsJsonArray()) {
                    if (e.isJsonObject()) {
                        SkillEntry entry = SkillEntry.fromCloudJson(e.getAsJsonObject());
                        if (entry != null
                                && (!filterByInstalledMods || allRequiredModsInstalled(entry.skill().requiresMods()))) {
                            list.add(entry);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("public_skills.json 解析失败: {}", e.getClass().getSimpleName());
        }
        return list;
    }

    /** 条目的依赖 mod 是否全部已安装（requires_mods 为空 = 仅原版，恒通过） */
    private static boolean allRequiredModsInstalled(List<String> requiresMods) {
        if (requiresMods == null || requiresMods.isEmpty()) return true;
        for (String mod : requiresMods) {
            if (mod.isBlank()) continue;
            if (!ModList.get().isLoaded(mod.trim())) {
                return false;
            }
        }
        return true;
    }

    /** 按本机已安装 mod 过滤条目列表 */
    private static List<SkillEntry> filterByInstalledMods(List<SkillEntry> list) {
        List<SkillEntry> out = new ArrayList<>();
        for (SkillEntry e : list) {
            if (allRequiredModsInstalled(e.skill().requiresMods())) out.add(e);
        }
        return List.copyOf(out);
    }

    /** 条目列表 → JSON body（用于保存过滤后的本地快照） */
    private static String serializeEntries(List<SkillEntry> list) {
        JsonArray array = new JsonArray();
        for (SkillEntry e : list) array.add(e.toCloudJson());
        return GSON.toJson(array);
    }

    private static Set<String> parseBlacklist(String body) {
        Set<String> set = new HashSet<>();
        try {
            JsonElement el = JsonParser.parseString(body);
            if (el.isJsonArray()) {
                for (JsonElement e : el.getAsJsonArray()) {
                    if (e.isJsonPrimitive()) set.add(e.getAsString());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("blacklist.json 解析失败: {}", e.getClass().getSimpleName());
        }
        return set;
    }

    private static Optional<Long> parseVersion(String body) {
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (obj.has("version")) return Optional.of(obj.get("version").getAsLong());
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    // ===== 本地快照文件（离线查询）=====

    private static Path cacheDir() {
        if (currentLevel == null) return Path.of("data", "all_spirit_continent");
        // SavedData 数据目录 = 世界根目录/data/，LevelResource 未提供 DATA_DIR 常量
        return currentLevel.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve("all_spirit_continent");
    }

    private static void loadSnapshot() {
        try {
            Path dir = cacheDir();
            if (!Files.exists(dir)) return;
            Path skills = dir.resolve(FILE_PUBLIC_SKILLS);
            if (Files.exists(skills)) {
                String body = Files.readString(skills, StandardCharsets.UTF_8);
                cachedSkills = parseSkills(body, true);
                LOGGER.info("本地快照魂技库: {} 条", cachedSkills.size());
            }
            Path black = dir.resolve(FILE_BLACKLIST);
            if (Files.exists(black)) {
                cachedBlacklist = parseBlacklist(Files.readString(black, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            LOGGER.warn("本地快照读取失败: {}", e.getClass().getSimpleName());
        }
    }

    private static void saveSnapshotFile(String name, String body) {
        try {
            Path dir = cacheDir();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name), body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("本地快照写入失败: {} ({})", name, e.getClass().getSimpleName());
        }
    }
}
