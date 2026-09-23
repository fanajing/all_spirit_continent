package org.fanajing.all_spirit_continent.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 云端配置（config/all_spirit_continent-cloud.toml，SERVER 级）。
 * <p>
 * 包含：阿里云 OSS 直连参数、DeepSeek（OpenAI 兼容）AI 生成接口、同步与熔断参数。
 * 凭据注入优先级：环境变量/系统属性 → 本配置文件 → 内置混淆凭据（ObfuscatedSecrets，随 jar 分发）。
 */
public class CloudConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ===== 阿里云 OSS =====
    /** OSS Bucket 绑定域名（含 bucket 名），如 https://douluofan.oss-cn-beijing.aliyuncs.com */
    public static final ModConfigSpec.ConfigValue<String> OSS_ENDPOINT = BUILDER
            .comment("阿里云 OSS Bucket 绑定域名（含 bucket 名）",
                    "例如: https://douluofan.oss-cn-beijing.aliyuncs.com")
            .define("oss.endpoint", "https://douluofan.oss-cn-beijing.aliyuncs.com");

    /** OSS Bucket 名称（签名资源路径 /bucket/key 用） */
    public static final ModConfigSpec.ConfigValue<String> OSS_BUCKET = BUILDER
            .comment("OSS Bucket 名称（与 endpoint 一致）")
            .define("oss.bucket", "douluofan");

    /** OSS AccessKey ID（写操作签名用；匿名读可留空） */
    public static final ModConfigSpec.ConfigValue<String> OSS_ACCESS_KEY_ID = BUILDER
            .comment("阿里云 OSS AccessKey ID（服务端写 public_skills.json 用；留空则写操作禁用，仅读）",
                    "【隐私】请优先用环境变量 ASC_OSS_ACCESS_KEY_ID 注入，或依赖内置混淆凭据；",
                    "此处仅作本机兜底，切勿填写真实 Key")
            .define("oss.accessKeyId", "");

    /** OSS AccessKey Secret（写操作签名用） */
    public static final ModConfigSpec.ConfigValue<String> OSS_ACCESS_KEY_SECRET = BUILDER
            .comment("阿里云 OSS AccessKey Secret（写操作签名用）",
                    "【隐私】请优先用环境变量 ASC_OSS_ACCESS_KEY_SECRET 注入，或依赖内置混淆凭据；此处勿填真实 Key")
            .define("oss.accessKeySecret", "");

    // ===== AI 生成接口（OpenAI 兼容）=====
    /** 作者 API 地址（共享阵营优先使用） */
    public static final ModConfigSpec.ConfigValue<String> AI_AUTHOR_URL = BUILDER
            .comment("作者 API 地址（OpenAI 兼容 chat/completions；共享阵营优先使用）")
            .define("ai.authorUrl", "https://api.deepseek.com/chat/completions");

    /** 作者 API Key（共享阵营使用；耗尽时降级玩家私人 Key） */
    public static final ModConfigSpec.ConfigValue<String> AI_AUTHOR_KEY = BUILDER
            .comment("作者 API Key（DeepSeek / OpenAI 兼容）",
                    "【隐私】请优先用环境变量 ASC_AI_AUTHOR_API_KEY 注入，此处勿填真实 Key")
            .define("ai.authorApiKey", "");

    /** AI 模型名 */
    public static final ModConfigSpec.ConfigValue<String> AI_MODEL = BUILDER
            .comment("AI 模型名（DeepSeek 为 deepseek-chat）")
            .define("ai.model", "deepseek-chat");

    /** AI 请求超时（秒）；满足 §13.1.3：默认 10 秒（最低 10 秒硬下限防玩家误设阻塞） */
    public static final ModConfigSpec.ConfigValue<Integer> AI_TIMEOUT_SECONDS = BUILDER
            .comment("AI 生成请求超时（秒），最小 10 秒（§13.1.3）")
            .define("ai.timeoutSeconds", 10);

    /** 熔断：连续失败多少次后熔断 */
    public static final ModConfigSpec.ConfigValue<Integer> AI_BREAKER_FAILURES = BUILDER
            .comment("熔断阈值：连续失败 N 次后进入熔断")
            .define("ai.circuitBreakerFailures", 3);

    /** 熔断开启时长（秒） */
    public static final ModConfigSpec.ConfigValue<Integer> AI_BREAKER_OPEN_SECONDS = BUILDER
            .comment("熔断开启时长（秒），期间 AI 请求直接失败")
            .define("ai.circuitBreakerOpenSeconds", 60);

    // ===== 云端同步 =====
    /** 定时增量同步间隔（秒） */
    public static final ModConfigSpec.ConfigValue<Integer> SYNC_INTERVAL_SECONDS = BUILDER
            .comment("定时拉取/写回云端间隔（秒）")
            .define("sync.intervalSeconds", 300);

    /** 默认武魂名（玩家未设置武魂时的组合键） */
    public static final ModConfigSpec.ConfigValue<String> DEFAULT_WUHUN = BUILDER
            .comment("默认武魂名（云端组合键 (武魂+怪物+血量档位) 用）")
            .define("defaultWuhun", "昊天锤");

    public static final ModConfigSpec SPEC = BUILDER.build();

    private CloudConfig() {
    }
}
