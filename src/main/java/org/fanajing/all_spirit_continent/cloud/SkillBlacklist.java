package org.fanajing.all_spirit_continent.cloud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地魂技黑名单（引擎文档 §13.4.2：服务端必须有"恶意条目"识别 + 黑名单机制）。
 * <p>
 * 与云端 {@code blacklist.json}（按 wuhun|mobId|segment 组合键）不同，本类按
 * <b>uuid</b> 持久化命中过的恶意条目，写入 {@code config/all_spirit_continent/blacklist_local.json}。
 * <ul>
 *   <li>{@link #add(String, String)}：被 {@link org.fanajing.all_spirit_continent.skill.engine.SkillAnomalyDetector}
 *       命中异常规则的 uuid 加入黑名单（带原因）</li>
 *   <li>{@link #contains(String)}：拉取云端 / 本地推演条目时跳过黑名单 uuid</li>
 *   <li>重启后从磁盘恢复；服务端启动即生效</li>
 * </ul>
 * <p>
 * 文件格式（有序，便于运维 grep）：
 * <pre>{@code
 * {
 *   "version": 1,
 *   "entries": {
 *     "<uuid>": { "reason": "数值溢出：...", "addedAt": 12345678 }
 *   }
 * }
 * }</pre>
 */
public final class SkillBlacklist {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 持久化文件：config/all_spirit_continent/blacklist_local.json */
    private static final Path FILE = FMLPaths.CONFIGDIR.get()
            .resolve("all_spirit_continent").resolve("blacklist_local.json");

    /** uuid → 命中原因；按加入顺序保留便于人工排查 */
    private static final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public record Entry(String reason, long addedAt) {
        public Entry(String reason) {
            this(reason == null ? "" : reason, System.currentTimeMillis() / 1000L);
        }
    }

    static {
        load();
    }

    private SkillBlacklist() {
    }

    /** 启动时从 JSON 恢复；文件不存在/损坏视为空黑名单 */
    private static void load() {
        try {
            if (!Files.exists(FILE)) return;
            JsonObject root = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject obj = root.has("entries") && root.get("entries").isJsonObject()
                    ? root.getAsJsonObject("entries") : new JsonObject();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonObject v = e.getValue().getAsJsonObject();
                String reason = v.has("reason") ? v.get("reason").getAsString() : "";
                long ts = v.has("addedAt") ? v.get("addedAt").getAsLong() : 0L;
                entries.put(e.getKey(), new Entry(reason, ts));
            }
            LOGGER.info("本地魂技黑名单载入: {} 条", entries.size());
        } catch (Exception e) {
            LOGGER.warn("本地魂技黑名单加载失败（使用空黑名单）: {}", e.getClass().getSimpleName());
        }
    }

    /** 写回磁盘；每次新增时同步保存。失败仅警告，不抛异常（黑名单是防御性设施）。 */
    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonObject obj = new JsonObject();
            // 按加入时间排序便于人工排查
            entries.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(
                            java.util.Comparator.comparingLong(Entry::addedAt)))
                    .forEach(e -> {
                        JsonObject v = new JsonObject();
                        v.addProperty("reason", e.getValue().reason());
                        v.addProperty("addedAt", e.getValue().addedAt());
                        obj.add(e.getKey(), v);
                    });
            root.add("entries", obj);
            Files.writeString(FILE, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("本地魂技黑名单保存失败: {}", e.getClass().getSimpleName());
        }
    }

    /** 加入黑名单（重复加入仅更新时间戳） */
    public static void add(String uuid, String reason) {
        if (uuid == null || uuid.isBlank()) return;
        entries.put(uuid, new Entry(reason));
        save();
    }

    /** 该 uuid 是否已被列入黑名单 */
    public static boolean contains(String uuid) {
        if (uuid == null) return false;
        return entries.containsKey(uuid);
    }

    /** 黑名单条数（诊断用） */
    public static int size() {
        return entries.size();
    }

    /** 获取指定 uuid 的命中原因；不存在返回 null */
    public static String reasonOf(String uuid) {
        Entry e = entries.get(uuid);
        return e == null ? null : e.reason();
    }

    /** 清空黑名单（运维命令：/douluo blacklist clear） */
    public static void clear() {
        entries.clear();
        save();
    }

    /** 全量快照（仅调试用） */
    public static Map<String, Entry> snapshot() {
        return new LinkedHashMap<>(entries);
    }

    /** 反序列化辅助：把云端 blacklist.json 的数组格式解析为 uuid 集合（与本地按 uuid 黑名单合并时用） */
    public static Set<String> fromCloudArray(JsonArray arr) {
        Set<String> set = new HashSet<>();
        for (JsonElement e : arr) {
            if (e.isJsonPrimitive()) set.add(e.getAsString());
        }
        return set;
    }
}