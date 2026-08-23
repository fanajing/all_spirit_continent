package org.fanajing.all_spirit_continent.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.entity.SoulRingEntity;
import org.fanajing.all_spirit_continent.util.SoulBeastAge;
import org.fanajing.all_spirit_continent.util.SoulRingAge;

/**
 * 魂环实体渲染器（纯客户端）：
 *  - 平铺地面的旋转发光环，贴图与颜色随年限档位（与玩家/魂兽魂环共用 6 档贴图）
 *  - 半径随年限对数增长
 *  - 环上方年限文字：直接复用原版 renderNameTag 命名牌机制（实体渲染上下文，渲染可靠）
 */
public class SoulRingEntityRenderer extends EntityRenderer<SoulRingEntity> {

    /** 旋转速度（度/tick），与玩家魂环一致 */
    private static final float SPIN_SPEED = 3.0F;
    /** 高度偏移：略微抬离地面，避免 z-fighting */
    private static final float Y_OFFSET = 0.08F;

    public SoulRingEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(SoulRingEntity entity, float yaw, float partialTick,
                       PoseStack pose, MultiBufferSource buffer, int packedLight) {
        int age = entity.getRingAge();
        if (age <= 0) return;

        float time = entity.level().getGameTime() + partialTick;

        // ===== 地面旋转魂环：半径随年限增长（十年 ~0.66 → 9999万年 ~1.35） =====
        float radius = 0.55F + 0.10F * (float) Math.log10(age + 1);
        RenderType ringType = RenderType.entityTranslucentEmissive(SoulRingAge.getTexture(age));

        pose.pushPose();
        pose.translate(0, Y_OFFSET, 0);
        pose.mulPose(Axis.YP.rotationDegrees(-time * SPIN_SPEED));
        SoulRingRenderer.drawFlatQuad(buffer.getBuffer(ringType), pose.last().pose(), radius);
        pose.popPose();

        // ===== 环上方年限文字：复用原版命名牌渲染（与生物头顶名字同管线，天然可靠） =====
        this.renderNameTag(entity, entity.getDisplayName(), pose, buffer, packedLight, partialTick);
    }

    /** 无独立贴图：渲染器不通过纹理渲染实体本体 */
    @Override
    public ResourceLocation getTextureLocation(SoulRingEntity entity) {
        return ResourceLocation.fromNamespaceAndPath(All_spirit_continent.MODID, "textures/item/soul_ring_decade.png");
    }
}
