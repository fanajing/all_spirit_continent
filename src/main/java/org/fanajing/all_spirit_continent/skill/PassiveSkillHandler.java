package org.fanajing.all_spirit_continent.skill;

import net.minecraft.server.level.ServerPlayer;
import org.fanajing.all_spirit_continent.data.PlayerSkillDataStore;
import org.fanajing.all_spirit_continent.init.ModAttachments;
import org.fanajing.all_spirit_continent.util.PlayerLevelData;
import org.fanajing.all_spirit_continent.util.PlayerSkillConfig;

/**
 * 融合被动处理器：武魂身份激活且手持武魂武器（主手非空）时，
 * 周期刷新所有已绑定魂技的 passive 效果（纯 buff，不消耗精神力、无冷却）。
 * <p>
 * 设计思路：被动的本质 = 手持武魂时由系统持续施加增益效果。
 * 每 {@link #REFRESH_INTERVAL} tick 统一刷新一次，效果 duration（默认 200 tick）
 * 远大于刷新间隔，到期前反复续杯，玩家体感为常驻 buff。
 */
public final class PassiveSkillHandler {
    /** 被动刷新间隔（tick）：每 2 秒刷新一次 */
    private static final int REFRESH_INTERVAL = 40;

    private PassiveSkillHandler() {
    }

    /** 服务端每 tick 调用：条件满足时刷新融合被动 */
    public static void tick(ServerPlayer player) {
        if (player.tickCount % REFRESH_INTERVAL != 0) return;
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        if (data == null || !data.isActivated() || data.getRingCount() <= 0) return;
        // 手持武魂武器（主手非空）才获得被动
        if (player.getMainHandItem().isEmpty()) return;
        PlayerSkillConfig cfg = PlayerSkillDataStore.get(player.serverLevel()).config(player);
        for (SkillData skill : cfg.rings().values()) {
            if (skill == null || skill.passive() == null || skill.passive().isEmpty()) continue;
            for (SkillData.ExecutionStep step : skill.passive()) {
                SkillExecutor.applyPassive(player, step);
            }
        }
    }
}
