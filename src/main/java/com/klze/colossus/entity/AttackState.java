package com.klze.colossus.entity;

import com.klze.colossus.move.MoveDef;
import com.klze.colossus.state.ActiveState;
import com.klze.colossus.state.FrameRunner;
import com.klze.colossus.state.State;

/**
 * 招式执行状态：AttackState 是 MoveDef 的运行时壳。
 * onStart 写同步帧（ATTACK_INDEX）并建帧表执行器，每 tick advance(tick) 触发帧表，
 * duration 用尽即 END。全程不可打断（"转段即技能、技能即状态"——Cataclysm 验证过的语义）。
 */
public final class AttackState implements State<ColossusBossEntity> {

    private final MoveDef move;
    private FrameRunner<ColossusBossEntity> runner;

    public AttackState(MoveDef move) {
        this.move = move;
    }

    public MoveDef move() { return move; }

    @Override
    public void onStart(ColossusBossEntity boss) {
        this.runner = move.newRunner();
        boss.beginAttack(move);
        com.klze.colossus.anim.ColossusAnims.fireAttackStart(boss, move); // 显示层缝（GL4=triggerAnim）
    }

    @Override
    public Result onTick(ColossusBossEntity boss, ActiveState<ColossusBossEntity> self) {
        int tick = self.tick();
        boss.syncAttackTick(tick);
        // 面朝目标（锁身体不锁头——StateGoal 已接管 MOVE/LOOK）
        if (boss.getTarget() != null && boss.getTarget().isAlive()) {
            boss.lookAtTarget(boss.getTarget());
        }
        runner.advance(boss, tick);
        return tick >= move.duration() ? Result.END : Result.CONTINUE;
    }

    @Override
    public void onEnd(ColossusBossEntity boss) {
        boss.endAttack(move);
    }

    @Override
    public boolean isInterruptable(ColossusBossEntity boss) {
        return false;
    }

    @Override
    public String name() { return "attack:" + move.id(); }
}
