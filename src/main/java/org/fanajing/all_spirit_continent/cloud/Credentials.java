package org.fanajing.all_spirit_continent.cloud;

import org.fanajing.all_spirit_continent.config.CloudConfig;

/**
 * 隐私凭据注入：OSS / AI 的 AccessKey 默认值不写入源码明文，按优先级从运行时注入：
 * <ol>
 *   <li>JVM 系统属性：-DASC_OSS_ACCESS_KEY_ID=xxx / -DASC_AI_AUTHOR_API_KEY=xxx（IDE / 启动脚本注入）</li>
 *   <li>环境变量：    ASC_OSS_ACCESS_KEY_ID=xxx / ASC_AI_AUTHOR_API_KEY=xxx（服务端进程环境注入）</li>
 *   <li>配置文件兜底：serverconfig/all_spirit_continent-cloud.toml（作者本机手动填写）</li>
 *   <li>内置混淆兜底：{@link ObfuscatedSecrets} 随 jar 分发，供玩家共享（优先级最低）</li>
 * </ol>
 * 日志一律只输出状态码与脱敏信息，绝不打印凭据明文。
 */
public final class Credentials {

    private Credentials() {
    }

    /** OSS AccessKey ID */
    public static String ossAccessKeyId() {
        String v = firstNonEmpty("ASC_OSS_ACCESS_KEY_ID", CloudConfig.OSS_ACCESS_KEY_ID.get());
        if (!v.isEmpty()) return v;
        return ObfuscatedSecrets.ossAccessKeyId();
    }

    /** OSS AccessKey Secret */
    public static String ossAccessKeySecret() {
        String v = firstNonEmpty("ASC_OSS_ACCESS_KEY_SECRET", CloudConfig.OSS_ACCESS_KEY_SECRET.get());
        if (!v.isEmpty()) return v;
        return ObfuscatedSecrets.ossAccessKeySecret();
    }

    /** 作者 AI API Key（共享阵营用；耗尽时降级玩家私人 Key） */
    public static String aiAuthorApiKey() {
        String v = firstNonEmpty("ASC_AI_AUTHOR_API_KEY", CloudConfig.AI_AUTHOR_KEY.get());
        if (!v.isEmpty()) return v;
        // 内置混淆兜底：随 jar 分发供玩家共享使用；仅当环境变量与配置文件都无 Key 时生效。
        // 注意：混淆只能挡普通玩家解包，反编译/抓包仍可还原，勿放高权限 Key，请设余额上限。
        return ObfuscatedSecrets.aiKey();
    }

    /** 系统属性优先，其次环境变量，最后 config 文件值（无则空串） */
    private static String firstNonEmpty(String name, String fallback) {
        String v = System.getProperty(name);
        if (v != null && !v.isBlank()) return v.trim();
        v = System.getenv(name);
        if (v != null && !v.isBlank()) return v.trim();
        return fallback == null ? "" : fallback.trim();
    }
}
