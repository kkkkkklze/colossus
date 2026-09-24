package com.klze.colossus.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 进身 goal（仅空闲时生效）：把目标带到攻击距离内但不做任何攻击——
 * 出招完全归状态机管。替代 MeleeAttackGoal 是为了避开它自带的 vanilla 伤害结算。
 */
public class BossApproachGoal extends Goal {

    private final ColossusBossEntity boss;
    private final double speedModifier;
    private int recalcCooldown;

    public BossApproachGoal(ColossusBossEntity boss, double speedModifier) {
        this.boss = boss;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return boss.getStateController().isIdle()
                && !boss.isDeathPending()
                && boss.getTarget() != null && boss.getTarget().isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        LivingEntity target = boss.getTarget();
        if (target == null) return;
        double reach = boss.approachReach();
        if (boss.distanceToSqr(target) > reach * reach) {
            if (--recalcCooldown <= 0) {
                recalcCooldown = 10;
                boss.getNavigation().moveTo(target, speedModifier);
            }
        } else {
            boss.getNavigation().stop();
        }
    }
}
