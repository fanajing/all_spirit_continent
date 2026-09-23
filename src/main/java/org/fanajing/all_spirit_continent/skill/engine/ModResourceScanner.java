package org.fanajing.all_spirit_continent.skill.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.fanajing.all_spirit_continent.cloud.SkillBlacklist;
import org.fanajing.all_spirit_continent.skill.RatingStats;
import org.fanajing.all_spirit_continent.skill.SkillData;
import org.fanajing.all_spirit_continent.skill.SkillEntry;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 数据驱动 mod 扫描器（引擎文档 §9.8）。
 * <p>
 * 扫描 {@code mods/} 与 {@code coremods/} 下每个 jar 的
 * {@code data/<modid>/skills/<uuid>.json}，把通过校验与异常检测的魂技直接
 * 注入到 {@link org.fanajing.all_spirit_continent.cloud.CloudSyncService} 的内存缓存
 * （等价于"由 mod 作者在安装时上传了一批魂技"，玩家无需联网即可使用整合包作者
 * 预先设计的魂技库）。
 *
 * <h2>JSON 格式</h2>
 * <pre>{@code
 * {
 *   "wuhun": "昊天锤", "mob_id": "minecraft:zombie", "mob_health_segment": "20_30",
 *   "skill_data": { ... 完整 SkillData 字段 ... },
 *   "signature_mechanism": "B"
 * }
 * }</pre>
 * <p>文件名约定 {@code <uuid>.json}；若 {@code skill_data.uuid} 字段存在则优先使用。
 *
 * <h2>集成</h2>
 * {@link #scanAndLoad()} 在 {@code FMLCommonSetupEvent} 阶段调用一次；
 * 结果先写入本地快照（{@code config/all_spirit_continent/mod_skills_cache.json}），
 * 服务端启动时直接由 CloudSyncService 读取。
 */
public class ModResourceScanner {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 扫描根目录候选 */
    private static final String[] SCAN_DIRS = {"mods", "coremods", "mod"};
    /** jar 内资源目录前缀：data/<modid>/skills/ */
    private static final String RESOURCE_PREFIX = "data/";
    private static final String SKILLS_SUFFIX = "/skills/";
    /** 本地快照：避免每次重启都遍历所有 jar */
    private static final Path SNAPSHOT_FILE = FMLPaths.CONFIGDIR.get()
            .resolve("all_spirit_continent").resolve("mod_skills_cache.json");

    /** 上次扫描元数据：jar path → mtime */
    private final Map<String, Long> jarMeta = new HashMap<>();

    private final List<SkillEntry> loaded = new ArrayList<>();

    /** 已加载的本地快照条目数（仅供诊断） */
    public static volatile int lastLoadedCount = 0;

    /**
     * 扫描 mods/coremods 下所有 jar，提取 data/&lt;modid&gt;/skills/*.json。
     */
    public List<SkillEntry> scanAndLoad() {
        loaded.clear();
        jarMeta.clear();
        Path gameDir = FMLPaths.GAMEDIR.get();
        int scannedJars = 0;
        for (String sub : SCAN_DIRS) {
            Path dir = gameDir.resolve(sub);
            if (!Files.isDirectory(dir)) continue;
            try (var stream = Files.list(dir)) {
                for (Path jar : (Iterable<Path>) stream::iterator) {
                    String name = jar.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!name.endsWith(".jar")) continue;
                    scannedJars++;
                    try {
                        long mtime = Files.getLastModifiedTime(jar).toMillis();
                        jarMeta.put(jar.toString(), mtime);
                        scanSingleJar(jar);
                    } catch (Exception e) {
                        LOGGER.warn("扫描 jar 失败（跳过）: {} — {}", jar, e.getClass().getSimpleName());
                    }
                }
            } catch (IOException e) {
                LOGGER.debug("扫描目录 {} 不可访问: {}", dir, e.getClass().getSimpleName());
            }
        }
        lastLoadedCount = loaded.size();
        if (!loaded.isEmpty()) {
            LOGGER.info("mod 数据驱动魂技扫描完成: 扫描 {} jar, 共载入 {} 条", scannedJars, loaded.size());
            saveSnapshot();
        } else if (scannedJars > 0) {
            LOGGER.info("mod 数据驱动扫描: {} 个 jar 内未发现 skills 资源", scannedJars);
        }
        return new ArrayList<>(loaded);
    }

    private void scanSingleJar(Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            List<String> matching = new ArrayList<>();
            for (java.util.Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                JarEntry entry = en.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (!name.startsWith(RESOURCE_PREFIX)) continue;
                int skillsIdx = name.indexOf(SKILLS_SUFFIX);
                if (skillsIdx <= 0) continue;
                if (!name.endsWith(".json")) continue;
                matching.add(name);
            }
            for (String resourcePath : matching) {
                try (InputStream is = jf.getInputStream(jf.getEntry(resourcePath))) {
                    if (is == null) continue;
                    String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    parseResource(resourcePath, body).ifPresent(loaded::add);
                } catch (Exception e) {
                    LOGGER.warn("mod 魂技资源解析失败: {} — {}", resourcePath, e.getClass().getSimpleName());
                }
            }
        }
    }

    /** 解析单个 mod 资源 JSON → SkillEntry；过校验与异常检测 */
    private Optional<SkillEntry> parseResource(String path, String body) {
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            String fallbackUuid = fileName.endsWith(".json")
                    ? fileName.substring(0, fileName.length() - 5)
                    : fileName;
            SkillData data = SkillData.fromCloudJson(obj);
            if (data == null) return Optional.empty();
            // 若 skill_data 内无 uuid 且文件名是合法 uuid，则用文件名作为兜底
            boolean sdHasUuid = obj.has("skill_data") && obj.getAsJsonObject("skill_data").has("uuid")
                    && !obj.getAsJsonObject("skill_data").get("uuid").getAsString().isBlank();
            if (!sdHasUuid && !fallbackUuid.isBlank()) {
                data = new SkillData(fallbackUuid, data.name(), data.description(), data.cooldown(),
                        data.trigger(), data.execution(), data.passive(), data.wuhun(),
                        data.mobId(), data.mobHealthSegment(), data.ringAge(),
                        data.requiresMods(), data.signatureMechanism());
            }
            SkillAnomalyDetector.Result r = SkillAnomalyDetector.detect(data, 0f);
            if (r.anomalous()) {
                SkillBlacklist.add(data.uuid(), r.reason());
                LOGGER.warn("mod 资源异常拦截: {} ({})", path, r.reason());
                return Optional.empty();
            }
            return Optional.of(new SkillEntry(data, RatingStats.POOL_PIN_JIAN, new RatingStats(), "", ""));
        } catch (Exception e) {
            LOGGER.warn("mod 资源 JSON 解析失败: {} — {}", path, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 把当前加载结果写入本地快照（加速重启） */
    private void saveSnapshot() {
        try {
            Files.createDirectories(SNAPSHOT_FILE.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("scannedAt", System.currentTimeMillis() / 1000L);
            JsonObject meta = new JsonObject();
            for (Map.Entry<String, Long> e : jarMeta.entrySet()) {
                meta.addProperty(e.getKey(), e.getValue());
            }
            root.add("jarMeta", meta);
            JsonArray arr = new JsonArray();
            for (SkillEntry e : loaded) arr.add(e.toCloudJson());
            root.add("entries", arr);
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().create();
            Files.writeString(SNAPSHOT_FILE, gson.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.debug("mod 扫描快照保存失败: {}", e.getClass().getSimpleName());
        }
    }

    /** 读取上次扫描快照（跳过 jar 扫描的快路径） */
    public List<SkillEntry> loadSnapshot() {
        loaded.clear();
        try {
            if (!Files.exists(SNAPSHOT_FILE)) return List.of();
            JsonObject root = JsonParser.parseString(
                    Files.readString(SNAPSHOT_FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("entries") && root.get("entries").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("entries")) {
                    if (el.isJsonObject()) {
                        SkillEntry entry = SkillEntry.fromCloudJson(el.getAsJsonObject());
                        if (entry != null && !SkillBlacklist.contains(entry.uuid())) {
                            loaded.add(entry);
                        }
                    }
                }
            }
            lastLoadedCount = loaded.size();
            if (!loaded.isEmpty()) {
                LOGGER.info("mod 扫描快照载入: {} 条", loaded.size());
            }
            return new ArrayList<>(loaded);
        } catch (Exception e) {
            LOGGER.debug("mod 扫描快照读取失败: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }
}