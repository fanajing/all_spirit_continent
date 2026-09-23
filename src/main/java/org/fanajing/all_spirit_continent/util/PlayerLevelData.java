package org.fanajing.all_spirit_continent.util;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 玩家等级系统数据，通过 NeoForge Attachment 挂载到每个玩家。
 * 绑定到玩家 UUID，支持持久化保存和客户端自动同步。
 * 只有水晶球碎裂激活后，数据才生效。
 */
public class PlayerLevelData {
    private boolean activated = false;
    private int level = 1;
    /** 当前等级内已积累的经验值（吸收魂环获得，升级时扣除所需经验） */
    private int exp = 0;
    private float spiritPower = 100f;
    private float maxSpiritPower = 100f;
    /** 自定义封号（91级后可 /fh 设置，最多2个字，空=未设置） */
    private String title = "";
    /** 每环年限（年，index = 环序号，0 = 无环占位；十年=10、百年=100…百万年=1000000，支持非整数级年限如 6070） */
    private List<Integer> ringAges;
    /** 已消耗「重刷（自行推演）」机会的环位集合（index 与 ringAges 一致；每玩家每环位仅一次） */
    private final Set<Integer> rerolledRings;
    /** 武魂模式（开环物品栏替换）是否开启：服务端权威状态；异常退出时随存档恢复，保证物品栏一致 */
    private boolean martialSoulOpen = false;

    /** 持久化序列化 Codec（服务端 NBT 存档） */
    public static final Codec<PlayerLevelData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.BOOL.fieldOf("activated").forGetter(d -> d.activated),
                    Codec.INT.fieldOf("level").forGetter(d -> d.level),
                    Codec.INT.optionalFieldOf("exp", 0).forGetter(d -> d.exp),
                    Codec.FLOAT.fieldOf("spiritPower").forGetter(d -> d.spiritPower),
                    Codec.FLOAT.fieldOf("maxSpiritPower").forGetter(d -> d.maxSpiritPower),
                    Codec.STRING.optionalFieldOf("title", "").forGetter(d -> d.title),
                    Codec.INT.listOf().optionalFieldOf("ringAges", List.of()).forGetter(d -> d.ringAges),
                    Codec.INT.listOf().optionalFieldOf("rerolledRings", List.of()).forGetter(d -> List.copyOf(d.rerolledRings)),
                    Codec.BOOL.optionalFieldOf("martialSoulOpen", false).forGetter(d -> d.martialSoulOpen)
            ).apply(instance, PlayerLevelData::new)
    );

    /** 网络同步 StreamCodec（服务端 → 客户端自动同步） */
    public static final StreamCodec<FriendlyByteBuf, PlayerLevelData> STREAM_CODEC =
            StreamCodec.of(
                    (buf, data) -> {
                        buf.writeBoolean(data.activated);
                        buf.writeInt(data.level);
                        buf.writeInt(data.exp);
                        buf.writeFloat(data.spiritPower);
                        buf.writeFloat(data.maxSpiritPower);
                        buf.writeUtf(data.title);
                        for (int i = 0; i < SoulRingLayout.MAX_RINGS; i++) {
                            buf.writeVarInt(data.getRingAge(i));
                        }
                    },
                    buf -> {
                        PlayerLevelData data = new PlayerLevelData(
                                buf.readBoolean(),
                                buf.readInt(),
                                buf.readInt(),
                                buf.readFloat(),
                                buf.readFloat(),
                                buf.readUtf(),
                                List.of(),
                                List.of(),
                                false // 武魂状态不网络同步（客户端动画由动画包单独驱动）
                        );
                        for (int i = 0; i < SoulRingLayout.MAX_RINGS; i++) {
                            data.setRingAge(i, buf.readVarInt());
                        }
                        return data;
                    }
            );

    /** 无参构造（AttachmentType 工厂需要） */
    public PlayerLevelData() {
        this.ringAges = new ArrayList<>(Collections.nCopies(SoulRingLayout.MAX_RINGS, 0));
        this.rerolledRings = new HashSet<>();
    }

    /** 全参构造（Codec 反序列化）：旧档位（0~5）自动迁移为具体年限；精神力上限按成长公式重算 */
    public PlayerLevelData(boolean activated, int level, int exp, float spiritPower, float maxSpiritPower,
                           String title, List<Integer> ringAges, List<Integer> rerolledRings, boolean martialSoulOpen) {
        this.activated = activated;
        this.level = level;
        this.exp = Math.max(0, exp);
        this.title = title == null ? "" : title;
        this.martialSoulOpen = martialSoulOpen;
        this.ringAges = new ArrayList<>();
        for (int age : ringAges) {
            this.ringAges.add(migrateRingAge(age));
        }
        this.rerolledRings = new HashSet<>(rerolledRings == null ? List.of() : rerolledRings);
        // 精神力上限按成长公式重算（旧存档的固定值被覆盖，保证新成长公式生效）；
        // maxSpiritPower 参数仅用于保持 Codec 格式兼容
        this.maxSpiritPower = SoulGrowth.maxSpiritPower(this);
        this.spiritPower = Math.min(spiritPower, this.maxSpiritPower);
    }

    /** 旧存档档位（0=十年…5=百万年）→ 具体年限；0（无环占位）与 10+（新格式）保持不变 */
    private static int migrateRingAge(int age) {
        if (age >= 1 && age <= 5) {
            return switch (age) {
                case 1 -> 100;
                case 2 -> 1000;
                case 3 -> 10000;
                case 4 -> 100000;
                case 5 -> 1000000;
                default -> 10;
            };
        }
        return age;
    }

    // ----- 激活状态 -----
    public boolean isActivated() {
        return activated;
    }

    public void setActivated(boolean activated) {
        this.activated = activated;
    }

    // ----- 等级 -----
    public int getLevel() {
        return level;
    }

    /** 设置等级并重算精神力：上限按成长公式（等级成长 + 已获魂环年限加成），等级变化时精神力回满 */
    public void setLevel(int level) {
        this.level = level;
        refreshSpiritPower();
    }

    /**
     * 已获得魂环数 = 年限大于 0 的环位数量（实际持有魂环数，与等级解耦）。
     * 节点等级（10/20/.../90）封顶期间未获得新魂环不计入，
     * 吸收魂环获得新环（不升级）后 +1。
     */
    public int getRingCount() {
        int count = 0;
        for (int age : ringAges) {
            if (age > 0) count++;
        }
        return count;
    }

    /** 重算精神力上限并按当前等级回满（魂环年限变化后也要调用） */
    public void refreshSpiritPower() {
        this.maxSpiritPower = SoulGrowth.maxSpiritPower(this);
        this.spiritPower = this.maxSpiritPower;
    }

    /** 升级并回复精神力 */
    public void levelUp() {
        this.level++;
        this.spiritPower = this.maxSpiritPower;
    }

    // ----- 经验 -----
    public int getExp() {
        return exp;
    }

    public void setExp(int exp) {
        this.exp = Math.max(0, exp);
    }

    /** 增加经验（可为负：升级时扣除所需经验），不为负 */
    public void addExp(int amount) {
        this.exp = Math.max(0, this.exp + amount);
    }

    // ----- 精神力（魔法值）-----
    public float getSpiritPower() {
        return spiritPower;
    }
    public void setSpiritPower(float spiritPower) {
        this.spiritPower = Math.max(0, Math.min(spiritPower, maxSpiritPower));
    }

    public float getMaxSpiritPower() {
        return maxSpiritPower;
    }

    public void setMaxSpiritPower(float maxSpiritPower) {
        this.maxSpiritPower = maxSpiritPower;
        this.spiritPower = Math.min(this.spiritPower, this.maxSpiritPower);
    }

    public void consumeSpiritPower(float amount) {
        this.spiritPower = Math.max(0, this.spiritPower - amount);
    }

    public void restoreSpiritPower(float amount) {
        this.spiritPower = Math.min(this.maxSpiritPower, this.spiritPower + amount);
    }

    // ----- 自定义封号（称号） -----
    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? "" : title;
    }

    // ----- 武魂模式（开环物品栏替换，服务端状态）-----
    public boolean isMartialSoulOpen() {
        return martialSoulOpen;
    }

    public void setMartialSoulOpen(boolean martialSoulOpen) {
        this.martialSoulOpen = martialSoulOpen;
    }

    // ----- 魂环年限（每环独立，具体年限值）-----
    /** 获取第 index 环（从0开始）的年限（年）；越界/缺失返回 0（无环） */
    public int getRingAge(int index) {
        if (index < 0 || index >= ringAges.size()) return 0;
        return ringAges.get(index);
    }

    /** 设置第 index 环（从0开始）的年限（列表自动扩展，缺失位默认 0 = 无环） */
    public void setRingAge(int index, int age) {
        if (index < 0) return;
        while (ringAges.size() <= index) {
            ringAges.add(0);
        }
        ringAges.set(index, Math.max(0, Math.min(age, SoulBeastAge.MAX_AGE)));
    }

    /** 读取全部环年限列表副本（网络同步用） */
    public List<Integer> getRingAges() {
        return List.copyOf(ringAges);
    }

    /** 整体覆盖全部环年限（网络同步接收用，长度不足自动补十年） */
    public void setRingAges(List<Integer> ages) {
        ringAges.clear();
        ringAges.addAll(ages);
        while (ringAges.size() < SoulRingLayout.MAX_RINGS) {
            ringAges.add(0);
        }
    }

    // ----- 重刷（自行推演）机会 -----
    /** 该环位是否已消耗重刷机会（每玩家每环位仅一次） */
    public boolean hasRerolled(int ringIndex) {
        return rerolledRings.contains(ringIndex);
    }

    /** 标记该环位已消耗重刷机会 */
    public void markRerolled(int ringIndex) {
        rerolledRings.add(ringIndex);
    }

    /** 退还该环位的重刷机会（拒绝吸收回滚，该环未保留时调用） */
    public void unmarkRerolled(int ringIndex) {
        rerolledRings.remove(ringIndex);
    }

    /** 从另一份数据整体复制（死亡保留/换维度同步用，含魂环与经验） */
    public void copyFrom(PlayerLevelData other) {
        this.activated = other.activated;
        this.level = other.level;
        this.exp = other.exp;
        this.spiritPower = other.spiritPower;
        this.maxSpiritPower = other.maxSpiritPower;
        this.title = other.title;
        this.ringAges = new ArrayList<>(other.ringAges);
        this.rerolledRings.clear();
        this.rerolledRings.addAll(other.rerolledRings);
        // 武魂模式不随克隆复制：新实体默认关闭（死亡/重进前先强制恢复物品栏）
        this.martialSoulOpen = false;
    }

    /** 重置为全新玩家状态（死亡清除魂师身份用：魂师身份与全部魂环一并删除） */
    public void reset() {
        this.activated = false;
        this.level = 1;
        this.exp = 0;
        this.spiritPower = 100f;
        this.maxSpiritPower = 100f;
        this.title = "";
        this.ringAges = new ArrayList<>(Collections.nCopies(SoulRingLayout.MAX_RINGS, 0));
        this.rerolledRings.clear();
        this.martialSoulOpen = false;
    }
}
