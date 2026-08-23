package org.fanajing.all_spirit_continent.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.fanajing.all_spirit_continent.client.SoulBeastClientCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 魂兽头顶第二行文字：在命名牌（名称+年限）下方显示「当前血量 / 最大血量」。
 * 注入原版命名牌渲染管线的末尾（TAIL），与命名牌同锚点、同朝向、同缩放，
 * 只有命名牌实际绘制时才执行（魂兽均开启了 customNameVisible），管线天然可靠。
 * 仅对客户端缓存中的魂兽生效，不影响玩家、普通命名牌生物与魂环实体。
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @Inject(method = "renderNameTag", at = @At("TAIL"))
    private void asc$renderSoulBeastHealthLine(Entity entity, Component displayName, PoseStack poseStack,
                                               MultiBufferSource buffer, int packedLight, float partialTick,
                                               CallbackInfo ci) {
        if (!(entity instanceof LivingEntity living)) return;
        // 仅魂兽：客户端缓存中有年限记录
        if (SoulBeastClientCache.getAge(entity) <= 0) return;

        // 与原版 renderNameTag 相同的锚点、朝向与缩放
        Vec3 anchor = entity.getAttachments().get(EntityAttachment.NAME_TAG, 0, entity.getViewYRot(partialTick));
        poseStack.pushPose();
        poseStack.translate(anchor.x, anchor.y + 0.5, anchor.z);
        poseStack.mulPose(Minecraft.getInstance().gameRenderer.getMainCamera().rotation());
        poseStack.scale(0.025F, -0.025F, 0.025F);

        Font font = Minecraft.getInstance().font;
        int current = (int) Math.ceil(living.getHealth());
        int max = (int) living.getMaxHealth();
        Component text = Component.literal(current + " / " + max);
        float ratio = max <= 0 ? 0f : living.getHealth() / max;
        // 血量颜色：高于一半绿色，高于四分之一黄色，否则红色
        int color = ratio > 0.5f ? 0xFF55FF55 : ratio > 0.25f ? 0xFFFFFF55 : 0xFFFF5555;
        // 第二行画在命名牌下方 9 像素处；backgroundColor=0 不加背景框
        font.drawInBatch(text, -font.width(text) / 2.0f, 9.0f, color, true,
                poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);

        poseStack.popPose();
    }
}
