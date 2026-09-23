package org.fanajing.all_spirit_continent.skill.engine;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import org.fanajing.all_spirit_continent.cloud.ApiClient;
import org.fanajing.all_spirit_continent.data.PlayerSkillDataStore;
import org.fanajing.all_spirit_continent.skill.AbilitySignature;
import org.fanajing.all_spirit_continent.skill.Param;
import org.fanajing.all_spirit_continent.skill.SkillData;
import org.fanajing.all_spirit_continent.skill.SkillPrimitive;
import org.fanajing.all_spirit_continent.skill.SkillPrimitive.Category;
import org.fanajing.all_spirit_continent.skill.profile.MobProfile;
import org.fanajing.all_spirit_continent.skill.profile.ProfileCache;
import org.fanajing.all_spirit_continent.util.PlayerSkillConfig;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 魂技生成引擎（引擎文档 §7）。5 阶段流水线：
 * <ol>
 *   <li><b>分析</b>：MobProfile（行为画像）+ 玩家已有魂技（联动上下文）</li>
 *   <li><b>签名</b>：SignaturePool 抽签并绑定玩家级签名机制（同环重刷保持同机制）</li>
 *   <li><b>草案</b>：3 候选并行生成（独立设计方向：爆发 / 控场 / 续航防御）</li>
 *   <li><b>自检</b>：SkillValidator 四层校验（基础 + 环位硬约束 + 签名唯一性 + 数值范围）</li>
 *   <li><b>输出</b>：5 项权重评分选优 + 多样性检查（jaccard &gt; 0.7 拒收）；
 *       全军覆没时降级调用 V6.0 {@link ApiClient#generate} 旧路径</li>
 * </ol>
 * 评分权重（引擎文档 §7.6）：签名机制契合 30% / 魂兽特征 25% / 联动 20% / 原语匹配 15% / 表现资源 10%。
 * <p>
 * 调用约定：{@link #generate} 必须在服务端主线程调用（阶段 1/2 同步执行，
 * 涉及 {@link PlayerSkillConfig} 写入）；阶段 3~5 异步执行，返回的 Future 在 HTTP 线程完成。
 */
public final class SkillGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final RandomSource RANDOM = RandomSource.create();

    /** 候选数（文档原设计：3 候选 × 评分选优，API 费用 ×3） */
    public static final int CANDIDATE_COUNT = 3;
    /** 多样性检查阈值：两候选原语集合 jaccard 超过该值拒收低分者 */
    public static final double DIVERSITY_JACCARD_LIMIT = 0.7;

    // ===== §7.10 温度控制（分阶段；与 ApiClient 的 clampTemperature 配合，严禁 0.9+）=====
    /** 阶段 3（草案）使用的高随机温度，配合 DIRECTIONS（3 个候选方向 prompt）已足够多样 */
    static final double TEMP_DRAFT = 1.0;
    /** 阶段 4（自检反思）使用的低随机温度——legacy 旧 prompt 重生成时用 */
    static final double TEMP_REFLECT = 0.3;
    /** 阶段 5（输出收束）使用的极低温度——再次强化数值精确性 */
    static final double TEMP_OUTPUT = 0.2;

    // ===== §7.11 多轮生成策略 =====
    /** 单候选最多重试次数（每次重试都会把校验错误回灌给 AI） */
    public static final int MAX_RETRY_PER_CANDIDATE = 3;

    // ===== 评分权重（§7.6）=====
    static final double W_MECHANISM = 0.30;
    static final double W_MOB = 0.25;
    static final double W_LINKAGE = 0.20;
    static final double W_PRIMITIVE = 0.15;
    static final double W_PRESENTATION = 0.10;

    /** 3 候选的差异化设计方向 */
    static final String[] DIRECTIONS = {
            "爆发输出（伤害优先，主动进攻，直取连招收割）",
            "控场削弱（控制/减益优先，先手铺垫再打击）",
            "续航防御（治疗/护盾/增益优先，稳中求胜）"
    };

    private SkillGenerator() {
    }

    // ===== 数据载体 =====

    /** 一次生成请求（全部字段由接入点在服务端主线程填充） */
    public record Request(
            ServerPlayer player,
            PlayerSkillConfig cfg,
            String wuhun,
            String mobId,
            String mobHealthSegment,
            int slot,
            int ringAge,
            float sourceMaxHealth
    ) {
    }

    /** 生成结果 */
    public record Result(SkillData skill, SignatureMechanism mechanism, String source, double score) {
        public static final String SOURCE_ENGINE = "ENGINE";
        public static final String SOURCE_FALLBACK = "FALLBACK";

        public boolean fromEngine() {
            return SOURCE_ENGINE.equals(source);
        }
    }

    /** 评分上下文（阶段 1 产物快照，异步阶段只读） */
    private record ScoringContext(
            int slot,
            RingPositionRules.RuleSpec rule,
            SignatureMechanism mechanism,
            MobProfile profile,
            List<SkillData> otherRingSkills
    ) {
    }

    // ===== 主入口 =====

    /**
     * 生成一个魂技（5 阶段流水线）。
     *
     * @return empty 表示：无可用签名机制 / 3 候选与降级路径全部失败
     */
    public static CompletableFuture<Optional<Result>> generate(Request req) {
        if (req == null || req.player() == null || req.cfg() == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        int slot = req.slot();
        if (slot < 1 || slot > RingPositionRules.maxSlot()) {
            LOGGER.warn("生成请求环位非法: {}", slot);
            return CompletableFuture.completedFuture(Optional.empty());
        }

        // ===== 阶段 1：分析（主线程同步）=====
        MobProfile profile = ProfileCache.get(req.mobId());
        List<SkillData> others = new ArrayList<>();
        for (Map.Entry<Integer, SkillData> e : req.cfg().rings().entrySet()) {
            if (e.getKey() != slot && e.getValue() != null) others.add(e.getValue());
        }

        // ===== 阶段 2：签名（主线程同步；玩家级持久化）=====
        SignatureMechanism mechanism = req.cfg().mechanismAt(slot).orElse(null);
        if (mechanism == null) {
            Optional<SignatureMechanism> drawn = SignaturePool.drawAndBind(slot, req.cfg(), RANDOM);
            if (drawn.isEmpty()) {
                LOGGER.warn("环位 {} 无可用签名机制（池耗尽），生成终止", slot);
                return CompletableFuture.completedFuture(Optional.empty());
            }
            mechanism = drawn.get();
            PlayerSkillDataStore.get(req.player().serverLevel()).setDirty();
            LOGGER.info("玩家 {} 环位 {} 抽得签名机制 {}「{}」",
                    req.player().getGameProfile().getName(), slot, mechanism.code, mechanism.displayName);
        }
        final SignatureMechanism finalMechanism0 = mechanism;
        final ScoringContext ctx = new ScoringContext(
                slot, RingPositionRules.forSlot(slot), finalMechanism0, profile, others);

        // ===== 阶段 3：草案（3 候选并行，异步，每个候选最多 MAX_RETRY_PER_CANDIDATE 次重试）=====
        // §7.11 多轮生成策略：每次候选生成注入独立 randomSeed + 校验失败 retry hint
        List<CompletableFuture<Optional<SkillData>>> calls = new ArrayList<>();
        for (int i = 0; i < CANDIDATE_COUNT; i++) {
            String direction = DIRECTIONS[i % DIRECTIONS.length];
            long seed = RANDOM.nextLong();
            String baseUserPrompt = buildUserPrompt(req, ctx, direction, seed);
            calls.add(generateOneCandidateWithRetry(req, ctx, direction, baseUserPrompt, seed));
        }

        // ===== 阶段 4 + 5：自检 → 评分选优（异步）=====
        SignatureMechanism finalMechanism = finalMechanism0;
        return CompletableFuture.allOf(calls.toArray(new CompletableFuture[0]))
                .thenCompose(v -> {
                    List<SkillData> valid = new ArrayList<>();
                    for (CompletableFuture<Optional<SkillData>> call : calls) {
                        SkillData data = call.join().orElse(null);
                        if (data == null) continue;
                        data = data.withMechanism(finalMechanism.code).withRingAge(req.ringAge());
                        List<String> errors = SkillValidator.collect(data, slot, req.cfg());
                        if (errors.isEmpty()) {
                            valid.add(data);
                        } else {
                            LOGGER.debug("候选被引擎校验拒收: {} -> {}", data.name(), errors);
                        }
                    }
                    if (valid.isEmpty()) {
                        // §8.2 第 2 层（重试）+ 第 3 层（LocalRepair）+ 第 4 层（MVP 兜底）链式降级
                        LOGGER.warn("环位 {} 的 3 候选全部未通过校验，启动 §8.2 三段式降级链", slot);
                        return legacyFallback(req, finalMechanism);
                    }
                    return CompletableFuture.completedFuture(Optional.of(selectBest(valid, ctx, finalMechanism)));
                })
                .exceptionally(ex -> {
                    LOGGER.warn("生成引擎异常，启动 §8.2 三段式降级链: {}", ex.getClass().getSimpleName());
                    return legacyFallback(req, finalMechanism).join();
                });
    }

    /**
     * §8.2 三段式降级链（生成主流程的兜底）：
     * <ol>
     *   <li><b>V6.0 旧 prompt</b>（{@link ApiClient#generate}）→ 拿到一条 legacy 技能</li>
     *   <li><b>本地规则修复</b>（{@link LocalRepair}）→ 数值夹紧 / target 修正 / 禁用原语删除</li>
     *   <li><b>数值反推</b>（{@link ValueCalculator#calibrate}）→ 按伤害目标反推 value</li>
     *   <li>任何一步失败都跳到 <b>MVP 兜底</b>（{@link FallbackSkillBuilder}），100% 可用</li>
     * </ol>
     */
    private static CompletableFuture<Optional<Result>> legacyFallback(Request req, SignatureMechanism mechanism) {
        return ApiClient.generate(req.cfg(), req.wuhun(), req.mobId(), req.mobHealthSegment(),
                        req.ringAge(), req.sourceMaxHealth())
                .thenCompose(opt -> {
                    // 没有 legacy 输出 → 直接走 MVP 兜底
                    if (opt.isEmpty()) {
                        LOGGER.warn("V6.0 legacy 路径无输出，直接生成 MVP 兜底技能");
                        return CompletableFuture.completedFuture(Optional.of(buildFallback(req, mechanism, "legacy-empty")));
                    }
                    SkillData raw = opt.get().withMechanism(mechanism.code).withRingAge(req.ringAge());
                    RingPositionRules.RuleSpec rule = RingPositionRules.forSlot(req.slot());

                    // §8.2 第 3 层：本地规则修复
                    LocalRepair.Outcome repair = LocalRepair.repair(raw, rule, mechanism);
                    if (!repair.ok()) {
                        LOGGER.warn("LocalRepair 无法修复（{}），生成 MVP 兜底技能", repair.repairs());
                        return CompletableFuture.completedFuture(Optional.of(buildFallback(req, mechanism, "repair-failed")));
                    }
                    SkillData repaired = repair.data();

                    // §8.3 数值反推校正
                    if (req.sourceMaxHealth() > 0) {
                        repaired = ValueCalculator.calibrate(repaired, req.sourceMaxHealth(), req.ringAge());
                    }

                    // 再次校验：修复 + 反推后必须通过
                    List<String> postErrors = SkillValidator.collect(repaired, req.slot(), req.cfg());
                    if (!postErrors.isEmpty()) {
                        LOGGER.warn("修复后技能仍未通过校验 {}，生成 MVP 兜底技能", postErrors);
                        return CompletableFuture.completedFuture(Optional.of(buildFallback(req, mechanism, "post-validate-failed")));
                    }
                    LOGGER.info("V6.0 legacy 路径经 LocalRepair + calibrate 修复成功: {}", repaired.name());
                    return CompletableFuture.completedFuture(Optional.of(
                            new Result(repaired, mechanism, Result.SOURCE_FALLBACK, 0.0)));
                });
    }

    /** MVP 兜底技能包装为 Result；reason 用于日志区分（legacy-empty / repair-failed / post-validate-failed） */
    private static Result buildFallback(Request req, SignatureMechanism mechanism, String reason) {
        SkillData fb = FallbackSkillBuilder.build(
                req.slot(), req.wuhun(), req.mobId(), req.mobHealthSegment(),
                mechanism, req.ringAge());
        LOGGER.info("MVP 兜底技能生成完成 (原因: {}, 环位 {} / 机制 {} / 名称 {})",
                reason, req.slot(), mechanism != null ? mechanism.code : "?", fb.name());
        return new Result(fb, mechanism, Result.SOURCE_FALLBACK, 0.0);
    }

    /**
     * §7.11 单候选多轮重试：
     * <ol>
     *   <li>第 1 次用 {@link #TEMP_DRAFT}（草案高随机）调用 AI</li>
     *   <li>解析失败 / 引擎校验不通过 → 把错误列表作为 retryHint 注入 user prompt，
     *       温度降到 {@link #TEMP_REFLECT} 提升 JSON 精确性，重试</li>
     *   <li>最多 {@link #MAX_RETRY_PER_CANDIDATE} 次；最后一次失败时丢弃</li>
     *   <li>整个流程在 HTTP 线程中顺序执行（同一候选不能并发）</li>
     * </ol>
     */
    private static CompletableFuture<Optional<SkillData>> generateOneCandidateWithRetry(
            Request req, ScoringContext ctx, String direction, String baseUserPrompt, long randomSeed) {
        String systemPrompt = buildSystemPrompt(req.slot(), ctx.mechanism());
        CompletableFuture<Optional<SkillData>> chain = CompletableFuture.completedFuture(Optional.empty());
        for (int attempt = 0; attempt < MAX_RETRY_PER_CANDIDATE; attempt++) {
            final int retryNo = attempt;
            chain = chain.thenCompose(prev -> {
                // 上一轮有合法 SkillData → 提前成功
                if (prev.isPresent()) return CompletableFuture.completedFuture(prev);

                double temp = retryNo == 0 ? TEMP_DRAFT : TEMP_REFLECT;
                String userPrompt = baseUserPrompt;
                if (retryNo > 0) {
                    // §7.11 反馈式重试：附上次校验错误；温度降低
                    userPrompt = baseUserPrompt + "\n[第 " + (retryNo + 1) + " 次重试] 上次输出未通过引擎校验，"
                            + "请修正以下问题后重新输出 JSON：\n"
                            + "- 严禁 markdown 围栏（直接输出 JSON 对象）\n"
                            + "- execution 步骤数必须恰好为 " + ctx.rule().primitiveCountMin() + "\n"
                            + "- 数值（value/radius/duration/cooldown）必须落在数值提示范围内\n"
                            + "- target 规则：伤害/减益/控制原语必须为 TARGET，增益/防御原语为 SELF\n"
                            + "- signature_mechanism 必须为 \"" + ctx.mechanism().code + "\"\n"
                            + "- passive 仅限白名单原语，且 target 必须为 SELF\n";
                }
                return ApiClient.chatJson(req.cfg(), systemPrompt, userPrompt, temp)
                        .thenApply(json -> json.map(j -> SkillData.fromAiJson(j, req.wuhun(), req.mobId(), req.mobHealthSegment())))
                        .thenApply(parsed -> {
                            if (parsed.isEmpty()) {
                                LOGGER.debug("候选 #{} 第 {} 次 AI 解析失败（方向 {} / 种子 #{}）",
                                        retryNo + 1, retryNo + 1, direction, Long.toUnsignedString(randomSeed, 36));
                                return Optional.<SkillData>empty();
                            }
                            SkillData data = parsed.get()
                                    .withMechanism(ctx.mechanism().code)
                                    .withRingAge(req.ringAge());
                            List<String> errors = SkillValidator.collect(data, req.slot(), req.cfg());
                            if (!errors.isEmpty()) {
                                LOGGER.debug("候选 #{} 第 {} 次引擎校验失败: {} -> {}",
                                        retryNo + 1, retryNo + 1, data.name(), errors);
                                return Optional.<SkillData>empty();
                            }
                            LOGGER.info("候选 #{} 第 {} 次重试命中（种子 #{}, 方向 {}）",
                                    retryNo + 1, retryNo + 1, Long.toUnsignedString(randomSeed, 36), direction);
                            return Optional.of(data);
                        });
            });
        }
        return chain;
    }

    // ===== 阶段 5：评分选优 + 多样性检查 =====

    /** 按评分降序排列，jaccard > 0.7 的低分候选拒收，取首个保留者 */
    static Result selectBest(List<SkillData> valid, ScoringContext ctx, SignatureMechanism mechanism) {
        List<SkillData> sorted = new ArrayList<>(valid);
        sorted.sort((a, b) -> Double.compare(score(b, ctx), score(a, ctx)));
        List<SkillData> kept = new ArrayList<>();
        for (SkillData candidate : sorted) {
            boolean tooSimilar = false;
            for (SkillData accepted : kept) {
                if (jaccard(primitiveNames(candidate), primitiveNames(accepted)) > DIVERSITY_JACCARD_LIMIT) {
                    tooSimilar = true;
                    break;
                }
            }
            if (!tooSimilar) kept.add(candidate);
        }
        SkillData best = kept.isEmpty() ? sorted.get(0) : kept.get(0);
        double bestScore = score(best, ctx);
        LOGGER.info("评分选优: {} 个有效候选, 最优「{}」{} 分（机制 {}）",
                valid.size(), best.name(), String.format(Locale.ROOT, "%.1f", bestScore), mechanism.code);
        return new Result(best, mechanism, Result.SOURCE_ENGINE, bestScore);
    }

    /** 5 项加权评分（0~100） */
    static double score(SkillData data, ScoringContext ctx) {
        double s = W_MECHANISM * mechanismFit(data, ctx)
                + W_MOB * mobFit(data, ctx)
                + W_LINKAGE * linkageFit(data, ctx)
                + W_PRIMITIVE * primitiveFit(data, ctx)
                + W_PRESENTATION * presentationFit(data);
        return Math.max(0.0, Math.min(100.0, s * 100.0));
    }

    /** 签名机制契合度（0~1）：技能是否真的体现了抽到的机制 */
    static double mechanismFit(SkillData data, ScoringContext ctx) {
        SignatureMechanism m = ctx.mechanism();
        if (m == null) return 0.5;
        double cdMid = (ctx.rule().cooldownMin() + ctx.rule().cooldownMax()) / 2.0;
        double valMid = (ctx.rule().valueMin() + ctx.rule().valueMax()) / 2.0;
        double radMid = (ctx.rule().radiusMin() + ctx.rule().radiusMax()) / 2.0;
        double durMid = (ctx.rule().durationMin() + ctx.rule().durationMax()) / 2.0;
        return switch (m.code) {
            case "A", "I" -> data.cooldown() <= cdMid ? 1.0 : 0.3;           // 短 CD / 瞬发
            case "B" -> paramVsMid(data, "radius", radMid);                  // 范围放大
            case "C" -> paramVsMid(data, "value", valMid);                   // 强化效果
            case "D", "E" -> paramVsMid(data, "duration", durMid);           // 延时 / 持续
            case "F" -> 0.7;                                                  // 引导施法（无参数特征）
            case "G" -> hasAnyPrimitive(data, "PROJECTILE") ? 1.0 : 0.3;     // 锁定目标
            case "H" -> maxParam(data, "count") > 1 ? 1.0
                    : (countOfCategory(data, Category.STRONG) >= 2 ? 0.7 : 0.2); // 多重目标
            case "J" -> {                                                    // 蓄力最大
                double v = maxParam(data, "value");
                yield v >= valMid && hasAnyPrimitive(data, "STUN") ? 1.0
                        : v >= valMid ? 0.8 : 0.3;
            }
            case "K" -> hasAnyPrimitive(data, "REFLECT", "TAUNT") ? 1.0 : 0.2;      // 反弹
            case "L" -> hasAnyPrimitive(data, "BARRIER", "SHIELD_TRANSFER",
                    "IRON_BODY", "HARDEN") ? 1.0 : 0.2;                             // 护盾
            case "M" -> hasAnyPrimitive(data, "PHANTOM", "EVADE") ? 1.0 : 0.2;      // 隐身
            case "N" -> hasAnyPrimitive(data, "HOT", "REVIVE",
                    "SHIELD_TRANSFER") ? 1.0 : 0.2;                                 // 附加治疗
            case "O" -> hasAnyPrimitive(data, "WEAKEN", "SLOW", "BLIND", "CONFUSE",
                    "POTION", "BIND", "SILENCE", "DISARM", "ROOT") ? 1.0 : 0.2;     // 削弱
            default -> 0.5;
        };
    }

    /**
     * 魂兽特征契合度（0~1）：原语与画像入池签名（置信度 > 0.5）推荐原语的重合率；
     * 未观察给中性分。
     */
    static double mobFit(SkillData data, ScoringContext ctx) {
        MobProfile profile = ctx.profile();
        if (profile == null || !profile.observed()) return 0.6;
        Set<String> used = primitiveNames(data);
        if (used.isEmpty()) return 0.5;
        Set<String> recommended = new HashSet<>();
        for (AbilitySignature sig : profile.reliableSignatures()) {
            for (SkillPrimitive p : sig.recommendedPrimitives) recommended.add(p.name());
        }
        if (recommended.isEmpty()) return 0.5;
        long hit = used.stream().filter(recommended::contains).count();
        return (double) hit / used.size();
    }

    /** 联动契合度（0~1）：连招结构 + 与玩家已有技能的类别互补 */
    static double linkageFit(SkillData data, ScoringContext ctx) {
        List<SkillData> others = ctx.otherRingSkills();
        if (others == null || others.isEmpty()) return 0.7; // 首环无联动对象
        double s = 0.4;
        if (hasCombo(data)) s += 0.3;
        Set<Category> theirs = EnumSet.noneOf(Category.class);
        for (SkillData other : others) theirs.addAll(categories(other));
        boolean complementary = categories(data).stream().anyMatch(c -> !theirs.contains(c));
        if (complementary) s += 0.3;
        return Math.min(1.0, s);
    }

    /** 原语匹配度（0~1）：类别覆盖度 + 画像推荐原语命中 */
    static double primitiveFit(SkillData data, ScoringContext ctx) {
        int distinct = categories(data).size();
        int required = Math.max(1, ctx.rule().minCategoriesRequired());
        double coverage = Math.min(1.0, distinct / (double) required);
        return 0.7 * coverage + 0.3 * mobFit(data, ctx);
    }

    /** 表现资源分（0~1）：恰好 1 个 PARTICLE/SOUND 步骤最佳 */
    static double presentationFit(SkillData data) {
        int count = 0;
        for (SkillData.ExecutionStep step : allSteps(data)) {
            if ("PARTICLE".equals(step.primitive()) || "SOUND".equals(step.primitive())) count++;
        }
        if (count == 1) return 1.0;
        if (count == 0) return 0.6;
        return 0.2;
    }

    // ===== Prompt 构建 =====

    /** 引擎版 system prompt：环位硬约束 + 签名机制指令（比 V6.0 多一层强约束） */
    static String buildSystemPrompt(int slot, SignatureMechanism mechanism) {
        RingPositionRules.RuleSpec rule = RingPositionRules.forSlot(slot);
        StringBuilder sb = new StringBuilder();
        sb.append("你是斗罗大陆世界观下的魂技设计大师，当前运行在「魂技生成引擎」的严格约束模式下，");
        sb.append("为第 ").append(slot).append(" 环位（").append(rule.tierName()).append("）生成魂技。\n");
        sb.append("必须严格输出一个 JSON 对象，格式：\n");
        sb.append("{\"name\":\"技能名\",\"description\":\"不超过30字的描述\",\"cooldown\":<int>,")
          .append("\"trigger\":\"RIGHT_CLICK\",\"execution\":[{\"primitive\":\"...\",\"target\":\"...\"}],")
          .append("\"passive\":[{...}],\"signature_mechanism\":\"").append(mechanism.code).append("\"}\n");
        sb.append("passive 可省略。只输出 JSON，不要任何解释或 markdown。\n\n");

        sb.append("一、本次硬约束（任何一条违反都会被引擎直接废弃本次生成）：\n");
        sb.append("1. execution 必须恰好包含 ").append(rule.primitiveCountMin()).append(" 个步骤。\n");
        sb.append("2. 只能使用以下原语（参数仅限原语后中括号内列出的键）：\n");
        for (SkillPrimitive p : SkillPrimitive.values()) {
            if (!rule.allowedCategories().contains(p.category())) continue;
            if (rule.forbiddenPrimitives().contains(p)) continue;
            sb.append("   ").append(p.name()).append(" [");
            boolean first = true;
            for (Param param : p.allowedParams()) {
                if (!first) sb.append(", ");
                sb.append(param.key());
                first = false;
            }
            sb.append("]\n");
        }
        sb.append("3. 数值硬范围（duration/cooldown 单位 tick，1秒=20tick）：")
          .append(ValueCalculator.fullHint(slot, mechanism)).append("。\n");
        sb.append("4. 签名机制 = ").append(mechanism.code).append("「").append(mechanism.displayName)
          .append("」").append(mechanism.description).append("。")
          .append(mechanismDirective(mechanism)).append('\n');
        sb.append("5. signature_mechanism 字段必须填 \"").append(mechanism.code).append("\"。\n\n");

        sb.append("二、创意要求：\n");
        sb.append("- 主题契合：技能名与机制必须体现武魂与魂兽的标志性特征。\n");
        sb.append("- 机制联动：多步骤必须构成战斗连招（先控制/减益→再爆发；先增益→再突进），禁止互不相干的数值堆叠。\n");
        sb.append("- 表现步骤（PARTICLE/SOUND）至多 1 个，不可喧宾夺主。\n\n");

        sb.append("三、target 规则（写错会伤到自己）：伤害/减益/控制类原语 target 必须为 TARGET；")
          .append("增益/治疗/防御/位移类原语 target 为 SELF（治疗队友可用 TARGET）；")
          .append("AOE_DAMAGE/AOE_BURST 以 target 为圆心，必须为 TARGET。\n\n");

        sb.append("四、被动（passive，可选）：只能使用白名单原语：ACCELERATE、BUFF_STATS、BUFF_ALL、HOT、")
          .append("SHIELD_TRANSFER、CLEANSE、REFLECT、BARRIER、PHANTOM、PARTICLE；target 必须为 SELF，")
          .append("duration 统一给 200，amplifier 1~3，BUFF_STATS 的 percent 给 0.1~0.5。\n\n");

        // §13.7 反推公式、EXECUTE 语义、武魂真身（9 环）禁止
        sb.append("五、★ 数值反推公式（§13.7 + §7.5）：\n");
        sb.append("   最终伤害 = raw_value × 原语系数 × 年限倍率。\n");
        sb.append("   raw_value = 目标伤害 ÷ 原语系数 ÷ 年限倍率，且 raw_value 必须落在本提示第 3 条给出的数值范围内（否则引擎直接拒收）。\n");
        sb.append("   原语系数：BURST×2、AOE_BURST×1.5、AOE_DAMAGE×1、COMBO×1/段、BACKSTAB×2.5、ARMOR_BREAK×1.2、EXECUTE×4(斩杀)/×1(普通)、CHARGE×1。\n");
        sb.append("六、EXECUTE 语义（§13.7.2 必须明确，否则价值语义自相矛盾）：\n");
        sb.append("   value 同时承担两个角色——\n");
        sb.append("   · 斩杀阈值：target.getHealth() ≤ value × 2 时进入斩杀分支\n");
        sb.append("   · 伤害基准：斩杀伤害 = value × 4 × 年限倍率；普通伤害 = value × 1 × 年限倍率\n");
        sb.append("   推荐 raw value ≈ 目标满血量的 1%~2%，使斩杀线 ≈ 满血 2%~4%。\n");
        if (slot == 9) {
            sb.append("七、★ 武魂真身（9 环）硬约束（§13.7.6）：\n");
            sb.append("   - 严禁使用 SUMMON_ENTITY（召唤实体），魂技必须是自身爆发型。\n");
            sb.append("   - 生活系原语（CROP_GROW/FOOD_BLESS/HARVEST/BONEMEAL）严禁出现在战斗技能中（§13.7.3）。\n");
        }
        return sb.toString();
    }

    /**
     * 引擎版 user prompt：组合信息 + 画像 + 联动上下文 + 候选方向 + 随机种子（§7.10）。
     *
     * @param randomSeed 本次候选的随机种子（§7.10 随机性注入：避免同一组合产出雷同设计）；
     *                   传 0L 时不写入种子段（兼容无种子场景）
     */
    static String buildUserPrompt(Request req, ScoringContext ctx, String direction, long randomSeed) {
        StringBuilder sb = new StringBuilder();
        sb.append("请为以下组合设计 1 个魂技。\n");
        sb.append("武魂: ").append(req.wuhun()).append('\n');
        sb.append("魂兽: ").append(ApiClient.describeMobForAi(req.mobId())).append('\n');
        sb.append("魂环年限: ").append(req.ringAge()).append(" 年");
        if (req.sourceMaxHealth() > 0) {
            sb.append("（该年限魂兽最大生命约 ").append((long) req.sourceMaxHealth()).append(" 点）");
        }
        sb.append('\n');
        sb.append("目标环位: 第 ").append(req.slot()).append(" 环（")
          .append(ctx.rule().tierName()).append("）\n");

        MobProfile profile = ctx.profile();
        if (profile != null && profile.observed()) {
            // 属性推断的战斗定位（§5.7）：体型/攻击模式/关键词
            sb.append("魂兽属性推断: 体型").append(profile.size().displayName)
              .append("、").append(profile.attackPattern().displayName);
            if (!profile.combatTraits().isEmpty()) {
                sb.append("、定位「").append(String.join("、", profile.combatTraits())).append('」');
            }
            sb.append('\n');
            // 行为签名（§5.2）：入池签名 + 置信度标签（核心特征/可靠/已确认）
            StringBuilder sigs = new StringBuilder();
            for (AbilitySignature sig : profile.reliableSignatures()) {
                if (sigs.length() > 0) sigs.append("、");
                sigs.append(sig.displayName);
                if (profile.isCore(sig)) sigs.append("[核心特征]");
                else if (profile.isReliable(sig)) sigs.append("[可靠]");
            }
            if (sigs.length() > 0) {
                sb.append("魂兽行为画像（已观察 ").append(profile.observationCount())
                  .append(" 次）: ").append(sigs).append('\n');
            } else {
                sb.append("魂兽行为画像（已观察 ").append(profile.observationCount())
                  .append(" 次，签名待确认）: 暂无高置信度行为\n");
            }
            // 多阶段 boss（§5.3）：≥2 个血量段有行为签名 → 引导 AI 设计阶段变化机制
            if (profile.isMultiPhase()) {
                sb.append("⚠ 该魂兽为多阶段 boss，战斗阶段划分:\n");
                for (var phase : profile.phases()) {
                    sb.append("  阶段").append(phase.phase()).append("（").append(phase.trigger()).append("）: ")
                      .append(String.join("、", phase.abilities())).append('\n');
                }
                sb.append("设计提示: 可考虑体现阶段变化机制（如越战越强/低血量解锁新形态）\n");
            }
            // §5.6 冷启动：画像不完整 → 允许 AI 补 1 个同系别通用原语
            if (profile.incomplete()) {
                sb.append("⚠ 画像不完整（已观察签名 ").append(profile.signatures().size())
                  .append(" 个，少于阈值 ").append(MobProfile.COLD_START_MIN_SIGNATURES).append("）：\n");
                sb.append("  允许从同系别通用原语中补 1 个作为兜底，标记为「补全原语」而非「画像特征」。\n");
                sb.append("  优先从 combatTraits 推断系别：");
                if (!profile.combatTraits().isEmpty()) {
                    sb.append(String.join("、", profile.combatTraits()));
                } else {
                    sb.append("无明显系别 → 任意增益/防御系原语");
                }
                sb.append("\n");
            }
        } else {
            sb.append("魂兽行为画像: 暂无观察数据（请根据魂兽物种特征自由发挥主题契合）\n");
            // §5.6 冷启动：无画像 → 允许 AI 从同系别通用原语中补 1 个
            sb.append("⚠ 画像完全为空 → 允许从同系别通用原语（增益/防御/控制/治疗）中补 1 个作为兜底。\n");
        }

        if (ctx.otherRingSkills().isEmpty()) {
            sb.append("玩家已有魂技: 无（这是第一环，请奠定武魂的战斗基调）\n");
        } else {
            sb.append("玩家已有魂技（联动参考，避免定位重复）:\n");
            for (SkillData other : ctx.otherRingSkills()) {
                sb.append("   - 「").append(other.name()).append("」（机制 ")
                  .append(other.signatureMechanism()).append("）\n");
            }
        }
        sb.append("本候选设计方向: ").append(direction).append('\n');

        // §7.10 随机性注入：把种子写进 prompt，让 AI 产生不同设计
        if (randomSeed != 0L) {
            sb.append("\n[随机种子 #").append(Long.toUnsignedString(randomSeed, 36))
              .append("] 本次随机种子是 ").append(randomSeed)
              .append("。请基于这个种子产生与上次不同的设计，")
              .append("原语顺序在不违反定位的前提下可自由打乱。\n");
        }

        // §7.10 原语顺序随机：在不违反环位定位前提下打乱顺序，避免同质化（显式提示）
        sb.append("§7.12 随机性边界提醒: 只能在合法原语/参数/数值范围内随机选择，")
          .append("原语白名单、target 规则、数值范围、被动白名单不可突破。\n");

        return sb.toString();
    }

    /** 签名机制 → prompt 指令（数值取向 + 原语偏好） */
    static String mechanismDirective(SignatureMechanism m) {
        return switch (m.code) {
            case "A" -> "cooldown 必须取范围低段（短 CD 连击），execution 用多段伤害构成链式连招。";
            case "B" -> "radius/距离参数必须取范围高段（作用范围显著放大）。";
            case "C" -> "value 必须取范围高段（数值显著强化）。";
            case "D" -> "体现延时引爆：先用控制/标记铺垫，末段高伤害爆发（duration 取中低段表现延时节奏）。";
            case "E" -> "持续效果的 duration 必须取范围高段（长时间生效）。";
            case "F" -> "描述中体现蓄力引导施法的过程感，数值取中段。";
            case "G" -> "必须使用 PROJECTILE 原语（弹道锁定自动寻的）。";
            case "H" -> "体现多目标：使用 count 参数或多段/大范围多目标原语。";
            case "I" -> "cooldown 必须取范围低段（瞬发，0 施法时间）。";
            case "J" -> "value 取范围高段（蓄满增伤），建议配合 STUN（蓄力眩晕）。";
            case "K" -> "必须包含 REFLECT 或 TAUNT 原语（反弹/嘲讽联动）。";
            case "L" -> "必须包含 BARRIER / SHIELD_TRANSFER / IRON_BODY / HARDEN 之一（护盾核心）。";
            case "M" -> "必须包含 PHANTOM 或 EVADE 原语（隐身/闪避）。";
            case "N" -> "必须包含 HOT / REVIVE / SHIELD_TRANSFER 之一（附带治疗）。";
            case "O" -> "必须包含 WEAKEN / SLOW / BLIND / CONFUSE / POTION 等削弱原语（多层减益叠加）。";
            default -> "";
        };
    }

    // ===== 评分工具 =====

    /** 参数最大值 vs 中位：≥中位 1.0 / 存在但 < 中位 0.5 / 不存在 0.2 */
    private static double paramVsMid(SkillData data, String key, double mid) {
        double v = maxParam(data, key);
        if (Double.isNaN(v)) return 0.2;
        return v >= mid ? 1.0 : 0.5;
    }

    /** 所有步骤（execution + passive）中某参数的最大值；不存在返回 NaN */
    private static double maxParam(SkillData data, String key) {
        double max = Double.NaN;
        for (SkillData.ExecutionStep step : allSteps(data)) {
            Object v = step.params().get(key);
            if (v instanceof Number n) {
                max = Double.isNaN(max) ? n.doubleValue() : Math.max(max, n.doubleValue());
            }
        }
        return max;
    }

    private static boolean hasAnyPrimitive(SkillData data, String... names) {
        Set<String> used = primitiveNames(data);
        for (String n : names) {
            if (used.contains(n)) return true;
        }
        return false;
    }

    /** 是否存在「控制/增益在前、伤害在后」的连招结构 */
    private static boolean hasCombo(SkillData data) {
        List<SkillData.ExecutionStep> steps = data.execution();
        for (int i = 0; i < steps.size(); i++) {
            SkillPrimitive pi = SkillPrimitive.byName(steps.get(i).primitive());
            if (pi == null) continue;
            if (pi.category() != Category.CONTROL && pi.category() != Category.SUPPORT) continue;
            for (int j = i + 1; j < steps.size(); j++) {
                SkillPrimitive pj = SkillPrimitive.byName(steps.get(j).primitive());
                if (pj != null && pj.category() == Category.STRONG) return true;
            }
        }
        return false;
    }

    private static int countOfCategory(SkillData data, Category category) {
        int count = 0;
        for (SkillData.ExecutionStep step : data.execution()) {
            SkillPrimitive p = SkillPrimitive.byName(step.primitive());
            if (p != null && p.category() == category) count++;
        }
        return count;
    }

    private static Set<Category> categories(SkillData data) {
        Set<Category> set = EnumSet.noneOf(Category.class);
        for (SkillData.ExecutionStep step : data.execution()) {
            SkillPrimitive p = SkillPrimitive.byName(step.primitive());
            if (p != null) set.add(p.category());
        }
        return set;
    }

    private static List<SkillData.ExecutionStep> allSteps(SkillData data) {
        List<SkillData.ExecutionStep> all = new ArrayList<>(data.execution());
        if (data.passive() != null) all.addAll(data.passive());
        return all;
    }

    private static Set<String> primitiveNames(SkillData data) {
        Set<String> set = new HashSet<>();
        for (SkillData.ExecutionStep step : data.execution()) {
            String n = step.primitive();
            if (n != null) set.add(n.toUpperCase(Locale.ROOT));
        }
        return set;
    }

    /** 集合 jaccard 相似度（多样性检查用） */
    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }
}
