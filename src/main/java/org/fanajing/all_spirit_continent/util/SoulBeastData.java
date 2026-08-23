package org.fanajing.all_spirit_continent.util;

import com.mojang.serialization.Codec;

/**
 * 魂兽数据，通过 NeoForge Attachment 挂载到每个魂兽实体。
 * 只保存年限（年），持久化到实体 NBT，随存档保存、跨区块卸载保留。
 * 客户端不直接读取此附件，年限通过 SoulBeastSyncPayload 同步到客户端缓存。
 */
public class SoulBeastData {
    /** 魂兽年限（年，1 ~ 9999 万年） */
    private int age;

    /** 持久化序列化 Codec（服务端实体 NBT 存档） */
    public static final Codec<SoulBeastData> CODEC =
            Codec.INT.fieldOf("age").xmap(SoulBeastData::new, d -> d.age).codec();

    /** 无参构造（AttachmentType 工厂需要） */
    public SoulBeastData() {
        this(0);
    }

    public SoulBeastData(int age) {
        this.age = Math.max(0, age);
    }

    public int age() {
        return age;
    }

    public void setAge(int age) {
        this.age = Math.max(0, age);
    }
}
