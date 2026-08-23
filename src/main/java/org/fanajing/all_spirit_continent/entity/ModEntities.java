package org.fanajing.all_spirit_continent.entity;

import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import org.fanajing.all_spirit_continent.All_spirit_continent;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, All_spirit_continent.MODID);

    // ===== 魂环实体：击杀魂兽后原地生成的环状实体（不可拾取，后续右键吸收） =====
    public static final DeferredHolder<EntityType<?>, EntityType<SoulRingEntity>> SOUL_RING_ENTITY =
            ENTITY_TYPES.register("soul_ring_entity",
                    () -> EntityType.Builder.of(SoulRingEntity::new, MobCategory.MISC)
                            .sized(0.5F, 0.1F)
                            .clientTrackingRange(128)
                            .updateInterval(1)
                            .build("soul_ring_entity"));

    // ===== 在此处注册你的生物/怪物 =====
    // 格式:
    // public static final DeferredHolder<EntityType<?>, EntityType<XXX>> XXX = ENTITY_TYPES.register("xxx",
    //         () -> EntityType.Builder.of(XXX::new, MobCategory.CREATURE) // 或 MobCategory.MONSTER
    //                 .sized(0.6f, 1.8f)   // 碰撞箱宽度、高度
    //                 .build("xxx"));
    //
    // MobCategory 可选:
    //   MobCategory.CREATURE    - 友好生物
    //   MobCategory.MONSTER     - 敌对怪物
    //   MobCategory.AMBIENT     - 环境生物（如蝙蝠）
    //   MobCategory.WATER_CREATURE - 水生生物
    //   MobCategory.MISC        - 杂项
}
