package org.fanajing.all_spirit_continent.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.init.ModAttachments;
import org.fanajing.all_spirit_continent.util.PlayerLevelData;
import org.fanajing.all_spirit_continent.util.SoulExp;
import org.fanajing.all_spirit_continent.util.TitleSystem;

@EventBusSubscriber(value = Dist.CLIENT, modid = All_spirit_continent.MODID, bus = EventBusSubscriber.Bus.MOD)
public class LevelHudOverlay {

    private static final ResourceLocation HUD_ID =
            ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "level_hud");

    private static final int PANEL_X = 10;
    private static final int PANEL_Y = 10;
    private static final int LINE_SPACING = 12;
    private static final int PANEL_PADDING = 5;
    private static final int PANEL_WIDTH = 130;
    private static final int BG_COLOR = 0x80000000;  // 半透明黑色

    /** 达到封号斗罗且未设置封号时，本次会话只弹一次输入框 */
    private static boolean fhPromptShown = false;

    @SubscribeEvent
    public static void registerOverlay(RegisterGuiLayersEvent event) {
        event.registerAboveAll(HUD_ID, LevelHudOverlay::render);
    }

    private static void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;

        Player player = mc.player;
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        if (!data.isActivated()) return;

        // 达到封号斗罗境界（91级以上）且未自定义封号时，弹出输入框提示（本次会话只弹一次）
        if (!fhPromptShown
                && data.getLevel() >= TitleSystem.FENG_HAO_LEVEL
                && data.getTitle().isEmpty()) {
            fhPromptShown = true;
            mc.gui.setTitle(Component.translatable("title.all_spirit_continent.fh_prompt"));
            mc.setScreen(new ChatScreen("/fh "));
        }

        // 计算面板大小（称号 + 6行数据 + 经验条）
        int lineCount = 8;
        int panelHeight = lineCount * LINE_SPACING + PANEL_PADDING * 2;

        // 绘制半透明背景
        guiGraphics.fill(
                PANEL_X, PANEL_Y,
                PANEL_X + PANEL_WIDTH, PANEL_Y + panelHeight,
                BG_COLOR
        );

        int textX = PANEL_X + PANEL_PADDING;
        int y = PANEL_Y + PANEL_PADDING;

        int labelColor = 0xAAAAAA;
        int valueColor = 0xFFFFFF;

        // 称号（显示在等级上方，金色；境界由已获魂环数决定）
        Component title = TitleSystem.getTitle(data.getLevel(), data.getRingCount(), data.getTitle());
        drawStatLine(guiGraphics, mc, textX, y,
                "hud.all_spirit_continent.rank",
                title.getString(), labelColor, 0xFFD700);
        y += LINE_SPACING;

        // 等级
        drawStatLine(guiGraphics, mc, textX, y,
                "hud.all_spirit_continent.level",
                String.valueOf(data.getLevel()), labelColor, valueColor);
        y += LINE_SPACING;

        // 瓶颈封顶（节点等级未获得对应魂环）：经验行显示魂环进度 0/1，否则显示经验值
        boolean bottleneck = SoulExp.isBottleneck(data);
        if (bottleneck) {
            drawStatLine(guiGraphics, mc, textX, y,
                    "hud.all_spirit_continent.exp",
                    "0/1", labelColor, 0xFFD700);
        } else {
            int expRequired = SoulExp.expRequired(data.getLevel());
            drawStatLine(guiGraphics, mc, textX, y,
                    "hud.all_spirit_continent.exp",
                    formatBig(data.getExp()) + "/" + formatBig(expRequired),
                    labelColor, 0x55FF55);
        }
        y += LINE_SPACING;

        // 经验条：封顶时显示获取魂环提示，否则深灰底 + 绿色进度填充（99 级满级时进度钳到 100%）
        int barX = textX;
        int barY = y + 2;
        int barW = PANEL_WIDTH - PANEL_PADDING * 2;
        int barH = 7;
        if (bottleneck) {
            guiGraphics.drawString(mc.font,
                    Component.translatable("hud.all_spirit_continent.need_ring").getString(),
                    barX, barY, 0xFFD700, false);
        } else {
            int expRequired = SoulExp.expRequired(data.getLevel());
            guiGraphics.fill(barX, barY, barX + barW, barY + barH, 0xFF333333);
            float progress = Math.min(1f, data.getExp() / (float) expRequired);
            if (progress > 0) {
                guiGraphics.fill(barX, barY, barX + (int) (barW * progress), barY + barH, 0xFF55FF55);
            }
        }
        y += LINE_SPACING;

        // 血量 - 当前/上限（上限随等级与魂环年限成长，大数值显示为「X.X万」）
        drawStatLine(guiGraphics, mc, textX, y,
                "hud.all_spirit_continent.health",
                formatBig(player.getHealth()) + "/" + formatBig(player.getMaxHealth()),
                labelColor, 0xFF5555);
        y += LINE_SPACING;

        // 饱食度 - 来自原版
        drawStatLine(guiGraphics, mc, textX, y,
                "hud.all_spirit_continent.hunger",
                String.format("%d/20", player.getFoodData().getFoodLevel()),
                labelColor, 0xFFAA00);
        y += LINE_SPACING;

        // 精神力 - 自定义（上限随等级与魂环年限成长）
        drawStatLine(guiGraphics, mc, textX, y,
                "hud.all_spirit_continent.spirit",
                formatBig(data.getSpiritPower()) + "/" + formatBig(data.getMaxSpiritPower()),
                labelColor, 0x55AAFF);
        y += LINE_SPACING;

        // 护甲 - 来自原版
        drawStatLine(guiGraphics, mc, textX, y,
                "hud.all_spirit_continent.armor",
                String.valueOf(player.getArmorValue()),
                labelColor, 0x888888);
    }

    private static void drawStatLine(GuiGraphics guiGraphics, Minecraft mc,
                                      int x, int y, String labelKey,
                                      String value, int labelColor, int valueColor) {
        String label = Component.translatable(labelKey).getString() + ": ";
        guiGraphics.drawString(mc.font, label, x, y, labelColor, false);
        int valueX = x + mc.font.width(label);
        guiGraphics.drawString(mc.font, value, valueX, y, valueColor, false);
    }

    /** 大数值缩写：1 万及以上显示为「X.X万」，其余保留整数 */
    private static String formatBig(float value) {
        if (value >= 10000f) {
            return String.format("%.1f万", value / 10000f);
        }
        return String.format("%.0f", value);
    }
}
