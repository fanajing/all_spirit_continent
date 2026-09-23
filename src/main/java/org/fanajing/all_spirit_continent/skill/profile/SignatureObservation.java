package org.fanajing.all_spirit_continent.skill.profile;

import org.fanajing.all_spirit_continent.skill.AbilitySignature;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 单次 mob 行为观察的具名参数实例（引擎文档 §5.2 + §5.4）。
 * <p>
 * 与 {@link AbilitySignature}（行为类别枚举）配对：
 * 枚举只描述「是哪种行为」，本记录描述「这次行为具体参数是什么」。
 * 例：{@code PROJECTILE + {projectile_type: "arrow"}}、{@code EXPLOSION + {radius: 4.0}}、
 * {@code POTION + {effect: "slowness", amplifier: 2}}。
 * <p>
 * 字段命名遵循 §5.1 表格 signature 格式约定（{@code radius=} / {@code duration=} / {@code value=} /
 * {@code speed=} / {@code count=}），便于观察器统一赋值与容差去重。
 * <p>
 * {@code params} 默认不可变；{@link #withParam(String, Object)} 链式追加返回新实例。
 */
public record SignatureObservation(
        AbilitySignature abilityType,
        String signature,
        Map<String, Object> params,
        float confidence,
        int observedCount,
        long observedAtTick
) {

    public SignatureObservation {
        Objects.requireNonNull(abilityType, "abilityType");
        signature = signature == null ? "" : signature;
        params = params == null ? Map.of() : Map.copyOf(params);
        if (confidence < 0f) confidence = 0f;
        if (confidence > 1f) confidence = 1f;
        if (observedCount < 0) observedCount = 0;
    }

    /** 构造空观察（用于 mob 被杀时清空暂存） */
    public static SignatureObservation of(AbilitySignature type) {
        return new SignatureObservation(type, "", Map.of(),
                MobProfile.CONFIDENCE_INITIAL, 1, 0L);
    }

    /** 构造带具体参数的单次观察 */
    public static SignatureObservation of(AbilitySignature type, String signature,
                                          Map<String, Object> params, long atTick) {
        return new SignatureObservation(type, signature, params,
                MobProfile.CONFIDENCE_INITIAL, 1, atTick);
    }

    /** 追加一个参数（链式） */
    public SignatureObservation withParam(String key, Object value) {
        Map<String, Object> next = new HashMap<>(params);
        next.put(key, value);
        return new SignatureObservation(abilityType, signature, next,
                confidence, observedCount, observedAtTick);
    }

    /** 复制并累加计数（§5.5 置信度公式） */
    public SignatureObservation withIncrementedCount(long atTick) {
        int nextCount = observedCount + 1;
        float nextConfidence = Math.min(MobProfile.CONFIDENCE_MAX,
                MobProfile.CONFIDENCE_INITIAL + MobProfile.CONFIDENCE_STEP * (nextCount - 1));
        return new SignatureObservation(abilityType, signature, params,
                nextConfidence, nextCount, atTick);
    }

    /** 读参数为 double（用于容差比较），缺失时返回 NaN */
    public double paramDouble(String key) {
        Object v = params.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) { return Double.NaN; }
        }
        return Double.NaN;
    }

    /** 读参数为 int */
    public int paramInt(String key) {
        Object val = params.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { return Integer.MIN_VALUE; }
        }
        return Integer.MIN_VALUE;
    }

    /** 读参数为 String */
    public String paramString(String key) {
        Object v = params.get(key);
        return v == null ? null : String.valueOf(v).toLowerCase(Locale.ROOT);
    }

    /** 该观察的全部参数键（不可变） */
    public java.util.Set<String> paramKeys() {
        return Collections.unmodifiableSet(params.keySet());
    }

    @Override
    public String toString() {
        return "SigObs[" + abilityType + " sig=" + signature + " params=" + params
                + " conf=" + String.format(Locale.ROOT, "%.2f", confidence)
                + " count=" + observedCount + "]";
    }
}