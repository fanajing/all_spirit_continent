package org.fanajing.all_spirit_continent.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.util.SoulBeastAge;
import org.fanajing.all_spirit_continent.util.SoulRingAge;

/**
 * 魂兽渲染器（纯客户端）：
 *  - 每只魂兽脚底一个旋转魂环：年限越高半径越大（与体型 scale 联动）
 *  - 距离限制：64 格内渲染魂环
 *  - 定期清理 SoulBeastClientCache 中的失效条目
 *  （头顶年限文字改由原版命名牌机制显示，见 SoulBeastSpawnHandler.applySoulBeast）
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = All_spirit_continent.MODID, bus = EventBusSubscriber.Bus.GAME)
public class SoulBeastRingRenderer {

    /** 旋转速度（度/tick），与玩家魂环一致：3度/tick ≈ 6秒一圈 */
    private static final float SPIN_SPEED = 3.0F;
    /** 高度偏移：略微抬离地面，避免 z-fighting */
    private static final float Y_OFFSET = 0.06F;
    /** 魂环渲染距离（格） */
    private static final double RING_VIEW_DIST_SQ = 64.0 * 64.0;

    /** 6 档年限对应的渲染类型缓存（避免每帧重复创建） */
    private static final RenderType[] RING_TYPES = new RenderType[SoulRingAge.AGE_COUNT];

    static {
        for (int i = 0; i < SoulRingAge.AGE_COUNT; i++) {
            RING_TYPES[i] = RenderType.entityTranslucentEmissive(SoulRingAge.getTierTexture(i));
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        float partialTick = event.getPartialTick().getGameTimeDeltaTicks();
        float time = level.getGameTime() + partialTick;
        var camPos = event.getCamera().getPosition();

        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (living.isRemoved() || !living.isAlive()) continue;

            int age = SoulBeastClientCache.getAge(entity);
            if (age <= 0) continue;

            double distSq = entity.distanceToSqr(camPos);
            if (distSq > RING_VIEW_DIST_SQ) continue;

            double dx = entity.xOld + (entity.getX() - entity.xOld) * partialTick - camPos.x;
            double dy = living.yOld + (living.getY() - living.yOld) * partialTick - camPos.y;
            double dz = entity.zOld + (entity.getZ() - entity.zOld) * partialTick - camPos.z;

            int tier = SoulBeastAge.tierOf(age);
            RenderType ringType = RING_TYPES[tier];

            // ===== 脚底魂环：年限越高半径越大（getBbWidth 已含体型 scale） =====
            float radius = living.getBbWidth() * 0.5F + 0.45F
                    + 0.10F * (float) Math.log10(age + 1);
            pose.pushPose();
            pose.translate(dx, dy + Y_OFFSET, dz);
            // 每只魂兽用自身 ID 做相位偏移，环与环错开不齐排
            pose.mulPose(Axis.YP.rotationDegrees(-time * SPIN_SPEED + (entity.getId() % 360)));
            SoulRingRenderer.drawFlatQuad(bufferSource.getBuffer(ringType), pose.last().pose(), radius);
            pose.popPose();
            bufferSource.endBatch(ringType);
        }
    }

    /** 定期清理失效缓存（实体已消失或 ID 被复用） */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        if (level.getGameTime() % 40 != 0) return;
        SoulBeastClientCache.cleanup(level);
    }
}
