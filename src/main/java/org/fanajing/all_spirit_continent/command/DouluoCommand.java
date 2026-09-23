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
import org.fanajing.all_spirit_continent.cloud.SkillBlacklist;
import org.fanajing.all_spirit_continent.data.PlayerSkillDataStore;
import org.fanajing.all_spirit_continent.skill.AbilitySignature;
import org.fanajing.all_spirit_continent.skill.engine.ModResourceScanner;
import org.fanajing.all_spirit_continent.skill.profile.MobProfile;
import org.fanajing.all_spirit_continent.skill.profile.ProfileCache;
import org.fanajing.all_spirit_continent.skill.RatingService;
import org.fanajing.all_spirit_continent.skill.SkillData;
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
 * /douluo signature                 查看 9 环位已绑定的魂技与签名机制（A~O）
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
                .then(Commands.literal("signature")
                        .executes(ctx -> signature(ctx.getSource())))
                .then(Commands.literal("blacklist")
                        .executes(ctx -> blacklistShow(ctx.getSource()))
                        .then(Commands.literal("clear")
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> blacklistClear(ctx.getSource()))))
                .then(Commands.literal("mobprofile")
                        // /douluo mobprofile  → 列出已观察的 mob 画像（按观察次数降序前 10）
                        .executes(ctx -> mobProfileList(ctx.getSource()))
                        // /douluo mobprofile show <mobId>  → 查看画像详情
                        .then(Commands.literal("show")
                                .then(Commands.argument("mobId", StringArgumentType.greedyString())
                                        .executes(ctx -> mobProfileShow(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "mobId")))))
                        // /douluo mobprofile add <mobId> <签名>  → 玩家手动补充签名
                        .then(Commands.literal("add")
                                .then(Commands.argument("mobId", StringArgumentType.word())
                                        .then(Commands.argument("signature", StringArgumentType.word())
                                                .suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                                        java.util.Arrays.stream(AbilitySignature.values())
                                                                .map(Enum::name).toArray(String[]::new),
                                                        builder))
                                                .executes(ctx -> mobProfileAdd(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "mobId"),
                                                        StringArgumentType.getString(ctx, "signature"))))))
                        // /douluo mobprofile complete <mobId>  → 标记画像为完整（玩家确认）
                        .then(Commands.literal("complete")
                                .then(Commands.argument("mobId", StringArgumentType.greedyString())
                                        .executes(ctx -> mobProfileComplete(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "mobId"))))))
                .executes(ctx -> status(ctx.getSource())));
    }

    // ===== blacklist =====

    /** /douluo blacklist：显示本地黑名单条数 + mod 数据驱动魂技载入数 */
    private static int blacklistShow(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        player.sendSystemMessage(Component.translatable("command.all_spirit_continent.blacklist_size",
                SkillBlacklist.size()).withStyle(ChatFormatting.AQUA));
        player.sendSystemMessage(Component.translatable("command.all_spirit_continent.mod_skills_loaded",
                ModResourceScanner.lastLoadedCount).withStyle(ChatFormatting.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    /** /douluo blacklist clear：清空本地黑名单（需 OP 2 级） */
    private static int blacklistClear(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        int before = SkillBlacklist.size();
        SkillBlacklist.clear();
        player.sendSystemMessage(Component.translatable("command.all_spirit_continent.blacklist_cleared",
                before).withStyle(ChatFormatting.YELLOW));
        return Command.SINGLE_SUCCESS;
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

    // ===== signature（查看 9 环位签名机制）=====

    private static int signature(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlayerSkillConfig cfg = PlayerSkillDataStore.get(player.serverLevel()).config(player);
        player.sendSystemMessage(Component.translatable("command.all_spirit_continent.signature_header")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        for (int slot = 1; slot <= 9; slot++) {
            SkillData skill = cfg.skillAt(slot);
            var mech = cfg.mechanismAt(slot);
            if (skill != null) {
                player.sendSystemMessage(Component.translatable(
                                "command.all_spirit_continent.signature_slot_bound", slot,
                                skill.name(), mech.map(m -> m.code + " " + m.displayName).orElse("-"))
                        .withStyle(ChatFormatting.AQUA));
            } else {
                player.sendSystemMessage(Component.translatable(
                                "command.all_spirit_continent.signature_slot_empty", slot)
                        .withStyle(ChatFormatting.GRAY));
            }
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
        player.sendSystemMessage(Component.translatable("command.all_spirit_continent.status_cloud_upload",
                onOff(CloudSyncService.isUploadEnabled())).withStyle(ChatFormatting.AQUA));
        // §6.4 批量上传窗口（来自 ModConfig.UPLOAD_BATCH_INTERVAL_MINUTES）
        try {
            int batchMin = org.fanajing.all_spirit_continent.config.ModConfig.UPLOAD_BATCH_INTERVAL_MINUTES.get();
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.status_batch_window",
                    String.valueOf(batchMin)).withStyle(ChatFormatting.AQUA));
        } catch (Exception ignored) {
        }
        // §6.6 严格环境隔离
        try {
            boolean strict = org.fanajing.all_spirit_continent.config.ModConfig.STRICT_ENVIRONMENT_ISOLATION.get();
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.status_env_isolation",
                    strict ? "开" : "关").withStyle(ChatFormatting.AQUA));
        } catch (Exception ignored) {
        }
        // §6.5 跨版本画像数
        try {
            int stale = (int) org.fanajing.all_spirit_continent.skill.profile.ProfileCache.all().stream()
                    .filter(p -> p.isStale()).count();
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.status_profiles_stale",
                    String.valueOf(stale)).withStyle(stale > 0 ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
        } catch (Exception ignored) {
        }
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

    // ===== mobprofile（§5.6 玩家手动补充入口）=====

    /** /douluo mobprofile：列出已观察的 mob 画像（按观察次数降序前 10） */
    private static int mobProfileList(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        var all = new java.util.ArrayList<>(ProfileCache.all());
        all.sort((a, b) -> Integer.compare(b.observationCount(), a.observationCount()));
        if (all.isEmpty()) {
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.mobprofile_empty")
                    .withStyle(ChatFormatting.YELLOW));
            return Command.SINGLE_SUCCESS;
        }
        player.sendSystemMessage(Component.translatable("command.all_spirit_continent.mobprofile_header",
                all.size()).withStyle(ChatFormatting.AQUA));
        int shown = 0;
        for (MobProfile profile : all) {
            if (shown >= 10) break;
            String mark = profile.incomplete() ? "⚠" : "✓";
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.mobprofile_item",
                    mark, profile.mobId(), profile.observationCount(),
                    String.join("、", profile.reliableSignatures().stream()
                            .map(s -> s.displayName).toList())));
            shown++;
        }
        return Command.SINGLE_SUCCESS;
    }

    /** /douluo mobprofile show <mobId>：查看画像详情（属性 + 签名 + 阶段 + 主题） */
    private static int mobProfileShow(CommandSourceStack source, String mobIdArg) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        String mobId = mobIdArg.toLowerCase(java.util.Locale.ROOT).contains(":") ? mobIdArg.toLowerCase(java.util.Locale.ROOT)
                : "entity." + mobIdArg.toLowerCase(java.util.Locale.ROOT);
        MobProfile profile = ProfileCache.get(mobId);
        if (!profile.observed()) {
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.mobprofile_not_found",
                    mobId).withStyle(ChatFormatting.RED));
            return 0;
        }
        player.sendSystemMessage(Component.translatable("command.all_spirit_continent.mobprofile_show_header",
                profile.mobId(), profile.observationCount(),
                profile.incomplete() ? "⚠ 不完整" : "✓ 完整")
                .withStyle(ChatFormatting.AQUA));
        player.sendSystemMessage(Component.translatable("command.all_spirit_continent.mobprofile_show_attrs",
                profile.size().displayName, profile.attackPattern().displayName,
                String.format("%.0f", profile.attributes().maxHealth()),
                String.format("%.1f", profile.attributes().attackDamage()),
                String.format("%.2f", profile.attributes().movementSpeed()))
                .withStyle(ChatFormatting.GRAY));
        if (!profile.combatTraits().isEmpty()) {
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.mobprofile_show_traits",
                    String.join("、", profile.combatTraits())).withStyle(ChatFormatting.GRAY));
        }
        if (!profile.themeKeywords().isEmpty()) {
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.mobprofile_show_themes",
                    String.join("、", profile.themeKeywords())).withStyle(ChatFormatting.GRAY));
        }
        if (!profile.signatures().isEmpty()) {
            StringBuilder sigs = new StringBuilder();
            for (AbilitySignature sig : profile.signatures()) {
                if (sigs.length() > 0) sigs.append("、");
                sigs.append(sig.displayName).append('×').append(profile.observationCount(sig))
                    .append(String.format("[%.2f]", profile.confidence(sig)));
            }
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.mobprofile_show_sigs",
                    sigs.toString()).withStyle(ChatFormatting.GRAY));
        }
        if (profile.isMultiPhase()) {
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.mobprofile_show_phases")
                    .withStyle(ChatFormatting.GOLD));
            for (var phase : profile.phases()) {
                player.sendSystemMessage(Component.literal("  阶段" + phase.phase() + "（" + phase.trigger() + "）："
                        + String.join("、", phase.abilities())).withStyle(ChatFormatting.GOLD));
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    /** /douluo mobprofile add <mobId> <签名>：玩家手动补充签名（§5.6 兜底） */
    private static int mobProfileAdd(CommandSourceStack source, String mobIdArg, String sigArg) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        String mobId = mobIdArg.toLowerCase(java.util.Locale.ROOT);
        if (!mobId.contains(".")) mobId = "entity." + mobId;
        AbilitySignature sig;
        try {
            sig = AbilitySignature.valueOf(sigArg.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.mobprofile_invalid_sig",
                    sigArg).withStyle(ChatFormatting.RED));
            return 0;
        }
        MobProfile before = ProfileCache.get(mobId);
        MobProfile updated = before.withManualSignature(sig, player.level().getGameTime());
        ProfileCache.put(updated);
        player.sendSystemMessage(Component.translatable("command.all_spirit_continent.mobprofile_added",
                sig.displayName, mobId, updated.signatures().size(),
                updated.incomplete() ? "⚠ 仍不完整" : "✓ 已完整")
                .withStyle(updated.incomplete() ? ChatFormatting.YELLOW : ChatFormatting.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    /** /douluo mobprofile complete <mobId>：标记画像为完整（玩家明确确认） */
    private static int mobProfileComplete(CommandSourceStack source, String mobIdArg) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        String mobId = mobIdArg.toLowerCase(java.util.Locale.ROOT);
        if (!mobId.contains(".")) mobId = "entity." + mobId;
        MobProfile before = ProfileCache.get(mobId);
        if (!before.observed()) {
            player.sendSystemMessage(Component.translatable("command.all_spirit_continent.mobprofile_not_found",
                    mobId).withStyle(ChatFormatting.RED));
            return 0;
        }
        MobProfile updated = before.markComplete();
        ProfileCache.put(updated);
        player.sendSystemMessage(Component.translatable("command.all_spirit_continent.mobprofile_marked_complete",
                mobId).withStyle(ChatFormatting.GREEN));
        return Command.SINGLE_SUCCESS;
    }
}
