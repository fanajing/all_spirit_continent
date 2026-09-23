package org.fanajing.all_spirit_continent.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.skill.SkillData;
import org.fanajing.all_spirit_continent.util.PlayerSkillConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家魂技配置存储（SavedData，挂载在主世界 DataStorage）。
 * <p>
 * 等价实现需求文档的 player_config.json（服务端权威、防并发写损坏）：
 * 以 Map&lt;UUID, PlayerSkillConfig&gt; 按玩家存储 camp/apiKey/环位绑定/评分冷却/武魂。
 * 所有修改方法自动 setDirty() 落盘；旧存档无数据时按默认值（SHARED、空环位）兼容。
 */
public class PlayerSkillDataStore extends SavedData {

    private static final String DATA_ID = All_spirit_continent.MODID + "_player_skills";
    private static final String TAG_PLAYERS = "players";

    private final Map<UUID, PlayerSkillConfig> players = new HashMap<>();

    private static final Factory<PlayerSkillDataStore> FACTORY = new Factory<>(
            PlayerSkillDataStore::new,
            (tag, provider) -> load(tag)
    );

    public PlayerSkillDataStore() {
    }

    /** 获取全局玩家魂技配置（统一走主世界 DataStorage） */
    public static PlayerSkillDataStore get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage()
                .computeIfAbsent(FACTORY, DATA_ID);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag playersTag = new CompoundTag();
        for (Map.Entry<UUID, PlayerSkillConfig> e : players.entrySet()) {
            playersTag.put(e.getKey().toString(), e.getValue().save(new CompoundTag()));
        }
        tag.put(TAG_PLAYERS, playersTag);
        return tag;
    }

    private static PlayerSkillDataStore load(CompoundTag tag) {
        PlayerSkillDataStore store = new PlayerSkillDataStore();
        if (tag.contains(TAG_PLAYERS, Tag.TAG_COMPOUND)) {
            CompoundTag playersTag = tag.getCompound(TAG_PLAYERS);
            for (String key : playersTag.getAllKeys()) {
                try {
                    store.players.put(UUID.fromString(key),
                            PlayerSkillConfig.load(playersTag.getCompound(key)));
                } catch (IllegalArgumentException ignored) {
                    // 损坏的 UUID 键直接跳过
                }
            }
        }
        return store;
    }

    // ===== 读取 =====

    /** 获取玩家配置（无则创建默认配置并落盘） */
    public PlayerSkillConfig config(ServerPlayer player) {
        UUID uuid = player.getUUID();
        PlayerSkillConfig cfg = players.get(uuid);
        if (cfg == null) {
            cfg = new PlayerSkillConfig();
            players.put(uuid, cfg);
            setDirty();
        }
        return cfg;
    }

    public PlayerSkillConfig config(UUID uuid) {
        return players.computeIfAbsent(uuid, u -> new PlayerSkillConfig());
    }

    public boolean hasConfig(UUID uuid) {
        return players.containsKey(uuid);
    }

    // ===== 修改（自动落盘）=====

    public void bindSkill(ServerPlayer player, int slot, SkillData data) {
        config(player).bindSkill(slot, data);
        setDirty();
    }

    public void unbindSkill(ServerPlayer player, int slot) {
        config(player).unbindSkill(slot);
        setDirty();
    }

    /** 绑定签名机制到环位（三池条目绑定技能时回放玩家级持有状态）；冲突时返回 false */
    public boolean bindMechanism(ServerPlayer player, int slot, org.fanajing.all_spirit_continent.skill.engine.SignatureMechanism mechanism) {
        boolean ok = config(player).bindMechanism(slot, mechanism);
        if (ok) setDirty();
        return ok;
    }

    /** 解绑环位签名机制（拒绝吸收回滚时调用） */
    public void unbindMechanism(ServerPlayer player, int slot) {
        config(player).unbindMechanism(slot);
        setDirty();
    }

    public void setCamp(ServerPlayer player, String camp) {
        config(player).setCamp(camp);
        setDirty();
    }

    public void setApiKey(ServerPlayer player, String apiKey) {
        config(player).setApiKey(apiKey);
        setDirty();
    }

    public void setSoloUpload(ServerPlayer player, boolean value) {
        config(player).setSoloUpload(value);
        setDirty();
    }

    public void setSoloDownload(ServerPlayer player, boolean value) {
        config(player).setSoloDownload(value);
        setDirty();
    }

    public void setWuhun(ServerPlayer player, String wuhun) {
        config(player).setWuhun(wuhun);
        setDirty();
    }

    /** 记录评分时间戳与星数（24h 防刷）；返回是否成功（冷却中返回 false） */
    public boolean markRated(ServerPlayer player, String skillUuid, long nowTicks, int stars) {
        PlayerSkillConfig cfg = config(player);
        if (!cfg.canRate(skillUuid, nowTicks)) return false;
        cfg.markRated(skillUuid, nowTicks, stars);
        setDirty();
        return true;
    }

    public void unmarkRated(ServerPlayer player, String skillUuid) {
        config(player).unmarkRated(skillUuid);
        setDirty();
    }
}
