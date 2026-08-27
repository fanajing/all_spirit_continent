package org.fanajing.all_spirit_continent.mixin.client;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 玩家最大生命超过 40 点（20 颗爱心）时跳过原版爱心血条渲染：
 * 高等级魂师血量可达数千上万，原版 Gui.renderHearts 会按每 2 点血一颗爱心
 * 渲染成百上千颗爱心（垂直无限向下排列），导致死亡重生瞬间严重卡顿。
 * 血量数值已由自定义 HUD（LevelHudOverlay）展示，低等级（≤40 血）玩家不受影响。
 */
@Mixin(Gui.class)
public abstract class GuiMixin {

    @Inject(method = "renderHearts", at = @At("HEAD"), cancellable = true)
    private void asc$skipHeartsForHighMaxHealth(GuiGraphics guiGraphics, Player player,
            int x, int y, int height, int offsetHeartX, float maxHealth,
            int currentHealth, int displayHealth, int regenCounter, boolean forceFlat,
            CallbackInfo ci) {
        if (maxHealth > 40f) {
            ci.cancel();
        }
    }
}
