package com.klze.colossus.entity;

import com.klze.colossus.state.ActiveState;
import com.klze.colossus.state.State;
import net.minecraft.world.damagesource.DamageSource;

/**
 * 死亡演出状态（六样本的共同刚需）：
 * hp 归零被基类钉回 1.0 并挂起死亡 → 免死亡、清目标、停音乐、血条隐藏，
 * 演完 resolveDeath 的结算（击杀板/共享 credit/挑战计数）后才走真正的原版死亡序列。
 */
public final class DeathState implements State<ColossusBossEntity> {

    private final DamageSource source;
    private final int duration;
    private boolean resolved = false;

    public DeathState(ColossusBossEntity boss, DamageSource source) {
        this.source = source;
        this.duration = Math.max(1, boss.deathAnimationTicks());
    }

    @Override
    public void onStart(ColossusBossEntity boss) {
        boss.setTarget(null);
        boss.syncAttackNone();
        boss.rollDeathLoot(source); // INTO_CHEST：演出开场即 roll 入缓冲（TF 时序）
        boss.onDeathSequenceStart();
        com.klze.colossus.anim.ColossusAnims.fireDeathStart(boss);
        boss.sendBossVisualEvent("colossus:dying");
    }

    @Override
    public Result onTick(ColossusBossEntity boss, ActiveState<ColossusBossEntity> self) {
        boss.syncDeathTick(self.tick());
        if (self.tick() >= duration) {
            if (!resolved) {
                resolved = true;
                boss.resolveDeath(source);
            }
            return Result.END;
        }
        return Result.CONTINUE;
    }

    @Override
    public boolean isInterruptable(ColossusBossEntity boss) {
        return false;
    }

    @Override
    public String name() { return "death"; }
}
