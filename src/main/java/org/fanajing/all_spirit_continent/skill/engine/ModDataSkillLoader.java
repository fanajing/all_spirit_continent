package org.fanajing.all_spirit_continent.skill.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.fanajing.all_spirit_continent.skill.SkillData;
import org.fanajing.all_spirit_continent.skill.SkillPrimitive;
import org.fanajing.all_spirit_continent.util.PlayerSkillConfig;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据驱动 mod 技能加载器（引擎文档 §9.8）。
 * <p>
 * 检测路径：
 * <ul>
 *   <li>{@code config/all_spirit_continent/mod_skills/*.json} —— 整合包作者预先写好的技能模板</li>
 *   <li>{@code config/all_spirit_continent/mod_skills/<modid>/<mob>.json} —— 按 mod 分目录</li>
 *   <li>每个 JSON 文件描述某个 mob 的若干技能模板，命中时直接走模板，绕过 AI 生成</li>
 * </ul>
 * 如果 mod 没有提供数据驱动技能 → 走 AI 五阶段生成（V6.0） + 观察法（现有体系）。
 * <p>
 * <b>文件格式</b>：
 * <pre>{@code
 * {
 *   "mob_id": "twilightforest:lich",
 *   "display_name": "巫妖王",
 *   "mod_version": "1.21.1-4.6.0",
 *   "templates": [
 *     {
 *       "name": "昊天·巫妖破盾葬",
 *       "slot": 4,
 *       "cooldown": 60,
 *       "trigger": "RIGHT_CLICK",
 *       "signature_mechanism": "C",
 *       "execution": [{"primitive": "ARMOR_BREAK", "target": "TARGET", "value": 3.0, "duration": 80}],
 *       "passive":    [{"primitive": "BUFF_STATS", "target": "SELF", "attribute": "ATTACK_DAMAGE", "percent": 0.2, "duration": 200}]
 *     }
 *   ]
 * }
 * }</pre>
 * <p>
 * <b>降级策略</b>：模板加载失败 / 模板数据损坏 → 不进入模板池，仍走 AI 生成（§9.8 优先数据驱动，
 * 否则硬编码 + 观察法）。{@link #getTemplatesFor(String)} 永远返回 {@code List}，可能为空。
 */
public final class ModDataSkillLoader {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 配置文件目录：config/all_spirit_continent/mod_skills/ */
    public static final Path CONFIG_DIR = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
            .resolve("all_spirit_continent").resolve("mod_skills");

    /** 已加载的模板：mob_id → 该 mob 的模板列表 */
    private static final Map<String, List<LoadedTemplate>> TEMPLATES = new ConcurrentHashMap<>();

    /** 加载状态：避免重复扫描 */
    private static volatile boolean LOADED = false;

    private ModDataSkillLoader() {
    }

    /**
     * 已加载的模板快照（不可变视图）。
     *
     * @param name           模板名（来自 JSON 的"name"）
     * @param slot           环位 1~9
     * @param mobId          对应 mob 注册名（小写归一化）
     * @param modId          数据来源 mod（"all_spirit_continent" 表示本 mod 默认范例）
     * @param rawJson        原始 JSON 文本（用于调试/重新解析）
     * @param passesStrict   是否通过 {@link SkillValidator} 严格校验
     * @param failures       未通过的字段名 + 错误信息（用于在 UI 上向玩家显示）
     */
    public record LoadedTemplate(
            String name,
            int slot,
            String mobId,
            String modId,
            String rawJson,
            boolean passesStrict,
            List<String> failures) {
    }

    /**
     * 在主类 {@code commonSetup} 阶段调用一次：扫描目录 + 解析 + 注册。
     * 调用后任何 {@link #getTemplatesFor(String)} 都立即可用。
     *
     * @param cfg AI Key 配置；为 null 时不影响加载（只是 AI 资源分类缓存不会做）
     */
    public static void scanAndLoad(PlayerSkillConfig cfg) {
        if (LOADED) return;
        synchronized (ModDataSkillLoader.class) {
            if (LOADED) return;
            LOADED = true;  // 先占位，避免重入
            try {
                if (!Files.isDirectory(CONFIG_DIR)) {
                    LOGGER.info("mod_skills 目录不存在（{}），未加载任何数据驱动技能", CONFIG_DIR);
                    return;
                }
                int files = 0;
                int accepted = 0;
                List<Path> jsonFiles = collectJsonFiles(CONFIG_DIR);
                for (Path file : jsonFiles) {
                    files++;
                    try {
                        String text = Files.readString(file, StandardCharsets.UTF_8);
                        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
                        List<LoadedTemplate> loaded = parseOneFile(root, file);
                        for (LoadedTemplate t : loaded) {
                            TEMPLATES.computeIfAbsent(t.mobId(), k -> new ArrayList<>()).add(t);
                            accepted++;
                        }
                    } catch (Exception e) {
                        LOGGER.warn("数据驱动技能文件解析失败（已跳过）: {} - {}", file, e.getClass().getSimpleName());
                    }
                }
                LOGGER.info("§9.8 mod_skills 扫描完成: {} 个 JSON 文件 / {} 个模板 / 覆盖 {} 个 mob",
                        files, accepted, TEMPLATES.size());
            } catch (Exception e) {
                LOGGER.warn("mod_skills 目录扫描失败（已禁用数据驱动）: {}", e.getClass().getSimpleName());
            }
        }
    }

    /** 递归收集目录下所有 .json（深度 2） */
    private static List<Path> collectJsonFiles(Path dir) {
        List<Path> out = new ArrayList<>();
        try (var stream = Files.walk(dir, 2)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .forEach(out::add);
        } catch (Exception e) {
            LOGGER.debug("mod_skills 目录遍历失败: {}", e.getClass().getSimpleName());
        }
        return out;
    }

    /** 解析单个 JSON 文件 → 0..N 个模板 */
    private static List<LoadedTemplate> parseOneFile(JsonObject root, Path file) {
        List<LoadedTemplate> out = new ArrayList<>();
        if (!root.has("mob_id") || !root.has("templates")) return out;
        String mobId = root.get("mob_id").getAsString().toLowerCase(Locale.ROOT);
        String modId = file.getParent().getFileName().toString();
        // 根目录文件 → modId 用文件名（去掉扩展名）
        if (modId.equals("mod_skills")) {
            modId = stripExt(file.getFileName().toString());
        }
        JsonArray arr = root.getAsJsonArray("templates");
        for (JsonElement el : arr) {
            try {
                LoadedTemplate t = parseTemplate(mobId, modId, el.getAsJsonObject());
                if (t != null) out.add(t);
            } catch (Exception e) {
                LOGGER.warn("模板解析失败 {}: {}", file, e.getClass().getSimpleName());
            }
        }
        return out;
    }

    private static LoadedTemplate parseTemplate(String mobId, String modId, JsonObject obj) {
        String name = obj.has("name") ? obj.get("name").getAsString() : "";
        int slot = obj.has("slot") ? obj.get("slot").getAsInt() : -1;
        if (slot < 1 || slot > 9) {
            return new LoadedTemplate(name, slot, mobId, modId, obj.toString(), false,
                    List.of("slot 必须在 1~9"));
        }
        SkillData data;
        try {
            data = SkillData.fromAiJson(obj, "", mobId, "");
        } catch (Exception e) {
            return new LoadedTemplate(name, slot, mobId, modId, obj.toString(), false,
                    List.of("SkillData.fromAiJson 失败: " + e.getClass().getSimpleName()));
        }
        List<String> errors = SkillValidator.collect(data, slot, null);
        boolean passes = errors.isEmpty();
        return new LoadedTemplate(name, slot, mobId, modId, obj.toString(), passes,
                passes ? List.of() : errors);
    }

    /** 查询某 mob 的所有数据驱动模板（无则返回空 List） */
    public static List<LoadedTemplate> getTemplatesFor(String mobId) {
        if (mobId == null) return List.of();
        scanAndLoad(null);  // 懒启动
        return TEMPLATES.getOrDefault(mobId.toLowerCase(Locale.ROOT), List.of());
    }

    /** 按环位筛选（玩家需要「第 4 环 模板」时调用） */
    public static List<LoadedTemplate> getTemplatesFor(String mobId, int slot) {
        List<LoadedTemplate> all = getTemplatesFor(mobId);
        List<LoadedTemplate> filtered = new ArrayList<>();
        for (LoadedTemplate t : all) if (t.slot() == slot) filtered.add(t);
        return filtered;
    }

    /** 全部已加载的 mobId（UI 调试用） */
    public static java.util.Set<String> loadedMobIds() {
        scanAndLoad(null);
        return TEMPLATES.keySet();
    }

    /** 全部模板计数 */
    public static int totalLoaded() {
        scanAndLoad(null);
        return TEMPLATES.values().stream().mapToInt(List::size).sum();
    }

    /** 把一个模板实例化（恢复 SkillData）。失败时返回 empty（模板格式异常） */
    public static Optional<SkillData> instantiate(LoadedTemplate t) {
        if (t == null || t.rawJson() == null) return Optional.empty();
        try {
            return Optional.of(SkillData.fromAiJson(JsonParser.parseString(t.rawJson()).getAsJsonObject(),
                    "", t.mobId(), ""));
        } catch (Exception e) {
            LOGGER.debug("模板实例化失败 {}: {}", t.name(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 把 SkillPrimitive 名称映射为友好显示（仅在 UI 显示用，不影响逻辑） */
    public static String primitiveDisplay(SkillPrimitive p) {
        if (p == null) return "?";
        return p.name();
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /**
     * 供整合包作者参考的目录 + 示例文件名（写入 README，
     * 不在此强制创建文件——避免覆盖玩家现有配置）。
     */
    public static String recommendedLayoutHint() {
        return "推荐布局:\n" +
                "  config/all_spirit_continent/mod_skills/<modid>/<mob>.json\n" +
                "  或简写: config/all_spirit_continent/mod_skills/<mob>.json\n" +
                "示例: config/all_spirit_continent/mod_skills/twilightforest/lich.json\n" +
                "字段: mob_id (必填), templates[] (必填), mod_version (可选), display_name (可选)";
    }
}