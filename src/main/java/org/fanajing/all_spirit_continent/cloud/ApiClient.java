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
 *   <li>分阶段温度（§13.1.1 + §13.7.1）：调用方按任务类型传入，
 *       {@link #DEFAULT_TEMPERATURE} 是后备常量（候选草案 0.7 中性偏发散）</li>
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

    /**
     * 默认 AI 温度（§13.1.1）：候选草案阶段使用 0.7（中性偏发散，配合 3 候选方向 prompt 已足够多样）；
     * 严禁使用 0.9（§13.7.1：temperature 0.9 太高，会产出乱码 JSON 与数值溢出）。
     * 解析/分类等"事实性"子任务可由调用方传更低温度（0.3~0.5）保持稳定。
     */
    public static final double DEFAULT_TEMPERATURE = 0.7;

    private ApiClient() {
    }

    // ===== 公开入口 =====

    /**
     * 异步生成魂技。返回 empty 表示：无可用 API / 熔断 / 生成失败 / 校验不通过。
     * 调用方不得阻塞主线程等待（内部 sendAsync 异步）。
     * <p>使用 {@link #DEFAULT_TEMPERATURE} 草案温度。
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
            return request(url, playerKey, wuhun, mobId, mobHealthSegment, ringAge, sourceMaxHealth, DEFAULT_TEMPERATURE);
        }

        // 共享：作者 API → 玩家 Key 降级
        String authorKey = Credentials.aiAuthorApiKey();
        if (!authorKey.isEmpty()) {
            return request(url, authorKey, wuhun, mobId, mobHealthSegment, ringAge, sourceMaxHealth, DEFAULT_TEMPERATURE)
                    .thenCompose(res -> {
                        if (res.isPresent()) return CompletableFuture.completedFuture(res);
                        if (!playerKey.isEmpty()) {
                            LOGGER.warn("AI 作者 API 生成失败，降级使用玩家私人 Key");
                            return request(url, playerKey, wuhun, mobId, mobHealthSegment, ringAge, sourceMaxHealth, DEFAULT_TEMPERATURE);
                        }
                        return CompletableFuture.completedFuture(Optional.empty());
                    });
        }
        if (!playerKey.isEmpty()) {
            return request(url, playerKey, wuhun, mobId, mobHealthSegment, ringAge, sourceMaxHealth, DEFAULT_TEMPERATURE);
        }
        return CompletableFuture.completedFuture(Optional.empty());
    }

    // ===== 请求 =====

    /**
     * 通用 AI 对话入口（魂技生成引擎 §7 用），使用 {@link #DEFAULT_TEMPERATURE}。
     * <p>与 {@link #generate} 共用 Key 选择（作者 → 玩家降级）与熔断器，
     * 但 system/user prompt 由调用方（SkillGenerator）完全自定义。
     *
     * @return 解析后的 skill_data JSON 对象；失败/熔断/无 Key 返回 empty
     */
    public static CompletableFuture<Optional<JsonObject>> chatJson(
            PlayerSkillConfig cfg, String systemPrompt, String userPrompt) {
        return chatJson(cfg, systemPrompt, userPrompt, DEFAULT_TEMPERATURE);
    }

    /**
     * 同 {@link #chatJson(PlayerSkillConfig, String, String)} 但允许调用方指定温度（§13.1.1 分阶段温度）：
     * <ul>
     *   <li>候选草案（生成 3 个候选）：0.7 中性偏发散</li>
     *   <li>签名选择 / 自检 / 分类（事实型子任务）：0.3~0.5 稳定</li>
     *   <li>严禁 0.9 以上（§13.7.1 会产出乱码 JSON）</li>
     * </ul>
     */
    public static CompletableFuture<Optional<JsonObject>> chatJson(
            PlayerSkillConfig cfg, String systemPrompt, String userPrompt, double temperature) {
        String playerKey = cfg == null ? "" : cfg.apiKey();
        String url = CloudConfig.AI_AUTHOR_URL.get();
        boolean solo = cfg != null && cfg.isSolo();
        double safeTemp = clampTemperature(temperature);

        if (solo) {
            if (playerKey.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
            return postChatJson(url, playerKey, systemPrompt, userPrompt, safeTemp);
        }
        String authorKey = Credentials.aiAuthorApiKey();
        if (!authorKey.isEmpty()) {
            return postChatJson(url, authorKey, systemPrompt, userPrompt, safeTemp)
                    .thenCompose(res -> {
                        if (res.isPresent()) return CompletableFuture.completedFuture(res);
                        if (!playerKey.isEmpty()) {
                            LOGGER.warn("AI 作者 API 生成失败，降级使用玩家私人 Key");
                            return postChatJson(url, playerKey, systemPrompt, userPrompt, safeTemp);
                        }
                        return CompletableFuture.completedFuture(Optional.empty());
                    });
        }
        if (!playerKey.isEmpty()) {
            return postChatJson(url, playerKey, systemPrompt, userPrompt, safeTemp);
        }
        return CompletableFuture.completedFuture(Optional.empty());
    }

    /** 把任意温度夹紧到 [0, 0.9]；负数/NaN 视作默认温度（防御性兜底） */
    private static double clampTemperature(double t) {
        if (Double.isNaN(t) || Double.isInfinite(t)) return DEFAULT_TEMPERATURE;
        return Math.max(0.0, Math.min(0.9, t));
    }

    /** 单次请求：自定义 prompt → 返回解析后的 JSON 对象 */
    private static CompletableFuture<Optional<JsonObject>> postChatJson(
            String url, String apiKey, String systemPrompt, String userPrompt, double temperature) {
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", systemPrompt);
        messages.add(system);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userPrompt);
        messages.add(user);
        return postChat(url, apiKey, messages, temperature).thenApply(content ->
                content.map(c -> {
                    try {
                        return JsonParser.parseString(stripJsonFence(c)).getAsJsonObject();
                    } catch (Exception e) {
                        LOGGER.warn("AI 响应 JSON 解析失败: {}", e.getClass().getSimpleName());
                        return null;
                    }
                }));
    }

    private static CompletableFuture<Optional<SkillData>> request(
            String url, String apiKey, String wuhun, String mobId, String mobHealthSegment,
            int ringAge, float sourceMaxHealth, double temperature) {
        if (url.isEmpty() || apiKey.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return postChat(url, apiKey,
                buildMessages(wuhun, mobId, ringAge, sourceMaxHealth), temperature)
                .thenApply(content -> content.flatMap(c ->
                        parseSkill(c, wuhun, mobId, mobHealthSegment)));
    }

    /** HTTP 核心：发送 chat/completions 请求，成功返回 message.content 字符串 */
    private static CompletableFuture<Optional<String>> postChat(String url, String apiKey, JsonArray messages, double temperature) {
        if (url == null || url.isEmpty() || apiKey == null || apiKey.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (isBreakerOpen()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("model", CloudConfig.AI_MODEL.get());
        payload.add("messages", messages);
        payload.addProperty("temperature", clampTemperature(temperature));
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
                        return Optional.<String>empty();
                    }
                    recordSuccess();
                    try {
                        String content = resp.body() == null ? "" : resp.body();
                        JsonObject root = JsonParser.parseString(content).getAsJsonObject();
                        return Optional.of(root.getAsJsonArray("choices").get(0)
                                .getAsJsonObject().getAsJsonObject("message")
                                .get("content").getAsString());
                    } catch (Exception e) {
                        LOGGER.warn("AI 响应结构异常: {}", e.getClass().getSimpleName());
                        return Optional.<String>empty();
                    }
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
                + "要求: 结合魂兽的标志性机制设计有创意的联动魂技，技能名称体现武魂与魂兽特征，直接按格式输出 JSON。");
        messages.add(user);
        return messages;
    }

    /**
     * 把魂兽 mob_id（如 entity.iceandfire.fire_dragon）转换成带模组来源的中文描述，
     * 仅用于 AI 提示词，避免同名生物歧义（例如不同模组都有"巫妖王"）。
     * 原版生物 → "Minecraft中的<生物名>"；模组生物 → "<模组名>模组的<生物名>"。
     * <p>公开给魂技生成引擎（SkillGenerator）复用。
     */
    public static String describeMobForAi(String mobId) {
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

    /** 从 message.content（已提取的字符串）解析 skill_data；失败返回 empty */
    private static Optional<SkillData> parseSkill(String content, String wuhun, String mobId, String segment) {
        try {
            JsonObject skillJson = JsonParser.parseString(stripJsonFence(content)).getAsJsonObject();
            return Optional.ofNullable(SkillData.fromAiJson(skillJson, wuhun, mobId, segment));
        } catch (Exception e) {
            LOGGER.warn("AI 响应解析失败: {}", e.getClass().getSimpleName());
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
        sb.append("{\"name\":\"技能名\",\"description\":\"技能描述\",\"cooldown\":20,\"trigger\":\"RIGHT_CLICK\",\"execution\":[{\"primitive\":\"AOE_DAMAGE\",\"target\":\"TARGET\",\"radius\":3.0,\"value\":5.0}],\"passive\":[{\"primitive\":\"BUFF_STATS\",\"target\":\"SELF\",\"attribute\":\"ATTACK_DAMAGE\",\"percent\":0.3,\"duration\":200}]}\n");
        sb.append("要求：\n");
        sb.append("0. 创意设计（比格式更重要，禁止平庸输出）：\n");
        sb.append("   - 主题契合：研究魂兽的标志性招式与世界观，把它的招牌机制变成魂技核心（例：巫妖王=灵魂操控+亡灵统御+冰霜；冰龙=龙息+冰封；凤凰=涅槃+灼烧；蜘蛛女皇=织网+毒液+产卵）。\n");
        sb.append("   - 机制联动：execution 的多步骤必须构成战斗连招（例：先减速定身→再爆发；先施虚弱→再斩杀；先召唤仆从→再自身强化），禁止各步骤互不相干的数值堆叠。\n");
        sb.append("   - 战斗定位：明确本技能是 爆发/控场/斩杀/续航/召唤/防御 中的哪一两类，按定位选原语。\n");
        sb.append("   - 视觉表现：可用 PARTICLE/SOUND 提升打击感，但至多 1 个表现步骤，不可喧宾夺主。\n");
        sb.append("   - 禁止裸堆叠：禁止只输出\"一个范围伤害+一个减速\"式的最小组合，至少体现一次机制联动或主题特征。\n");
        sb.append("   - 融合被动：每个魂技可带可选 passive 数组（手持武魂武器时由系统持续施加，常驻生效，属于\"融合被动\"）。passive 只能使用被动白名单原语（见要求2末），target 必须为 SELF，禁止伤害/控制/召唤类原语混入。\n");
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
        sb.append("   被动白名单（passive 专用，仅限以下原语）：ACCELERATE、BUFF_STATS、BUFF_ALL、HOT、SHIELD_TRANSFER、CLEANSE、REFLECT、BARRIER、PHANTOM、PARTICLE；这些原语也可用于主动 execution。\n");
        sb.append("3. 参数说明：value=数值(double)、radius=范围半径格数(double)、duration=持续tick(int，1秒=20tick)、");
        sb.append("target=SELF或TARGET、effect=药水效果注册名、entity_id=实体注册名、");
        sb.append("percent=百分比(0.0~1.0)、count=数量(int)、speed=速度倍率(double)、damage_type=MELEE/MAGIC/FIRE/EXPLOSION。\n");
        sb.append("4. target 目标规则（最关键，写错会导致减益/伤害作用到自己身上）：\n");
        sb.append("   - 伤害/减益/控制类原语（BURST/AOE_DAMAGE/AOE_BURST/ARMOR_BREAK/EXECUTE/COMBO/BACKSTAB/CHARGE/FIRE/PROJECTILE/KNOCKBACK/POTION/SLOW/BLIND/ROOT/WEAKEN/CONFUSE/STUN/BIND/SILENCE/DISARM）：target 必须为 TARGET，作用于敌人，绝不允许 SELF。\n");
        sb.append("   - 增益/治疗/防御/位移类原语（ACCELERATE/BUFF_STATS/BUFF_ALL/HOT/REVIVE/REFLECT/BARRIER/IRON_BODY/HARDEN/PHANTOM/EVADE/CLEANSE/SHIELD_TRANSFER/DASH/TAUNT）：target 为 SELF（作用于自身）；HOT/REVIVE/CLEANSE/SHIELD_TRANSFER 想治疗队友时可用 TARGET。\n");
        sb.append("   - AOE_DAMAGE/AOE_BURST 以 target 为圆心，target 必须为 TARGET；省略 target 时默认以自身为圆心且不会伤害自己。\n");
        sb.append("5. 数值规则（以提供的魂环年限与魂兽最大生命为基准，禁止套用固定小数值）：\n");
        sb.append("   - 系统最终伤害 = value × 原语系数 × 年限倍率。年限倍率：十年×1、百年×2、千年×4、万年×8、十万年×16、百万年×32。原语系数：BURST×2、AOE_BURST×1.5、AOE_DAMAGE×1、COMBO×1(每段)、BACKSTAB×2.5、ARMOR_BREAK×1.2、EXECUTE×4(斩杀)/×1(普通)、CHARGE×1(贯穿)。\n");
        sb.append("   - 设计目标：单体技能单发约为魂兽最大生命的 8%~15%，范围技能单发约为 3%~6%，斩首/终结技可达 20% 以上。\n");
        sb.append("   - ★ 反推公式：raw_value = 目标伤害 ÷ 原语系数 ÷ 年限倍率。\n");
        sb.append("     ★ 关键：raw_value 必须落在本环位提示的数值范围 [min, max] 内，否则引擎校验直接拒收！\n");
        sb.append("     例如 9 环 value 范围 [76, 140] → raw_value 必须取 76~140 之间；百年环 value 范围 [4, 10] → 必须取 4~10。\n");
        sb.append("   - EXECUTE 语义（§13.7.2，引擎 §7.5）：value 同时承担两个角色——\n");
        sb.append("     · 斩杀阈值：target.getHealth() ≤ value × 2 时进入斩杀分支（伤害 ×4）\n");
        sb.append("     · 伤害基准：斩杀时伤害 = value × 4 × 年限倍率；普通分支 = value × 1 × 年限倍率\n");
        sb.append("     推荐：value ≈ 目标满血量的 1%~2%，使斩杀线约为满血 2%~4%，对残血敌人一击必杀、对满血敌人仍有压力。\n");
        sb.append("   - 治疗/护盾：value 约为玩家生命值(20点)的 30%~60% ÷ 年限倍率；SHIELD_TRANSFER 用 amplifier 表示护盾强度(1~4)、duration 表示持续(秒×20)。\n");
        sb.append("   - 被动（passive）：duration 统一给 200（10秒，系统每 2 秒自动续杯），amplifier 1~3，BUFF_STATS 的 percent 给 0.1~0.5。\n");
        sb.append("   - 其他参考：减益/增益效果等级不超过 2。\n");
        sb.append("6. 武魂真身（第 9 环）硬约束（§13.7.6）：严禁使用 SUMMON_ENTITY 原语，魂技必须为自身爆发型；生活系原语（CROP_GROW/FOOD_BLESS/HARVEST/BONEMEAL）同样禁用于战斗技能。\n");
        sb.append("7. 优秀范例（学习其组合思路与数值反推法；注意：raw_value 已校准到对应环位范围，AI 必须按本次环位提示范围重新计算）：\n");
        sb.append("   范例一「昊天·灵魂哀霜葬」：9 环控场+斩杀+护盾联动（巫妖王系，百万年/满血600000，倍率32，value 范围 [76, 140]）：\n");
        sb.append("   {\"name\":\"昊天·灵魂哀霜葬\",\"description\":\"锤击爆发灵魂震荡，虚弱敌群，斩首濒死者并凝亡魂护盾\",\"cooldown\":1800,\"trigger\":\"RIGHT_CLICK\",\"execution\":[{\"primitive\":\"AOE_BURST\",\"target\":\"TARGET\",\"value\":100,\"radius\":6,\"damage_type\":\"MAGIC\"},{\"primitive\":\"WEAKEN\",\"target\":\"TARGET\",\"duration\":1000,\"amplifier\":1},{\"primitive\":\"EXECUTE\",\"target\":\"TARGET\",\"value\":100,\"crit_chance\":0.25},{\"primitive\":\"SHIELD_TRANSFER\",\"target\":\"SELF\",\"amplifier\":2,\"duration\":1000}],\"passive\":[{\"primitive\":\"BUFF_STATS\",\"target\":\"SELF\",\"attribute\":\"ATTACK_DAMAGE\",\"percent\":0.3,\"duration\":200},{\"primitive\":\"PARTICLE\",\"particle\":\"minecraft:soul_fire_flame\",\"count\":2}]}\n");
        sb.append("   （反推：AOE_BURST raw 100 → 单发 100×1.5×32=4800（满血 0.8%）；EXECUTE raw 100 → 斩杀线 200 血、斩杀 100×4×32=12800（2.1%）、普通 100×1×32=3200（0.5%））\n");
        sb.append("   范例二「赤焰·浴火冲锋」：5 环突进+点燃+爆发联动（火龙系，万年/满血10000，倍率8，value 范围 [16, 32]）：\n");
        sb.append("   {\"name\":\"赤焰·浴火冲锋\",\"description\":\"炎翼疾冲贯穿敌阵，龙焰点燃后灼热爆发\",\"cooldown\":360,\"trigger\":\"RIGHT_CLICK\",\"execution\":[{\"primitive\":\"CHARGE\",\"target\":\"TARGET\",\"distance\":6,\"value\":24,\"speed\":1.5},{\"primitive\":\"FIRE\",\"target\":\"TARGET\",\"duration\":160,\"radius\":0},{\"primitive\":\"BURST\",\"target\":\"TARGET\",\"value\":24,\"damage_type\":\"FIRE\",\"crit_chance\":0.2}]}\n");
        sb.append("   （反推：CHARGE raw 24 → 单发 24×1×8=192（满血 1.9%）；BURST raw 24 → 单发 24×2×8=384（3.8%）；合计连段命中约 6% 满血）\n");
        sb.append("7. 只输出 JSON，不要任何解释或 markdown 标记。");
        return sb.toString();
    }
}
