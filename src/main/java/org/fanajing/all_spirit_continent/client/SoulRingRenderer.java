package org.fanajing.all_spirit_continent.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.init.ModAttachments;
import org.fanajing.all_spirit_continent.item.TiaoShiBangItem;
import org.fanajing.all_spirit_continent.util.DebugStickMode;
import org.fanajing.all_spirit_continent.util.PlayerLevelData;
import org.fanajing.all_spirit_continent.util.SoulRingAge;
import org.fanajing.all_spirit_continent.util.SoulRingLayout;
import org.joml.Matrix4f;

/**
 * 魂环世界渲染器（纯客户端）。
 *  - 魂环数量 = 已获魂环数（实际持有的年限非零环位；节点封顶未获取新魂环不计入）
 *  - 魂环常隐藏：只有「开武魂」动画触发后才显示
 *  - 开武魂动画：从第 1 环开始依次释放——每环从玩家头部出现，
 *    尺寸从前一环大小逐渐增大到峰值（比目标略大）再缩小回目标尺寸，
 *    同时从头部逐步下移到脚底（不是同时移动，环与环之间有先后间隔）
 *  - 旋转方向交替：第1个顺时针 → 第2个逆时针 → 第3个顺时针……
 *
 * 说明：当前仅渲染本机玩家自己的魂环（客户端只持有自己的等级数据），
 * 后期如需所有玩家互相可见，需广播所有玩家的等级数据。
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = All_spirit_continent.MODID, bus = EventBusSubscriber.Bus.GAME)
public class SoulRingRenderer {

    /** 旋转速度（度/tick），3度/tick = 约6秒一圈 */
    private static final float SPIN_SPEED = 3.0F;
    /** 高度偏移：略微抬离地面，避免与地面贴图 z-fighting */
    private static final float Y_OFFSET = 0.05F;
    /** 每级对应的魂环等级跨度（与等级系统一致：每10级一个魂环） */
    private static final int LEVELS_PER_RING = 10;

    // ===== 开武魂动画参数（动画1：头部释放下移） =====
    /** 单个环的动画时长（tick） */
    private static final float RING_ANIM_TICKS = 20F;
    /** 相邻环开始释放的时间间隔（tick）：只保持先后顺序，不一个一个出；总时长 = 20 + 9×8 = 92 tick ≈ 4.6 秒（5 秒内出完） */
    private static final float RING_ANIM_DELAY = 8F;
    /** 动画起始高度（头部，格） */
    private static final float HEAD_Y = 1.6F;
    /** 峰值尺寸系数：移动到脚下之前比目标尺寸大一点点 */
    private static final float PEAK_FACTOR = 1.2F;
    /** 到达峰值的进度点（0~1）：前段膨胀，后段收缩到目标尺寸 */
    private static final float PEAK_FRACTION = 0.6F;
    /** 第 1 环动画起始尺寸（没有前一个魂环，从小尺寸开始） */
    private static final float FIRST_RING_START = 0.1F;

    // ===== 开武魂动画参数（动画2：脚底膨胀依次出现） =====
    /** 单环动画时长（tick），总时长 = 9×8 + 16 = 88 tick ≈ 4.4 秒 */
    private static final float RING2_TICKS = 16F;
    /** 动画2到达峰值的进度点（0~1）：前一环到峰值时下一环才开始 */
    private static final float RING2_PEAK_FRACTION = 0.5F;
    /** 相邻环开始时间间隔 = 前一环到达峰值的时刻 */
    private static final float RING2_START_INTERVAL = RING2_TICKS * RING2_PEAK_FRACTION;
    /** 动画2每环的起始尺寸（从小开始膨胀） */
    private static final float RING2_START_SIZE = 0.1F;

    // ===== 开武魂动画状态（仅客户端） =====
    /** 开武魂后显示的魂环数（0 = 武魂未开/魂环隐藏） */
    private static int animRingCount = 0;
    /** 动画开始的游戏时间（tick） */
    private static long animStartTick = -1;
    /** 当前播放的动画类型：0 = 动画1（头部释放），1 = 动画2（脚底膨胀） */
    private static int animType = 0;

    /** 由网络包调用：按指定动画类型开始播放开武魂动画 */
    public static void startAnimation(int ringCount, int type) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        animRingCount = ringCount;
        animType = type;
        animStartTick = mc.level.getGameTime();
    }

    /** 由网络包调用：关闭武魂（魂环立即隐藏） */
    public static void stopAnimation() {
        animRingCount = 0;
        animStartTick = -1;
    }

    /** 武魂当前是否处于开启状态（客户端按键判断开关用） */
    public static boolean isMartialSoulOpen() {
        return animRingCount > 0;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        // 等级系统未激活或武魂未开启时不渲染（魂环常隐藏）
        PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        if (!data.isActivated()) return;
        if (animRingCount <= 0) {
            // 未开武魂：不渲染魂环，但仍允许「调节环大小」模式的预览环
            renderResizePreview(event, mc, player, data, 0);
            return;
        }
        // 武魂开启中：环数取实时已获魂环数（获取/移除魂环后立即生效，新环直接出现在最终位置）
        int ringCount = Math.min(data.getRingCount(), SoulRingLayout.MAX_RINGS);
        if (ringCount <= 0) return;

        // 时间与插值：保证旋转平滑
        float partialTick = event.getPartialTick().getGameTimeDeltaTicks();
        float time = mc.level.getGameTime() + partialTick;

        // 玩家插值位置（相对相机的渲染坐标）
        var camPos = event.getCamera().getPosition();
        double px = player.xOld + (player.getX() - player.xOld) * partialTick;
        double py = player.yOld + (player.getY() - player.yOld) * partialTick;
        double pz = player.zOld + (player.getZ() - player.zOld) * partialTick;

        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        pose.pushPose();
        pose.translate(px - camPos.x, py - camPos.y, pz - camPos.z);

        for (int i = 0; i < ringCount; i++) {
            // 旋转方向交替：第1个顺时针，第2个逆时针，第3个顺时针……
            // OpenGL 绕Y轴正角度旋转从上方看为逆时针，因此顺时针取负值
            float angle = (i % 2 == 0) ? -time * SPIN_SPEED : time * SPIN_SPEED;

            float y;
            float radius;
            if (animType == 1) {
                // ===== 动画2：脚底膨胀依次出现 =====
                // 第 i 环在第 i-1 环到达峰值时开始（间隔 = 单环时长 × 峰值进度）
                float ringStart = animStartTick + i * RING2_START_INTERVAL;
                float elapsed = time - ringStart;
                if (elapsed < 0) {
                    continue; // 该环还没轮到，不渲染
                }
                y = Y_OFFSET + i * 0.002F;
                float target = SoulRingLayout.getScaledRadius(i);
                if (elapsed >= RING2_TICKS) {
                    // 动画完成：停在目标尺寸
                    radius = target;
                } else {
                    float progress = elapsed / RING2_TICKS;
                    // 尺寸：从小 → 峰值（比目标大一点点） → 目标尺寸
                    float peak = target * PEAK_FACTOR;
                    if (progress < RING2_PEAK_FRACTION) {
                        radius = lerp(RING2_START_SIZE, peak, progress / RING2_PEAK_FRACTION);
                    } else {
                        radius = lerp(peak, target, (progress - RING2_PEAK_FRACTION) / (1F - RING2_PEAK_FRACTION));
                    }
                }
            } else {
                // ===== 动画1（默认）：头部释放下移 =====
                float ringStart = animStartTick + i * RING_ANIM_DELAY;
                float elapsed = time - ringStart;
                if (elapsed < 0) {
                    continue; // 该环还没轮到，不渲染
                }
                if (elapsed >= RING_ANIM_TICKS) {
                    // 动画完成：停在脚下目标尺寸
                    y = Y_OFFSET + i * 0.002F;
                    radius = SoulRingLayout.getScaledRadius(i);
                } else {
                    float progress = elapsed / RING_ANIM_TICKS;
                    // 位置：从头部线性下移到脚底
                    y = lerp(HEAD_Y, Y_OFFSET, progress) + i * 0.002F;
                    // 尺寸：前一个魂环大小 → 峰值（比目标大一点点） → 目标尺寸
                    float target = SoulRingLayout.getScaledRadius(i);
                    float prevRadius = i > 0 ? SoulRingLayout.getScaledRadius(i - 1) : FIRST_RING_START;
                    float peak = target * PEAK_FACTOR;
                    if (progress < PEAK_FRACTION) {
                        radius = lerp(prevRadius, peak, progress / PEAK_FRACTION);
                    } else {
                        radius = lerp(peak, target, (progress - PEAK_FRACTION) / (1F - PEAK_FRACTION));
                    }
                }
            }

            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(angle));
            pose.translate(0, y, 0);
            Matrix4f matrix = pose.last().pose();
            // 每环独立年限贴图（年限不同则颜色不同）
            RenderType ringType = RenderType.entityTranslucentEmissive(
                    SoulRingAge.getTexture(data.getRingAge(i)));
            drawFlatQuad(bufferSource.getBuffer(ringType), matrix, radius);
            pose.popPose();
            bufferSource.endBatch(ringType);
        }
        pose.popPose();

        // 调节环大小模式：渲染半透明预览环
        renderResizePreview(event, mc, player, data, ringCount);
    }

    /** 「调节环大小」模式下的半透明白色预览环（有魂环无魂环都能看） */
    private static void renderResizePreview(RenderLevelStageEvent event, Minecraft mc, Player player,
                                            PlayerLevelData data, int ringCount) {
        if (TiaoShiBangItem.getMode(player.getMainHandItem()) != DebugStickMode.RESIZE_RING) return;

        float partialTick = event.getPartialTick().getGameTimeDeltaTicks();
        float time = mc.level.getGameTime() + partialTick;
        var camPos = event.getCamera().getPosition();
        double px = player.xOld + (player.getX() - player.xOld) * partialTick;
        double py = player.yOld + (player.getY() - player.yOld) * partialTick;
        double pz = player.zOld + (player.getZ() - player.zOld) * partialTick;

        int previewCount = Math.max(ringCount, 1);
        float previewRadius = SoulRingLayout.getScaledRadius(previewCount - 1);

        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        // 预览环用对应环的年限贴图
        RenderType renderType = RenderType.entityTranslucentEmissive(
                SoulRingAge.getTexture(data.getRingAge(previewCount - 1)));
        VertexConsumer consumer = bufferSource.getBuffer(renderType);

        pose.pushPose();
        pose.translate(px - camPos.x, py - camPos.y + Y_OFFSET, pz - camPos.z);
        pose.mulPose(Axis.YP.rotationDegrees(-time * SPIN_SPEED));
        pose.translate(0, 0.03F, 0); // 比正常魂环略高，避免 z-fighting
        drawFlatQuad(consumer, pose.last().pose(), previewRadius, 150);
        pose.popPose();

        bufferSource.endBatch(renderType);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** 绘制一个平铺在地面的正方形贴图平面（自发光全亮，默认不透明）。供玩家魂环与魂兽魂环共用 */
    public static void drawFlatQuad(VertexConsumer consumer, Matrix4f matrix, float radius) {
        drawFlatQuad(consumer, matrix, radius, 255);
    }

    /** 绘制一个平铺在地面的正方形贴图平面（自发光，可指定透明度 alpha 0-255） */
    private static void drawFlatQuad(VertexConsumer consumer, Matrix4f matrix, float radius, int alpha) {
        int light = LightTexture.FULL_BRIGHT;
        int overlay = OverlayTexture.NO_OVERLAY;
        consumer.addVertex(matrix, -radius, 0, -radius)
                .setUv(0, 0).setColor(255, 255, 255, alpha).setLight(light)
                .setOverlay(overlay).setNormal(0, 1, 0);
        consumer.addVertex(matrix, -radius, 0, radius)
                .setUv(0, 1).setColor(255, 255, 255, alpha).setLight(light)
                .setOverlay(overlay).setNormal(0, 1, 0);
        consumer.addVertex(matrix, radius, 0, radius)
                .setUv(1, 1).setColor(255, 255, 255, alpha).setLight(light)
                .setOverlay(overlay).setNormal(0, 1, 0);
        consumer.addVertex(matrix, radius, 0, -radius)
                .setUv(1, 0).setColor(255, 255, 255, alpha).setLight(light)
                .setOverlay(overlay).setNormal(0, 1, 0);
    }
}
