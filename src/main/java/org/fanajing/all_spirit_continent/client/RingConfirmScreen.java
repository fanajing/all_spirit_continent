package org.fanajing.all_spirit_continent.client;

import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.fanajing.all_spirit_continent.entity.SoulRingEntity;
import org.fanajing.all_spirit_continent.network.ConfirmRingAbsorbPayload;
import org.fanajing.all_spirit_continent.skill.SkillData;
import org.fanajing.all_spirit_continent.util.SoulBeastAge;

import java.util.List;

/**
 * 魂环·魂技绑定窗口（V6.2：魂环已在吸收动画落位瞬间加给玩家并消散，本窗口决定魂环去留 + 魂技绑定）。
 * <p>
 * - 展示魂技名称/描述/数值预览/冷却/来源评分 + 魂环年限/怪物种类，
 *   按钮「吸收 / 拒绝 / 自行推演」
 * - 吸收 → 保留已吸收的魂环并把魂技固化到该环位；拒绝 → 回滚本次吸收（魂环碎裂消散，
 *   移除刚吸收的魂环，回到瓶颈封顶），点击立即发送对应动作并关闭窗口；
 *   评分可选（/douluo rate <环位> <1-5>）
 * - 「自行推演」强制 AI 重生成（服务端回发新技能后由 OpenRingAbsorbScreenPayload 刷新本窗口）
 * - 无自动关闭倒计时，窗口保持到玩家做出选择；不暂停游戏；
 *   ESC/死亡等任何未选择就关闭窗口的方式一律视为「拒绝」（回滚本次吸收），
 *   只有点击「吸收」按钮才保留魂环
 * - V6.2 起魂环实体通常在动画落位后已消散，怪物种类优先取魂环实体快照，
 *   实体缺失时回退用魂技自身携带的 mob_id 展示（两者一致，均为魂兽注册名）
 */
public class RingConfirmScreen extends Screen {

    private final int ringEntityId;
    private final int ringNumber;
    private final int ringAge;
    private final double avgRating;
    private final int ratingCount;
    private final boolean aiGenerated;
    private final String uploaderName;
    private final SkillData skill; // JSON 损坏时为 null（兜底显示）

    /** 客户端魂环实体快照（实体已卸载/消散时为 null） */
    private SoulRingEntity ring;

    /** 已做出明确选择（吸收/拒绝）则为 true；防止自动拒绝重复发包 */
    private boolean decided;
    /** 本窗口即将被「自行推演」的新技能刷新替换（新窗口到达时标记），不视为放弃 */
    private boolean refreshed;

    public RingConfirmScreen(int ringEntityId, String skillJson, int ringNumber, int ringAge,
                             double avgRating, int ratingCount, boolean aiGenerated,
                             String uploaderName) {
        super(Component.translatable("screen.all_spirit_continent.ring_absorb.title"));
        this.ringEntityId = ringEntityId;
        this.ringNumber = ringNumber;
        this.ringAge = ringAge;
        this.avgRating = avgRating;
        this.ratingCount = ratingCount;
        this.aiGenerated = aiGenerated;
        this.uploaderName = uploaderName;
        this.skill = parseSkill(skillJson);
    }

    private static SkillData parseSkill(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return SkillData.fromCloudJson(JsonParser.parseString(json).getAsJsonObject());
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    protected void init() {
        if (minecraft.level != null
                && minecraft.level.getEntity(ringEntityId) instanceof SoulRingEntity entity) {
            ring = entity;
        }
        rebuildButtons();
    }

    private void rebuildButtons() {
        clearWidgets();
        int cx = this.width / 2;

        // 感应阶段：吸收 / 拒绝 / 自行推演（点击即发送动作并关闭，不进入评分界面）
        int y = this.height / 2 + 52;
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.all_spirit_continent.ring_absorb.absorb"),
                        btn -> onDecide(ConfirmRingAbsorbPayload.ACTION_ABSORB))
                .bounds(cx - 112, y, 72, 20).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.all_spirit_continent.ring_absorb.reject"),
                        btn -> onDecide(ConfirmRingAbsorbPayload.ACTION_REJECT))
                .bounds(cx - 36, y, 72, 20).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.all_spirit_continent.ring_absorb.roll"),
                        btn -> onRoll())
                .bounds(cx + 40, y, 72, 20).build());
    }

    /** 点击「吸收 / 拒绝」：发送动作（不评分，stars=0）并立即关闭 */
    private void onDecide(String action) {
        decided = true;
        PacketDistributor.sendToServer(new ConfirmRingAbsorbPayload(ringEntityId, action, 0));
        onClose();
    }

    /** 点击「自行推演」：发 ROLL，服务端重生成后回发新技能刷新本窗口 */
    private void onRoll() {
        PacketDistributor.sendToServer(
                new ConfirmRingAbsorbPayload(ringEntityId, ConfirmRingAbsorbPayload.ACTION_ROLL, 0));
    }

    /** 新技能刷新窗口到达时调用：标记本窗口是被替换而非被放弃，removed() 不再自动拒绝 */
    public void markRefreshed() {
        refreshed = true;
    }

    /**
     * 窗口以任何方式被移除（ESC 关闭、死亡界面替换等）且未点「吸收」/「拒绝」时，
     * 视为「拒绝」：回滚本次吸收（移除刚吸收的魂环）。只有点击「吸收」按钮才会保留魂环。
     * 「自行推演」刷新走 setScreen 替换本窗口（refreshed=true），不触发自动拒绝。
     */
    @Override
    public void removed() {
        if (!decided && !refreshed) {
            decided = true;
            PacketDistributor.sendToServer(
                    new ConfirmRingAbsorbPayload(ringEntityId, ConfirmRingAbsorbPayload.ACTION_REJECT, 0));
        }
        super.removed();
    }

    /** 窗口打开期间游戏不暂停 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        int y = 30;

        // 标题：§6【第 X 魂环 · 魂技感应】
        graphics.drawCenteredString(font,
                Component.translatable("screen.all_spirit_continent.ring_absorb.sense_title", ringNumber)
                        .withStyle(ChatFormatting.GOLD), cx, y, 0xFFFFFF);
        y += 16;

        // 魂环信息：怪物种类 + 年限（V6.2 实体常已消散 → 回退用魂技 mob_id，两者一致）
        String sourceKey = null;
        if (ring != null && !ring.getSourceType().isEmpty()) {
            sourceKey = ring.getSourceType();
        } else if (skill != null && skill.mobId() != null
                && !skill.mobId().isEmpty() && !"unknown".equalsIgnoreCase(skill.mobId())) {
            sourceKey = skill.mobId();
        }
        MutableComponent beast = Component.translatable("screen.all_spirit_continent.ring_absorb.beast",
                sourceKey != null ? Component.translatable(sourceKey)
                        : Component.translatable("screen.all_spirit_continent.ring_absorb.unknown_beast"));
        MutableComponent age = Component.translatable("screen.all_spirit_continent.ring_absorb.age",
                ring != null ? SoulBeastAge.format(ring.getRingAge()) : SoulBeastAge.format(ringAge));
        graphics.drawCenteredString(font, beast.withStyle(ChatFormatting.WHITE), cx, y, 0xFFFFFF);
        graphics.drawCenteredString(font, age.withStyle(ChatFormatting.WHITE), cx, y + 12, 0xFFFFFF);
        y += 30;

        if (skill == null) {
            // 技能数据异常兜底
            graphics.drawCenteredString(font,
                    Component.translatable("screen.all_spirit_continent.ring_absorb.skill_broken")
                            .withStyle(ChatFormatting.RED), cx, y, 0xFFFFFF);
            return;
        }

        // 技能名称（金）
        graphics.drawCenteredString(font,
                Component.literal(skill.name()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), cx, y, 0xFFFFFF);
        y += 14;

        // 描述（白，自动换行）
        List<FormattedCharSequence> descLines = font.split(
                Component.literal(skill.description()).withStyle(ChatFormatting.WHITE), this.width - 120);
        for (FormattedCharSequence line : descLines) {
            graphics.drawCenteredString(font, line, cx, y, 0xFFFFFF);
            y += 10;
        }
        y += 6;

        // 数值预览（绿，原语摘要）
        for (Component line : skill.previewLines()) {
            graphics.drawCenteredString(font, line.copy().withStyle(ChatFormatting.GREEN), cx, y, 0xFFFFFF);
            y += 10;
        }
        y += 4;

        // 冷却（青）
        graphics.drawCenteredString(font,
                Component.translatable("screen.all_spirit_continent.ring_absorb.cooldown",
                        String.format("%.1f", skill.cooldown() / 20.0f))
                        .withStyle(ChatFormatting.AQUA), cx, y, 0xFFFFFF);
        y += 14;

        // 来源（灰）：AI 推演 / 上传者 + 评分 / 匿名魂师
        MutableComponent source;
        if (aiGenerated) {
            source = Component.translatable("screen.all_spirit_continent.ring_absorb.source_ai")
                    .withStyle(ChatFormatting.LIGHT_PURPLE);
        } else if (uploaderName != null && !uploaderName.isEmpty()) {
            if (ratingCount > 0) {
                source = Component.translatable("screen.all_spirit_continent.ring_absorb.source_by_rating",
                        uploaderName, String.format("%.1f", avgRating), ratingCount)
                        .withStyle(ChatFormatting.GRAY);
            } else {
                source = Component.translatable("screen.all_spirit_continent.ring_absorb.source_by", uploaderName)
                        .withStyle(ChatFormatting.GRAY);
            }
        } else if (ratingCount > 0) {
            source = Component.translatable("screen.all_spirit_continent.ring_absorb.source",
                    String.format("%.1f", avgRating), ratingCount)
                    .withStyle(ChatFormatting.GRAY);
        } else {
            source = Component.translatable("screen.all_spirit_continent.ring_absorb.source_unrated")
                    .withStyle(ChatFormatting.GRAY);
        }
        graphics.drawCenteredString(font, source, cx, y, 0xFFFFFF);

        // 底部提示：本窗口决定魂环去留（吸收保留并绑定 / 拒绝碎裂回滚）与魂技绑定
        graphics.drawCenteredString(font,
                Component.translatable("screen.all_spirit_continent.ring_absorb.bind_hint", ringNumber)
                        .withStyle(ChatFormatting.GRAY), cx, this.height - 32, 0xFFFFFF);
        // 底部提示：评分可另行通过 /douluo rate <环位> <1-5> 进行（可选，不影响使用）
        graphics.drawCenteredString(font,
                Component.translatable("screen.all_spirit_continent.ring_absorb.rate_hint")
                        .withStyle(ChatFormatting.DARK_GRAY), cx, this.height - 20, 0xFFFFFF);
    }
}
