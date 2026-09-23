package org.fanajing.all_spirit_continent.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import org.fanajing.all_spirit_continent.util.SoulBeastAge;
import org.fanajing.all_spirit_continent.util.SoulGrowth;
import org.fanajing.all_spirit_continent.util.SoulRingLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 昊天锤：武魂模式（开环）下由系统发放的武魂专属武器。
 * <p>
 * 设计要点：
 *  - 模型/贴图直接复用原版下界合金斧（模型 json parent 指向 minecraft:item/netherite_axe）
 *  - 攻击力不写死，由 MartialSoulInventoryHandler 在发放/刷新时按魂环年限写入
 *    DataComponents.ATTRIBUTE_MODIFIERS（攻击力 = 魂环攻击加成）
 *  - 防卡 bug：无法丢出（Q）、无法放入任何容器/背包/合成台，槽位由系统锁定在物品栏 0 号
 *  - 介绍面板（RPG 魂器风格）：品级 → 来历 → 属性 → 魂环之力 → 魂师绑定 → 锁定提示
 */
public class MartialSoulHammerItem extends AxeItem {
    /** 环位中文序数（第1环~第9环） */
    private static final String[] CN_ORDINALS = {"一", "二", "三", "四", "五", "六", "七", "八", "九"};

    public MartialSoulHammerItem(Properties properties) {
        // 1.21.1 AxeItem 只有 (Tier, Properties) 构造；攻击力/攻速通过 attributes 传入
        // （这里给 0，实际数值由开武魂时写入的 ATTRIBUTE_MODIFIERS 组件覆盖）
        super(Tiers.NETHERITE, properties.attributes(DiggerItem.createAttributes(Tiers.NETHERITE, 0.0F, 0.0F)));
    }

    @Override
    public boolean onDroppedByPlayer(ItemStack stack, Player player) {
        // 武魂武器不可主动丢出（防 Q 键/丢弃）
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        // —— 品级行 ——
        tooltip.add(Component.translatable("tooltip.all_spirit_continent.martial_soul_hammer.type")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        // —— 数据快照：与 ATTRIBUTE_MODIFIERS 同源同刷新（见 MartialSoulInventoryHandler.refreshSoulData），
        //    不依赖客户端实体上下文，展示稳定且与实际属性一致 ——
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();

        // —— 魂师信息（紧跟品级，置顶展示） ——
        tooltip.add(Component.literal("-".repeat(32)).withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.all_spirit_continent.martial_soul_hammer.level",
                        tag.contains("Level") ? tag.getInt("Level") : 0)
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        if (tag.contains("Title") && !tag.getString("Title").isEmpty()) {
            tooltip.add(Component.translatable("tooltip.all_spirit_continent.martial_soul_hammer.title", tag.getString("Title"))
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }

        // —— 属性面板 ——
        float attack = tag.contains("Attack") ? tag.getFloat("Attack") : 1f;
        tooltip.add(Component.translatable("tooltip.all_spirit_continent.martial_soul_hammer.attack", fmt(attack))
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.all_spirit_continent.martial_soul_hammer.speed", "1.6")
                .withStyle(ChatFormatting.AQUA));

        // 魂环之力：按环位展示，每环按年限档位染色。
        // 格式：◆ 第N环 《魂技名》 魂兽名 · 年限；魂技名/魂兽名/年限三列分别以当前最长项为准对齐
        List<String> ordinals = new ArrayList<>();
        List<String> skillNames = new ArrayList<>();
        List<String> beastNames = new ArrayList<>();
        List<String> ageStrs = new ArrayList<>();
        List<ChatFormatting> colors = new ArrayList<>();
        for (int i = 0; i < SoulRingLayout.MAX_RINGS; i++) {
            if (!tag.contains("RingAge" + i)) continue;
            int age = tag.getInt("RingAge" + i);
            if (age <= 0) continue;
            String skillName = tag.contains("SkillName" + i) ? tag.getString("SkillName" + i) : "";
            String beastName = tag.contains("BeastKey" + i)
                    ? beastNameComponent(tag.getString("BeastKey" + i)).getString()
                    : "";
            ordinals.add(CN_ORDINALS[i]);
            skillNames.add(skillName);
            beastNames.add(beastName);
            ageStrs.add(SoulBeastAge.format(age));
            colors.add(SoulBeastAge.tierFormatting(age));
        }
        int count = ordinals.size();
        if (count > 0) {
            // 魂环之力独立成区：上方加分隔线
            tooltip.add(Component.literal("-".repeat(32)).withStyle(ChatFormatting.DARK_GRAY));
            tooltip.add(Component.translatable("tooltip.all_spirit_continent.martial_soul_hammer.rings", count)
                    .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            int skillMax = maxDisplayWidth(skillNames);
            int beastMax = maxDisplayWidth(beastNames);
            int ageMax = maxDisplayWidth(ageStrs);
            for (int k = 0; k < count; k++) {
                if (skillNames.get(k).isEmpty()) {
                    // 未感应魂技：独立格式，不参与列对齐
                    tooltip.add(Component.translatable("tooltip.all_spirit_continent.martial_soul_hammer.ring_line_empty",
                                    ordinals.get(k), ageStrs.get(k))
                            .withStyle(colors.get(k)));
                    continue;
                }
                // 左对齐：补位空格一律在《》之外，书名号内不填空白
                String line = "◆ 第" + ordinals.get(k) + "环 "
                        + padRight("《" + skillNames.get(k) + "》", skillMax + 2)
                        + " " + padRight(beastNames.get(k), beastMax) + " · " + padRight(ageStrs.get(k), ageMax);
                tooltip.add(Component.literal(line).withStyle(colors.get(k)));
            }
        }

        // —— 绑定与锁定提示 ——
        tooltip.add(Component.literal("-".repeat(32)).withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.all_spirit_continent.martial_soul_hammer")
                .withStyle(ChatFormatting.RED));

        // —— 来历（史诗描述，置于面板最底部收尾） ——
        tooltip.add(Component.translatable("tooltip.all_spirit_continent.martial_soul_hammer.desc1")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        tooltip.add(Component.translatable("tooltip.all_spirit_continent.martial_soul_hammer.desc2")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    /** 数值人性化：>=1万 显示"x.x万"，>=100 取整，其余保留 1 位小数 */
    private static String fmt(float v) {
        if (v >= 10_000f) return String.format(Locale.ROOT, "%.1f万", v / 10_000f);
        if (v >= 100f) return String.format(Locale.ROOT, "%.0f", v);
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** 魂兽名称：翻译键存在则本地化显示；否则退化为去掉 entity. 前缀的短 ID（防止 raw key 露出） */
    private static Component beastNameComponent(String key) {
        if (net.minecraft.client.resources.language.I18n.exists(key)) {
            return Component.translatable(key);
        }
        String shortId = key.startsWith("entity.") ? key.substring("entity.".length()) : key;
        return Component.literal(shortId);
    }

    // ===== 列对齐工具（中文全角按 2 格计算，半角按 1 格） =====

    /** 取列表中最长的显示宽度（空串不计入，避免未感应魂技的空白行撑宽列） */
    private static int maxDisplayWidth(List<String> list) {
        int max = 0;
        for (String s : list) {
            if (s.isEmpty()) continue;
            max = Math.max(max, displayWidth(s));
        }
        return max;
    }

    /** 左对齐：尾部补空格到指定显示宽度 */
    private static String padRight(String s, int width) {
        int diff = width - displayWidth(s);
        return diff <= 0 ? s : s + " ".repeat(diff);
    }

    /** 显示宽度：中文全角=2，半角=1（与 Minecraft 等宽渲染一致） */
    private static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += isFullWidth(s.charAt(i)) ? 2 : 1;
        }
        return w;
    }

    private static boolean isFullWidth(char c) {
        if (c == '\u3000') return true;            // 全角空格
        if (c >= '\u2E80' && c <= '\u9FFF') return true;  // CJK 部首/汉字/兼容区
        if (c >= '\uFF00' && c <= '\uFF60') return true;  // 全角 ASCII 与标点
        if (c >= '\uFFE0' && c <= '\uFFE6') return true;  // 全角符号
        return false;
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, net.minecraft.world.entity.Entity entity, int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);
        // 兜底：任何时刻发现昊天锤在玩家身上（主物品栏 0-35）之外的位置，直接清除
        // （正常流程中锤子不会出现在这里；出现在这里说明被异常方式移动了）
        if (!level.isClientSide && entity instanceof net.minecraft.server.level.ServerPlayer sp) {
            if (slotId >= 36) {
                stack.shrink(stack.getCount());
                sp.containerMenu.broadcastChanges();
            }
        }
    }
}
