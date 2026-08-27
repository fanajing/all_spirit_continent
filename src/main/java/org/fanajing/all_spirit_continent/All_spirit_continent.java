package org.fanajing.all_spirit_continent;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.fanajing.all_spirit_continent.block.ModBlocks;
import org.fanajing.all_spirit_continent.entity.ModEntities;
import org.fanajing.all_spirit_continent.init.ModAttachments;
import org.fanajing.all_spirit_continent.init.ModCreativeTabs;
import org.fanajing.all_spirit_continent.item.ModItems;
import org.fanajing.all_spirit_continent.network.PlayerLevelSyncPayload;
import org.fanajing.all_spirit_continent.util.PlayerLevelData;
import org.fanajing.all_spirit_continent.util.SoulFlight;
import org.fanajing.all_spirit_continent.util.SoulGrowth;
import org.fanajing.all_spirit_continent.util.TitleSystem;
import org.slf4j.Logger;

@Mod(All_spirit_continent.MODID)
public class All_spirit_continent {
    public static final String MODID = "all_spirit_continent";
    private static final Logger LOGGER = LogUtils.getLogger();

    public All_spirit_continent(IEventBus modEventBus, ModContainer modContainer) {
        // === 注册模组配置 ===
        // config/all_spirit_continent-server.toml：玩家死亡魂师身份开关
        modContainer.registerConfig(ModConfig.Type.SERVER, org.fanajing.all_spirit_continent.config.ModConfig.SPEC);
        // config/all_spirit_continent-soul_ring.toml：自定义魂环年限表（整合包作者用）
        modContainer.registerConfig(ModConfig.Type.SERVER,
                org.fanajing.all_spirit_continent.config.SoulRingConfig.SPEC,
                "all_spirit_continent-soul_ring.toml");
        // config/all_spirit_continent-cloud.toml：OSS/AI 云端配置（V6.0 魂技社区共享系统）
        modContainer.registerConfig(ModConfig.Type.SERVER,
                org.fanajing.all_spirit_continent.config.CloudConfig.SPEC,
                "all_spirit_continent-cloud.toml");

        // === 注册所有模组组件 ===
        ModItems.ITEMS.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        ModEntities.ENTITY_TYPES.register(modEventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        ModAttachments.ATTACHMENT_TYPES.register(modEventBus);
        // 附魔由数据包 JSON 注册（data/all_spirit_continent/enchantment/soul_vision.json），
        // 1.21.1 附魔是动态注册表，RegisterEvent 对静态注册表以外的注册无效

        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("===========================================");
        LOGGER.info("  欢迎游玩 全魂大陆 (All Spirit Continent)!");
        LOGGER.info("===========================================");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("全魂大陆 - 服务端启动中，欢迎游玩本模组!");
        // V6.0 云端同步服务：启动拉取魂技库/黑名单，定时增量同步
        org.fanajing.all_spirit_continent.cloud.CloudSyncService.onServerStarted(event.getServer().overworld());
    }

    @SubscribeEvent
    public void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        // V6.0 云端同步服务：停止前写回本地改动
        org.fanajing.all_spirit_continent.cloud.CloudSyncService.onServerStopped();
    }

    /**
     * 玩家克隆时（换维度/死亡重生）同步等级数据。
     * 死亡重生：按配置 clearSoulMasterOnDeath 决定是否保留魂师身份（含魂环）；
     * 手动复制/重置，不依赖 copyOnDeath，保证配置开关生效。
     */
    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone event) {
        PlayerLevelData original = event.getOriginal().getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        PlayerLevelData copy = event.getEntity().getData(ModAttachments.PLAYER_LEVEL_DATA.get());

        if (event.isWasDeath()) {
            // 死亡重生：按配置决定是否保留魂师身份
            if (org.fanajing.all_spirit_continent.config.ModConfig.CLEAR_SOUL_MASTER_ON_DEATH.get()) {
                // 清除：魂师身份与全部魂环一并删除（copyOnDeath 可能已复制，显式重置）
                copy.reset();
            } else {
                // 保留：手动复制全部数据（含魂环）
                copy.copyFrom(original);
            }
        } else {
            // 换维度等非死亡克隆：完整复制
            copy.copyFrom(original);
        }
        // 重算血量/攻击力加成（transient 修饰符不随 NBT 持久化，克隆/重生后必须重新应用）
        SoulGrowth.applyHealth(event.getEntity());
        SoulGrowth.applyAttack(event.getEntity());
        // 死亡重生：血量回满（原版重生只回 20 点基础血量，不恢复魂环加成部分）
        if (event.isWasDeath()) {
            event.getEntity().setHealth(event.getEntity().getMaxHealth());
        }
        // 注意：此处不能立即 syncLevelDataToClient —— Clone 事件触发时客户端的 respawn 包
        // 尚未发送，payload 会先到客户端写入旧玩家实体，重生重建后丢失。
        // 改由 onPlayerRespawn（respawn 包发出后）延迟 1 tick 同步。
    }

    /**
     * 死亡重生完成后重新同步等级数据到客户端。
     * PlayerRespawnEvent 在客户端 respawn 包发送之后触发，再延迟 1 tick 执行，
     * 确保客户端玩家实体已重建完成，HUD 立即恢复（配合 GuiMixin 防止原版爱心血条
     * 在同步前因超高血量渲染海量爱心导致卡顿）。
     */
    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) return;
        SoulGrowth.applyHealth(player);
        SoulGrowth.applyAttack(player);
        if (player.getServer() != null) {
            player.getServer().execute(() -> syncLevelDataToClient(player));
        }
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        event.getEntity().sendSystemMessage(
            Component.literal("===========================================")
                .withStyle(ChatFormatting.GREEN)
        );
        event.getEntity().sendSystemMessage(
            Component.literal("  欢迎游玩 ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal("全魂大陆").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" (All Spirit Continent)!")
                .withStyle(ChatFormatting.GOLD))
        );
        event.getEntity().sendSystemMessage(
            Component.literal("===========================================")
                .withStyle(ChatFormatting.GREEN)
        );
        // 重算血量/攻击力加成（重进世界后 transient 修饰符已丢失，按存档数据恢复）
        SoulGrowth.applyHealth(event.getEntity());
        SoulGrowth.applyAttack(event.getEntity());
        // 重新登录补满血量：NBT 加载阶段血量修饰符尚未应用（基础上限 20），
        // 存档血量（如 100w）会被原版钳制为 20，属性恢复后当前血量已失真，需补满。
        // 跨维度传送不重建玩家实体、不重新加载 NBT，不受此问题影响。
        if (event.getEntity().getData(ModAttachments.PLAYER_LEVEL_DATA).isActivated()) {
            event.getEntity().setHealth(event.getEntity().getMaxHealth());
        }
        // 同步等级数据到客户端（确保重进后 HUD 正确显示）
        syncLevelDataToClient(event.getEntity());
    }

    /**
     * 玩家跨维度传送（如主世界→暮色森林）后保持等级/魂环/加成不丢失：
     * 客户端玩家实体跨维度时会重建，attachment 需重新下发；
     * transient 属性修饰符也一并重算（服务端实体重建场景保险）。
     */
    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) return;
        SoulGrowth.applyHealth(player);
        SoulGrowth.applyAttack(player);
        syncLevelDataToClient(player);
    }

    /**
     * 玩家每 tick（仅服务端）：魂帝境界飞行能力与精神力消耗/回复。
     */
    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (!player.level().isClientSide) {
            SoulFlight.tick(player);
        }
    }

    /** 将服务端等级数据同步到客户端 */
    public static void syncLevelDataToClient(net.minecraft.world.entity.player.Player player) {
        if (player.level().isClientSide) return;
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        PacketDistributor.sendToPlayer(
                (net.minecraft.server.level.ServerPlayer) player,
                new PlayerLevelSyncPayload(
                        data.isActivated(),
                        data.getLevel(),
                        data.getExp(),
                        data.getSpiritPower(),
                        data.getMaxSpiritPower(),
                        data.getTitle(),
                        data.getRingAges()
                )
        );
    }

    // ==================== 称号系统 /fh 指令 ====================

    /** 注册 /fh 指令：/fh 封号（1-2个字）设置封号；/fh clear 清除；/fh 无参数查询 */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("fh")
                        .executes(ctx -> showFengHao(ctx.getSource()))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> setFengHao(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name"))))
        );
        // V6.0 魂技社区共享系统 /douluo 指令
        org.fanajing.all_spirit_continent.command.DouluoCommand.register(event);
    }

    /** /fh 无参数：显示当前封号与用法 */
    private static int showFengHao(CommandSourceStack source) {
        if (!(source.getEntity() instanceof Player player)) return 0;
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        String fh = data.getTitle();
        Component current = fh.isEmpty()
                ? Component.translatable("msg.all_spirit_continent.fh_none")
                : Component.literal(fh);
        player.sendSystemMessage(
                Component.translatable("msg.all_spirit_continent.fh_usage", current)
                        .withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    /** /fh xx：设置自定义封号（需 91 级及以上，1-2 个字）；/fh clear：清除封号 */
    private static int setFengHao(CommandSourceStack source, String name) {
        if (!(source.getEntity() instanceof Player player)) return 0;
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());

        // 等级校验：封号斗罗（91级）起才能自定义封号
        if (data.getLevel() < TitleSystem.FENG_HAO_LEVEL) {
            player.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.fh_no_permission")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        // 去掉所有空白后校验
        String cleaned = name.replaceAll("\\s+", "");

        if (cleaned.equalsIgnoreCase("clear")) {
            data.setTitle("");
            syncLevelDataToClient(player);
            player.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.fh_cleared",
                                    TitleSystem.getTitle(data.getLevel(), data.getRingCount(), data.getTitle()))
                            .withStyle(ChatFormatting.GOLD));
            return 1;
        }

        if (cleaned.isEmpty() || cleaned.length() > 2) {
            player.sendSystemMessage(
                    Component.translatable("msg.all_spirit_continent.fh_invalid")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        data.setTitle(cleaned);
        syncLevelDataToClient(player);
        player.sendSystemMessage(
                Component.translatable("msg.all_spirit_continent.fh_set",
                                TitleSystem.getTitle(data.getLevel(), data.getRingCount(), data.getTitle()))
                        .withStyle(ChatFormatting.GOLD));
        return 1;
    }
}
