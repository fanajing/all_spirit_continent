package org.fanajing.all_spirit_continent.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 魂兽年限客户端缓存（纯客户端）。
 * 服务端通过 SoulBeastSyncPayload 下发（玩家跟踪实体时），
 * 渲染器按 entityId + UUID 双重校验读取，防止实体 ID 复用导致数据错配。
 */
public final class SoulBeastClientCache {

    private record Entry(UUID uuid, int age) {}

    private static final Map<Integer, Entry> DATA = new HashMap<>();

    private SoulBeastClientCache() {}

    /** 写入/更新一只魂兽的年限（payload 处理） */
    public static void set(int entityId, UUID uuid, int age) {
        if (age <= 0) {
            DATA.remove(entityId);
        } else {
            DATA.put(entityId, new Entry(uuid, age));
        }
    }

    /** 读取实体的年限；无数据或 UUID 不匹配（ID 被复用）返回 0 */
    public static int getAge(Entity entity) {
        Entry entry = DATA.get(entity.getId());
        if (entry == null || !entry.uuid().equals(entity.getUUID())) return 0;
        return entry.age();
    }

    /** 定期清理：移除世界中已不存在或 UUID 已不匹配的条目 */
    public static void cleanup(ClientLevel level) {
        DATA.entrySet().removeIf(entry -> {
            Entity entity = level.getEntity(entry.getKey());
            return entity == null || !entity.getUUID().equals(entry.getValue().uuid());
        });
    }
}
