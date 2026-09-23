package org.fanajing.all_spirit_continent.item;

import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import org.fanajing.all_spirit_continent.All_spirit_continent;

public class ModItems {
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(All_spirit_continent.MODID);

    // ===== 水晶球 - 右键蓄力2秒后碎裂 =====
    public static final DeferredItem<ShuiJingQiuItem> SHUIJINGQIU = ITEMS.register("shuijingqiu",
            () -> new ShuiJingQiuItem(new Item.Properties().stacksTo(64).rarity(Rarity.UNCOMMON)));

    // ===== 调试棒 - 手持此物 + 潜行 + 滚轮切换模式，右键触发功能 =====
    public static final DeferredItem<TiaoShiBangItem> TIAOSHI_BANG = ITEMS.register("tiaoshibang",
            () -> new TiaoShiBangItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    // ===== 魂环 - 击杀魂兽后掉落，标注魂兽名字与魂环年限（贴图随年限换色） =====
    public static final DeferredItem<SoulRingItem> SOUL_RING = ITEMS.register("soul_ring",
            () -> new SoulRingItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    // ===== 昊天锤 - 武魂模式专属武器（开武魂由系统发放到物品栏，攻击力随魂环年限） =====
    public static final DeferredItem<MartialSoulHammerItem> MARTIAL_SOUL_HAMMER = ITEMS.register("martial_soul_hammer",
            () -> new MartialSoulHammerItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    // ===== 在此处继续注册你的物品 =====
    // register("id", supplier)       → 自定义 Item 子类（可带 Properties 属性）
    // registerSimpleItem("id")       → 普通物品
    // registerSimpleBlockItem(...)   → 方块物品（需先在 ModBlocks 中注册方块，再注册对应物品）
}
