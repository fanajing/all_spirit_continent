package org.fanajing.all_spirit_continent.skill.profile;

import org.fanajing.all_spirit_continent.skill.AbilitySignature;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mob 行为观察去重器（引擎文档 §5.4 本地去重）。
 * <p>
 * 当 mob 被击杀时调用：把战斗中累积的 {@link SignatureObservation} 列表与画像中已有签名比对——
 * <ul>
 *   <li>同一能力类型 + 容差范围内的参数 → 视为同一签名，计数 +1，置信度按 §5.5 公式更新</li>
 *   <li>差异超过容差 → 视为新签名加入，标记需上传</li>
 * </ul>
 * 容差表（§5.4）：
 * <pre>
 *   radius   ± 1.0
 *   duration ± 20 tick
 *   value    ± 10%
 *   speed    ± 0.2
 *   count    ± 1
 * </pre>
 * 字符串签名（{@code projectile_type} / {@code effect} / {@code damage_type} 等）按值完全相等判定，
 * 大小写不敏感。
 * <p>
 * 注意：本类只负责「去重判定」与「合并计数」，具体「写回画像」由调用方（{@link MobObserver}）完成。
 * 这样做的原因是画像可能由多个来源叠加（本地观察 / 云端聚合 / 玩家手动补充），
 * 每种来源的去重策略略有差异，本类只提供最纯粹的「单次击杀事件」合并。
 */
public final class MobProfileDeduper {

    // ===== 容差常量（引擎文档 §5.4 表格） =====

    /** radius 容差 ±1.0 格 */
    public static final double RADIUS_TOLERANCE = 1.0;
    /** duration 容差 ±20 tick（1 秒） */
    public static final double DURATION_TOLERANCE = 20.0;
    /** value 容差 ±10%（相对值） */
    public static final double VALUE_RATIO_TOLERANCE = 0.10;
    /** speed 容差 ±0.2 m/tick */
    public static final double SPEED_TOLERANCE = 0.2;
    /** count 容差 ±1 */
    public static final int COUNT_TOLERANCE = 1;

    private MobProfileDeduper() {
    }

    /**
     * 在已有观察列表中查找与 given 相似（同类 + 参数容差内）的观察。
     *
     * @return 第一个匹配的已有观察；无匹配返回 null
     */
    public static SignatureObservation findSimilar(SignatureObservation given,
                                                   Collection<SignatureObservation> existing) {
        if (given == null || existing == null || existing.isEmpty()) return null;
        for (SignatureObservation candidate : existing) {
            if (candidate.abilityType() != given.abilityType()) continue;
            if (!isSignatureStringSimilar(candidate, given)) continue;
            if (isWithinTolerance(candidate, given)) return candidate;
        }
        return null;
    }

    /**
     * 把 given 的参数与 cached 的参数逐项对比：所有已知数值/字符串参数均在容差内才算相似。
     * 不在 given 中的参数忽略；只在 cached 中有的参数也忽略（新增观察可能未携带全部参数）。
     */
    public static boolean isWithinTolerance(SignatureObservation a, SignatureObservation b) {
        if (a == null || b == null) return false;
        if (a.abilityType() != b.abilityType()) return false;
        if (!isSignatureStringSimilar(a, b)) return false;

        Map<String, Object> aParams = a.params();
        Map<String, Object> bParams = b.params();
        // 任一非空参数集合中逐项比
        for (Map.Entry<String, Object> entry : aParams.entrySet()) {
            String key = entry.getKey();
            if (!bParams.containsKey(key)) continue;  // b 没记录这个 key，跳过
            if (!withinParamTolerance(key, a.paramDouble(key), b.paramDouble(key),
                    a.paramInt(key), b.paramInt(key))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 合并一组本场战斗观察到一个画像：找到相似的 → 计数 +1，找不到 → 加入为新签名。
     * <p>
     * 返回 {@link DedupResult} 含「合并后画像」与「需上传的新签名列表」（供云端增量推送）。
     */
    public static DedupResult mergeInto(MobProfile profile,
                                        List<SignatureObservation> observedThisFight,
                                        long atTick) {
        if (profile == null) profile = MobProfile.empty("");
        if (observedThisFight == null || observedThisFight.isEmpty()) {
            return new DedupResult(profile, List.of());
        }
        // 把已有签名映射成观察列表（用于容差比较）
        List<SignatureObservation> existing = new ArrayList<>();
        for (Map.Entry<AbilitySignature, Integer> e : profile.signatureCounts().entrySet()) {
            int count = e.getValue();
            float conf = profile.confidence(e.getKey());
            existing.add(new SignatureObservation(e.getKey(), "", Map.of(),
                    conf, count, profile.observedAtTick()));
        }

        MobProfile current = profile;
        List<SignatureObservation> newOnes = new ArrayList<>();
        for (SignatureObservation obs : observedThisFight) {
            SignatureObservation similar = findSimilar(obs, existing);
            if (similar != null) {
                // 找到相似 → 用 cache 的 abilityType + 累加计数 + atTick 更新画像
                current = current.withObservedSignature(similar.abilityType(), atTick, 0);
                existing.remove(similar);
                existing.add(similar.withIncrementedCount(atTick));
            } else {
                // 没找到 → 全新签名
                current = current.withObservedSignature(obs.abilityType(), atTick, 0);
                newOnes.add(obs);
            }
        }
        return new DedupResult(current, List.copyOf(newOnes));
    }

    // ===== 内部判定 =====

    /** signature 字符串完全相等（忽略大小写） */
    private static boolean isSignatureStringSimilar(SignatureObservation a, SignatureObservation b) {
        String sa = a.signature();
        String sb = b.signature();
        if (sa == null || sa.isEmpty()) return sb == null || sb.isEmpty();
        return sa.equalsIgnoreCase(sb);
    }

    /**
     * 单个参数容差判定（按 key 名称路由）：
     * <ul>
     *   <li>{@code radius} → 绝对差 ≤ {@link #RADIUS_TOLERANCE}</li>
     *   <li>{@code duration} → 绝对差 ≤ {@link #DURATION_TOLERANCE}</li>
     *   <li>{@code value} → 相对差 ≤ {@link #VALUE_RATIO_TOLERANCE}</li>
     *   <li>{@code speed} → 绝对差 ≤ {@link #SPEED_TOLERANCE}</li>
     *   <li>{@code count} → 绝对差 ≤ {@link #COUNT_TOLERANCE}</li>
     *   <li>其他数值参数 → 视为精确相等</li>
     *   <li>字符串参数（{@code projectile_type} / {@code effect} / {@code damage_type}）
     *       → 在外层 {@link #isSignatureStringSimilar} 已判过；这里跳过</li>
     * </ul>
     * 任一参数任一为 NaN（未读取到）则该参数跳过，不参与判定（避免假阴性）。
     */
    private static boolean withinParamTolerance(String key, double da, double db,
                                                int ia, int ib) {
        String lk = key == null ? "" : key.toLowerCase(Locale.ROOT);
        switch (lk) {
            case "radius": {
                if (Double.isNaN(da) || Double.isNaN(db)) return true;
                return Math.abs(da - db) <= RADIUS_TOLERANCE;
            }
            case "duration": {
                if (Double.isNaN(da) || Double.isNaN(db)) return true;
                return Math.abs(da - db) <= DURATION_TOLERANCE;
            }
            case "value": {
                if (Double.isNaN(da) || Double.isNaN(db)) return true;
                double base = Math.max(Math.abs(da), Math.abs(db));
                if (base <= 1e-6) return true;
                return Math.abs(da - db) / base <= VALUE_RATIO_TOLERANCE;
            }
            case "speed": {
                if (Double.isNaN(da) || Double.isNaN(db)) return true;
                return Math.abs(da - db) <= SPEED_TOLERANCE;
            }
            case "count": {
                if (ia == Integer.MIN_VALUE || ib == Integer.MIN_VALUE) return true;
                return Math.abs(ia - ib) <= COUNT_TOLERANCE;
            }
            default:
                // 其他参数：数值要求精确相等，字符串忽略（已在外层判定）
                if (Double.isNaN(da) || Double.isNaN(db)) return true;
                return Math.abs(da - db) < 1e-6;
        }
    }

    /** 去重结果（合并后画像 + 需上传的新签名） */
    public record DedupResult(MobProfile mergedProfile, List<SignatureObservation> newSignatures) {
    }
}