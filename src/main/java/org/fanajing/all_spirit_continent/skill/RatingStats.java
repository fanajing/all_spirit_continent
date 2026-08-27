package org.fanajing.all_spirit_continent.skill;

import com.google.gson.JsonObject;

/**
 * 魂技评分统计模型（对应云端条目 stats 字段）。
 * <p>
 * 评分规则：1-2 星 → reject_votes +1（差评）；3 星中性；4-5 星 → 计入 rating_sum / rating_count。
 * 差评率近似 = reject_votes / (rating_count + reject_votes)。
 */
public class RatingStats {
    public static final String POOL_ZHEN_PIN = "ZHEN_PIN";
    public static final String POOL_PIN_JIAN = "PIN_JIAN";
    public static final String POOL_FAN_PIN = "FAN_PIN";
    public static final String POOL_PENDING = "PENDING";

    /** 臻品池：平均分 >= 4.0 且评价数 >= 3 */
    public static final double ZHEN_PIN_MIN_AVG = 4.0;
    public static final int ZHEN_PIN_MIN_COUNT = 3;
    /** 凡品池：平均分 < 2.5 */
    public static final double FAN_PIN_MAX_AVG = 2.5;
    /** 待确认池：差评数 >= 5 且差评率 > 60% */
    public static final int PENDING_MIN_REJECTS = 5;
    public static final double PENDING_MAX_REJECT_RATE = 0.6;
    /** 品鉴观察期（tick）：上传 7 天内默认品鉴 */
    public static final long PIN_JIAN_TRIAL_TICKS = 7L * 24 * 60 * 60 * 20;
    /** 24 小时防刷（tick） */
    public static final long RATE_COOLDOWN_TICKS = 24L * 60 * 60 * 20;

    private int ratingSum;
    private int ratingCount;
    private int rejectVotes;
    private int downloads;
    private long uploadTime;

    public RatingStats() {
        this(0, 0, 0, 0, 0);
    }

    public RatingStats(int ratingSum, int ratingCount, int rejectVotes, int downloads, long uploadTime) {
        this.ratingSum = Math.max(0, ratingSum);
        this.ratingCount = Math.max(0, ratingCount);
        this.rejectVotes = Math.max(0, rejectVotes);
        this.downloads = Math.max(0, downloads);
        this.uploadTime = Math.max(0, uploadTime);
    }

    public int ratingSum() { return ratingSum; }
    public int ratingCount() { return ratingCount; }
    public int rejectVotes() { return rejectVotes; }
    public int downloads() { return downloads; }
    public long uploadTime() { return uploadTime; }

    public void addDownloads(int n) { this.downloads += n; }

    /** 平均分（无评价返回 0） */
    public double avg() {
        return ratingCount == 0 ? 0 : (double) ratingSum / ratingCount;
    }

    /** 差评率 = 差评 / (好评 + 差评)，均无返回 0 */
    public double rejectRate() {
        int total = ratingCount + rejectVotes;
        return total == 0 ? 0 : (double) rejectVotes / total;
    }

    /** 判定所属池（臻品/品鉴/凡品/待确认） */
    public String determinePool() {
        if (rejectVotes >= PENDING_MIN_REJECTS && rejectRate() > PENDING_MAX_REJECT_RATE) {
            return POOL_PENDING;
        }
        double avg = avg();
        if (ratingCount >= ZHEN_PIN_MIN_COUNT && avg >= ZHEN_PIN_MIN_AVG) return POOL_ZHEN_PIN;
        if (ratingCount > 0 && avg < FAN_PIN_MAX_AVG) return POOL_FAN_PIN;
        return POOL_PIN_JIAN;
    }

    /** 判定所属池（考虑 7 天观察期：新上传暂留品鉴池等待评价） */
    public String determinePool(long nowTicks) {
        String pool = determinePool();
        if (pool.equals(POOL_PIN_JIAN) && uploadTime > 0 && nowTicks - uploadTime < PIN_JIAN_TRIAL_TICKS) {
            return POOL_PIN_JIAN;
        }
        return pool;
    }

    /** 追加一条评分（1-2 星 → 差评；4-5 星 → 计入评分；3 星中性）。返回变化后所属池 */
    public String applyRating(int stars) {
        if (stars <= 2) {
            rejectVotes++;
        } else if (stars >= 4) {
            ratingSum += stars;
            ratingCount++;
        }
        return determinePool();
    }

    /** 撤回到期/错误评分（cancel 用，粗略回滚：4-5 星从评分中扣除） */
    public void undoRating(int stars) {
        if (stars <= 2) {
            rejectVotes = Math.max(0, rejectVotes - 1);
        } else if (stars >= 4) {
            ratingSum = Math.max(0, ratingSum - stars);
            ratingCount = Math.max(0, ratingCount - 1);
        }
    }

    // ===== JSON =====

    public static RatingStats fromJson(JsonObject obj) {
        if (obj == null) return new RatingStats();
        return new RatingStats(
                obj.has("rating_sum") ? obj.get("rating_sum").getAsInt() : 0,
                obj.has("rating_count") ? obj.get("rating_count").getAsInt() : 0,
                obj.has("reject_votes") ? obj.get("reject_votes").getAsInt() : 0,
                obj.has("downloads") ? obj.get("downloads").getAsInt() : 0,
                obj.has("upload_time") ? obj.get("upload_time").getAsLong() : 0);
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("rating_sum", ratingSum);
        obj.addProperty("rating_count", ratingCount);
        obj.addProperty("avg_rating", Math.round(avg() * 10.0) / 10.0);
        obj.addProperty("reject_votes", rejectVotes);
        obj.addProperty("downloads", downloads);
        obj.addProperty("upload_time", uploadTime);
        return obj;
    }

    /** 池权重：臻品 ×3 / 品鉴 ×1 / 凡品 ×0.3（PENDING 不参与抽取） */
    public static double poolWeight(String pool) {
        return switch (pool) {
            case POOL_ZHEN_PIN -> 3.0;
            case POOL_FAN_PIN -> 0.3;
            case POOL_PENDING -> 0.0;
            default -> 1.0;
        };
    }
}
