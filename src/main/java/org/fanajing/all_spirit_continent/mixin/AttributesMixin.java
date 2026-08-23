package org.fanajing.all_spirit_continent.mixin;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让攻击力属性同步到客户端：
 * 原版 generic.attack_damage 注册时未开启 clientSyncable（客户端不需要攻击力做伤害计算），
 * 因此 AttributeMap 的同步集合（getAttributesToSync / getAttributesToUpdate）不包含它，
 * 客户端 getAttributeValue(ATTACK_DAMAGE) 永远只能拿到基础值（如僵尸 3），
 * 调试棒面板显示的攻击力永远是个位数。
 * 在属性注册表 bootstrap 完成后开启同步标志（所有实体创建前生效），
 * 客户端即可收到攻击力的修饰符并显示真实值。
 */
@Mixin(Attributes.class)
public abstract class AttributesMixin {

    @Inject(method = "bootstrap", at = @At("TAIL"))
    private static void asc$makeAttackDamageSyncable(Registry<Attribute> registry,
                                                     CallbackInfoReturnable<Holder<Attribute>> cir) {
        Attributes.ATTACK_DAMAGE.value().setSyncable(true);
    }
}
