package com.klze.colossus.move;

import com.klze.colossus.entity.ColossusBossEntity;
import net.minecraft.world.entity.LivingEntity;

/**
 * 选招上下文：权重函数与准入谓词的唯一输入。
 * 把它做成 record 是为了将来 JSON/表达式化时仍可序列重建。
 */
public record AttackContext(ColossusBossEntity boss, LivingEntity target, double distSq) {

    public double dist() { return Math.sqrt(distSq); }

    public int phase() { return boss.getPhase(); }
}
