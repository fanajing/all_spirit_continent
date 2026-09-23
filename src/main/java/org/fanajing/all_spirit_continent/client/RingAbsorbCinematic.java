package org.fanajing.all_spirit_continent.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.fanajing.all_spirit_continent.All_spirit_continent;
import org.fanajing.all_spirit_continent.entity.SoulRingEntity;
import org.fanajing.all_spirit_continent.init.ModAttachments;
import org.fanajing.all_spirit_continent.network.RingAbsorbLandPayload;
import org.fanajing.all_spirit_continent.util.PlayerLevelData;
import org.fanajing.all_spirit_continent.util.SoulRingAge;
import org.fanajing.all_spirit_continent.util.SoulRingLayout;
import org.joml.Matrix4f;

/**
 * 吸收魂环动画（V6.2，纯客户端）。
 * <p>
 * 服务端在右键吸收判定通过后发 PlayRingAbsorbCinematicPayload 启动本动画：
 * - 魂环灵体从地面魂环原位「升空」→ 沿弧线「飞向玩家头顶」→ 到头顶后「武魂显形」
 * - 头顶「盘旋等待」魂技就绪（AI 云端生成延迟不定，动画节奏自适应）：
 *   收到 RingAbsorbSkillReadyPayload 且盘旋满最短时长后才开始「头顶→脚下」落位
 * - 落位完成瞬间上报 RingAbsorbLandPayload：服务端此刻才真正加环、消散魂环实体、
 *   同步数据并弹「绑定魂技」窗口；窗口到达（OpenRingAbsorbScreenPayload）即结束动画。
 * <p>
 * 相机（V6.2 环绕运镜）：镜头与玩家实体分离，锁定绕玩家水平旋转，始终对准玩家；
 * 运镜期间玩家被锁定在吸收起始位置、禁止移动。落位瞬间相机回正到玩家原视角。
 * 动画只做渲染/相机/镜头，不改武魂开关、物品栏与玩家数据。
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = All_spirit_continent.MODID, bus = EventBusSubscriber.Bus.GAME)
public class RingAbsorbCinematic {

    // ===== 阶段 =====
    private static final int PHASE_IDLE = -1;
    private static final int PHASE_LIFT = 0;      // 魂环从地面原位升空
    private static final int PHASE_FLY = 1;       // 弧线飞向玩家头顶
    private static final int PHASE_HOVER = 2;     // 头顶盘旋等待魂技就绪
    private static final int PHASE_DESCEND = 3;   // 头顶 → 脚下落位
    private static final int PHASE_SETTLE = 4;    // 已落位，等待服务端加环/弹窗

    // ===== 时长（tick）=====
    private static final float LIFT_TICKS = 26F;
    private static final float FLY_TICKS = 62F;
    private static final float HOVER_MIN_TICKS = 60F; // 最短盘旋时间（魂技通常已就绪，略盘旋即落位）
    private static final float DESCEND_TICKS = 56F;
    private static final int SETTLE_TIMEOUT_TICKS = 160; // 落位后若窗口迟迟未到（异常），兜底结束动画

    // ===== 空间常量 =====
    /** 自转速度：与静态魂环「绕自身中心旋转」完全一致（度/tick） */
    private static final float SPIN_SPEED = 3.0F;
    private static final float Y_OFFSET = 0.05F;       // 与 SoulRingRenderer 一致
    /** 升空高度：只飞到头顶上方即可，不再冲天（原 12 格） */
    private static final float LIFT_HEIGHT = 3.0F;
    /** 头顶盘旋中心高度（相对脚底，像光环悬在头顶上方一点） */
    private static final float HOVER_CENTER_Y = 2.4F;
    /** 盘旋时的水平晃动：只有极小的晃动点缀，不是绕头的大轨道 */
    private static final float HOVER_SWAY = 0.12F;
    private static final float HOVER_BOB_AMP = 0.16F;  // 盘旋上下浮动幅度
    private static final float FLY_ARC = 0.6F;         // 飞行中段的弧线抬升（限幅，不高冲）

    // ===== 流程状态 =====
    private static boolean active = false;
    private static int entityId = -1;
    private static int preRingCount = 0;      // 吸收前已有魂环数
    private static int newRingIndex = 0;      // 本次吸收魂环的脚标（0-based）
    private static int ringAge = 10;
    private static int phase = PHASE_IDLE;
    private static int phaseTick = 0;
    private static boolean skillReady = false;
    private static boolean showingRings = false; // 武魂显形（仅环显形代替）
    private static boolean landSent = false;

    // ===== 起点与尺寸 =====
    private static float originX, originY, originZ;   // 魂环地面位置（升空起点）
    private static float groundRadius;                // 魂环实体半径（升空初尺寸）
    private static float targetRadius;                // 落位最终半径（按环位布局）
    private static float hoverRadius;                 // 头顶盘旋半径

    // ===== 落位曲线快照（从盘旋切换到落位那一刻） =====
    private static float descOx, descOz; // 盘旋中心相对玩家的水平偏移（收敛到 0）
    private static float descBob;        // 盘旋浮动量（收敛到 0）
    private static float descStartRadius;

    // ===== 相机（分离环绕运镜，由 CameraMixin 覆盖实现） =====
    private static boolean cameraChanged = false;
    private static CameraType previousCameraType = CameraType.FIRST_PERSON;
    /** 环绕镜头已走完的全局时长（tick，客户端 tick 累加） */
    private static float cinematicElapsed = 0F;
    /** 环绕起始朝向：吸收开始瞬间玩家 → 魂环方向的角度（度） */
    private static float cameraStartYaw = 0F;

    // ===== 玩家锁定（运镜期间禁止移动） =====
    private static boolean playerLocked = false;
    private static double freezeX, freezeY, freezeZ;

    // ===== 对外 API =====

    /** 开始吸收动画（服务端 PlayRingAbsorbCinematicPayload 触发）；已有动画在播放则忽略 */
    public static void start(int ringEntityId, int age) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (active) return; // 已有吸收动画在播放（服务端流程锁会阻止真正的并发）
        PlayerLevelData data = mc.player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
        if (!data.isActivated()) return;

        Entity e = mc.level.getEntity(ringEntityId);
        if (e instanceof SoulRingEntity ring) {
            originX = (float) ring.getX();
            originY = (float) ring.getY();
            originZ = (float) ring.getZ();
            groundRadius = 0.55F + 0.10F * (float) Math.log10(ring.getRingAge() + 1);
            ringAge = ring.getRingAge();
        } else {
            // 魂环实体未找到（异常时序）：以玩家脚底为升空起点，也能正常播放
            originX = (float) mc.player.getX();
            originY = (float) mc.player.getY();
            originZ = (float) mc.player.getZ();
            groundRadius = 0.55F + 0.10F * (float) Math.log10(Math.max(1, age) + 1);
            ringAge = Math.max(10, age);
        }

        preRingCount = Math.min(data.getRingCount(), SoulRingLayout.MAX_RINGS);
        newRingIndex = preRingCount;
        targetRadius = SoulRingLayout.getScaledRadius(Math.min(newRingIndex, SoulRingLayout.MAX_RINGS - 1));
        hoverRadius = Math.max(0.9F, targetRadius * 0.72F);

        // ===== 分离环绕相机：本体镜头交给 CameraMixin，这里只保证「非第一人称」让玩家模型可见 =====
        previousCameraType = mc.options.getCameraType();
        if (previousCameraType == CameraType.FIRST_PERSON) {
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }
        cameraChanged = true;
        cinematicElapsed = 0F;
        // 环绕起始朝向 = 玩家→魂环方向的垂直侧向：开场以侧面构图看到「地面升空→头顶盘旋」的完整轨迹
        cameraStartYaw = (float) Math.toDegrees(Math.atan2(
                originZ - (float) mc.player.getZ(), originX - (float) mc.player.getX())) + 90F;

        // ===== 运镜期间锁定玩家在吸收起始位置 =====
        playerLocked = true;
        freezeX = mc.player.getX();
        freezeY = mc.player.getY();
        freezeZ = mc.player.getZ();

        entityId = ringEntityId;
        skillReady = false;
        showingRings = false;
        landSent = false;
        phase = PHASE_LIFT;
        phaseTick = 0;
        active = true;
    }

    /** 魂技感应就绪（RingAbsorbSkillReadyPayload）：盘旋满最短时长后自动进入落位 */
    public static void skillReady(int ringEntityId) {
        if (!active || ringEntityId != entityId) return;
        skillReady = true;
    }

    /** 服务端取消本次吸收（RingAbsorbCancelPayload：感应失败等，魂环不消散） */
    public static void cancel(int ringEntityId) {
        if (!active || ringEntityId != entityId) return;
        finalizeCinematic();
    }

    /** 魂技绑定窗口到达（OpenRingAbsorbScreenPayload）：魂环已吸收完毕，结束动画 */
    public static void onAbsorbWindowOpened(int ringEntityId) {
        if (!active || ringEntityId != entityId) return;
        finalizeCinematic();
    }

    /** 动画期间是否要隐藏对应的地面魂环实体（其渲染被「魂环灵体」取代） */
    public static boolean isEntityHidden(int entityId) {
        return active && entityId == RingAbsorbCinematic.entityId;
    }

    /** 武魂显形（仅环显形代替开武魂）：动画期间需要画出已持有魂环 */
    public static boolean isShowingRings() {
        return active && showingRings;
    }

    /** 动画是否进行中（供调试/其它系统判断） */
    public static boolean isActive() {
        return active;
    }

    /** 分离环绕相机是否生效（CameraMixin 用）：落位瞬间回正后返回 false */
    public static boolean isCameraActive() {
        return active && cameraChanged;
    }

    /** 环绕镜头已走完的全局时长（tick 数，供 CameraMixin 计算环绕进度） */
    public static float cameraElapsedTicks() {
        return cinematicElapsed;
    }

    /** 环绕起始朝向（度，供 CameraMixin 使用） */
    public static float cameraStartYawDeg() {
        return cameraStartYaw;
    }

    /** 结束并还原：玩家解锁、环显形关闭、相机还原、魂环实体恢复渲染 */
    private static void finalizeCinematic() {
        active = false;
        playerLocked = false;
        showingRings = false;
        skillReady = false;
        landSent = false;
        phase = PHASE_IDLE;
        phaseTick = 0;
        entityId = -1;
        cinematicElapsed = 0F;
        if (cameraChanged) {
            Minecraft mc = Minecraft.getInstance();
            mc.options.setCameraType(previousCameraType);
            cameraChanged = false;
        }
    }

    // ===== 客户端 tick：驱动阶段推进 =====

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            finalizeCinematic();
            return;
        }
        // 玩家死亡/移除：动画中断（服务端登出/死亡也会清流程锁，环仍留地面）
        if (mc.player.isRemoved() || mc.player.getHealth() <= 0) {
            finalizeCinematic();
            return;
        }

        // 魂环实体在落位上报前消失（如被其它参与者经验吸收完而消散）→ 中止，不再加环
        if (!landSent) {
            Entity e = mc.level.getEntity(entityId);
            if (!(e instanceof SoulRingEntity)) {
                finalizeCinematic();
                return;
            }
        }

        phaseTick++;
        cinematicElapsed++;   // 环绕镜头全局时钟
        freezePlayer(mc.player);

        switch (phase) {
            case PHASE_LIFT -> {
                if (phaseTick >= LIFT_TICKS) {
                    phase = PHASE_FLY;
                    phaseTick = 0;
                }
            }
            case PHASE_FLY -> {
                if (phaseTick >= FLY_TICKS) {
                    phase = PHASE_HOVER;
                    phaseTick = 0;
                    // 武魂显形：飞到头顶开始盘旋时，脚下已持有魂环显形。
                    // 显形仅负责「开关」，出现演出由 SoulRingRenderer 的浮现时钟按开武魂方式逐环错开播放
                    showingRings = true;
                }
            }
            case PHASE_HOVER -> {
                // 魂技就绪且盘旋满最短时长 → 开始落位
                if (skillReady && phaseTick >= HOVER_MIN_TICKS) {
                    captureDescendStart();
                    phase = PHASE_DESCEND;
                    phaseTick = 0;
                }
            }
            case PHASE_DESCEND -> {
                if (phaseTick >= DESCEND_TICKS) {
                    phase = PHASE_SETTLE;
                    phaseTick = 0;
                    onLanded();
                }
            }
            case PHASE_SETTLE -> {
                // 兜底：落位后窗口迟迟未到（如服务端异常丢包）→ 超时结束显形，避免卡状态
                if (phaseTick > SETTLE_TIMEOUT_TICKS) {
                    finalizeCinematic();
                }
            }
            default -> { }
        }
    }

    /** 运镜期间禁止玩家移动：清除输入脉冲 + 速度清零 + 位置锁定在吸收起始点 */
    private static void freezePlayer(Player player) {
        if (player instanceof LocalPlayer local) {
            local.input.leftImpulse = 0F;
            local.input.forwardImpulse = 0F;
            local.input.up = false;
            local.input.down = false;
            local.input.left = false;
            local.input.right = false;
            local.input.jumping = false;
        }
        player.setDeltaMovement(0, 0, 0);
        if (!playerLocked) return;
        double dx = player.getX() - freezeX;
        double dy = player.getY() - freezeY;
        double dz = player.getZ() - freezeZ;
        if (dx * dx + dz * dz > 1.0E-4D || dy * dy > 1.0E-4D) {
            player.setPos(freezeX, freezeY, freezeZ);
            player.resetFallDistance();
        }
    }

    /** 落位瞬间：解锁玩家 + 相机回正 + 上报服务端真正加环（此后静态环与飞入环无缝衔接） */
    private static void onLanded() {
        if (landSent) return;
        landSent = true;
        playerLocked = false; // 环已入体，玩家恢复可控（随后绑定窗口马上弹出）
        // 相机回正到原视角，玩家以正常视角看到环落位入体的瞬间
        if (cameraChanged) {
            Minecraft mc = Minecraft.getInstance();
            mc.options.setCameraType(previousCameraType);
            cameraChanged = false;
        }
        PacketDistributor.sendToServer(new RingAbsorbLandPayload(entityId));
    }

    /** 进入落位前记录盘旋终点，保证动画连续 */
    private static void captureDescendStart() {
        float t = phaseTick;
        float ang = t * 0.045F;
        descOx = (float) Math.cos(ang) * HOVER_SWAY;
        descOz = (float) Math.sin(ang) * HOVER_SWAY;
        descBob = (float) Math.sin(t * 0.18F) * HOVER_BOB_AMP;
        descStartRadius = hoverRadius;
    }

    // ===== 渲染：魂环灵体 =====

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!active || event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        // 落位且数据已同步出新魂环：静态环已画出本环，不再画灵体（无缝衔接）
        if (phase == PHASE_SETTLE) {
            PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
            if (data.getRingCount() > preRingCount) return;
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaTicks();
        float time = mc.level.getGameTime() + partialTick;

        // 玩家插值位置（世界坐标）
        double px = player.xOld + (player.getX() - player.xOld) * partialTick;
        double py = player.yOld + (player.getY() - player.yOld) * partialTick;
        double pz = player.zOld + (player.getZ() - player.zOld) * partialTick;

        // 当前阶段内的局部时间（含插值，动画更平滑）
        float local = phaseTick + partialTick;
        float x;
        float y;
        float z;
        float radius;

        switch (phase) {
            case PHASE_LIFT -> {
                float s = easeOut(Math.min(1F, local / LIFT_TICKS));
                x = originX;
                z = originZ;
                y = originY + 0.08F + LIFT_HEIGHT * s;
                radius = groundRadius;
            }
            case PHASE_FLY -> {
                float t = Math.min(1F, local / FLY_TICKS);
                float s = smooth(t);
                float sx = originX;
                float sy = originY + 0.08F + LIFT_HEIGHT;
                float sz = originZ;
                float ex = (float) px;
                float ey = (float) py + HOVER_CENTER_Y;
                float ez = (float) pz;
                x = lerp(sx, ex, s);
                z = lerp(sz, ez, s);
                y = lerp(sy, ey, s) + FLY_ARC * (float) Math.sin(t * Math.PI);
                radius = lerp(groundRadius, hoverRadius, s);
            }
            case PHASE_HOVER -> {
                // 盘旋 = 绕自身中心旋转 + 上下浮动 + 极小水平晃动（不是绕头大轨道），
                // 旋转方向由下方公共逻辑套用静态魂环自转（奇数环反向），等待期间只是原地自转
                float t = Math.max(0F, local);
                float ang = t * 0.045F;
                x = (float) px + (float) Math.cos(ang) * HOVER_SWAY;
                z = (float) pz + (float) Math.sin(ang) * HOVER_SWAY;
                y = (float) py + HOVER_CENTER_Y + (float) Math.sin(t * 0.18F) * HOVER_BOB_AMP;
                radius = hoverRadius;
            }
            case PHASE_DESCEND -> {
                float t = Math.min(1F, local / DESCEND_TICKS);
                float s = smooth(t);
                float footY = (float) py + Y_OFFSET + newRingIndex * 0.002F;
                x = (float) px + descOx * (1F - s);
                z = (float) pz + descOz * (1F - s);
                y = lerp((float) py + HOVER_CENTER_Y + descBob, footY, s);
                radius = lerp(descStartRadius, targetRadius, s);
                radius *= 1F + 0.06F * (float) Math.sin(t * Math.PI); // 落位中轻微脉动
            }
            default -> {
                // PHASE_SETTLE：魂环在最终位置待命（等待数据同步补上静态环）
                x = (float) px;
                z = (float) pz;
                y = (float) py + Y_OFFSET + newRingIndex * 0.002F;
                radius = targetRadius;
            }
        }

        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        var camPos = event.getCamera().getPosition();

        pose.pushPose();
        pose.translate(x - camPos.x, y - camPos.y, z - camPos.z);
        // 旋转套用静态魂环的「绕自身中心自转」特效：与最终该环位的静态魂环同号一致
        // （偶数环顺时针/奇数环逆时针），落位后与静态环无感衔接
        float angle = (newRingIndex % 2 == 0) ? -time * SPIN_SPEED : time * SPIN_SPEED;
        pose.mulPose(Axis.YP.rotationDegrees(angle));

        RenderType ringType = RenderType.entityTranslucentEmissive(SoulRingAge.getTexture(ringAge));
        var consumer = bufferSource.getBuffer(ringType);
        Matrix4f matrix = pose.last().pose();
        SoulRingRenderer.drawFlatQuad(consumer, matrix, radius);
        // 反向再画一面：头顶盘旋时从下往上看也能看见环面
        pose.mulPose(Axis.XP.rotationDegrees(180F));
        SoulRingRenderer.drawFlatQuad(consumer, pose.last().pose(), radius);
        pose.popPose();
        bufferSource.endBatch(ringType);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** smoothstep 缓动 */
    private static float smooth(float t) {
        return t * t * (3F - 2F * t);
    }

    /** easeOut 缓动 */
    private static float easeOut(float t) {
        return 1F - (1F - t) * (1F - t);
    }
}
