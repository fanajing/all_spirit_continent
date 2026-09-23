package org.fanajing.all_spirit_continent.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组服务端配置。
 * 配置文件生成于 config/all_spirit_continent-server.toml，修改后重启游戏生效。
 */
public class ModConfig {

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /**
     * 玩家死亡后是否清除魂师身份。
     * true  = 死亡重生后清除魂师身份，全部魂环一并删除
     * false = 死亡重生后保留魂师身份与魂环（默认）
     */
    public static final ModConfigSpec.BooleanValue CLEAR_SOUL_MASTER_ON_DEATH =
            BUILDER.comment(
                    "Whether to clear the soul master identity when the player dies.",
                    "true  = clear soul master identity and all soul rings on death",
                    "false = keep soul master identity and soul rings on death (default)")
                    .define("clearSoulMasterOnDeath", false);

    /**
     * 是否允许向云端上传本地魂技改动（评分/手动上传/写回黑名单）。
     * <p>
     * 满足引擎文档 §13.3 隐私条款：服务端必须能为玩家提供「关闭上传」选项。
     * 关闭后所有本地改动仅保留在内存与本地快照，不发送任何写请求到 OSS；
     * 云端拉取与本地缓存查询不受影响（玩家仍可使用云端魂技库）。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_CLOUD_UPLOAD =
            BUILDER.comment(
                    "Whether the server may upload local skill changes (ratings / manual uploads / write-backs) to the cloud.",
                    "true  = uploads enabled (default, required for community sharing)",
                    "false = all local changes stay offline only; cloud reads still work")
                    .define("enableCloudUpload", true);

    /**
     * §6.4 批量上传窗口（分钟）：玩家评分/上传/推演触发 flush 后，
     * 同一 imageset 内新增改动合并到下一次 {@code syncCycle} 一起上传，
     * 避免每条单独 PUT 拉高请求频率。
     * <p>默认 30 分钟；设为 0 表示「每次本地改动立即 flush」（不推荐）。
     */
    public static final ModConfigSpec.IntValue UPLOAD_BATCH_INTERVAL_MINUTES =
            BUILDER.comment(
                    "§6.4 batch upload window in minutes: local changes within this window merge into one OSS PUT.",
                    "0 = flush immediately on every change (not recommended).",
                    "30 = default, matches ProfileCloudSync PUSH_THROTTLE_MS.")
                    .defineInRange("uploadBatchIntervalMinutes", 30, 0, 360);

    /**
     * §6.6 环境隔离：是否启用 environmentHash 严格分组（关掉后视为同环境整合）。
     * 主要用于集成测试与单机调试环境。生产建议保持 true。
     */
    public static final ModConfigSpec.BooleanValue STRICT_ENVIRONMENT_ISOLATION =
            BUILDER.comment(
                    "§6.6 strict environment isolation: profiles from different mod lists stay strictly separate.",
                    "true  = strict isolation (default, recommended for production)",
                    "false = relaxed: profiles merge even across different mod lists")
                    .define("strictEnvironmentIsolation", true);

    public static final ModConfigSpec SPEC = BUILDER.build();
}
