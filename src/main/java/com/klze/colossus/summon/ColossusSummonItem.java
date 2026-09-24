package com.klze.colossus.summon;

import com.klze.colossus.entity.ColossusBossEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 召唤物基类（Confluence BossSummoningItem 模式）：
 * gate 谓词（进度板/维度/装备随便查）→ 同类在场查重 → 落点生成 → 消耗物品。
 *
 * <p>与"一次性召唤石方块"（TF/首领崛起）互补：方块适合世界自然生成点，物品适合 Boss 奖杯复用闭环。
 */
public class ColossusSummonItem extends Item {

    private final Supplier<? extends EntityType<? extends ColossusBossEntity>> bossType;
    private final double summonDistance;
    private final Predicate<ServerPlayer> gate;
    private final ResourceLocation bossId;

    /**
     * @param summonDistance 在玩家视线方向多少格外落地
     * @param gate           允许召唤的谓词（false 时提示 failMessageKey 由子类/lorent 决定）
     * @param bossId         对应 Boss 定义 id（查同类用）
     */
    public ColossusSummonItem(Supplier<? extends EntityType<? extends ColossusBossEntity>> bossType,
                              Item.Properties properties, double summonDistance,
                              Predicate<ServerPlayer> gate, ResourceLocation bossId) {
        super(properties);
        this.bossType = bossType;
        this.summonDistance = summonDistance;
        this.gate = gate;
        this.bossId = bossId;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player playerIn, InteractionHand hand) {
        ItemStack stack = playerIn.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }
        if (!(playerIn instanceof ServerPlayer player)) {
            return InteractionResultHolder.fail(stack);
        }
        if (!gate.test(player)) {
            player.displayClientMessage(
                    Component.translatable("colossus.summon.gate_locked."
                            + bossId.getNamespace() + "." + bossId.getPath(), true), true);
            return InteractionResultHolder.fail(stack);
        }
        EntityType<? extends ColossusBossEntity> type = bossType.get();
        ColossusBossEntity existing = findNearbyBoss(player, type);
        if (existing != null) {
            player.displayClientMessage(Component.translatable("colossus.summon.alive"), true);
            return InteractionResultHolder.fail(stack);
        }
        ColossusBossEntity boss = type.create(level);
        if (boss == null) return InteractionResultHolder.fail(stack);
        var look = player.getLookAngle();
        double x = player.getX() + look.x * summonDistance;
        double z = player.getZ() + look.z * summonDistance;
        Double ground = findGround(level, x, player.getY(), z);
        if (ground == null) {
            // 前方无地（虚空/深坑）：退回玩家脚下生成，宁贴脸不掷虚空（审查 P2#14）
            x = player.getX();
            z = player.getZ();
            ground = player.getY();
        }
        boss.moveTo(x, ground, z, player.getYRot(), 0f);
        boss.finalizeSpawn((ServerLevel) level, level.getCurrentDifficultyAt(boss.blockPosition()),
                MobSpawnType.MOB_SUMMONED, null, null);
        level.addFreshEntity(boss);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 1.0f, 0.8f);
        stack.shrink(1);
        return InteractionResultHolder.consume(stack);
    }

    @Nullable
    private static ColossusBossEntity findNearbyBoss(ServerPlayer player,
                                                     EntityType<? extends ColossusBossEntity> type) {
        AABB box = player.getBoundingBox().inflate(96);
        for (ColossusBossEntity e : player.level().getEntitiesOfClass(ColossusBossEntity.class, box,
                LivingEntity::isAlive)) {
            if (e.getType() == type) return e;
        }
        return null;
    }

    @Nullable
    private static Double findGround(Level level, double x, double fromY, double z) {
        int y = (int) fromY;
        for (int i = 0; i < 24; i++, y--) {
            if (!level.isEmptyBlock(new net.minecraft.core.BlockPos((int) x, y, (int) z))) {
                return y + 1.0;
            }
        }
        return null;
    }
}
