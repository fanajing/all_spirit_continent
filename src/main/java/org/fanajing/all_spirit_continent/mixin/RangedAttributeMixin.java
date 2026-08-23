package org.fanajing.all_spirit_continent.mixin;

import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 解除血量与攻击力的原版上限裁剪：
 * 原版 generic.max_health 上限 1024（RangedAttribute 20.0, 1.0, 1024.0）、
 * generic.attack_damage 上限 2048，魂兽的乘算大倍数加成（如 9999 万年约 ×250 万）
 * 会被 sanitizeValue 裁剪到上限，导致服务端 maxHealth 恒为 1024、attack 恒为 2048。
 * 对这两个属性跳过裁剪（仅保底非负），其余属性（护甲、移动速度等）维持原版行为。
 * 服务端与客户端同时加载（双方计算属性值时都会走 sanitizeValue），保证两端一致。
 */
@Mixin(RangedAttribute.class)
public abstract class RangedAttributeMixin extends Attribute {

    protected RangedAttributeMixin(String descriptionId, double defaultValue) {
        super(descriptionId, defaultValue);
    }

    @Inject(method = "sanitizeValue", at = @At("HEAD"), cancellable = true)
    private void asc$liftSoulBeastCaps(double value, CallbackInfoReturnable<Double> cir) {
        String id = getDescriptionId();
        if ("attribute.name.generic.max_health".equals(id)
                || "attribute.name.generic.attack_damage".equals(id)) {
            cir.setReturnValue(Math.max(value, 0.0D));
        }
    }
}
