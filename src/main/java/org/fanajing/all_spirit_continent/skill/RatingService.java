package org.fanajing.all_spirit_continent.skill;

import net.minecraft.server.level.ServerPlayer;
import org.fanajing.all_spirit_continent.cloud.CloudSyncService;
import org.fanajing.all_spirit_continent.data.PlayerSkillDataStore;
import org.fanajing.all_spirit_continent.util.PlayerSkillConfig;

import java.util.function.Consumer;

/**
 * 评分服务（/douluo rate 指令与吸收确认界面共用）。
 * <ul>
 *   <li>rate：24h 防刷校验 → 记录评分记录（星数）→ 更新云端条目 stats（1-2 星差评 / 4-5 星计分）→ 排队写回</li>
 *   <li>cancel：撤回最近一次评分（回滚 stats，仅允许 24h 内评过的）</li>
 * </ul>
 */
public final class RatingService {
    private RatingService() {
    }

    /** 评分 1-5 星。返回 false：星数非法 / 24h 冷却中 / 技能不存在 */
    public static boolean rate(ServerPlayer player, String skillUuid, int stars) {
        if (stars < 1 || stars > 5) return false;
        PlayerSkillDataStore store = PlayerSkillDataStore.get(player.serverLevel());
        long nowTicks = player.serverLevel().getGameTime();
        if (!store.markRated(player, skillUuid, nowTicks, stars)) {
            return false; // 24h 防刷
        }
        updateStats(skillUuid, stats -> stats.applyRating(stars));
        return true;
    }

    /** 撤回评分。返回 false：未评过该技能 */
    public static boolean cancel(ServerPlayer player, String skillUuid) {
        PlayerSkillDataStore store = PlayerSkillDataStore.get(player.serverLevel());
        PlayerSkillConfig cfg = store.config(player);
        PlayerSkillConfig.RatingRecord record = cfg.ratingOf(skillUuid);
        if (record == null) return false;
        cfg.unmarkRated(skillUuid);
        store.setDirty();
        int stars = record.stars();
        if (stars > 0) {
            updateStats(skillUuid, stats -> stats.undoRating(stars));
        }
        return true;
    }

    /** 更新云端条目评分统计（复制后修改，避免并发改共享引用）并排队写回 */
    private static void updateStats(String skillUuid, Consumer<RatingStats> action) {
        CloudSyncService.findById(skillUuid).ifPresent(entry -> {
            RatingStats s = entry.stats();
            RatingStats copy = new RatingStats(s.ratingSum(), s.ratingCount(),
                    s.rejectVotes(), s.downloads(), s.uploadTime());
            action.accept(copy);
            CloudSyncService.queueStatsUpdate(skillUuid, copy);
        });
    }
}
