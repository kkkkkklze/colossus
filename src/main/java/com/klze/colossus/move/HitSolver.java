package com.klze.colossus.move;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 线段扫掠命中求解器（TACZ {@code EntityKineticBullet} 配方的近战化）。
 *
 * <p>解决的正是 AABB 单帧判定的死角：本框架每逻辑 tick 只 advance 一次，
 * 若一帧内位移超过目标体积（高速冲刺挥击/远距离吐息），静态盒可能整个漏过目标。
 * 步骤（与 TACZ 一致）：
 * <ol>
 *   <li>线段 from→to 先被方块挡住（COLLIDER clip 截断有效段）；</li>
 *   <li>取线段的 AABB 沿路径 inflate 成"扫掠走廊"做粗筛；</li>
 *   <li>逐实体对截断后的线段做 {@code AABB.clip} 求精确命中点；</li>
 *   <li>按距离升序返回（穿透链的近→远次序）。</li>
 * </ol>
 */
public final class HitSolver {

    /** 一个命中：实体 + 线段上的命中点 + 距起点的距离平方。 */
    public record Target(LivingEntity entity, Vec3 hitPos, double distanceSq) {}

    private HitSolver() {}

    /**
     * @param inflate 走廊膨胀（实体盒外扩，容忍擦边）；建议 0.4~1.0
     * @param filter  通过粗筛后的自定义谓词
     */
    public static List<Target> sweep(Level level, Entity attacker, Vec3 from, Vec3 to,
                                     double inflate, Predicate<LivingEntity> filter) {
        Vec3 end = to;
        BlockHitResult block = level.clip(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, attacker));
        if (block != null && block.getType() == HitResult.Type.BLOCK) {
            end = block.getLocation();
        }
        AABB corridor = new AABB(from, end).inflate(inflate);
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, corridor,
                e -> e != attacker && e.isAlive() && !e.isSpectator() && filter.test(e));
        List<Target> hits = new ArrayList<>();
        for (LivingEntity e : candidates) {
            Optional<Vec3> clip = e.getBoundingBox().inflate(inflate * 0.5).clip(from, end);
            clip.ifPresent(p -> hits.add(new Target(e, p, from.distanceToSqr(p))));
        }
        hits.sort(Comparator.comparingDouble(Target::distanceSq));
        return hits;
    }
}
