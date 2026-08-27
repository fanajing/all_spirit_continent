package org.fanajing.all_spirit_continent.config;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.fanajing.all_spirit_continent.util.SoulBeastAge;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义魂环年限配置（生成于 config/all_spirit_continent-soul_ring.toml）。
 * 整合包作者可为特定生物（含其他 mod 的生物与 boss）指定魂环年限范围，
 * 优先级高于默认魂兽化逻辑（无视生成方式过滤与新手保护，配置匹配即魂兽化）。
 * 修改配置文件后重启游戏生效。
 */
public class SoulRingConfig {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /**
     * 自定义魂环年限表。
     * 每条格式："生物ID=最小年限-最大年限"（闭区间随机）或 "生物ID=年限"（固定值）。
     * 生物 ID 为 EntityType 注册名（如 minecraft:zombie，其他 mod 生物同样适用）。
     */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CUSTOM_OVERRIDES =
            BUILDER.comment(
                    "Custom soul ring age overrides. Pack authors can pin a specific mob to a fixed",
                    "age or an age range. These take priority over the default soul beast logic",
                    "(spawn-type filtering and spawn protection are bypassed for matched mobs).",
                    "",
                    "Format per entry: \"<entity id>=<min age>-<max age>\" (random in range, inclusive)",
                    "or \"<entity id>=<age>\" (fixed age). Age range: 1 ~ 99999999.",
                    "Entity id is the EntityType registry name, e.g. minecraft:zombie (mod mobs work too).",
                    "",
                    "TEMPLATE - copy a line, remove the #, adjust the numbers:",
                    "# minecraft:zombie=100-1000",
                    "# minecraft:wither=1000000",
                    "# minecraft:iron_golem=100-100000",
                    "# some_mod:custom_boss=500000-900000")
                    .defineListAllowEmpty("customSoulRingOverrides", List::of, () -> "", o -> o instanceof String);

    /**
     * 刷怪笼刷出的生物是否魂兽化（默认开启）。
     * 暮色森林等 mod 的 boss 均通过刷怪笼/刷怪点生成，关闭后这些生物不会变成魂兽；
     * 若担心玩家搭建刷怪塔刷魂环，可改为 false。
     */
    public static final ModConfigSpec.BooleanValue SPAWNER_SOUL_BEAST =
            BUILDER.comment(
                    "Whether mobs spawned from spawners become soul beasts (drop soul rings).",
                    "Twilight Forest bosses etc. spawn from spawners, so keep true to let them",
                    "turn into soul beasts. Set false to prevent spawner-farm soul ring farming.",
                    "Default: true")
                    .define("spawnerSoulBeast", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** 单条覆盖规则：实体 ID → 年限范围（闭区间） */
    public record Override(ResourceLocation entityId, int minAge, int maxAge) {
        /** 在该范围内随机年限（min==max 时固定） */
        public int roll(RandomSource random) {
            return minAge == maxAge ? minAge : minAge + random.nextInt(maxAge - minAge + 1);
        }
    }

    /** 解析后的规则缓存（首次访问时解析；配置修改需重启游戏才生效） */
    private static volatile List<Override> CACHE = null;

    /** 查找某实体类型的自定义年限规则；无匹配返回 null */
    public static Override find(EntityType<?> type) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (id == null) return null;
        for (Override override : overrides()) {
            if (override.entityId().equals(id)) return override;
        }
        return null;
    }

    /** 懒解析配置列表（首次调用解析并缓存） */
    private static List<Override> overrides() {
        List<Override> cached = CACHE;
        if (cached != null) return cached;
        synchronized (SoulRingConfig.class) {
            cached = CACHE;
            if (cached != null) return cached;
            List<Override> parsed = new ArrayList<>();
            for (String entry : CUSTOM_OVERRIDES.get()) {
                Override override = parse(entry);
                if (override != null) parsed.add(override);
            }
            CACHE = parsed;
            return parsed;
        }
    }

    /** 解析单条 "id=min-max" / "id=age"；格式非法时告警并跳过 */
    private static Override parse(String entry) {
        String line = entry.strip();
        if (line.isEmpty()) return null;

        int eq = line.indexOf('=');
        if (eq <= 0) {
            LOGGER.warn("[全魂大陆] 自定义魂环配置格式错误（缺少 =）: {}", line);
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(line.substring(0, eq).strip());
        if (id == null) {
            LOGGER.warn("[全魂大陆] 自定义魂环配置实体 ID 非法: {}", line);
            return null;
        }

        String rangePart = line.substring(eq + 1).strip();
        String[] parts = rangePart.split("-");
        try {
            if (parts.length == 1) {
                int age = clampAge(Integer.parseInt(parts[0].strip()));
                return new Override(id, age, age);
            }
            if (parts.length == 2) {
                int min = clampAge(Integer.parseInt(parts[0].strip()));
                int max = clampAge(Integer.parseInt(parts[1].strip()));
                return new Override(id, Math.min(min, max), Math.max(min, max));
            }
        } catch (NumberFormatException ignored) {
        }
        LOGGER.warn("[全魂大陆] 自定义魂环配置年限非法: {}", line);
        return null;
    }

    /** 年限收敛到 1 ~ 9999万年 */
    private static int clampAge(int age) {
        return Math.max(1, Math.min(SoulBeastAge.MAX_AGE, age));
    }
}
