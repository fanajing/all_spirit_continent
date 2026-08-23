package org.fanajing.all_spirit_continent.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import org.fanajing.all_spirit_continent.init.ModAttachments;
import org.fanajing.all_spirit_continent.util.ModAdvancements;
import org.fanajing.all_spirit_continent.util.PlayerLevelData;

public class ShuiJingQiuItem extends Item {
    /** 蓄力时间：2秒 = 40 ticks */
    private static final int CHARGE_DURATION = 40;

    public ShuiJingQiuItem(Properties properties) {
        super(properties);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        // 使用弓的拉弦动画，表现蓄力效果
        return UseAnim.BOW;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return CHARGE_DURATION;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remainingTicks) {
        if (!level.isClientSide) return;

        // 蓄力期间的粒子特效
        int usedTicks = this.getUseDuration(stack, entity) - remainingTicks;
        float progress = (float) usedTicks / CHARGE_DURATION; // 0.0 ~ 1.0

        double x = entity.getX();
        double y = entity.getY() + entity.getEyeHeight() - 0.3;
        double z = entity.getZ();

        // 魔法粒子围绕水晶球旋转，越接近碎裂密度越高
        int particleCount = 1 + (int) (progress * 3); // 1~4 个粒子/tick
        for (int i = 0; i < particleCount; i++) {
            double radius = 0.6;
            double angle = level.random.nextDouble() * Math.PI * 2;
            double offsetX = Math.cos(angle) * radius;
            double offsetY = (level.random.nextDouble() - 0.5) * 1.2;
            double offsetZ = Math.sin(angle) * radius;

            level.addParticle(
                ParticleTypes.ENCHANT,
                x + offsetX, y + offsetY, z + offsetZ,
                0, 0, 0
            );
        }
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (entity instanceof Player player) {
            // 激活等级系统 —— 双端都设置，确保客户端 HUD 能读取到
            PlayerLevelData data = player.getData(ModAttachments.PLAYER_LEVEL_DATA.get());
            if (!data.isActivated()) {
                data.setActivated(true);
            }

            if (!level.isClientSide) {
                // 成就：使用完水晶球 → 《魂士》（重复使用重复授予无害）
                ModAdvancements.awardSoulMaster((ServerPlayer) player);

                // === 碎裂音效 ===
                level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.0F, 1.0F);

                // === 碎裂粒子特效 ===
                double x = player.getX();
                double y = player.getY() + player.getEyeHeight() - 0.3;
                double z = player.getZ();
                for (int i = 0; i < 30; i++) {
                    double vx = (level.random.nextDouble() - 0.5) * 3;
                    double vy = level.random.nextDouble() * 3;
                    double vz = (level.random.nextDouble() - 0.5) * 3;
                    level.addParticle(ParticleTypes.CRIT, x, y, z, vx, vy, vz);
                }
                // 额外加一些魔法闪光粒子
                for (int i = 0; i < 15; i++) {
                    double vx = (level.random.nextDouble() - 0.5) * 2;
                    double vy = (level.random.nextDouble() - 0.5) * 2;
                    double vz = (level.random.nextDouble() - 0.5) * 2;
                    level.addParticle(ParticleTypes.END_ROD, x, y, z, vx, vy, vz);
                }

                // 聊天提示（仅服务端）
                player.sendSystemMessage(
                        Component.literal("等级系统已激活！")
                                .withStyle(ChatFormatting.GREEN));

                // 消耗物品
                stack.shrink(1);
            }
        }
        return stack;
    }
}
