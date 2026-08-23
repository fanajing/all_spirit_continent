package org.fanajing.all_spirit_continent.init;

import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.util.PlayerLevelData;
import org.fanajing.all_spirit_continent.util.SoulBeastData;

import java.util.function.Supplier;

public class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, All_spirit_continent.MODID);

    /** 玩家等级系统数据（持久化保存 + 死亡保留，登录时手动同步至客户端） */
    public static final Supplier<AttachmentType<PlayerLevelData>> PLAYER_LEVEL_DATA =
            ATTACHMENT_TYPES.register("player_level_data",
                    () -> AttachmentType.builder(PlayerLevelData::new)
                            .serialize(PlayerLevelData.CODEC)
                            .copyOnDeath()
                            .build());

    /** 魂兽数据（年限，挂载到生物实体；持久化随实体 NBT 保存，跨区块卸载保留） */
    public static final Supplier<AttachmentType<SoulBeastData>> SOUL_BEAST =
            ATTACHMENT_TYPES.register("soul_beast",
                    () -> AttachmentType.builder(() -> new SoulBeastData())
                            .serialize(SoulBeastData.CODEC)
                            .build());
}
