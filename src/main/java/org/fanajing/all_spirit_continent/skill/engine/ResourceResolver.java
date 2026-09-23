package org.fanajing.all_spirit_continent.skill.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import org.fanajing.all_spirit_continent.cloud.ApiClient;
import org.fanajing.all_spirit_continent.util.PlayerSkillConfig;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 表现资源解析 + 降级链（引擎文档 §9，P1）。
 * 原语 ≠ 怪物专属技能，原语 = 效果库：mod 怪物有原版没有的表现资源
 * （灵魂火焰/虚空粒子等），通过注册表动态选择实现 mod 兼容。
 * <p>
 * 降级链（§9.3）：注册表直接命中（100%）→ 玩家覆盖配置 →
 * 注册表关键词自动扫描（§9.4，80%）→ 原版回退（永远可用，70%）。
 * 典型场景：AI 输出 "twilightforest:u_f_frost" 粒子但 mod 未安装 →
 * 关键词命中 "frost" → 自动降级为原版 snowflake 粒子，技能不失效。
 */
public final class ResourceResolver {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 语义类别（§9.4 关键词分类） */
    public enum SemanticKind {
        WEB("web", "silk", "net", "trap"),
        FIRE("fire", "flame", "burn", "inferno", "blaze"),
        ICE("ice", "frost", "freeze", "snow", "glacier"),
        POISON("poison", "toxic", "venom", "acid"),
        SOUL("soul", "spirit", "wisp", "ghost"),
        VOID("void", "end", "abyss", "dark"),
        LIGHTNING("lightning", "thunder", "electric", "shock", "spark");

        final String[] keywords;

        SemanticKind(String... keywords) {
            this.keywords = keywords;
        }
    }

    /** 各类别原版回退粒子（永远存在） */
    private static final Map<SemanticKind, String> VANILLA_PARTICLE = Map.of(
            SemanticKind.WEB, "minecraft:cloud",
            SemanticKind.FIRE, "minecraft:flame",
            SemanticKind.ICE, "minecraft:snowflake",
            SemanticKind.POISON, "minecraft:effect",
            SemanticKind.SOUL, "minecraft:soul_fire_flame",
            SemanticKind.VOID, "minecraft:portal",
            SemanticKind.LIGHTNING, "minecraft:electric_spark");

    /** 各类别原版回退音效 */
    private static final Map<SemanticKind, String> VANILLA_SOUND = Map.of(
            SemanticKind.WEB, "minecraft:block.wool.place",
            SemanticKind.FIRE, "minecraft:entity.blaze.shoot",
            SemanticKind.ICE, "minecraft:block.glass.break",
            SemanticKind.POISON, "minecraft:entity.witch.throw",
            SemanticKind.SOUL, "minecraft:particle.soul_escape",
            SemanticKind.VOID, "minecraft:entity.enderman.teleport",
            SemanticKind.LIGHTNING, "minecraft:entity.lightning_bolt.impact");

    /** 玩家覆盖配置（config/all_spirit_continent/primitives_override.json，懒加载） */
    private static volatile Map<SemanticKind, OverrideEntry> overrides = null;

    /** 注册表关键词扫描缓存（懒构建） */
    private static volatile Map<SemanticKind, List<ResourceLocation>> scannedParticles = null;

    /** AI 语义分类缓存（§9.5 兜底）：
     * 关键词失败 → 异步调 AI 分类 → 写入 cache 文件 + 此 Map。
     * 文件：config/all_spirit_continent/resource_classify_cache.json */
    private static final Map<String, SemanticKind> aiClassifyCache = new ConcurrentHashMap<>();

    /** 防止同一 id 短时间内重复触发 AI 请求（in-flight 去重） */
    private static final java.util.Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private static final Path AI_CLASSIFY_CACHE_FILE = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
            .resolve("all_spirit_continent").resolve("resource_classify_cache.json");

    private record OverrideEntry(String particle, String sound) {
    }

    private ResourceResolver() {
    }

    // ===== 对外入口（永不抛异常，保证技能表现永远可用）=====

    /**
     * 解析粒子 ID（降级链：注册表命中 → 玩家覆盖 → 关键词扫描 → 原版回退）。
     * AI 输出的 mod 粒子 ID 在未安装该 mod 时自动降级为语义近似的可用粒子。
     */
    public static ParticleOptions resolveParticle(String rawId) {
        ParticleType<?> direct = registryGet(rawId);
        if (direct instanceof ParticleOptions options) return options;
        SemanticKind kind = classify(rawId);
        return particleFor(kind == null ? SemanticKind.FIRE : kind);
    }

    /** 解析音效 ID（降级链同粒子；极端情况返回 generic.explode） */
    public static SoundEvent resolveSound(String rawId) {
        SoundEvent direct = BuiltInRegistries.SOUND_EVENT.get(
                ResourceLocation.tryParse(rawId == null ? "" : rawId));
        if (direct != null) return direct;
        SemanticKind kind = classify(rawId);
        return soundFor(kind == null ? SemanticKind.LIGHTNING : kind);
    }

    /** 某语义类别的最佳粒子：玩家覆盖 → 注册表扫描 → 原版回退 */
    public static ParticleOptions particleFor(SemanticKind kind) {
        OverrideEntry entry = overrides().get(kind);
        if (entry != null && entry.particle() != null) {
            ParticleType<?> p = registryGet(entry.particle());
            if (p instanceof ParticleOptions options) return options;
            LOGGER.debug("覆盖配置的粒子不存在，继续降级: {}", entry.particle());
        }
        List<ResourceLocation> found = scannedParticles().get(kind);
        if (found != null && !found.isEmpty()) {
            ParticleType<?> p = BuiltInRegistries.PARTICLE_TYPE.get(found.get(0));
            if (p instanceof ParticleOptions options) return options;
        }
        ParticleType<?> fallback = registryGet(VANILLA_PARTICLE.get(kind));
        return fallback instanceof ParticleOptions options ? options : ParticleTypes.FLAME;
    }

    /** 某语义类别的最佳音效（同降级链） */
    public static SoundEvent soundFor(SemanticKind kind) {
        OverrideEntry entry = overrides().get(kind);
        if (entry != null && entry.sound() != null) {
            SoundEvent s = BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.tryParse(entry.sound()));
            if (s != null) return s;
        }
        SoundEvent s = BuiltInRegistries.SOUND_EVENT.get(
                ResourceLocation.tryParse(VANILLA_SOUND.get(kind)));
        return s != null ? s : BuiltInRegistries.SOUND_EVENT.get(
                ResourceLocation.tryParse("minecraft:entity.generic.explode"));
    }

    /** 按 ID 字符串做语义分类（"soul_fire_flame" → SOUL；无法识别返回 null）
     * <p>分类链（§9.5）：
     * <ol>
     *   <li>AI 分类缓存（config/resource_classify_cache.json）→ 命中即返回</li>
     *   <li>关键词扫描（§9.4）→ 命中即返回</li>
     *   <li>无 cfg 时返回 null（调用方走原版 fallback）</li>
     *   <li>有 cfg 时入 in-flight 队列，异步调 AI 分类；成功后写缓存，
     *       下次同 id 出现直接走缓存命中（1）</li>
     * </ol>
     */
    public static SemanticKind classify(String id) {
        if (id == null || id.isBlank()) return null;
        // 1. AI 缓存
        ensureAiClassifyCacheLoaded();
        SemanticKind cached = aiClassifyCache.get(id);
        if (cached != null) return cached;
        // 2. 关键词
        String lower = id.toLowerCase(Locale.ROOT);
        for (SemanticKind kind : SemanticKind.values()) {
            for (String kw : kind.keywords) {
                if (lower.contains(kw)) return kind;
            }
        }
        // 3. 无法识别
        return null;
    }

    /**
     * 异步请求 AI 对未识别的资源 ID 做语义分类（§9.5）。
     * 同一 id 在 in-flight 期间多次调用只会触发一次；成功写本地缓存。
     *
     * @param cfg    AI Key 配置；为 null 时立即返回（保护无 Key 环境）
     * @param id     待分类资源 ID（如 "twilightforest:u_f_frost"）
     * @return 异步结果；失败/熔断返回 empty
     */
    public static CompletableFuture<Optional<SemanticKind>> requestAiClassifyAsync(PlayerSkillConfig cfg, String id) {
        if (cfg == null || id == null || id.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        ensureAiClassifyCacheLoaded();
        SemanticKind cached = aiClassifyCache.get(id);
        if (cached != null) return CompletableFuture.completedFuture(Optional.of(cached));
        if (!inFlight.add(id)) {
            // 已在请求中：返回当前结果（可能尚未写回，返回 empty 让调用方继续用 fallback）
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String system = "你是魂技资源分类助手。给定一个 Minecraft 资源 ID（可能来自任意 mod），" +
                "判断其最接近的语义类别。\n" +
                "可选类别：WEB（网/陷阱/丝）、FIRE（火焰/燃烧/熔岩）、ICE（冰霜/冻结/雪）、" +
                "POISON（毒/酸/腐蚀）、SOUL（灵魂/幽灵/精神）、VOID（虚空/末影/深渊）、" +
                "LIGHTNING（闪电/雷电/电流）。\n" +
                "如果完全无法判断（ID 极怪或乱码），返回 UNKNOWN。\n" +
                "严格返回 JSON：{\"kind\": \"FIRE\", \"confidence\": 0.85}";
        String user = "资源 ID：" + id;
        return ApiClient.chatJson(cfg, system, user, 0.3)
                .thenApply(opt -> {
                    inFlight.remove(id);
                    if (opt.isEmpty()) return Optional.<SemanticKind>empty();
                    try {
                        JsonObject obj = opt.get();
                        String kindStr = obj.has("kind") ? obj.get("kind").getAsString().toUpperCase(Locale.ROOT) : "";
                        if ("UNKNOWN".equals(kindStr) || kindStr.isEmpty()) {
                            return Optional.<SemanticKind>empty();
                        }
                        SemanticKind kind = SemanticKind.valueOf(kindStr);
                        aiClassifyCache.put(id, kind);
                        saveAiClassifyCache();
                        LOGGER.info("AI 资源分类: {} → {} (置信度 {})",
                                id, kind, obj.has("confidence") ? obj.get("confidence").getAsDouble() : 0.0);
                        return Optional.of(kind);
                    } catch (Exception e) {
                        LOGGER.debug("AI 分类响应解析失败: {}", e.getClass().getSimpleName());
                        return Optional.<SemanticKind>empty();
                    }
                });
    }

    /** 启动时从磁盘载入 AI 分类缓存 */
    private static void ensureAiClassifyCacheLoaded() {
        if (!aiClassifyCache.isEmpty()) return;
        try {
            if (Files.exists(AI_CLASSIFY_CACHE_FILE)) {
                JsonObject root = JsonParser.parseString(
                        Files.readString(AI_CLASSIFY_CACHE_FILE, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonElement cache = root.get("cache");
                if (cache != null && cache.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : cache.getAsJsonObject().entrySet()) {
                        try {
                            SemanticKind k = SemanticKind.valueOf(e.getValue().getAsString());
                            aiClassifyCache.put(e.getKey(), k);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    LOGGER.info("AI 资源分类缓存载入: {} 条", aiClassifyCache.size());
                }
            }
        } catch (Exception e) {
            LOGGER.debug("AI 资源分类缓存载入失败（使用空缓存）: {}", e.getClass().getSimpleName());
        }
    }

    /** 把当前缓存写回磁盘（每次成功新增 AI 分类时调用） */
    private static void saveAiClassifyCache() {
        try {
            Files.createDirectories(AI_CLASSIFY_CACHE_FILE.getParent());
            JsonObject root = new JsonObject();
            JsonObject cache = new JsonObject();
            for (Map.Entry<String, SemanticKind> e : aiClassifyCache.entrySet()) {
                cache.addProperty(e.getKey(), e.getValue().name());
            }
            root.add("cache", cache);
            Files.writeString(AI_CLASSIFY_CACHE_FILE, root.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.debug("AI 资源分类缓存保存失败: {}", e.getClass().getSimpleName());
        }
    }

    // ===== 内部 =====

    private static ParticleType<?> registryGet(String id) {
        if (id == null || id.isBlank()) return null;
        ResourceLocation rl = ResourceLocation.tryParse(id.toLowerCase(Locale.ROOT));
        return rl == null ? null : BuiltInRegistries.PARTICLE_TYPE.get(rl);
    }

    /** 关键词扫描缓存（懒构建常驻；§9.4 启动扫描的惰性版，mod 粒子优先） */
    private static Map<SemanticKind, List<ResourceLocation>> scannedParticles() {
        Map<SemanticKind, List<ResourceLocation>> cache = scannedParticles;
        if (cache != null) return cache;
        Map<SemanticKind, List<ResourceLocation>> built = new EnumMap<>(SemanticKind.class);
        for (SemanticKind kind : SemanticKind.values()) built.put(kind, new ArrayList<>());
        for (ResourceLocation id : BuiltInRegistries.PARTICLE_TYPE.keySet()) {
            SemanticKind kind = classify(id.getPath());
            if (kind == null || built.get(kind).size() >= 8) continue;
            // mod 粒子插队首（整合包装了火系 mod 自动用其火焰粒子，原版只作兜底）
            if ("minecraft".equals(id.getNamespace())) {
                built.get(kind).add(id);
            } else {
                built.get(kind).add(0, id);
            }
        }
        scannedParticles = built;
        return built;
    }

    /**
     * 玩家/整合包覆盖配置（懒加载一次）。文件：
     * {@code config/all_spirit_continent/primitives_override.json}
     * <pre>{@code
     * {"FIRE": {"particle": "somemod:inferno", "sound": "somemod:inferno_cast"}}
     * }</pre>
     */
    private static Map<SemanticKind, OverrideEntry> overrides() {
        Map<SemanticKind, OverrideEntry> cache = overrides;
        if (cache != null) return cache;
        Map<SemanticKind, OverrideEntry> loaded = new EnumMap<>(SemanticKind.class);
        try {
            Path file = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve("all_spirit_continent").resolve("primitives_override.json");
            if (Files.exists(file)) {
                JsonObject root = JsonParser.parseString(
                        Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                for (var entry : root.entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    try {
                        SemanticKind kind = SemanticKind.valueOf(entry.getKey().toUpperCase(Locale.ROOT));
                        JsonObject obj = entry.getValue().getAsJsonObject();
                        String particle = obj.has("particle") ? obj.get("particle").getAsString() : null;
                        String sound = obj.has("sound") ? obj.get("sound").getAsString() : null;
                        loaded.put(kind, new OverrideEntry(particle, sound));
                    } catch (IllegalArgumentException ignored) {
                        LOGGER.debug("覆盖配置忽略未知类别: {}", entry.getKey());
                    }
                }
                LOGGER.info("表现资源覆盖配置载入: {} 个类别", loaded.size());
            }
        } catch (Exception e) {
            LOGGER.warn("表现资源覆盖配置读取失败（使用默认降级链）: {}", e.getClass().getSimpleName());
        }
        overrides = loaded;
        return loaded;
    }
}
