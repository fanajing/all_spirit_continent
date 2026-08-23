package org.fanajing.all_spirit_continent.network;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.fanajing.all_spirit_continent.All_spirit_continent;

/**
 * 网络包注册中心
 * 管理所有自定义数据包（Payload）的注册与处理
 */
@EventBusSubscriber(modid = All_spirit_continent.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ModNetwork {

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(All_spirit_continent.MODID);

        // 服务端 → 客户端：同步等级系统数据（登录时触发）
        registrar.playToClient(
                PlayerLevelSyncPayload.TYPE,
                PlayerLevelSyncPayload.STREAM_CODEC,
                PlayerLevelSyncPayload::handleClient
        );

        // 客户端 → 服务端：同步调试棒当前模式（滚轮切换时触发）
        registrar.playToServer(
                SetDebugModePayload.TYPE,
                SetDebugModePayload.STREAM_CODEC,
                SetDebugModePayload::handleServer
        );

        // 服务端 → 客户端：通知播放「开武魂」动画（右键触发）
        registrar.playToClient(
                OpenSoulAnimationPayload.TYPE,
                OpenSoulAnimationPayload.STREAM_CODEC,
                OpenSoulAnimationPayload::handleClient
        );

        // 客户端 → 服务端：按键触发「开/关武魂」（默认 N 键）
        registrar.playToServer(
                KaiWuHunPayload.TYPE,
                KaiWuHunPayload.STREAM_CODEC,
                KaiWuHunPayload::handleServer
        );

        // 服务端 → 客户端：同步魂兽年限（玩家开始跟踪魂兽实体时触发）
        registrar.playToClient(
                SoulBeastSyncPayload.TYPE,
                SoulBeastSyncPayload.STREAM_CODEC,
                SoulBeastSyncPayload::handleClient
        );

        // 服务端 → 客户端：打开魂环吸收确认窗口（蹲着右键魂环校验通过后触发）
        registrar.playToClient(
                OpenRingAbsorbScreenPayload.TYPE,
                OpenRingAbsorbScreenPayload.STREAM_CODEC,
                OpenRingAbsorbScreenPayload::handleClient
        );

        // 客户端 → 服务端：确认吸收魂环（吸收确认窗口「吸收」按钮触发）
        registrar.playToServer(
                ConfirmRingAbsorbPayload.TYPE,
                ConfirmRingAbsorbPayload.STREAM_CODEC,
                ConfirmRingAbsorbPayload::handleServer
        );

        // 客户端 → 服务端：增减等级（调试棒增减等级模式左键触发）
        registrar.playToServer(
                AdjustLevelPayload.TYPE,
                AdjustLevelPayload.STREAM_CODEC,
                AdjustLevelPayload::handleServer
        );
    }
}
