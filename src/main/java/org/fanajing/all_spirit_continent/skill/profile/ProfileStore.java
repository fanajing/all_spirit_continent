package org.fanajing.all_spirit_continent.skill.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.skill.AbilitySignature;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * 画像持久化（引擎文档 §5，P1）：观察到的 Mob 画像落盘世界目录
 * {@code world/data/all_spirit_continent/mob_profiles.json}，启动时载入回 {@link ProfileCache}。
 * 启动载入 / 每 10 分钟自动保存 / 关服最终保存；写入用临时文件 + 原子移动防损坏。
 */
@EventBusSubscriber(modid = All_spirit_continent.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ProfileStore {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_NAME = "mob_profiles.json";
    private static final long AUTOSAVE_INTERVAL_TICKS = 12000L;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static volatile Path storeDir = null;
    private static long lastSaveGameTime = -1L;

    private ProfileStore() {
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        ServerLevel overworld = event.getServer().overworld();
        storeDir = overworld.getServer().getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve("all_spirit_continent");
        lastSaveGameTime = overworld.getGameTime();
        load();
        // 云端画像拉取合并（异步；§6 众包聚合，签名计数 max 合并实现多玩家交叉验证）
        ProfileCloudSync.pullAndMergeAsync();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (storeDir == null) return;
        long gameTime = event.getServer().overworld().getGameTime();
        if (lastSaveGameTime >= 0 && gameTime - lastSaveGameTime >= AUTOSAVE_INTERVAL_TICKS) {
            lastSaveGameTime = gameTime;
            save();
            // 本地保存后顺带推云端（内部有 30 分钟节流 + 忙锁，§6.4 批量上传）
            ProfileCloudSync.pushAllAsync();
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        save();
        ProfileCloudSync.pushAllAsync().join();
        storeDir = null;
    }

    // ===== 载入 =====

    private static void load() {
        Path dir = storeDir;
        if (dir == null) return;
        Path file = dir.resolve(FILE_NAME);
        if (!Files.exists(file)) return;
        try {
            JsonArray arr = JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
            int loaded = 0;
            for (var el : arr) {
                if (!el.isJsonObject()) continue;
                MobProfile profile = fromJson(el.getAsJsonObject());
                if (profile != null) {
                    ProfileCache.put(profile);
                    loaded++;
                }
            }
            if (loaded > 0) {
                LOGGER.info("画像库载入完成: {} 个 mob 画像", loaded);
            }
        } catch (Exception e) {
            LOGGER.warn("画像库载入失败（将重建）: {}", e.getClass().getSimpleName());
        }
    }

    /** 画像 JSON 反序列化（包内共享：ProfileCloudSync 云端解析复用） */
    static MobProfile fromJson(JsonObject obj) {
        try {
            String mobId = obj.get("mob_id").getAsString();
            MobSize size = MobSize.valueOf(obj.get("size").getAsString());
            AttackPattern pattern = AttackPattern.valueOf(obj.get("attack_pattern").getAsString());
            EnumMap<AbilitySignature, Integer> counts = new EnumMap<>(AbilitySignature.class);
            if (obj.has("signatures") && obj.get("signatures").isJsonObject()) {
                for (var entry : obj.getAsJsonObject("signatures").entrySet()) {
                    AbilitySignature sig = AbilitySignature.valueOf(entry.getKey());
                    counts.put(sig, Math.max(1, entry.getValue().getAsInt()));
                }
            }
            List<String> traits = new ArrayList<>();
            if (obj.has("combat_traits") && obj.get("combat_traits").isJsonArray()) {
                for (var t : obj.getAsJsonArray("combat_traits")) {
                    traits.add(t.getAsString());
                }
            }
            // 多阶段 boss 分桶（§5.3）："phases": {"1": ["PROJECTILE"], "2": [...]}
            java.util.Map<Integer, java.util.Set<AbilitySignature>> phases = new java.util.HashMap<>();
            if (obj.has("phases") && obj.get("phases").isJsonObject()) {
                for (var entry : obj.getAsJsonObject("phases").entrySet()) {
                    try {
                        int seg = Integer.parseInt(entry.getKey());
                        if (seg < 1 || seg > PhaseInfo.MAX_PHASE || !entry.getValue().isJsonArray()) continue;
                        java.util.Set<AbilitySignature> set = java.util.EnumSet.noneOf(AbilitySignature.class);
                        for (var s : entry.getValue().getAsJsonArray()) {
                            set.add(AbilitySignature.valueOf(s.getAsString()));
                        }
                        phases.put(seg, set);
                    } catch (IllegalArgumentException ignored) {
                        // 旧版本/损坏数据：跳过该阶段桶
                    }
                }
            }
            long atTick = obj.has("observed_at_tick") ? obj.get("observed_at_tick").getAsLong() : 0L;
            int obsCount = obj.has("observation_count") ? obj.get("observation_count").getAsInt() : counts.size();
            // §5.3 扩展字段（旧文件可能缺失）
            EntityAttributes attrs = parseAttributes(obj);
            List<String> themeKw = parseStringList(obj, "theme_keywords");
            List<String> sounds = parseStringList(obj, "sounds");
            List<String> particles = parseStringList(obj, "particles");
            List<String> drops = parseStringList(obj, "drops");
            int contributors = obj.has("contributor_count") ? obj.get("contributor_count").getAsInt() : 0;
            boolean incomplete = !obj.has("incomplete") || obj.get("incomplete").getAsBoolean();
            // §6.5/§6.6：modVersion/mcVersion/environmentHash/isStale（旧文件缺失兜底）
            String modVersion = obj.has("mod_version") ? obj.get("mod_version").getAsString() : ModVersion.UNKNOWN;
            String mcVersion = obj.has("mc_version") ? obj.get("mc_version").getAsString() : McVersion.current();
            String envHash = obj.has("environment_hash") ? obj.get("environment_hash").getAsString() : EnvironmentHash.UNKNOWN;
            boolean stale = obj.has("is_stale") && obj.get("is_stale").getAsBoolean();
            return new MobProfile(mobId, size, pattern, counts, traits, phases, atTick, obsCount,
                    attrs, themeKw, sounds, particles, drops, contributors, incomplete,
                    modVersion, mcVersion, envHash, stale);
        } catch (Exception e) {
            return null;
        }
    }

    /** §5.3 扩展字段解析（缺失字段自动兜底） */
    private static EntityAttributes parseAttributes(JsonObject obj) {
        try {
            if (!obj.has("attributes") || !obj.get("attributes").isJsonObject()) return EntityAttributes.empty();
            JsonObject a = obj.getAsJsonObject("attributes");
            double hp = a.has("max_health") ? a.get("max_health").getAsDouble() : 0;
            double atk = a.has("attack_damage") ? a.get("attack_damage").getAsDouble() : 0;
            double spd = a.has("movement_speed") ? a.get("movement_speed").getAsDouble() : 0;
            double range = a.has("follow_range") ? a.get("follow_range").getAsDouble() : 0;
            List<String> imm = parseStringList(a, "damage_immunities");
            List<String> weak = parseStringList(a, "weaknesses");
            java.util.Map<String, Double> extra = new java.util.HashMap<>();
            if (a.has("extra") && a.get("extra").isJsonObject()) {
                for (var e : a.getAsJsonObject("extra").entrySet()) {
                    try { extra.put(e.getKey(), e.getValue().getAsDouble()); } catch (Exception ignored) {}
                }
            }
            return new EntityAttributes(hp, atk, spd, range, imm, weak, extra);
        } catch (Exception ignored) {
            return EntityAttributes.empty();
        }
    }

    private static List<String> parseStringList(JsonObject obj, String key) {
        List<String> list = new ArrayList<>();
        if (obj.has(key) && obj.get(key).isJsonArray()) {
            for (var e : obj.getAsJsonArray(key)) list.add(e.getAsString());
        }
        return list;
    }

    // ===== 保存 =====

    private static void save() {
        Path dir = storeDir;
        if (dir == null) return;
        try {
            JsonArray arr = new JsonArray();
            for (MobProfile profile : ProfileCache.all()) {
                arr.add(toJson(profile));
            }
            Files.createDirectories(dir);
            Path tmp = dir.resolve(FILE_NAME + ".tmp");
            Files.writeString(tmp, GSON.toJson(arr), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve(FILE_NAME),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("画像库保存失败: {}", e.getClass().getSimpleName());
        }
    }

    /** 画像 JSON 序列化（包内共享：ProfileCloudSync 云端上传复用） */
    static JsonObject toJson(MobProfile p) {
        JsonObject obj = new JsonObject();
        obj.addProperty("mob_id", p.mobId());
        obj.addProperty("size", p.size().name());
        obj.addProperty("attack_pattern", p.attackPattern().name());
        JsonObject sigs = new JsonObject();
        for (var e : p.signatureCounts().entrySet()) {
            sigs.addProperty(e.getKey().name(), e.getValue());
        }
        obj.add("signatures", sigs);
        JsonArray traits = new JsonArray();
        for (String t : p.combatTraits()) {
            traits.add(t);
        }
        obj.add("combat_traits", traits);
        // 多阶段 boss 分桶（§5.3）
        if (!p.phaseSignatures().isEmpty()) {
            JsonObject phases = new JsonObject();
            for (var e : p.phaseSignatures().entrySet()) {
                JsonArray seg = new JsonArray();
                for (AbilitySignature s : e.getValue()) {
                    seg.add(s.name());
                }
                phases.add(String.valueOf(e.getKey()), seg);
            }
            obj.add("phases", phases);
        }
        obj.addProperty("observed_at_tick", p.observedAtTick());
        obj.addProperty("observation_count", p.observationCount());

        // §5.3 扩展字段
        EntityAttributes attrs = p.attributes();
        JsonObject attrsJson = new JsonObject();
        attrsJson.addProperty("max_health", attrs.maxHealth());
        attrsJson.addProperty("attack_damage", attrs.attackDamage());
        attrsJson.addProperty("movement_speed", attrs.movementSpeed());
        attrsJson.addProperty("follow_range", attrs.followRange());
        JsonArray imm = new JsonArray();
        for (String s : attrs.damageImmunities()) imm.add(s);
        attrsJson.add("damage_immunities", imm);
        JsonArray weak = new JsonArray();
        for (String s : attrs.weaknesses()) weak.add(s);
        attrsJson.add("weaknesses", weak);
        if (!attrs.extra().isEmpty()) {
            JsonObject extra = new JsonObject();
            attrs.extra().forEach(extra::addProperty);
            attrsJson.add("extra", extra);
        }
        obj.add("attributes", attrsJson);

        if (!p.themeKeywords().isEmpty()) {
            JsonArray arr = new JsonArray();
            for (String s : p.themeKeywords()) arr.add(s);
            obj.add("theme_keywords", arr);
        }
        if (!p.sounds().isEmpty()) {
            JsonArray arr = new JsonArray();
            for (String s : p.sounds()) arr.add(s);
            obj.add("sounds", arr);
        }
        if (!p.particles().isEmpty()) {
            JsonArray arr = new JsonArray();
            for (String s : p.particles()) arr.add(s);
            obj.add("particles", arr);
        }
        if (!p.drops().isEmpty()) {
            JsonArray arr = new JsonArray();
            for (String s : p.drops()) arr.add(s);
            obj.add("drops", arr);
        }
        obj.addProperty("contributor_count", p.contributorCount());
        obj.addProperty("incomplete", p.incomplete());

        // §6.5/§6.6：版本管理 + 环境隔离元数据
        obj.addProperty("mod_version", p.modVersion() == null ? ModVersion.UNKNOWN : p.modVersion());
        obj.addProperty("mc_version", p.mcVersion() == null ? McVersion.UNKNOWN : p.mcVersion());
        obj.addProperty("environment_hash", p.environmentHash() == null ? EnvironmentHash.UNKNOWN : p.environmentHash());
        obj.addProperty("is_stale", p.isStale());
        return obj;
    }
}
