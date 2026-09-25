package com.klze.colossus.move;

import com.klze.colossus.entity.ColossusBossEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * 选招上下文：权重函数与准入谓词的唯一输入。
 * 把它做成 record 是为了将来 JSON/表达式化时仍可序列重建。
 *
 * <p>{@code candidate} 是<b>当前正在被打分的这一招</b>（第二十批加）。没有它，
 * "最近用过的招要降权/禁用"这类判据就没法表达——{@code MoveSet.pick} 原先只建一次
 * ctx 就给全表复用，函数看不到自己在评价谁。库内 577 仓零实现这一格（v10 Q3），
 * 所以这个字段是框架自己长出来的，不是照抄。
 */
public record AttackContext(ColossusBossEntity boss, LivingEntity target, double distSq, MoveDef candidate) {

    /** 旧形态三参构造（候选位取空）：历史类判据在缺位时一律放行，不会误禁。 */
    public AttackContext(ColossusBossEntity boss, LivingEntity target, double distSq) {
        this(boss, target, distSq, null);
    }

    public double dist() { return Math.sqrt(distSq); }

    public int phase() { return boss.getPhase(); }

    /** 换成"正在评价 {@code move}"的那一份（record 不可变，逐候选重建而非改字段）。 */
    public AttackContext withCandidate(MoveDef move) {
        return this.candidate == move ? this : new AttackContext(this.boss, this.target, this.distSq, move);
    }

    /**
     * 候选招式在最近 {@code withinLast} 次出招里是否出现过。
     * 候选位空或没有实体（纯逻辑自检里构造的 ctx）时返回 false——即"没用过"，
     * 于是 {@code not_recent} 会放行，判据在无历史可查时不会锁死选招。
     */
    public boolean usedRecently(int withinLast) {
        if (this.boss == null || this.candidate == null) return false;
        return this.boss.usedRecently(this.candidate.id(), withinLast);
    }
}
