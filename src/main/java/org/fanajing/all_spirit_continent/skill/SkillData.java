package org.fanajing.all_spirit_continent.skill;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.fanajing.all_spirit_continent.util.SoulBeastAge;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 魂技数据模型。AI/云端 JSON ↔ 引擎执行的中间载体。
 * <p>
 * 云端条目结构（public_skills.json 中每个元素）：
 * <pre>
 * {
 *   "uuid": "...", "wuhun": "昊天锤", "mob_id": "minecraft:zombie",
 *   "mob_health_segment": "20_30",
 *   "skill_data": { "name": "...", "description": "...", "cooldown": 20,
 *                   "trigger": "RIGHT_CLICK",
 *                   "execution": [ {"primitive": "AOE_DAMAGE", "radius": 3.0, "value": 5.0} ] },
 *   "pool": "PIN_JIAN", "stats": {...}, "uploader_hash": "***"
 * }
 * </pre>
 * 本类表示 skill_data + 冗余来源字段（uuid/wuhun/mobId/mobHealthSegment 便于本地缓存检索）。
 */
public record SkillData(
        String uuid,
        String name,
        String description,
        int cooldown,
        String trigger,
        List<ExecutionStep> execution,
        String wuhun,
        String mobId,
        String mobHealthSegment,
        int ringAge,
        List<String> requiresMods
) {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();

    /** 依赖 mod 列表（需全部安装该魂技才可用）；空 = 仅原版生效 */
    public SkillData {
        requiresMods = requiresMods == null ? List.of()
                : requiresMods.stream()
                        .filter(m -> m != null && !m.isBlank())
                        .map(String::trim)
                        .toList();
    }

    /** 冷却时间下限（tick）：任何魂技至少 1 秒冷却，防止无冷却滥用 */
    public static final int MIN_COOLDOWN = 20;

    /** 执行步骤：原语名 + 参数表（键为 snake_case，与 Param.key 一致） */
    public record ExecutionStep(String primitive, Map<String, Object> params) {
        public static ExecutionStep fromJson(JsonObject obj) {
            String primitive = obj.has("primitive") ? obj.get("primitive").getAsString() : "";
            Map<String, Object> params = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                if (e.getKey().equals("primitive")) continue;
                params.put(e.getKey(), jsonToJava(e.getValue()));
            }
            return new ExecutionStep(primitive, params);
        }

        private static Object jsonToJava(JsonElement el) {
            if (el.isJsonPrimitive()) {
                var p = el.getAsJsonPrimitive();
                if (p.isNumber()) {
                    double d = p.getAsDouble();
                    return d == Math.floor(d) && !Double.isInfinite(d) ? (int) d : d;
                }
                return p.getAsString();
            }
            if (el.isJsonArray()) {
                List<Object> list = new ArrayList<>();
                for (JsonElement child : el.getAsJsonArray()) list.add(jsonToJava(child));
                return list;
            }
            if (el.isJsonObject()) {
                Map<String, Object> map = new HashMap<>();
                for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                    map.put(e.getKey(), jsonToJava(e.getValue()));
                }
                return map;
            }
            return null;
        }
    }

    // ===== 序列化 =====

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("uuid", uuid);
        root.addProperty("wuhun", wuhun);
        root.addProperty("mob_id", mobId);
        root.addProperty("mob_health_segment", mobHealthSegment);
        JsonObject sd = new JsonObject();
        sd.addProperty("name", name);
        sd.addProperty("description", description);
        sd.addProperty("cooldown", cooldown);
        sd.addProperty("trigger", trigger == null ? "RIGHT_CLICK" : trigger);
        if (ringAge > 0) sd.addProperty("ring_age", ringAge);
        if (requiresMods != null && !requiresMods.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (String m : requiresMods) arr.add(m);
            sd.add("requires_mods", arr);
        }
        sd.add("execution", GSON.toJsonTree(execution));
        root.add("skill_data", sd);
        return root;
    }

    /**
     * 从云端条目 JSON 解析 SkillData。
     * 字段缺失时 uuid 自动生成（本地推演/未上传时）。
     * 校验失败返回 null（调用方丢弃该条并记录警告）。
     */
    public static SkillData fromCloudJson(JsonObject entry) {
        String uuid = entry.has("uuid") ? entry.get("uuid").getAsString() : UUID.randomUUID().toString();
        String wuhun = entry.has("wuhun") ? entry.get("wuhun").getAsString() : "";
        String mobId = entry.has("mob_id") ? entry.get("mob_id").getAsString() : "";
        String segment = entry.has("mob_health_segment") ? entry.get("mob_health_segment").getAsString() : "";
        if (!entry.has("skill_data") || !entry.get("skill_data").isJsonObject()) return null;
        JsonObject sd = entry.getAsJsonObject("skill_data");

        String name = sd.has("name") ? sd.get("name").getAsString().trim() : "";
        String description = sd.has("description") ? sd.get("description").getAsString().trim() : "";
        int cooldown = Math.max(MIN_COOLDOWN, sd.has("cooldown") ? sd.get("cooldown").getAsInt() : MIN_COOLDOWN);
        int ringAge = sd.has("ring_age") ? Math.max(0, sd.get("ring_age").getAsInt()) : 0;
        String trigger = sd.has("trigger") ? sd.get("trigger").getAsString() : "RIGHT_CLICK";

        List<ExecutionStep> execution = new ArrayList<>();
        if (sd.has("execution") && sd.get("execution").isJsonArray()) {
            for (JsonElement el : sd.getAsJsonArray("execution")) {
                if (el.isJsonObject()) {
                    ExecutionStep step = ExecutionStep.fromJson(el.getAsJsonObject());
                    if (isStepValid(step)) execution.add(step);
                }
            }
        }
        SkillData data = new SkillData(uuid, name, description, cooldown, trigger, execution,
                wuhun, mobId, segment, ringAge, parseRequiresMods(sd));
        return data.isValid() ? data : null;
    }

    /** 从 AI 返回的 skill_data JSON（不含 uuid/wuhun/mob 字段）解析，来源字段由调用方传入 */
    public static SkillData fromAiJson(JsonObject sd, String wuhun, String mobId, String segment) {
        String name = sd.has("name") ? sd.get("name").getAsString().trim() : "";
        String description = sd.has("description") ? sd.get("description").getAsString().trim() : "";
        int cooldown = Math.max(MIN_COOLDOWN, sd.has("cooldown") ? sd.get("cooldown").getAsInt() : MIN_COOLDOWN);
        String trigger = sd.has("trigger") ? sd.get("trigger").getAsString() : "RIGHT_CLICK";
        List<ExecutionStep> execution = new ArrayList<>();
        if (sd.has("execution") && sd.get("execution").isJsonArray()) {
            for (JsonElement el : sd.getAsJsonArray("execution")) {
                if (el.isJsonObject()) {
                    ExecutionStep step = ExecutionStep.fromJson(el.getAsJsonObject());
                    if (isStepValid(step)) execution.add(step);
                }
            }
        }
        SkillData data = new SkillData(UUID.randomUUID().toString(), name, description, cooldown,
                trigger, execution, wuhun, mobId, segment, 0, parseRequiresMods(sd));
        return data.isValid() ? data : null;
    }

    /** 解析 requires_mods：兼容数组（多个 mod）与单字符串两种写法 */
    private static List<String> parseRequiresMods(JsonObject sd) {
        if (!sd.has("requires_mods")) return List.of();
        JsonElement el = sd.get("requires_mods");
        List<String> list = new ArrayList<>();
        if (el.isJsonArray()) {
            for (JsonElement child : el.getAsJsonArray()) {
                if (child.isJsonPrimitive() && !child.getAsString().isBlank()) {
                    list.add(child.getAsString().trim());
                }
            }
        } else if (el.isJsonPrimitive() && !el.getAsString().isBlank()) {
            list.add(el.getAsString().trim());
        }
        return list;
    }

    /** 单个执行步骤校验：原语必须合法，参数必须为该原语允许且值类型正确 */
    private static boolean isStepValid(ExecutionStep step) {
        SkillPrimitive primitive = SkillPrimitive.byName(step.primitive);
        if (primitive == null) {
            LOGGER.warn("SkillData: 未知原语 '{}'，已丢弃该步骤", step.primitive);
            return false;
        }
        for (Map.Entry<String, Object> e : step.params.entrySet()) {
            Param param = Param.byKey(e.getKey());
            if (param == null || !primitive.allows(param)) {
                LOGGER.warn("SkillData: 原语 {} 不允许参数 '{}'，已忽略", primitive, e.getKey());
                continue;
            }
            Object v = e.getValue();
            boolean valid = switch (param) {
                case VALUE, RADIUS, PERCENT, DISTANCE, SPEED, CRIT_CHANCE -> v instanceof Number;
                case DURATION, COUNT, AMPLIFIER, GROWTH_STAGE -> v instanceof Number;
                case TARGET, DAMAGE_TYPE, ATTRIBUTE, DIRECTION, ENTITY_ID,
                     PROJECTILE_TYPE, EFFECT, SOUND, PARTICLE -> v instanceof String;
            };
            if (!valid) {
                LOGGER.warn("SkillData: 原语 {} 参数 '{}' 类型不合法，已丢弃该步骤", primitive, e.getKey());
                return false;
            }
        }
        return true;
    }

    /** 整条技能校验：名称与执行步骤非空 */
    public boolean isValid() {
        return name != null && !name.isEmpty() && execution != null && !execution.isEmpty();
    }

    /** 冷却时间（tick） */
    public int cooldown() {
        return cooldown;
    }

    /** 触发方式（当前统一 RIGHT_CLICK 语义，由技能释放键触发） */
    public String trigger() {
        return trigger == null ? "RIGHT_CLICK" : trigger;
    }

    /** 绑定魂环年限副本（吸收/推演后写入实际魂环年限，驱动伤害缩放） */
    public SkillData withRingAge(int age) {
        return new SkillData(uuid, name, description, cooldown, trigger, execution,
                wuhun, mobId, mobHealthSegment, Math.max(0, age), requiresMods);
    }

    /** 绑定依赖 mod 列表副本（上传时标记该魂技需要哪些 mod 才能生效，全部安装才下载/抽取） */
    public SkillData withRequiresMods(List<String> mods) {
        return new SkillData(uuid, name, description, cooldown, trigger, execution,
                wuhun, mobId, mobHealthSegment, ringAge, mods);
    }

    // ===== 数值预览（GUI 显示）=====

    /**
     * 生成人类可读的效果预览行（绿色），供吸收确认界面展示。
     * 每行 = 原语中文名 + 关键参数摘要；开头附魂环年限加成倍率（伤害随年限缩放）。
     */
    public List<Component> previewLines() {
        List<Component> lines = new ArrayList<>();
        if (ringAge > 0) {
            double mult = damageMultiplier(ringAge);
            if (mult > 1.0) {
                lines.add(Component.translatable("preview.all_spirit_continent.ring_age_mult",
                        SkillData.fmt(mult)).withStyle(ChatFormatting.GOLD));
            }
        }
        if (execution.isEmpty()) {
            lines.add(Component.translatable("primitive.all_spirit_continent.unknown").withStyle(ChatFormatting.GRAY));
            return lines;
        }
        for (ExecutionStep step : execution) {
            SkillPrimitive primitive = SkillPrimitive.byName(step.primitive);
            if (primitive == null) continue;
            String key = "primitive.all_spirit_continent." + primitive.name().toLowerCase(Locale.ROOT);
            MutableComponent line = Component.translatable(key).withStyle(ChatFormatting.GREEN);
            String summary = paramSummary(step.params());
            if (!summary.isEmpty()) {
                line.append(Component.literal(" " + summary).withStyle(ChatFormatting.DARK_GREEN));
            }
            lines.add(line);
        }
        return lines;
    }

    /** 关键参数摘要（数值/范围/持续 等） */
    private static String paramSummary(Map<String, Object> params) {
        List<String> parts = new ArrayList<>();
        Number value = num(params, "value");
        Number radius = num(params, "radius");
        Number duration = num(params, "duration");
        Number percent = num(params, "percent");
        if (value != null) parts.add(Component.translatable("preview.all_spirit_continent.value", fmt(value)).getString());
        if (radius != null) parts.add(Component.translatable("preview.all_spirit_continent.radius", fmt(radius)).getString());
        if (duration != null) parts.add(Component.translatable("preview.all_spirit_continent.duration", fmt(duration)).getString());
        if (percent != null) parts.add(Component.translatable("preview.all_spirit_continent.percent", fmt(percent)).getString());
        return String.join("·", parts);
    }

    private static Number num(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v instanceof Number n ? n : null;
    }

    /** 数值显示：整数去小数尾 */
    public static String fmt(Number n) {
        double d = n.doubleValue();
        if (d == Math.floor(d) && !Double.isInfinite(d)) {
            return String.valueOf((long) d);
        }
        return String.format(Locale.ROOT, "%.1f", d);
    }

    /**
     * 魂技伤害年限倍率：十年 ×1，每升一档 ×2
     * （百年 ×2 / 千年 ×4 / 万年 ×8 / 十万年 ×16 / 百万年 ×32），
     * 保证魂技伤害随吸收魂兽年限有存在感。
     */
    public static double damageMultiplier(int age) {
        return Math.pow(2, SoulBeastAge.tierOf(age));
    }
}
