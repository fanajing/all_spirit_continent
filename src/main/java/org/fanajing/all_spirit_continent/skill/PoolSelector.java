package org.fanajing.all_spirit_continent.skill;

import net.minecraft.util.RandomSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 三池分流抽取器（需求文档 9 节：三池分流）。
 * <ul>
 *   <li>臻品池 ×3 / 品鉴池 ×1 / 凡品池 ×0.3（PENDING 权重 0，已由查询层过滤）</li>
 *   <li>候选列表 = 按 (武魂+怪物注册名+血量档位) 从云端缓存过滤（PENDING 已剔除），再进行加权随机</li>
 *   <li>无候选时由吸收流程触发 AI 生成（此处不负责）</li>
 * </ul>
 */
public class PoolSelector {
    private static final RandomSource RANDOM = RandomSource.create();

    private PoolSelector() {
    }

    /** 从候选技能中按三池权重加权随机抽取一个；无候选返回 empty */
    public static Optional<SkillEntry> pick(List<SkillEntry> candidates) {
        if (candidates == null || candidates.isEmpty()) return Optional.empty();
        double total = 0.0;
        for (SkillEntry entry : candidates) {
            total += RatingStats.poolWeight(entry.pool());
        }
        if (total <= 0.0) return Optional.empty();
        double r = RANDOM.nextDouble() * total;
        for (SkillEntry entry : candidates) {
            r -= RatingStats.poolWeight(entry.pool());
            if (r < 0.0) return Optional.of(entry);
        }
        return Optional.of(candidates.get(candidates.size() - 1));
    }

    /** 抽取并累加下载量（感应命中时调用） */
    public static SkillEntry pickWithDownload(List<SkillEntry> candidates) {
        Optional<SkillEntry> picked = pick(candidates);
        picked.ifPresent(entry -> {
            RatingStats s = entry.stats();
            RatingStats copy = new RatingStats(s.ratingSum(), s.ratingCount(),
                    s.rejectVotes(), s.downloads() + 1, s.uploadTime());
            org.fanajing.all_spirit_continent.cloud.CloudSyncService.queueStatsUpdate(entry.uuid(), copy);
        });
        return picked.orElse(null);
    }

    /**
     * 分流验证工具：对给定候选执行 rounds 次抽取，统计各池命中次数。
     * 用于验证「臻品池抽中率显著高于凡品池」（需求文档验收标准 3）。
     */
    public static Map<String, Integer> verifySampling(int rounds, List<SkillEntry> candidates) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put(RatingStats.POOL_ZHEN_PIN, 0);
        counts.put(RatingStats.POOL_PIN_JIAN, 0);
        counts.put(RatingStats.POOL_FAN_PIN, 0);
        if (candidates == null || candidates.isEmpty()) return counts;
        for (int i = 0; i < rounds; i++) {
            pick(candidates).ifPresent(e -> counts.merge(e.pool(), 1, Integer::sum));
        }
        return counts;
    }
}
