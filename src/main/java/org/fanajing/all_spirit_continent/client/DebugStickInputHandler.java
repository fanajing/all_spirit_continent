package org.fanajing.all_spirit_continent.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.init.ModAttachments;
import org.fanajing.all_spirit_continent.item.ModItems;
import org.fanajing.all_spirit_continent.item.TiaoShiBangItem;
import org.fanajing.all_spirit_continent.network.AdjustLevelPayload;
import org.fanajing.all_spirit_continent.network.KaiWuHunPayload;
import org.fanajing.all_spirit_continent.network.SetDebugModePayload;
import org.fanajing.all_spirit_continent.util.DebugStickMode;
import org.fanajing.all_spirit_continent.util.PlayerLevelData;
import org.fanajing.all_spirit_continent.util.SoulRingAnimConfig;
import org.fanajing.all_spirit_continent.util.SoulRingLayout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 客户端输入处理（手持调试棒）：
 *  - Shift（潜行）+ 滚轮：切换调试棒模式
 *  - RESIZE_RING 模式下，非潜行滚轮：微调最大魂环半径（0.1格/档）
 *  - RESIZE_RING 模式下，Alt + 长按右键 1.5 秒：导出魂环大小配方 JSON 到桌面
 * 本地立即更新物品 NBT（即时反馈），同时发送网络包同步服务端。
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = All_spirit_continent.MODID, bus = EventBusSubscriber.Bus.GAME)
public class DebugStickInputHandler {

    /** 滚轮每档调节的最大环半径增量（格） */
    private static final float RESIZE_STEP = 0.1F;
    /** Alt+长按右键 触发导出的按住时长（tick，20 tick = 1秒） */
    private static final int EXPORT_HOLD_TICKS = 30;

    /** 长按右键累计 tick */
    private static int holdTicks = 0;
    /** 一次按住期间是否已触发导出（防止每 tick 重复写文件） */
    private static boolean exported = false;

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        // 必须手持调试棒
        ItemStack held = player.getMainHandItem();
        if (!held.is(ModItems.TIAOSHI_BANG.get())) return;

        DebugStickMode current = TiaoShiBangItem.getMode(held);

        if (player.isShiftKeyDown()) {
            // ===== 潜行 + 滚轮：切换模式 =====
            event.setCanceled(true);

            DebugStickMode next = event.getScrollDeltaY() > 0 ? current.next() : current.prev();

            // 本地立即写入（客户端即时反馈）
            TiaoShiBangItem.setMode(held, next);

            // 同步服务端（服务端权威 NBT，右键触发功能时读取）
            PacketDistributor.sendToServer(new SetDebugModePayload(next.getId()));

            player.displayClientMessage(
                    Component.translatable("msg.all_spirit_continent.debug_mode", next.getDisplayName())
                            .withStyle(ChatFormatting.AQUA),
                    true
            );
        } else if (current == DebugStickMode.RESIZE_RING) {
            // ===== 非潜行 + 滚轮：调节当前最外层（新获取的）魂环半径 =====
            event.setCanceled(true);

            // 调节目标：当前最外层环；没有魂环时调节第 1 环（预览）
            PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
            int ringCount = data.getRingCount();
            int targetIndex = Math.max(ringCount - 1, 0);

            // 当前值：该环已有调节值则用它，否则用默认布局值
            Float override = SoulRingLayout.getOverride(targetIndex);
            float currentValue = override != null ? override : SoulRingLayout.getRadius(targetIndex);
            float delta = event.getScrollDeltaY() > 0 ? RESIZE_STEP : -RESIZE_STEP;
            float newValue = Math.clamp(currentValue + delta, SoulRingLayout.MIN_RADIUS, SoulRingLayout.MAX_RADIUS);

            // 只保存当前调节的环，其他环的调节值不受影响
            SoulRingLayout.setOverride(targetIndex, newValue);

            player.displayClientMessage(
                    Component.translatable("msg.all_spirit_continent.ring_size",
                            targetIndex + 1,
                            String.format(Locale.ROOT, "%.1f", newValue))
                            .withStyle(ChatFormatting.YELLOW),
                    true
            );
        } else if (current == DebugStickMode.SOUL_ANIM) {
            // ===== 非潜行 + 滚轮：切换开武魂动画类型（动画1/动画2） =====
            event.setCanceled(true);

            if (event.getScrollDeltaY() > 0) {
                SoulRingAnimConfig.next();
            } else {
                SoulRingAnimConfig.prev();
            }

            player.displayClientMessage(
                    Component.translatable("msg.all_spirit_continent.soul_anim_type",
                            Component.translatable(SoulRingAnimConfig.getDisplayKey(SoulRingAnimConfig.getCurrentAnimId())))
                            .withStyle(ChatFormatting.GOLD),
                    true
            );
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        // ===== 开/关武魂按键（默认 N 键，可在按键绑定界面修改） =====
        while (ModKeyMappings.KAI_WU_HUN.consumeClick()) {
            // 已开则请求关闭，未开则请求开启（服务端校验魂环数）
            boolean open = !SoulRingRenderer.isMartialSoulOpen();
            PacketDistributor.sendToServer(new KaiWuHunPayload(open));
        }

        // ===== 增减等级模式：右键等级 +1，Alt+右键等级 -1（服务端校验执行） =====
        ItemStack heldLevel = player.getMainHandItem();
        if (heldLevel.is(ModItems.TIAOSHI_BANG.get())
                && TiaoShiBangItem.getMode(heldLevel) == DebugStickMode.LEVEL) {
            while (mc.options.keyUse.consumeClick()) {
                PacketDistributor.sendToServer(new AdjustLevelPayload(Screen.hasAltDown() ? -1 : 1));
            }
        }

        ItemStack held = player.getMainHandItem();
        boolean valid = held.is(ModItems.TIAOSHI_BANG.get())
                && TiaoShiBangItem.getMode(held) == DebugStickMode.RESIZE_RING
                && Screen.hasAltDown()
                && mc.options.keyUse.isDown();

        if (!valid) {
            // 松开任意键即重置（允许下次再导出）
            holdTicks = 0;
            exported = false;
            return;
        }

        if (exported) return; // 本次按住已导出过，不重复触发

        holdTicks++;
        if (holdTicks >= EXPORT_HOLD_TICKS) {
            exported = true;
            exportRecipeToDesktop(player);
        }
    }

    /** 导出魂环大小配方 JSON 到桌面（满配 10 环，导出所有环的最终半径） */
    private static void exportRecipeToDesktop(Player player) {
        final int ringCount = SoulRingLayout.MAX_RINGS; // 满配 10 环

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"description\": \"soul ring size recipe exported by all_spirit_continent\",\n");
        sb.append("  \"maxRingRadius\": ")
                .append(String.format(Locale.ROOT, "%.2f", SoulRingLayout.getScaledRadius(ringCount - 1)))
                .append(",\n");
        sb.append("  \"ringCount\": ").append(ringCount).append(",\n");
        sb.append("  \"radii\": [");
        for (int i = 0; i < ringCount; i++) {
            if (i > 0) sb.append(", ");
            // 每环最终半径：被调节过的环用调节值，其余用默认布局
            sb.append(String.format(Locale.ROOT, "%.2f", SoulRingLayout.getScaledRadius(i)));
        }
        sb.append("]\n}");
        sb.append("\n");

        Path path = Path.of(System.getProperty("user.home"), "Desktop", "soul_ring_recipe.json");
        try {
            Files.writeString(path, sb.toString());
            player.displayClientMessage(
                    Component.translatable("msg.all_spirit_continent.ring_size_exported", path.toAbsolutePath())
                            .withStyle(ChatFormatting.GREEN),
                    false
            );
        } catch (IOException e) {
            player.displayClientMessage(
                    Component.translatable("msg.all_spirit_continent.ring_size_export_failed")
                            .withStyle(ChatFormatting.RED),
                    false
            );
        }
    }
}
