package org.fanajing.all_spirit_continent.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.fanajing.all_spirit_continent.All_spirit_continent;

/**
 * 魂兽全局状态（SavedData，挂载在主世界 DataStorage，跨维度共享、随存档持久化）。
 * <p>
 * 管理百万年魂兽的两条全局规则：
 *  - 全世界（所有维度合计）最高存在 2 只百万年以上魂兽
 *  - 百万年魂兽被击杀后进入冷却，冷却期结束后才会再次生成
 */
public class SoulBeastWorldData extends SavedData {

    /** 百万年魂兽全世界数量上限 */
    public static final int MILLION_LIMIT = 2;
    /** 百万年魂兽死亡后的冷却时长：30 分钟（36000 tick） */
    public static final long COOLDOWN_TICKS = 20L * 60 * 30;

    private static final String DATA_ID = All_spirit_continent.MODID + "_soul_beast";
    private static final String TAG_ALIVE = "millionAlive";
    private static final String TAG_COOLDOWN = "cooldownUntil";

    /** 当前存活的百万年魂兽数量 */
    private int millionAlive;
    /** 冷却结束时间（游戏时间 tick，<= 当前时间表示可以生成百万年魂兽） */
    private long cooldownUntil;

    private static final SavedData.Factory<SoulBeastWorldData> FACTORY = new SavedData.Factory<>(
            SoulBeastWorldData::new,
            (tag, provider) -> new SoulBeastWorldData(
                    tag.getInt(TAG_ALIVE), tag.getLong(TAG_COOLDOWN))
    );

    public SoulBeastWorldData() {
        this(0, 0);
    }

    public SoulBeastWorldData(int millionAlive, long cooldownUntil) {
        this.millionAlive = Math.max(0, millionAlive);
        this.cooldownUntil = cooldownUntil;
    }

    /** 获取全局魂兽状态（统一走主世界 DataStorage，保证全维度共享） */
    public static SoulBeastWorldData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage()
                .computeIfAbsent(FACTORY, DATA_ID);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putInt(TAG_ALIVE, millionAlive);
        tag.putLong(TAG_COOLDOWN, cooldownUntil);
        return tag;
    }

    public int getMillionAlive() {
        return millionAlive;
    }

    public long getCooldownUntil() {
        return cooldownUntil;
    }

    /** 当前是否允许生成新的百万年魂兽（只判断，不计数） */
    public boolean canSpawnMillion(long gameTime) {
        return millionAlive < MILLION_LIMIT && gameTime >= cooldownUntil;
    }

    /** 一只百万年魂兽生成成功：计数 +1（调用前必须已通过 canSpawnMillion 校验） */
    public void onMillionSpawned() {
        millionAlive = Math.min(MILLION_LIMIT, millionAlive + 1);
        setDirty();
    }

    /** 一只百万年魂兽死亡：计数 -1 并进入冷却 */
    public void onMillionDeath(long gameTime) {
        millionAlive = Math.max(0, millionAlive - 1);
        cooldownUntil = gameTime + COOLDOWN_TICKS;
        setDirty();
    }
}
