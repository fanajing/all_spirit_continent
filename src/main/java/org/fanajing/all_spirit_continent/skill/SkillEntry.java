package org.fanajing.all_spirit_continent.skill;

import com.google.gson.JsonObject;

/**
 * 云端魂技库条目（public_skills.json 中每个元素的完整模型）。
 * <p>
 * = SkillData（技能本体）+ pool（当前所属池）+ stats（评分统计）
 * + uploaderHash（上传者匿名标识，供后台统计/封禁）+ uploaderName（上传者游戏 ID，供玩家展示）
 */
public record SkillEntry(SkillData skill, String pool, RatingStats stats,
                         String uploaderHash, String uploaderName) {

    public SkillEntry {
        pool = pool == null ? RatingStats.POOL_PIN_JIAN : pool;
        stats = stats == null ? new RatingStats() : stats;
        uploaderHash = uploaderHash == null ? "" : uploaderHash;
        uploaderName = uploaderName == null ? "" : uploaderName;
    }

    /** 从云端条目 JSON 解析；校验失败返回 null */
    public static SkillEntry fromCloudJson(JsonObject entry) {
        SkillData skill = SkillData.fromCloudJson(entry);
        if (skill == null) return null;
        String pool = entry.has("pool") ? entry.get("pool").getAsString() : RatingStats.POOL_PIN_JIAN;
        RatingStats stats = entry.has("stats") && entry.get("stats").isJsonObject()
                ? RatingStats.fromJson(entry.getAsJsonObject("stats"))
                : new RatingStats();
        String uploaderHash = entry.has("uploader_hash") ? entry.get("uploader_hash").getAsString() : "";
        String uploaderName = entry.has("uploader_name") ? entry.get("uploader_name").getAsString() : "";
        return new SkillEntry(skill, pool, stats, uploaderHash, uploaderName);
    }

    /** 构造 AI 新生成的条目（默认入品鉴池；上传者信息由调用方补充） */
    public static SkillEntry fromAi(SkillData skill) {
        return new SkillEntry(skill, RatingStats.POOL_PIN_JIAN, new RatingStats(), "", "");
    }

    /** 序列化为云端条目 JSON */
    public JsonObject toCloudJson() {
        JsonObject entry = skill.toJson();
        entry.addProperty("pool", pool);
        entry.add("stats", stats.toJson());
        entry.addProperty("uploader_hash", uploaderHash);
        entry.addProperty("uploader_name", uploaderName);
        return entry;
    }

    /** 换绑技能副本（如补写魂环年限），保持池/评分/上传者不变 */
    public SkillEntry withSkill(SkillData newSkill) {
        return new SkillEntry(newSkill, pool, stats, uploaderHash, uploaderName);
    }

    public String uuid() {
        return skill.uuid();
    }

    public String pool() {
        return pool;
    }

    /** 该条目是否处于待确认池（不参与抽取） */
    public boolean isPending() {
        return RatingStats.POOL_PENDING.equals(pool);
    }
}
