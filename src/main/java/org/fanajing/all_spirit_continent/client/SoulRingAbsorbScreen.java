package org.fanajing.all_spirit_continent.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.fanajing.all_spirit_continent.entity.SoulRingEntity;
import org.fanajing.all_spirit_continent.network.ConfirmRingAbsorbPayload;
import org.fanajing.all_spirit_continent.util.SoulBeastAge;

/**
 * 魂环吸收确认窗口：蹲着右键魂环后弹出。
 * 显示怪物种类、年限、技能（开发中）、吸收概率（开发中），
 * 「吸收」按钮确认后发 ConfirmRingAbsorbPayload 由服务端执行吸收；「取消」或 ESC 关闭。
 * 信息从客户端世界的魂环实体（synchedData）读取。
 */
public class SoulRingAbsorbScreen extends Screen {

    /** 目标魂环实体 ID（打开时快照客户端实体信息） */
    private final int ringEntityId;
    /** 客户端魂环实体快照（可能为 null：实体已卸载/消散，显示兜底文案） */
    private SoulRingEntity ring;

    public SoulRingAbsorbScreen(int ringEntityId) {
        super(Component.translatable("screen.all_spirit_continent.ring_absorb.title"));
        this.ringEntityId = ringEntityId;
    }

    @Override
    protected void init() {
        if (minecraft.level != null
                && minecraft.level.getEntity(ringEntityId) instanceof SoulRingEntity entity) {
            ring = entity;
        }

        int buttonY = this.height / 2 + 48;
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.all_spirit_continent.ring_absorb.confirm"),
                        btn -> onConfirm())
                .bounds(this.width / 2 - 108, buttonY, 100, 20).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.all_spirit_continent.ring_absorb.cancel"),
                        btn -> onClose())
                .bounds(this.width / 2 + 8, buttonY, 100, 20).build());
    }

    /** 「吸收」按钮：发确认包给服务端执行吸收，然后关闭窗口（V6.0 已由 RingConfirmScreen 全面替换） */
    private void onConfirm() {
        PacketDistributor.sendToServer(new ConfirmRingAbsorbPayload(
                ringEntityId, ConfirmRingAbsorbPayload.ACTION_ABSORB, 0));
        onClose();
    }

    /** 窗口打开期间游戏不暂停（与聊天等界面一致） */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        graphics.drawCenteredString(font, title, cx, 38, 0xFFFFFF);

        // 怪物种类：本地化键翻译（魂兽死亡时写入的 descriptionId，如 entity.minecraft.slime）；
        // 旧存档魂环无该字段 → 显示「未知魂兽」
        Component beast = ring != null && !ring.getSourceType().isEmpty()
                ? Component.translatable("screen.all_spirit_continent.ring_absorb.beast",
                        Component.translatable(ring.getSourceType()))
                : Component.translatable("screen.all_spirit_continent.ring_absorb.beast",
                        Component.translatable("screen.all_spirit_continent.ring_absorb.unknown_beast"));

        Component age = Component.translatable("screen.all_spirit_continent.ring_absorb.age",
                ring != null ? SoulBeastAge.format(ring.getRingAge()) : "?");

        // 技能与吸收概率：玩法未实装，占位显示「开发中」
        Component developing = Component.translatable("screen.all_spirit_continent.ring_absorb.developing");
        Component skill = Component.translatable("screen.all_spirit_continent.ring_absorb.skill", developing);
        Component probability = Component.translatable("screen.all_spirit_continent.ring_absorb.probability", developing);

        int y = 68;
        graphics.drawCenteredString(font, beast, cx, y, 0xFFFFFF);
        graphics.drawCenteredString(font, age, cx, y + 16, 0xFFFFFF);
        graphics.drawCenteredString(font, skill, cx, y + 32, 0xFFFFFF);
        graphics.drawCenteredString(font, probability, cx, y + 48, 0xFFFFFF);
    }
}
