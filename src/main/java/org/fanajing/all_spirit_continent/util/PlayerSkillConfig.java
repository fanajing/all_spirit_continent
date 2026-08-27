package org.fanajing.all_spirit_continent.util;

import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.fanajing.all_spirit_continent.skill.RatingStats;
import org.fanajing.all_spirit_continent.skill.SkillData;

import java.util.HashMap;
import java.util.Map;

/**
 * 玩家魂技配置（等价需求文档的 player_config.json 单玩家条目）。
 * <p>
 * 由 {@code PlayerSkillDataStore}（世界级 SavedData）按玩家 UUID 集中存储，
 * 服务端权威、支持并发安全落盘。apiKey 仅存在于服务端，绝不发给客户端。
 * <p>
 * 字段：
 * - camp：SHARED（共享，默认）/ SOLO（独狼）
 * - apiKey：独狼私人 Key（共享阵营留空）
 * - soloUpload / soloDownload：独狼上传/下载开关（仅 SOLO 生效）
 * - rings：环位 1-9 → 已绑定魂技（完整 SkillData；未绑定=该环无技能）
 * - ratedSkills：技能 uuid → 上次评分时刻（游戏 tick），24h 防刷
 * - wuhun：武魂名（默认「昊天锤」，云端组合键 (武魂+怪物注册名+血量档位) 用）
 */
public class PlayerSkillConfig {
    public static final String CAMP_SHARED = "SHARED";
    public static final String CAMP_SOLO = "SOLO";
    public static final String DEFAULT_WUHUN = "昊天锤";

    private String camp = CAMP_SHARED;
    private String apiKey = "";
    private boolean soloUpload = false;
    private boolean soloDownload = false;
    private final Map<Integer, SkillData> rings = new HashMap<>();
    /** uuid → 评分记录（时间 + 星数，用于 24h 防刷与撤回回滚） */
    private final Map<String, RatingRecord> ratedSkills = new HashMap<>();
    private String wuhun = DEFAULT_WUHUN;

    /** 评分记录：评星时刻（游戏 tick）+ 所评星数 */
    public record RatingRecord(long timeTicks, int stars) {
    }

    // ----- 阵营 -----
    public String camp() { return camp; }
    public boolean isSolo() { return CAMP_SOLO.equals(camp); }
    public void setCamp(String camp) {
        this.camp = CAMP_SOLO.equals(camp) ? CAMP_SOLO : CAMP_SHARED;
    }

    public String apiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey.trim(); }

    public boolean soloUpload() { return soloUpload; }
    public void setSoloUpload(boolean soloUpload) { this.soloUpload = soloUpload; }
    public boolean soloDownload() { return soloDownload; }
    public void setSoloDownload(boolean soloDownload) { this.soloDownload = soloDownload; }

    public String wuhun() { return wuhun; }
    public void setWuhun(String wuhun) { this.wuhun = wuhun == null || wuhun.isBlank() ? DEFAULT_WUHUN : wuhun.trim(); }

    // ----- 环位绑定（1-9）-----
    /** 该环位已绑定的技能；未绑定返回 null */
    public SkillData skillAt(int slot) { return rings.get(slot); }

    /** 该环位已绑定技能 uuid；未绑定返回 null */
    public String skillUuidAt(int slot) {
        SkillData data = rings.get(slot);
        return data == null ? null : data.uuid();
    }

    public boolean hasSkill(int slot) { return rings.get(slot) != null; }
    public void bindSkill(int slot, SkillData data) { if (slot >= 1 && data != null) rings.put(slot, data); }
    public void unbindSkill(int slot) { rings.remove(slot); }
    public Map<Integer, SkillData> rings() { return rings; }

    /** 已绑定技能的环位数 */
    public int boundSkillCount() { return rings.size(); }

    /** 第一个已绑定技能的环位；无返回 -1 */
    public int firstBoundSlot() {
        return rings.keySet().stream().min(Integer::compareTo).orElse(-1);
    }

    // ----- 评分防刷（24h）-----
    public boolean canRate(String skillUuid, long nowTicks) {
        RatingRecord last = ratedSkills.get(skillUuid);
        return last == null || nowTicks - last.timeTicks() >= RatingStats.RATE_COOLDOWN_TICKS;
    }

    public void markRated(String skillUuid, long nowTicks, int stars) {
        ratedSkills.put(skillUuid, new RatingRecord(nowTicks, stars));
    }

    /** 该技能最近一次评分记录；未评过返回 null */
    public RatingRecord ratingOf(String skillUuid) {
        return ratedSkills.get(skillUuid);
    }

    /** 撤回评分（/douluo rate X cancel）——仅允许最近 24h 内评过的 */
    public void unmarkRated(String skillUuid) {
        ratedSkills.remove(skillUuid);
    }

    public Map<String, RatingRecord> ratedSkills() { return ratedSkills; }

    // ===== NBT 序列化（SavedData 存档）=====

    public CompoundTag save(CompoundTag tag) {
        tag.putString("camp", camp);
        tag.putString("api_key", apiKey);
        tag.putBoolean("solo_upload", soloUpload);
        tag.putBoolean("solo_download", soloDownload);
        tag.putString("wuhun", wuhun);

        ListTag ringList = new ListTag();
        for (Map.Entry<Integer, SkillData> e : rings.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putInt("slot", e.getKey());
            t.putString("skill_json", e.getValue().toJson().toString());
            ringList.add(t);
        }
        tag.put("rings", ringList);

        ListTag ratedList = new ListTag();
        for (Map.Entry<String, RatingRecord> e : ratedSkills.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putString("uuid", e.getKey());
            t.putLong("time", e.getValue().timeTicks());
            t.putInt("stars", e.getValue().stars());
            ratedList.add(t);
        }
        tag.put("rated_skills", ratedList);
        return tag;
    }

    public static PlayerSkillConfig load(CompoundTag tag) {
        PlayerSkillConfig cfg = new PlayerSkillConfig();
        if (tag.contains("camp")) cfg.camp = CAMP_SOLO.equals(tag.getString("camp")) ? CAMP_SOLO : CAMP_SHARED;
        if (tag.contains("api_key")) cfg.apiKey = tag.getString("api_key");
        if (tag.contains("solo_upload")) cfg.soloUpload = tag.getBoolean("solo_upload");
        if (tag.contains("solo_download")) cfg.soloDownload = tag.getBoolean("solo_download");
        if (tag.contains("wuhun")) cfg.wuhun = tag.getString("wuhun");

        if (tag.contains("rings", Tag.TAG_LIST)) {
            for (Tag t : tag.getList("rings", Tag.TAG_COMPOUND)) {
                CompoundTag ct = (CompoundTag) t;
                // 旧存档（uuid 字段）兼容：跳过 uuid-only 绑定，保留新格式 skill_json
                if (!ct.contains("skill_json", Tag.TAG_STRING)) continue;
                try {
                    SkillData data = SkillData.fromCloudJson(
                            JsonParser.parseString(ct.getString("skill_json")).getAsJsonObject());
                    if (data != null) {
                        cfg.rings.put(ct.getInt("slot"), data);
                    }
                } catch (Exception ignored) {
                    // 损坏的技能 JSON 直接丢弃该环位
                }
            }
        }
        if (tag.contains("rated_skills", Tag.TAG_LIST)) {
            for (Tag t : tag.getList("rated_skills", Tag.TAG_COMPOUND)) {
                CompoundTag ct = (CompoundTag) t;
                long time = ct.getLong("time");
                int stars = ct.contains("stars", Tag.TAG_INT) ? ct.getInt("stars") : 0;
                cfg.ratedSkills.put(ct.getString("uuid"), new RatingRecord(time, stars));
            }
        }
        return cfg;
    }
}
