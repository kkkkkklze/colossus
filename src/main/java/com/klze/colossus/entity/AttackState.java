package com.klze.colossus.entity;

import com.klze.colossus.move.MoveDef;
import com.klze.colossus.state.ActiveState;
import com.klze.colossus.state.FrameRunner;
import com.klze.colossus.state.State;

/**
 * 招式执行状态：AttackState 是 MoveDef 的运行时壳。
 * onStart 写同步帧（ATTACK_ID / ATTACK_ANIM / ATTACK_DURATION / SEQ）并建帧表执行器，每 tick advance(tick) 触发帧表，
 * duration 用尽即 END。全程不可打断（"转段即技能、技能即状态"——Cataclysm 验证过的语义）。
 */
public final class AttackState implements State<ColossusBossEntity> {

    private final MoveDef move;
    /** {@code -1}＝正常起手；{@code >=0}＝从存档的"最后一次已执行逻辑帧"续播（第三十八批）。 */
    private final int resumeTickAt;
    private final long resumeBitmap;
    private FrameRunner<ColossusBossEntity> runner;
    /** 续播被判拒的理由（null＝没被拒）。走到这一支说明<b>实体侧的预检漏了</b>，是缺陷现场不是常态。 */
    private String resumeRejection;

    public AttackState(MoveDef move) {
        this(move, -1, 0L);
    }

    /**
     * 续播式构造：{@code tickAt}＝存档里的"最后一次已执行逻辑帧"，{@code bitmap}＝当时的已触发位图。
     * 两者都必须先过 {@link FrameRunner#resumeRejection(int, int, int, long, int, long, long)}
     * 再进来——{@code onStart} 里拒不掉（那时状态已经入栈），只能兜底缴械。
     */
    public AttackState(MoveDef move, int tickAt, long bitmap) {
        this.move = move;
        this.resumeTickAt = tickAt;
        this.resumeBitmap = bitmap;
    }

    public MoveDef move() { return move; }

    /** 当前已触发位图（存档侧要把它写进 NBT；{@code onStart} 之前为 0）。 */
    public long timelineBitmap() { return this.runner == null ? 0L : this.runner.firedBitmap(); }

    /** 续播被拒的理由；正常起手或续播成功都是 null。 */
    public String resumeRejection() { return this.resumeRejection; }

    @Override
    public void onStart(ColossusBossEntity boss) {
        this.runner = move.newRunner();
        if (this.resumeTickAt >= 0) {
            String bad = this.runner.resumeFrom(this.resumeTickAt, this.resumeBitmap, move.framesDigest());
            if (bad != null) {
                // <b>安全侧兜底，不是常态</b>：拒了也必须把已过窗口的帧记成已消费，
                // 否则 advance 会把这一发<b>整段重放</b>（存档错一位＝白挨一刀，这里是整发都挨）。
                // 掉帧（少打）比走帧（多打）温和，与本类"不补偿"的口径同向。
                this.resumeRejection = bad;
                this.runner.disarmUpTo(this.resumeTickAt);
                com.klze.colossus.Colossus.LOGGER.warn("boss {} refused to resume {} at frame {}: {}",
                        boss.getBossId(), move.id(), this.resumeTickAt, bad);
            }
        }
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
