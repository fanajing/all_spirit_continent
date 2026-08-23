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

    public static final ModConfigSpec SPEC = BUILDER.build();
}
