package org.fanajing.all_spirit_continent.cloud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import org.fanajing.all_spirit_continent.config.CloudConfig;
import org.fanajing.all_spirit_continent.skill.Param;
import org.fanajing.all_spirit_continent.skill.SkillData;
import org.fanajing.all_spirit_continent.skill.SkillPrimitive;
import org.fanajing.all_spirit_continent.util.PlayerSkillConfig;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * AI 魂技生成客户端（OpenAI 兼容 chat/completions，默认 DeepSeek）。
 * <ul>
 *   <li>共享阵营：优先作者 API Key → 失败/熔断自动降级玩家私人 Key</li>
 *   <li>独狼阵营：仅使用玩家私人 Key</li>
 *   <li>熔断：连续失败 N 次熔断 T 秒；失败只记录状态码与响应摘要，绝不打印 Key</li>
 * </ul>
 */
public class ApiClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Gson GSON = new GsonBuilder().create();

    // ===== 熔断状态（全局）=====
    private static volatile int consecutiveFailures;
    private static volatile long breakerOpensAtMillis;

    private ApiClient() {
    }

    // ===== 公开入口 =====

    /**
     * 异步生成魂技。返回 empty 表示：无可用 API / 熔断 / 生成失败 / 校验不通过。
     * 调用方不得阻塞主线程等待（内部 sendAsync 异步）。
     */
    public static CompletableFuture<Optional<SkillData>> generate(
            PlayerSkillConfig cfg, String wuhun, String mobId, String mobHealthSegment,
            int ringAge, float sourceMaxHealth) {
        String playerKey = cfg == null ? "" : cfg.apiKey();
        String url = CloudConfig.AI_AUTHOR_URL.get();
        boolean solo = cfg != null && cfg.isSolo();

        if (solo) {
            // 独狼：仅玩家私人 Key
            if (playerKey.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
            return request(url, playerKey, wuhun, mobId, mobHealthSegment, ringAge, sourceMaxHealth);
        }

        // 共享：作者 API → 玩家 Key 降级
        String authorKey = Credentials.aiAuthorApiKey();
        if (!authorKey.isEmpty()) {
            return request(url, authorKey, wuhun, mobId, mobHealthSegment, ringAge, sourceMaxHealth)
                    .thenCompose(res -> {
                        if (res.isPresent()) return CompletableFuture.completedFuture(res);
                        if (!playerKey.isEmpty()) {
                            LOGGER.warn("AI 作者 API 生成失败，降级使用玩家私人 Key");
                            return request(url, playerKey, wuhun, mobId, mobHealthSegment, ringAge, sourceMaxHealth);
                        }
                        return CompletableFuture.completedFuture(Optional.empty());
                    });
        }
        if (!playerKey.isEmpty()) {
            return request(url, playerKey, wuhun, mobId, mobHealthSegment, ringAge, sourceMaxHealth);
        }
        return CompletableFuture.completedFuture(Optional.empty());
    }

    // ===== 请求 =====

    private static CompletableFuture<Optional<SkillData>> request(
            String url, String apiKey, String wuhun, String mobId, String mobHealthSegment,
            int ringAge, float sourceMaxHealth) {
        if (url.isEmpty() || apiKey.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (isBreakerOpen()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("model", CloudConfig.AI_MODEL.get());
        payload.add("messages", buildMessages(wuhun, mobId, ringAge, sourceMaxHealth));
        payload.addProperty("temperature", 0.9);
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        payload.add("response_format", responseFormat);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(10, CloudConfig.AI_TIMEOUT_SECONDS.get())))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        recordFailure();
                        LOGGER.warn("AI 请求失败: HTTP {} (body: {})", resp.statusCode(), truncate(resp.body()));
                        return Optional.<SkillData>empty();
                    }
                    Optional<SkillData> data = parseSkill(resp.body(), wuhun, mobId, mobHealthSegment);
                    if (data.isPresent()) recordSuccess();
                    else recordFailure();
                    return data;
                })
                .exceptionally(ex -> {
                    recordFailure();
                    LOGGER.warn("AI 请求异常: {}", ex.getClass().getSimpleName());
                    return Optional.empty();
                });
    }

    private static JsonArray buildMessages(String wuhun, String mobId, int ringAge, float sourceMaxHealth) {
        JsonArray messages = new JsonArray();

        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", SYSTEM_PROMPT);
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", "请为以下组合设计 1 个魂技。\n"
                + "武魂: " + wuhun + "\n"
                + "魂兽: " + describeMobForAi(mobId) + "\n"
                + "魂环年限: " + ringAge + " 年"
                + (sourceMaxHealth > 0 ? "（该年限魂兽最大生命约 " + (long) sourceMaxHealth + " 点）" : "")
                + "\n"
                + "要求: 技能名称体现武魂与魂兽特征，可直接按格式输出 JSON。");
        messages.add(user);
        return messages;
    }

    /**
     * 把魂兽 mob_id（如 entity.iceandfire.fire_dragon）转换成带模组来源的中文描述，
     * 仅用于 AI 提示词，避免同名生物歧义（例如不同模组都有"巫妖王"）。
     * 原版生物 → "Minecraft中的<生物名>"；模组生物 → "<模组名>模组的<生物名>"。
     */
    private static String describeMobForAi(String mobId) {
        String raw = mobId == null ? "unknown" : mobId.trim();
        if (raw.isEmpty() || "unknown".equalsIgnoreCase(raw)) return "未知生物";

        String modId = null;
        String entityName = raw;
        if (raw.startsWith("entity.")) {
            String rest = raw.substring("entity.".length());
            int dot = rest.indexOf('.');
            if (dot > 0) {
                modId = rest.substring(0, dot);
                entityName = rest.substring(dot + 1);
            } else {
                entityName = rest;
            }
        } else if (raw.contains(":")) { // 兜底：直接给注册名
            int colon = raw.indexOf(':');
            modId = raw.substring(0, colon);
            entityName = raw.substring(colon + 1);
        }

        // 生物显示名：优先实体本地化名，翻译缺失/无法解析时退回注册名
        String displayName = entityName;
        if (modId != null && !entityName.isEmpty()) {
            try {
                ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(modId, entityName);
                Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(rl);
                if (type.isPresent()) {
                    String localized = type.get().getDescription().getString();
                    if (localized != null && !localized.isEmpty() && !localized.startsWith("entity.")) {
                        displayName = localized;
                    }
                }
            } catch (Exception ignored) { /* 未知实体，用注册名兜底 */ }
        }

        if (modId == null || modId.isEmpty()) return raw;
        if ("minecraft".equals(modId)) return "Minecraft中的" + displayName;

        String modDisplay = modId;
        try {
            Optional<? extends ModContainer> container = ModList.get().getModContainerById(modId);
            if (container.isPresent()) {
                String name = container.get().getModInfo().getDisplayName();
                if (name != null && !name.isEmpty()) modDisplay = name;
            }
        } catch (Exception ignored) { /* 未知 mod，用 modid 兜底 */ }
        return modDisplay + "模组的" + displayName;
    }

    // ===== 响应解析 =====

    private static Optional<SkillData> parseSkill(String body, String wuhun, String mobId, String segment) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String content = root.getAsJsonArray("choices").get(0)
                    .getAsJsonObject().getAsJsonObject("message")
                    .get("content").getAsString();
            String cleaned = stripJsonFence(content);
            JsonObject skillJson = JsonParser.parseString(cleaned).getAsJsonObject();
            return Optional.ofNullable(SkillData.fromAiJson(skillJson, wuhun, mobId, segment));
        } catch (Exception e) {
            LOGGER.warn("AI 响应解析失败: {} (body: {})", e.getClass().getSimpleName(), truncate(body));
            return Optional.empty();
        }
    }

    /** 去除 markdown 代码围栏（```json ... ```） */
    private static String stripJsonFence(String content) {
        String s = content.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline >= 0) s = s.substring(firstNewline + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.trim();
        }
        return s;
    }

    // ===== 熔断 =====

    private static boolean isBreakerOpen() {
        long openAt = breakerOpensAtMillis;
        if (openAt == 0) return false;
        if (System.currentTimeMillis() >= openAt) {
            breakerOpensAtMillis = 0;
            consecutiveFailures = 0;
            return false;
        }
        return true;
    }

    private static synchronized void recordFailure() {
        consecutiveFailures++;
        int threshold = Math.max(1, CloudConfig.AI_BREAKER_FAILURES.get());
        if (consecutiveFailures >= threshold) {
            breakerOpensAtMillis = System.currentTimeMillis()
                    + Math.max(5, CloudConfig.AI_BREAKER_OPEN_SECONDS.get()) * 1000L;
            LOGGER.warn("AI 接口连续失败 {} 次，已熔断 {} 秒", consecutiveFailures,
                    Math.max(5, CloudConfig.AI_BREAKER_OPEN_SECONDS.get()));
        }
    }

    private static synchronized void recordSuccess() {
        consecutiveFailures = 0;
        breakerOpensAtMillis = 0;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 150 ? s.substring(0, 150) + "..." : s;
    }

    // ===== System Prompt（原语约束）=====

    private static final String SYSTEM_PROMPT = buildSystemPrompt();

    private static String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是斗罗大陆世界观下的魂技设计大师。为指定 (武魂+魂兽+魂环年限) 组合生成魂技。\n");
        sb.append("必须严格输出 JSON 对象，格式如下：\n");
        sb.append("{\"name\":\"技能名\",\"description\":\"技能描述\",\"cooldown\":20,\"trigger\":\"RIGHT_CLICK\",\"execution\":[{\"primitive\":\"AOE_DAMAGE\",\"target\":\"TARGET\",\"radius\":3.0,\"value\":5.0}]}\n");
        sb.append("要求：\n");
        sb.append("1. 技能名体现武魂与魂兽特征；description 不超过 30 字。\n");
        sb.append("2. execution 为动作原语数组，1~4 个步骤。每个步骤 primitive 必须从以下原语中选取，参数只能使用该原语允许的参数：\n");
        for (SkillPrimitive p : SkillPrimitive.values()) {
            sb.append("   - ").append(p.name());
            sb.append(" [允许参数: ");
            boolean first = true;
            for (Param param : p.allowedParams()) {
                if (!first) sb.append(", ");
                sb.append(param.key());
                first = false;
            }
            sb.append("]\n");
        }
        sb.append("3. 参数说明：value=数值(double)、radius=范围半径格数(double)、duration=持续tick(int，1秒=20tick)、");
        sb.append("target=SELF或TARGET、effect=药水效果注册名、entity_id=实体注册名、");
        sb.append("percent=百分比(0.0~1.0)、count=数量(int)、speed=速度倍率(double)、damage_type=MELEE/MAGIC/FIRE/EXPLOSION。\n");
        sb.append("4. target 目标规则（最关键，写错会导致减益/伤害作用到自己身上）：\n");
        sb.append("   - 伤害/减益/控制类原语（BURST/AOE_DAMAGE/AOE_BURST/ARMOR_BREAK/EXECUTE/COMBO/BACKSTAB/CHARGE/FIRE/PROJECTILE/KNOCKBACK/POTION/SLOW/BLIND/ROOT/WEAKEN/CONFUSE/STUN/BIND/SILENCE/DISARM）：target 必须为 TARGET，作用于敌人，绝不允许 SELF。\n");
        sb.append("   - 增益/治疗/防御/位移类原语（ACCELERATE/BUFF_STATS/BUFF_ALL/HOT/REVIVE/REFLECT/BARRIER/IRON_BODY/HARDEN/PHANTOM/EVADE/CLEANSE/SHIELD_TRANSFER/DASH/TAUNT）：target 为 SELF（作用于自身）；HOT/REVIVE/CLEANSE/SHIELD_TRANSFER 想治疗队友时可用 TARGET。\n");
        sb.append("   - AOE_DAMAGE/AOE_BURST 以 target 为圆心，target 必须为 TARGET；省略 target 时默认以自身为圆心且不会伤害自己。\n");
        sb.append("5. 数值规则（以提供的魂环年限与魂兽最大生命为基准，禁止套用固定小数值）：\n");
        sb.append("   - 系统最终伤害 = value × 原语系数 × 年限倍率。年限倍率：十年×1、百年×2、千年×4、万年×8、十万年×16、百万年×32。原语系数：BURST×2、AOE_BURST×1.5、AOE_DAMAGE×1、COMBO×1(每段)、BACKSTAB×2.5、ARMOR_BREAK×1.2、EXECUTE×4(斩杀)/×1(普通)。\n");
        sb.append("   - 设计目标：单体技能单发约为魂兽最大生命的 8%~15%，范围技能单发约为 3%~6%，斩首/终结技可达 20% 以上。value = 目标单发伤害 ÷ 原语系数 ÷ 年限倍率。\n");
        sb.append("   - 治疗/护盾：value 约为玩家生命值(20点)的 30%~60% ÷ 年限倍率。\n");
        sb.append("   - 其他参考：radius 2~8、duration 40~200、cooldown 10~80，减益/增益效果等级不超过 2。\n");
        sb.append("6. 只输出 JSON，不要任何解释或 markdown 标记。");
        return sb.toString();
    }
}
