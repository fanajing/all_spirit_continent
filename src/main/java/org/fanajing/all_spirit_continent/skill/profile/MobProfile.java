package org.fanajing.all_spirit_continent.skill.profile;

import org.fanajing.all_spirit_continent.skill.AbilitySignature;
import org.fanajing.all_spirit_continent.skill.engine.SignatureMechanism;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 魂兽行为画像（引擎文档 §5）。结构化记录某 mob 的体型/攻击模式/行为签名集合，
 * 供 SkillGenerator 第 1 阶段分析 Mob 特征 → 推荐签名机制 + 数值方向。
 * <p>
 * 由 {@link MobObserver} 监听 NeoForge 事件抽取并写入。
 * <p>
 * <b>置信度机制（§5.5）</b>：每个签名按独立观察次数累计置信度——
 * 首次观察 0.3，每次重复观察 +0.1（封顶 0.95）。
 * 置信度 &gt; 0.5 进入生成池（{@link #reliableSignatures()}），
 * &gt; 0.7 视为「可靠」，&gt; 0.9 视为「核心特征」。
 * <p>
 * <b>字段说明（§5.3）</b>：
 * <ul>
 *   <li>{@link #attributes}：mob 实体属性快照（hp/atk/spd/range/免疫/弱点），
 *       用于 §5.7 属性推断战斗定位</li>
 *   <li>{@link #themeKeywords}：主题关键词（如「亡灵」「火焰」「机械」），
 *       注入 prompt 引导 AI 选取契合原语</li>
 *   <li>{@link #sounds} / {@link #particles}：观察到的声音/粒子特征（§5.1 表格），
 *       用于客户端表现层映射</li>
 *   <li>{@link #drops}：掉落物关键词（用于 §9 表现资源解耦）</li>
 *   <li>{@link #contributorCount}：累计贡献玩家数（云端聚合时累加）</li>
 *   <li>{@link #incomplete}：冷启动兜底标记（§5.6）—— 画像不完整时 AI 允许补 1 个同系别通用原语</li>
 *   <li>{@link #modVersion} / {@link #mcVersion}：画像来源版本（§6.5 版本管理）</li>
 *   <li>{@link #environmentHash}：整合包环境指纹（§6.6 环境隔离）</li>
 *   <li>{@link #isStale}：跨版本拉取时被标记为「可能过期」（§6.5）</li>
 * </ul>
 */
public record MobProfile(
        String mobId,
        MobSize size,
        AttackPattern attackPattern,
        Map<AbilitySignature, Integer> signatureCounts,
        List<String> combatTraits,
        Map<Integer, Set<AbilitySignature>> phaseSignatures,
        long observedAtTick,
        int observationCount,
        // ===== §5.3 扩展字段 =====
        EntityAttributes attributes,
        List<String> themeKeywords,
        List<String> sounds,
        List<String> particles,
        List<String> drops,
        int contributorCount,
        boolean incomplete,
        // ===== §6.5/§6.6 扩展字段（版本管理 + 环境隔离） =====
        String modVersion,
        String mcVersion,
        String environmentHash,
        boolean isStale
) {
    /** 置信度初值（首次观察） */
    public static final float CONFIDENCE_INITIAL = 0.3f;
    /** 每次重复观察的置信度增量 */
    public static final float CONFIDENCE_STEP = 0.1f;
    /** 置信度上限 */
    public static final float CONFIDENCE_MAX = 0.95f;
    /** 进入生成池的置信度门槛 */
    public static final float CONFIDENCE_POOL_THRESHOLD = 0.5f;
    /** 「可靠」标记门槛 */
    public static final float CONFIDENCE_RELIABLE = 0.7f;
    /** 「核心特征」标记门槛 */
    public static final float CONFIDENCE_CORE = 0.9f;
    /** §5.6 冷启动阈值：画像中已观察签名数低于此值视为不完整 */
    public static final int COLD_START_MIN_SIGNATURES = 3;

    public MobProfile {
        signatureCounts = signatureCounts == null ? Map.of()
                : Map.copyOf(signatureCounts);
        combatTraits = combatTraits == null || combatTraits.isEmpty() ? List.of() : List.copyOf(combatTraits);
        phaseSignatures = phaseSignatures == null || phaseSignatures.isEmpty() ? Map.of() : Map.copyOf(phaseSignatures);
        if (size == null) size = MobSize.MEDIUM;
        if (attackPattern == null) attackPattern = AttackPattern.MELEE;
        if (mobId != null) mobId = mobId.toLowerCase(Locale.ROOT);
        // §5.3 扩展字段默认值
        if (attributes == null) attributes = EntityAttributes.empty();
        themeKeywords = themeKeywords == null || themeKeywords.isEmpty() ? List.of() : List.copyOf(themeKeywords);
        sounds = sounds == null || sounds.isEmpty() ? List.of() : List.copyOf(sounds);
        particles = particles == null || particles.isEmpty() ? List.of() : List.copyOf(particles);
        drops = drops == null || drops.isEmpty() ? List.of() : List.copyOf(drops);
        if (contributorCount < 0) contributorCount = 0;
        // §6.5/§6.6 默认值
        modVersion = modVersion == null || modVersion.isEmpty() ? ModVersion.UNKNOWN : modVersion;
        mcVersion = mcVersion == null || mcVersion.isEmpty() ? McVersion.current() : mcVersion;
        environmentHash = environmentHash == null || environmentHash.isEmpty() ? EnvironmentHash.UNKNOWN : environmentHash;
        // 冷启动标记：观察到的签名数 < 3 → incomplete
        incomplete = incomplete || signatureCounts.size() < COLD_START_MIN_SIGNATURES;
    }

    /** 已观察到的全部签名（不论置信度） */
    public Set<AbilitySignature> signatures() {
        return signatureCounts.keySet();
    }

    /** 某签名的独立观察次数 */
    public int observationCount(AbilitySignature sig) {
        return signatureCounts.getOrDefault(sig, 0);
    }

    /**
     * 某签名的置信度（§5.5）：首次 0.3，每次重复 +0.1，封顶 0.95；未观察返回 0。
     */
    public float confidence(AbilitySignature sig) {
        int count = observationCount(sig);
        if (count <= 0) return 0f;
        return Math.min(CONFIDENCE_MAX, CONFIDENCE_INITIAL + CONFIDENCE_STEP * (count - 1));
    }

    /** 该签名是否为「可靠」特征（置信度 > 0.7） */
    public boolean isReliable(AbilitySignature sig) {
        return confidence(sig) > CONFIDENCE_RELIABLE;
    }

    /** 该签名是否为「核心特征」（置信度 > 0.9） */
    public boolean isCore(AbilitySignature sig) {
        return confidence(sig) > CONFIDENCE_CORE;
    }

    /**
     * 进入 AI 生成池的签名（置信度 > 0.5）。
     * 冷启动兜底：若尚无任何签名过门槛但已有观察数据，返回全部已观察签名
     * （避免画像有数据却完全不用，§5.6 冷启动原则）。
     */
    public Set<AbilitySignature> reliableSignatures() {
        Set<AbilitySignature> pooled = EnumSet.noneOf(AbilitySignature.class);
        for (AbilitySignature sig : signatureCounts.keySet()) {
            if (confidence(sig) > CONFIDENCE_POOL_THRESHOLD) pooled.add(sig);
        }
        if (pooled.isEmpty() && !signatureCounts.isEmpty()) {
            pooled.addAll(signatureCounts.keySet());
        }
        return pooled;
    }

    /** 该 mob 推荐的所有签名机制（合并所有入池签名的 suggestedMechanisms） */
    public Set<SignatureMechanism> suggestedMechanisms() {
        Set<SignatureMechanism> set = EnumSet.noneOf(SignatureMechanism.class);
        for (AbilitySignature s : reliableSignatures()) {
            for (SignatureMechanism m : s.suggestedMechanisms) set.add(m);
        }
        return set;
    }

    /**
     * 该 mob 对某签名机制的契合度（该机制被所有入池签名推荐的次数）。
     * @return 推荐命中次数（0 = 不契合）
     */
    public int affinity(SignatureMechanism mechanism) {
        int count = 0;
        for (AbilitySignature s : reliableSignatures()) {
            for (SignatureMechanism m : s.suggestedMechanisms) {
                if (m == mechanism) count++;
            }
        }
        return count;
    }

    /** 是否已被观察（至少 1 个签名） */
    public boolean observed() {
        return !signatureCounts.isEmpty() && observationCount > 0;
    }

    /** 空画像（未观察的默认占位，避免 NullObject） */
    public static MobProfile empty(String mobId) {
        return new MobProfile(mobId, MobSize.MEDIUM, AttackPattern.MELEE,
                Map.of(), List.of(), Map.of(), 0L, 0,
                EntityAttributes.empty(), List.of(), List.of(), List.of(), List.of(),
                0, true,
                ModVersion.UNKNOWN, McVersion.current(), EnvironmentHash.UNKNOWN, false);
    }

    /**
     * 累加一次行为观察（MobObserver 调用）：已存在的签名计数 +1（置信度上升），
     * 新签名以计数 1 加入（置信度 0.3，待重复观察确认）。
     *
     * @param phaseSegment 观察发生时的血量阶段（1~4，§5.3 多阶段 boss 分桶）；
     *                     ≤0 表示无血量上下文（载入/聚合），不记录阶段
     */
    public MobProfile withObservedSignature(AbilitySignature sig, long atTick, int phaseSegment) {
        Map<AbilitySignature, Integer> next = new EnumMap<>(AbilitySignature.class);
        next.putAll(signatureCounts);
        next.merge(sig, 1, Integer::sum);
        Map<Integer, Set<AbilitySignature>> nextPhases = phaseSignatures;
        if (phaseSegment >= 1 && phaseSegment <= PhaseInfo.MAX_PHASE) {
            nextPhases = new HashMap<>(phaseSignatures);
            Set<AbilitySignature> seg = nextPhases.get(phaseSegment);
            Set<AbilitySignature> updated = EnumSet.noneOf(AbilitySignature.class);
            if (seg != null) updated.addAll(seg);
            updated.add(sig);
            nextPhases.put(phaseSegment, updated);
        }
        return new MobProfile(mobId, size, attackPattern, next, combatTraits, nextPhases,
                atTick, observationCount + 1,
                attributes, themeKeywords, sounds, particles, drops, contributorCount, incomplete,
                modVersion, mcVersion, environmentHash, isStale);
    }

    /** 更新体型/攻击模式推断（属性推断首次观察时注入，值可空表示保持不变） */
    public MobProfile withInferredTraits(MobSize inferredSize, AttackPattern inferredPattern, long atTick) {
        return new MobProfile(mobId,
                inferredSize != null ? inferredSize : size,
                inferredPattern != null ? inferredPattern : attackPattern,
                signatureCounts, combatTraits, phaseSignatures,
                atTick, observationCount,
                attributes, themeKeywords, sounds, particles, drops, contributorCount, incomplete,
                modVersion, mcVersion, environmentHash, isStale);
    }

    /** 追加战斗定位关键词（属性推断 §5.7，去重） */
    public MobProfile withCombatTraits(List<String> traits) {
        if (traits == null || traits.isEmpty()) return this;
        List<String> merged = new ArrayList<>(combatTraits);
        for (String t : traits) {
            if (t != null && !t.isBlank() && !merged.contains(t)) merged.add(t);
        }
        return new MobProfile(mobId, size, attackPattern, signatureCounts, merged, phaseSignatures,
                observedAtTick, observationCount,
                attributes, themeKeywords, sounds, particles, drops, contributorCount, incomplete,
                modVersion, mcVersion, environmentHash, isStale);
    }

    /**
     * 多阶段 boss 视图（§5.3）：有签名的血量段按阶段升序。
     * ≥2 段即判定为多阶段 boss（小怪只在满血段出手，仅 1 段）。
     */
    public List<PhaseInfo> phases() {
        if (phaseSignatures.isEmpty()) return List.of();
        List<PhaseInfo> list = new ArrayList<>();
        for (int seg = 1; seg <= PhaseInfo.MAX_PHASE; seg++) {
            Set<AbilitySignature> sigs = phaseSignatures.get(seg);
            if (sigs != null && !sigs.isEmpty()) {
                list.add(new PhaseInfo(seg, PhaseInfo.triggerOf(seg),
                        sigs.stream().sorted().map(s -> s.displayName).toList()));
            }
        }
        return list;
    }

    /** 是否为多阶段 boss（≥2 个血量段有行为签名） */
    public boolean isMultiPhase() {
        return phaseSignatures.size() >= 2;
    }

    /**
     * 合并另一份画像（云端聚合 / 玩家手动补充用，§6.3）：
     * 签名计数取 max，关键词并集，阶段分桶并集，
     * 元数据自取较新侧（modVersion 精确优先，environmentHash 优先匹配本端）。
     */
    public MobProfile merge(MobProfile other) {
        if (other == null) return this;
        Map<AbilitySignature, Integer> mergedCounts = new EnumMap<>(AbilitySignature.class);
        mergedCounts.putAll(signatureCounts);
        for (Map.Entry<AbilitySignature, Integer> e : other.signatureCounts.entrySet()) {
            mergedCounts.merge(e.getKey(), e.getValue(), Math::max);
        }
        Map<Integer, Set<AbilitySignature>> mergedPhases = new HashMap<>(phaseSignatures);
        for (Map.Entry<Integer, Set<AbilitySignature>> e : other.phaseSignatures.entrySet()) {
            Set<AbilitySignature> union = EnumSet.noneOf(AbilitySignature.class);
            Set<AbilitySignature> mine = mergedPhases.get(e.getKey());
            if (mine != null) union.addAll(mine);
            union.addAll(e.getValue());
            mergedPhases.put(e.getKey(), union);
        }
        long tick = Math.max(observedAtTick, other.observedAtTick);
        int mergedContributors = Math.max(contributorCount, other.contributorCount) + 1;
        // §6.5 版本：合并时优先取本端 modVersion（正在使用的版本）；
        //           若仅 remote 侧有版本信息则取 remote 侧
        String mergedModVersion = !ModVersion.UNKNOWN.equals(modVersion) ? modVersion : other.modVersion;
        String mergedMcVersion = !McVersion.UNKNOWN.equals(mcVersion) ? mcVersion : other.mcVersion;
        // §6.6 环境隔离：取本端 environmentHash（聚合不应让缓存的画像覆盖本地环境）
        String mergedHash = !EnvironmentHash.UNKNOWN.equals(environmentHash) ? environmentHash : other.environmentHash;
        // §6.5 stale：跨版本精确匹配不上就保留 stale
        boolean mergedStale = isStale || other.isStale();
        MobProfile merged = new MobProfile(mobId, size, attackPattern, mergedCounts, combatTraits,
                mergedPhases, tick, Math.max(observationCount, other.observationCount),
                mergeAttributes(attributes, other.attributes),
                mergeStrings(themeKeywords, other.themeKeywords),
                mergeStrings(sounds, other.sounds),
                mergeStrings(particles, other.particles),
                mergeStrings(drops, other.drops),
                mergedContributors,
                incomplete && other.incomplete,  // 任一侧不完整 → 合并后仍不完整
                mergedModVersion, mergedMcVersion, mergedHash, mergedStale);
        return merged.withCombatTraits(other.combatTraits);
    }

    // ===== §5.3 字段操作便捷方法 =====

    /** 追加主题关键词（去重） */
    public MobProfile withThemeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) return this;
        if (themeKeywords.contains(keyword)) return this;
        List<String> next = new ArrayList<>(themeKeywords);
        next.add(keyword);
        return new MobProfile(mobId, size, attackPattern, signatureCounts, combatTraits, phaseSignatures,
                observedAtTick, observationCount,
                attributes, next, sounds, particles, drops, contributorCount, incomplete,
                modVersion, mcVersion, environmentHash, isStale);
    }

    /** 追加声音观察（去重，§5.1 SoundEvent 表格） */
    public MobProfile withSound(String sound) {
        if (sound == null || sound.isBlank()) return this;
        if (sounds.contains(sound)) return this;
        List<String> next = new ArrayList<>(sounds);
        next.add(sound);
        return new MobProfile(mobId, size, attackPattern, signatureCounts, combatTraits, phaseSignatures,
                observedAtTick, observationCount,
                attributes, themeKeywords, next, particles, drops, contributorCount, incomplete,
                modVersion, mcVersion, environmentHash, isStale);
    }

    /** 追加粒子观察（去重，§5.1 ParticleEvent 表格） */
    public MobProfile withParticle(String particle) {
        if (particle == null || particle.isBlank()) return this;
        if (particles.contains(particle)) return this;
        List<String> next = new ArrayList<>(particles);
        next.add(particle);
        return new MobProfile(mobId, size, attackPattern, signatureCounts, combatTraits, phaseSignatures,
                observedAtTick, observationCount,
                attributes, themeKeywords, sounds, next, drops, contributorCount, incomplete,
                modVersion, mcVersion, environmentHash, isStale);
    }

    /** 追加掉落物观察（去重，§5.1 + §9 资源解耦） */
    public MobProfile withDrop(String drop) {
        if (drop == null || drop.isBlank()) return this;
        if (drops.contains(drop)) return this;
        List<String> next = new ArrayList<>(drops);
        next.add(drop);
        return new MobProfile(mobId, size, attackPattern, signatureCounts, combatTraits, phaseSignatures,
                observedAtTick, observationCount,
                attributes, themeKeywords, sounds, particles, next, contributorCount, incomplete,
                modVersion, mcVersion, environmentHash, isStale);
    }

    /** 替换/写入实体属性快照（§5.3 EntityAttributes） */
    public MobProfile withAttributes(EntityAttributes a) {
        return new MobProfile(mobId, size, attackPattern, signatureCounts, combatTraits, phaseSignatures,
                observedAtTick, observationCount,
                a != null ? a : attributes, themeKeywords, sounds, particles, drops,
                contributorCount, incomplete,
                modVersion, mcVersion, environmentHash, isStale);
    }

    /** 玩家手动补充一个签名（§5.6 冷启动兜底，绕过容差判定） */
    public MobProfile withManualSignature(AbilitySignature sig, long atTick) {
        if (sig == null) return this;
        Map<AbilitySignature, Integer> next = new EnumMap<>(signatureCounts);
        next.merge(sig, 1, Integer::sum);
        boolean nowComplete = next.size() >= COLD_START_MIN_SIGNATURES;
        return new MobProfile(mobId, size, attackPattern, next, combatTraits, phaseSignatures,
                atTick, observationCount + 1,
                attributes, themeKeywords, sounds, particles, drops, contributorCount,
                incomplete && !nowComplete,
                modVersion, mcVersion, environmentHash, isStale);
    }

    /** §5.6：清除冷启动标记（玩家明确手动完整补充后调用） */
    public MobProfile markComplete() {
        if (!incomplete) return this;
        return new MobProfile(mobId, size, attackPattern, signatureCounts, combatTraits, phaseSignatures,
                observedAtTick, observationCount,
                attributes, themeKeywords, sounds, particles, drops, contributorCount, false,
                modVersion, mcVersion, environmentHash, isStale);
    }

    /** §6.5：标记为「可能过期」（跨版本拉取时使用） */
    public MobProfile markStale() {
        if (isStale) return this;
        return new MobProfile(mobId, size, attackPattern, signatureCounts, combatTraits, phaseSignatures,
                observedAtTick, observationCount,
                attributes, themeKeywords, sounds, particles, drops, contributorCount, incomplete,
                modVersion, mcVersion, environmentHash, true);
    }

    // ===== 内部工具 =====

    private static List<String> mergeStrings(List<String> a, List<String> b) {
        List<String> merged = new ArrayList<>(a == null ? List.of() : a);
        if (b != null) for (String s : b) if (s != null && !merged.contains(s)) merged.add(s);
        return merged;
    }

    private static EntityAttributes mergeAttributes(EntityAttributes a, EntityAttributes b) {
        if (a == null) return b == null ? EntityAttributes.empty() : b;
        if (b == null) return a;
        // 任一侧为 0/空则取另一侧，避免丢失信息
        double hp = Math.max(a.maxHealth(), b.maxHealth());
        double atk = Math.max(a.attackDamage(), b.attackDamage());
        double spd = Math.max(a.movementSpeed(), b.movementSpeed());
        double range = Math.max(a.followRange(), b.followRange());
        List<String> imm = mergeStrings(a.damageImmunities(), b.damageImmunities());
        List<String> weak = mergeStrings(a.weaknesses(), b.weaknesses());
        Map<String, Double> extra = new HashMap<>(a.extra());
        extra.putAll(b.extra());
        return new EntityAttributes(hp, atk, spd, range, imm, weak, extra);
    }
}