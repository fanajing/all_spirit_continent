package org.fanajing.all_spirit_continent.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.enchantment.ModEnchantments;
import org.fanajing.all_spirit_continent.item.TiaoShiBangItem;
import org.fanajing.all_spirit_continent.util.SoulBeastAge;

/**
 * 魂兽属性查看面板（纯客户端 HUD）：
 * 手持调试棒（主手或副手）**或穿戴「魂视」附魔头盔**，并将准星指向魂兽时，
 * 在准星下方显示该魂兽的魂环年限 / 攻击力 / 血量 / 体型等属性。
 * 数据全部来自客户端已同步的状态（血量、属性修饰符、年限缓存），无需额外网络包；
 * 准星直接复用原版每帧已计算的 mc.hitResult，零额外射线开销。
 * 后续新增特殊属性时在 render 里追加行即可。
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = All_spirit_continent.MODID, bus = EventBusSubscriber.Bus.MOD)
public class SoulBeastInspectorOverlay {

    private static final ResourceLocation HUD_ID =
            ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "soul_beast_inspector");

    private static final int LINE_SPACING = 12;
    private static final int PANEL_PADDING = 5;
    private static final int BG_COLOR = 0x90000000;  // 半透明黑色

    @SubscribeEvent
    public static void registerOverlay(RegisterGuiLayersEvent event) {
        event.registerAboveAll(HUD_ID, SoulBeastInspectorOverlay::render);
    }

    private static void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;

        // 手持调试棒（主手或副手），或穿戴「魂视」附魔头盔
        boolean holdingStick = mc.player.getMainHandItem().getItem() instanceof TiaoShiBangItem
                || mc.player.getOffhandItem().getItem() instanceof TiaoShiBangItem;
        ItemStack helmet = mc.player.getItemBySlot(EquipmentSlot.HEAD);
        // 附魔是数据驱动的动态注册表，按 ResourceKey 从客户端已同步的注册表 lookup 中取 Holder
        Holder<Enchantment> soulVision = mc.level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(ModEnchantments.SOUL_VISION_KEY);
        boolean hasSoulVision = EnchantmentHelper.getItemEnchantmentLevel(soulVision, helmet) > 0;
        if (!holdingStick && !hasSoulVision) return;

        // 准星指向的实体必须是魂兽（客户端缓存中有年限记录）
        if (!(mc.hitResult instanceof EntityHitResult hit)) return;
        if (!(hit.getEntity() instanceof LivingEntity living)) return;
        int age = SoulBeastClientCache.getAge(living);
        if (age <= 0) return;

        // ===== 面板数据（后续新增特殊属性在此追加行） =====
        String[] lines = new String[]{
                living.getType().getDescription().getString(),                                          // 名称（金色标题）
                Component.translatable("inspector.all_spirit_continent.ring_age").getString() + ": "
                        + SoulBeastAge.format(age),                                                     // 魂环年限
                Component.translatable("inspector.all_spirit_continent.attack").getString() + ": "
                        + (living.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)
                        ? String.format("%.1f", living.getAttributeValue(Attributes.ATTACK_DAMAGE))
                        : "N/A"),    // 攻击力（被动生物无攻击属性显示 N/A）
                Component.translatable("inspector.all_spirit_continent.health").getString() + ": "
                        + (int) Math.ceil(living.getHealth()) + " / " + (int) living.getMaxHealth(),    // 血量
                Component.translatable("inspector.all_spirit_continent.scale").getString() + ": "
                        + String.format("%.2f", living.getScale())                                      // 体型
        };
        int[] lineColors = {0xFFD700, 0xFFFFFF, 0xFFFF5555, 0xFF55FF55, 0x55AAFF};

        // 面板宽度按最长行自适应
        int maxWidth = 0;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, mc.font.width(line));
        }
        int panelWidth = maxWidth + PANEL_PADDING * 2;
        int panelHeight = lines.length * LINE_SPACING + PANEL_PADDING * 2;

        // 准星下方 20 像素，水平居中
        int x = mc.getWindow().getGuiScaledWidth() / 2 - panelWidth / 2;
        int y = mc.getWindow().getGuiScaledHeight() / 2 + 20;

        guiGraphics.fill(x, y, x + panelWidth, y + panelHeight, BG_COLOR);

        int textX = x + PANEL_PADDING;
        int textY = y + PANEL_PADDING;
        for (int i = 0; i < lines.length; i++) {
            guiGraphics.drawString(mc.font, lines[i], textX, textY, lineColors[i], false);
            textY += LINE_SPACING;
        }
    }
}
