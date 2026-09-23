package org.fanajing.all_spirit_continent.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import org.fanajing.all_spirit_continent.client.RingAbsorbCinematic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 吸收魂环动画的「分离环绕相机」（V6.2）。
 * <p>
 * 普通玩法里相机绑定在玩家身上；吸收动画期间 {@link RingAbsorbCinematic} 需要一段
 * 「镜头与玩家实体分离、锁定绕玩家水平环绕」的运镜：本 Mixin 在 {@link Camera#setup}
 * 完成后按动画的全局进度覆盖相机的位置与朝向，观察点始终对准玩家，镜头绕玩家缓慢旋转，
 * 期间可见头顶盘旋的魂环与脚下显形的武魂环。非吸收动画期间不产生任何影响。
 */
@Mixin(Camera.class)
public abstract class CameraMixin extends Camera {

    // ===== 环绕运镜参数 =====
    /** 相机绕玩家的水平半径（格） */
    private static final double ORBIT_RADIUS = 3.2D;
    /** 相机高度（相对玩家脚底，略高于盘旋环，形成俯瞰构图） */
    private static final double CAM_HEIGHT = 4.6D;
    /** 相机注视点高度（相对玩家脚底，看玩家头部/上半身） */
    private static final double LOOK_HEIGHT = 1.55D;
    /** 环绕角速度（度/tick，约 11°/秒：一圈约 33 秒） */
    private static final double ORBIT_SPEED = 0.55D;
    /** 总环绕上限（度）：长时间盘旋等 AI 时不至于一直转个没完 */
    private static final double MAX_TURN_DEG = 300D;

    @Inject(method = "setup", at = @At("TAIL"))
    private void asc$orbitCinematicCamera(BlockGetter level, Entity entity, boolean detached,
                                          boolean thirdPersonReverse, float partialTick,
                                          CallbackInfo ci) {
        // 仅在吸收动画的环绕镜头阶段生效
        if (!RingAbsorbCinematic.isCameraActive()) return;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        // 玩家插值位置（相机每次渲染帧都从这里重新取，环绕过程中若玩家被动位移也跟随）
        double px = player.xOld + (player.getX() - player.xOld) * partialTick;
        double py = player.yOld + (player.getY() - player.yOld) * partialTick;
        double pz = player.zOld + (player.getZ() - player.zOld) * partialTick;

        // 环绕进度 = 动画全局时长 × 角速度（封顶），起始朝向取吸收开始瞬间对准魂环的方向
        double t = RingAbsorbCinematic.cameraElapsedTicks() + partialTick;
        double turn = Math.min(t * ORBIT_SPEED, MAX_TURN_DEG);
        double ang = Math.toRadians(RingAbsorbCinematic.cameraStartYawDeg() + turn);

        double cx = px + ORBIT_RADIUS * Math.cos(ang);
        double cy = py + CAM_HEIGHT;
        double cz = pz + ORBIT_RADIUS * Math.sin(ang);

        // 相机朝向玩家头部（MC 视角方向 = (-sinYaw·cosPitch, -sinPitch, cosYaw·cosPitch)）
        double dx = px - cx;
        double dy = (py + LOOK_HEIGHT) - cy;
        double dz = pz - cz;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-5D) return; // 数值异常时保留原相机

        float yRot = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float xRot = (float) Math.toDegrees(Math.asin(-dy / len));

        this.setPosition(cx, cy, cz);
        this.setRotation(yRot, xRot, 0.0F); // roll=0：吸收运镜保持画面水平
    }
}
