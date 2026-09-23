package org.fanajing.all_spirit_continent.util;

import org.fanajing.all_spirit_continent.skill.PoolSelector;
import org.fanajing.all_spirit_continent.skill.RatingStats;
import org.fanajing.all_spirit_continent.skill.SkillData;
import org.fanajing.all_spirit_continent.skill.SkillEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V6.0 验收标准验证工具（纯逻辑，不启动游戏）。
 * 通过 Gradle 运行：gradlew verifyV6
 * <ul>
 *   <li>验证池判定规则（臻品/品鉴/凡品/待确认）</li>
 *   <li>验证评分流转：评 1 星 → reject_votes+1，达到阈值自动进 PENDING（验收标准 4）</li>
 *   <li>验证三池分流：臻品池抽中率显著高于凡品池（验收标准 3）</li>
 * </ul>
 */
public final class V6Verify {
    private V6Verify() {
    }

    public static void main(String[] args) {
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),
                true, java.nio.charset.StandardCharsets.UTF_8));
        boolean allOk = true;
        allOk &= verifyPoolJudgement();
        allOk &= verifyRatingFlow();
        allOk &= verifySampling();
        System.out.println("====== V6.0 验收验证结果：" + (allOk ? "全部通过" : "存在失败") + " ======");
        if (!allOk) System.exit(1);
    }

    // ===== 1. 池判定 =====
    private static boolean verifyPoolJudgement() {
        System.out.println("---- 1. 池判定规则 ----");
        boolean ok = true;
        ok &= expect(RatingStats.POOL_PIN_JIAN, new RatingStats().determinePool(), "全新条目 → 品鉴");
        ok &= expect(RatingStats.POOL_ZHEN_PIN, new RatingStats(13, 3, 0, 0, 0).determinePool(), "平均4.33分×3评 → 臻品");
        ok &= expect(RatingStats.POOL_PIN_JIAN, new RatingStats(4, 1, 0, 0, 0).determinePool(), "平均4.0分×1评 → 评价不足3次不入臻品");
        ok &= expect(RatingStats.POOL_FAN_PIN, new RatingStats(2, 1, 0, 0, 0).determinePool(), "平均2.0分 → 凡品");
        ok &= expect(RatingStats.POOL_PENDING, new RatingStats(0, 0, 5, 0, 0).determinePool(), "差评5且差评率100% → 待确认");
        ok &= expect(RatingStats.POOL_FAN_PIN, new RatingStats(8, 5, 5, 0, 0).determinePool(), "5好评5差评差评率50%不达标(需>60%) → 平均1.6落凡品");
        ok &= expect(RatingStats.POOL_PIN_JIAN, new RatingStats(0, 0, 4, 0, 0).determinePool(), "差评4(未达5) → 品鉴");
        System.out.println("池判定：" + (ok ? "通过" : "失败"));
        return ok;
    }

    // ===== 2. 评分流转 → PENDING（验收标准 4） =====
    private static boolean verifyRatingFlow() {
        System.out.println("---- 2. 评分流转（验收标准 4：评 1 星 → reject_votes+1，达阈值自动进 PENDING） ----");
        RatingStats stats = new RatingStats(6, 2, 0, 0, 0); // 已有 2 条 4星+2星? 用 5+1=6/2=3.0 平均
        String pool = stats.determinePool();
        System.out.println("初始：" + pool + " (ratingSum=6, count=2, reject=0)");
        boolean ok = true;
        for (int i = 1; i <= 5; i++) {
            pool = stats.applyRating(1);
            boolean pending = RatingStats.POOL_PENDING.equals(pool);
            System.out.println("  第" + i + "次差评后：reject=" + stats.rejectVotes() + " 差评率=" + String.format("%.0f%%", stats.rejectRate() * 100) + " → " + pool);
            if (i < 5 && pending) {
                System.out.println("  [NG] 未达阈值（reject>=5 且 差评率>60%）不应进 PENDING");
                ok = false;
            }
        }
        ok &= expect(RatingStats.POOL_PENDING, pool, "差评5次后自动进 PENDING");
        // 好评降回：撤掉 2 次差评后应离开 PENDING（差评率 60% 恰好不 > 60%）
        stats.undoRating(1);
        stats.undoRating(1);
        pool = stats.determinePool();
        System.out.println("  撤回2次差评后：reject=" + stats.rejectVotes() + " → " + pool);
        if (RatingStats.POOL_PENDING.equals(pool)) {
            System.out.println("  [NG] 差评率降至60%边界不应留在 PENDING");
            ok = false;
        }
        System.out.println("评分流转：" + (ok ? "通过" : "失败"));
        return ok;
    }

    // ===== 3. 三池分流抽中率（验收标准 3） =====
    private static boolean verifySampling() {
        System.out.println("---- 3. 三池分流（验收标准 3：臻品池抽中率显著高于凡品池） ----");
        int rounds = 20000;
        List<SkillEntry> candidates = new ArrayList<>();
        for (int i = 0; i < 20; i++) candidates.add(entry(RatingStats.POOL_ZHEN_PIN, new RatingStats(20, 5, 0, 0, 0)));
        for (int i = 0; i < 60; i++) candidates.add(entry(RatingStats.POOL_PIN_JIAN, new RatingStats(9, 3, 0, 0, 0)));
        for (int i = 0; i < 20; i++) candidates.add(entry(RatingStats.POOL_FAN_PIN, new RatingStats(2, 1, 0, 0, 0)));
        Map<String, Integer> counts = PoolSelector.verifySampling(rounds, candidates);
        int zhen = counts.getOrDefault(RatingStats.POOL_ZHEN_PIN, 0);
        int jian = counts.getOrDefault(RatingStats.POOL_PIN_JIAN, 0);
        int fan = counts.getOrDefault(RatingStats.POOL_FAN_PIN, 0);
        double zhenRate = (double) zhen / rounds;
        double jianRate = (double) jian / rounds;
        double fanRate = (double) fan / rounds;
        System.out.printf("抽样 %d 次：臻品 %d (%.2f%%) / 品鉴 %d (%.2f%%) / 凡品 %d (%.2f%%)%n",
                rounds, zhen, zhenRate * 100, jian, jianRate * 100, fan, fanRate * 100);
        System.out.printf("理论权重：臻品 60 / 品鉴 60 / 凡品 6 → 期望臻品 47.6%% / 凡品 4.8%%%n");
        boolean ok = true;
        if (zhenRate <= fanRate) {
            System.out.println("失败：臻品抽中率未显著高于凡品");
            ok = false;
        }
        // 允许 2% 浮动：臻品 ≈ 47.6% ±2%，凡品 ≈ 4.8% ±2%
        if (Math.abs(zhenRate - 60.0 / 126.0) > 0.02 || Math.abs(fanRate - 6.0 / 126.0) > 0.02) {
            System.out.println("失败：抽中率偏离权重预期（浮动超过 2%）");
            ok = false;
        }
        System.out.println("三池分流：" + (ok ? "通过" : "失败"));
        return ok;
    }

    private static SkillEntry entry(String pool, RatingStats stats) {
        SkillData sd = new SkillData(UUID.randomUUID().toString(), "验证魂技", "验证用", 20,
                "RIGHT_CLICK", List.of(), List.of(), "昊天锤", "minecraft:zombie", "20_30", 100, List.of(), "");
        return new SkillEntry(sd, pool, stats, "", "");
    }

    private static boolean expect(String expected, String actual, String desc) {
        boolean ok = expected.equals(actual);
        System.out.println("  " + (ok ? "[OK]" : "[NG]") + " " + desc + " → 期望=" + expected + " 实际=" + actual);
        return ok;
    }
}
