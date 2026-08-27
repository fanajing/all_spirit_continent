package org.fanajing.all_spirit_continent.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.fanajing.all_spirit_continent.cloud.CloudSyncService;
import org.fanajing.all_spirit_continent.data.PlayerSkillDataStore;
import org.fanajing.all_spirit_continent.skill.RatingService;
import org.fanajing.all_spirit_continent.skill.SkillEntry;
import org.fanajing.all_spirit_continent.util.PlayerSkillConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * /douluo 指令树（V6.0 魂技社区共享系统）。
 * <pre>
 * /douluo camp solo|shared          切换阵营（共享/独狼）
 * /douluo apikey &lt;key&gt;              设置私人 API Key（独狼必需）
 * /douluo solo upload on|off        独狼上传开关
 * /douluo solo download on|off      独狼下载开关
 * /douluo rate &lt;环位1-9&gt; &lt;1-5|cancel&gt;  评分/撤回（用环位定位，不用 uuid）
 * /douluo search &lt;武魂名&gt; &lt;怪物注册名&gt;   查询云端魂技
 * /douluo upload &lt;环位1-9&gt; [mods...] 手动上传该环位魂技（可选：空格分隔的依赖 mod id，如 iceandfire）
 * /douluo status                    查看当前配置
 * </pre>
 * 全部在服务端执行；apiKey 仅存服务端，绝不外发。
 */
public class DouluoCommand {

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("douluo")
                .requires(ctx -> ctx.getEntity() instanceof ServerPlayer)
                .then(Commands.literal("camp")
                        .then(Commands.argument("camp", StringArgumentType.word())
                                .suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                        new String[]{"solo", "shared"}, builder))
                                .executes(ctx -> setCamp(ctx.getSource(), StringArgumentType.getString(ctx, "camp")))))
                .then(Commands.literal("apikey")
                        .then(Commands.argument("key", StringArgumentType.greedyString())
                                .executes(ctx -> setApiKey(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "key")))))
                .then(Commands.literal("solo")
                        .then(Commands.argument("option", StringArgumentType.word())
                                .suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                        new String[]{"upload", "download"}, builder))
                                .then(Commands.argument("value", StringArgumentType.word())
                                        .suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                new String[]{"on", "off"}, builder))
                                        .executes(ctx -> setSoloOption(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "option"),
                                                StringArgumentType.getString(ctx, "value"))))))
                .then(Commands.literal("rate")
                        .then(Commands.argument("slot", IntegerArgumentType.integer(1, 9))
                                .then(Commands.argument("stars", StringArgumentType.word())
                                        .suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                new String[]{"1", "2", "3", "4", "5", "cancel"}, builder))
                                        .executes(ctx -> rate(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "slot"),
                                                StringArgumentType.getString(ctx, "stars"))))))
                .then(Commands.literal("search")
                        .then(Commands.argument("wuhun", StringArgumentType.word())
                                .then(Commands.argument("mob", StringArgumentType.word())
                                        .executes(ctx -> search(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "wuhun"),
                                                StringArgumentType.getString(ctx, "mob"))))))
                .then(Commands.literal("upload")
                        .then(Commands.argument("slot", IntegerArgumentType.integer(1, 9))
                                .executes(ctx -> upload(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "slot"), null))
                                .then(Commands.argument("mods", StringArgumentType.greedyString())
                                        .executes(ctx -> upload(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "slot"),
                                                StringArgumentType.getString(ctx, "mods"))))))
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource())))
                .executes(ctx -> status(ctx.getSource())));
    }

    // ===== camp =====

    private static int setCamp(CommandSourceStack source, String camp) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlayerSkillDataStore store = PlayerSkillDataStore.get(player.serverLevel());
        boolean solo = "solo".equalsIgnoreCase(camp);
        store.setCamp(player, solo ? PlayerSkillConfig.CAMP_SOLO : PlayerSkillConfig.CAMP_SHARED);
        player.sendSystemMessage(Component.translatable("command.all_spirit_continent.camp_set",
                Component.translatable(solo ? "command.all_spirit_continent.camp_solo"
                        : "command.all_spirit_continent.camp_shared"))
                .withStyle(solo ? ChatFormatting.GOLD : ChatFormatting.GREEN));
        if (solo && store.config(player).apiKey().isEmpty()) {
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.camp_solo_no_key")
                    .withStyle(ChatFormatting.RED));
        }
        return Command.SINGLE_SUCCESS;
    }

    // ===== apikey =====

    private static int setApiKey(CommandSourceStack source, String key) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (key == null || key.isBlank()) {
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.apikey_empty")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        PlayerSkillDataStore.get(player.serverLevel()).setApiKey(player, key);
        player.sendSystemMessage(Component.translatable("command.all_spirit_continent.apikey_set")
                .withStyle(ChatFormatting.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    // ===== solo upload/download =====

    private static int setSoloOption(CommandSourceStack source, String option, String value) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlayerSkillDataStore store = PlayerSkillDataStore.get(player.serverLevel());
        if (!store.config(player).isSolo()) {
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.need_solo")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        boolean on = "on".equalsIgnoreCase(value);
        boolean isUpload = "upload".equalsIgnoreCase(option);
        if (isUpload) {
            store.setSoloUpload(player, on);
        } else {
            store.setSoloDownload(player, on);
        }
        player.sendSystemMessage(Component.translatable(
                isUpload ? "command.all_spirit_continent.solo_upload_set"
                        : "command.all_spirit_continent.solo_download_set",
                Component.translatable(on ? "command.all_spirit_continent.on" : "command.all_spirit_continent.off"))
                .withStyle(ChatFormatting.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    // ===== rate <slot> <1-5|cancel> =====

    private static int rate(CommandSourceStack source, int slot, String starsArg) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlayerSkillDataStore store = PlayerSkillDataStore.get(player.serverLevel());
        String uuid = store.config(player).skillUuidAt(slot);
        if (uuid == null) {
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.ring_empty")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        if ("cancel".equalsIgnoreCase(starsArg)) {
            if (RatingService.cancel(player, uuid)) {
                player.sendSystemMessage(Component.translatable("command.all_spirit_continent.rate_cancel")
                        .withStyle(ChatFormatting.GREEN));
            } else {
                player.sendSystemMessage(Component.translatable("command.all_spirit_continent.rate_not_rated")
                        .withStyle(ChatFormatting.RED));
            }
            return Command.SINGLE_SUCCESS;
        }
        int stars;
        try {
            stars = Integer.parseInt(starsArg);
        } catch (NumberFormatException e) {
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.rate_invalid")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (RatingService.rate(player, uuid, stars)) {
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.rate_success",
                    Component.literal(stars + " ★").withStyle(ChatFormatting.GOLD))
                    .withStyle(ChatFormatting.GREEN));
        } else {
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.rate_cooldown")
                    .withStyle(ChatFormatting.RED));
        }
        return Command.SINGLE_SUCCESS;
    }

    // ===== search <wuhun> <mob> =====

    private static int search(CommandSourceStack source, String wuhun, String mob) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        List<SkillEntry> list = CloudSyncService.findSkills(wuhun, mob, "");
        if (list.isEmpty()) {
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.search_none")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        player.sendSystemMessage(Component.translatable("command.all_spirit_continent.search_result",
                list.size()).withStyle(ChatFormatting.GOLD));
        int shown = 0;
        for (SkillEntry entry : list) {
            if (shown >= 8) break;
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.search_item",
                    entry.skill().name(),
                    Component.translatable("pool.all_spirit_continent." + entry.pool().toLowerCase()),
                    String.format("%.1f", entry.stats().avg())));
            shown++;
        }
        return Command.SINGLE_SUCCESS;
    }

    // ===== upload <slot> =====

    private static int upload(CommandSourceStack source, int slot, String modsArg) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlayerSkillDataStore store = PlayerSkillDataStore.get(player.serverLevel());
        String uuid = store.config(player).skillUuidAt(slot);
        if (uuid == null) {
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.ring_empty")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        java.util.Optional<SkillEntry> entry = CloudSyncService.findById(uuid);
        if (entry.isEmpty()) {
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.upload_missing")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        SkillEntry toQueue = entry.get();
        if (modsArg != null && !modsArg.isBlank()) {
            // 追加依赖 mod 标记：只有安装这些 mod 的玩家才会下载/抽取该魂技
            List<String> mods = new ArrayList<>();
            if (toQueue.skill().requiresMods() != null) {
                mods.addAll(toQueue.skill().requiresMods());
            }
            for (String m : modsArg.trim().split("\\s+")) {
                if (!m.isBlank() && !mods.contains(m)) mods.add(m);
            }
            toQueue = toQueue.withSkill(toQueue.skill().withRequiresMods(mods));
        }
        CloudSyncService.queueSkill(toQueue);
        if (toQueue.skill().requiresMods() != null && !toQueue.skill().requiresMods().isEmpty()) {
            player.sendSystemMessage(Component.translatable(
                            "command.all_spirit_continent.upload_queued_mods",
                            String.join(", ", toQueue.skill().requiresMods()))
                    .withStyle(ChatFormatting.GREEN));
        } else {
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.upload_queued")
                    .withStyle(ChatFormatting.GREEN));
        }
        return Command.SINGLE_SUCCESS;
    }

    // ===== status =====

    private static int status(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlayerSkillConfig cfg = PlayerSkillDataStore.get(player.serverLevel()).config(player);
        player.sendSystemMessage(Component.translatable("command.all_spirit_continent.status_header")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        player.sendSystemMessage(Component.translatable("command.all_spirit_continent.status_camp",
                Component.translatable(cfg.isSolo() ? "command.all_spirit_continent.camp_solo"
                        : "command.all_spirit_continent.camp_shared"))
                .withStyle(ChatFormatting.AQUA));
        player.sendSystemMessage(Component.translatable("command.all_spirit_continent.status_apikey",
                cfg.apiKey().isEmpty() ? Component.translatable("command.all_spirit_continent.none")
                        : Component.literal("****" + maskTail(cfg.apiKey())))
                .withStyle(ChatFormatting.AQUA));
        player.sendSystemMessage(Component.translatable("command.all_spirit_continent.status_wuhun", cfg.wuhun())
                .withStyle(ChatFormatting.AQUA));
        player.sendSystemMessage(Component.translatable("command.all_spirit_continent.status_solo_upload",
                onOff(cfg.soloUpload())).withStyle(ChatFormatting.AQUA));
        player.sendSystemMessage(Component.translatable("command.all_spirit_continent.status_solo_download",
                onOff(cfg.soloDownload())).withStyle(ChatFormatting.AQUA));
        player.sendSystemMessage(Component.translatable("command.all_spirit_continent.status_rings",
                cfg.boundSkillCount()).withStyle(ChatFormatting.AQUA));
        player.sendSystemMessage(Component.translatable("command.all_spirit_continent.status_cache",
                CloudSyncService.cachedSkillCount()).withStyle(ChatFormatting.AQUA));
        player.sendSystemMessage(Component.literal("OSS 可写: " + (CloudSyncService.ossWritable() ? "是" : "否（凭据未注入，写回被禁用）"))
                .withStyle(CloudSyncService.ossWritable() ? ChatFormatting.GREEN : ChatFormatting.RED));
        player.sendSystemMessage(Component.literal("最近写回: " + CloudSyncService.lastWriteStatus())
                .withStyle(ChatFormatting.AQUA));
        return Command.SINGLE_SUCCESS;
    }

    private static Component onOff(boolean value) {
        return Component.translatable(value ? "command.all_spirit_continent.on" : "command.all_spirit_continent.off");
    }

    /** 显示 Key 尾部 4 位，其余打码 */
    private static String maskTail(String key) {
        if (key.length() <= 4) return "";
        return key.substring(key.length() - 4);
    }
}
