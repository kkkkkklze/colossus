package com.klze.colossus.entity;

import com.klze.colossus.state.ActiveState;
import com.klze.colossus.state.State;

/**
 * 阶段过场状态：一次"不可打断的技能"。
 * 全程免伤（phaseLock），动画中点帧真正改阶段（applyTick），
 * 结束时清全部冷却（Ignis 的 resetAttacks 语义）。
 */
public final class PhaseChangeState implements State<ColossusBossEntity> {

    private final int targetPhase;
    private final int duration;
    private final int applyTick;

    public PhaseChangeState(ColossusBossEntity boss, int targetPhase) {
        this.targetPhase = targetPhase;
        this.duration = Math.max(20, boss.phaseTransitionTicks());
        this.applyTick = this.duration / 2;
    }

    @Override
    public void onStart(ColossusBossEntity boss) {
        boss.setPhaseLock(true);
        boss.syncAttackNone();
        com.klze.colossus.anim.ColossusAnims.firePhaseChangeStart(boss, targetPhase);
        boss.sendBossVisualEvent("colossus:phase_change_" + targetPhase);
    }

    @Override
    public Result onTick(ColossusBossEntity boss, ActiveState<ColossusBossEntity> self) {
        int tick = self.tick();
        if (tick == applyTick) {
            boss.applyPhase(targetPhase);
        }
        return tick >= duration ? Result.END : Result.CONTINUE;
    }

    @Override
    public void onEnd(ColossusBossEntity boss) {
        boss.setPhaseLock(false);
        boss.resetAttacks();
    }

    @Override
    public boolean isInterruptable(ColossusBossEntity boss) {
        return false;
    }

    @Override
    public String name() { return "phase->" + targetPhase; }
}
