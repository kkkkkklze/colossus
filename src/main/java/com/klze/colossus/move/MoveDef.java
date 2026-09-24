package com.klze.colossus.move;

import com.klze.colossus.state.FrameRunner;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * 一个招式（技能）的完整声明：时长/冷却/阶段门/距离门/权重/动画名/帧表。
 *
 * <p>前摇、判定、后摇不手写——由 {@code duration + 帧表} 推论：
 * 首个触发帧之前是前摇，触发帧当帧结算，duration 尾段是后摇。
 *
 * <p>字段全部只读，经 {@link MoveSetBuilder} 构建；id 用 {@link ResourceLocation}
 * 字符串做协议（拒绝数组索引协议——Cataclysm/Lionfish 的错位教训）。
 */
public final class MoveDef {

    private final ResourceLocation id;
    private final int duration;
    private final int cooldownTicks;
    private final int minPhase;
    private final int maxPhase; // 开区间上界，Integer.MAX_VALUE = 不限
    private final float range;  // 目标最大选招距离（格）；<=0 不限
    private final String animName;
    private final ToIntFunction<AttackContext> weightFn;
    private final Predicate<AttackContext> extraCheck;
    private final int postAttackInvuln;
    private final List<FrameRunner.Frame<com.klze.colossus.entity.ColossusBossEntity>> frames;

    MoveDef(ResourceLocation id, int duration, int cooldownTicks, int minPhase, int maxPhase,
            float range, String animName, ToIntFunction<AttackContext> weightFn,
            Predicate<AttackContext> extraCheck, int postAttackInvuln,
            List<FrameRunner.Frame<com.klze.colossus.entity.ColossusBossEntity>> frames) {
        this.id = id;
        this.duration = duration;
        this.cooldownTicks = cooldownTicks;
        this.minPhase = minPhase;
        this.maxPhase = maxPhase;
        this.range = range;
        this.animName = animName;
        this.weightFn = weightFn;
        this.extraCheck = extraCheck;
        this.postAttackInvuln = postAttackInvuln;
        this.frames = frames;
    }

    public ResourceLocation id() { return id; }
    /** 招式总时长（逻辑 tick，不含转场窗口）。 */
    public int duration() { return duration; }
    public int cooldownTicks() { return cooldownTicks; }
    public int minPhase() { return minPhase; }
    public int maxPhase() { return maxPhase; }
    public float range() { return range; }
    /** 客户端动画名（同步协议用字符串；无动画后端时可忽略）。 */
    public String animName() { return animName; }

    /** 选招准入：阶段门 + 距离门 + 自定义谓词。 */
    public boolean available(AttackContext ctx) {
        if (ctx.phase() < minPhase || ctx.phase() >= maxPhase) return false;
        if (range > 0 && ctx.distSq() > (double) range * range) return false;
        return extraCheck.test(ctx);
    }

    /** 上下文权重（<=0 视为不可选）。 */
    public int weight(AttackContext ctx) { return weightFn.applyAsInt(ctx); }

    /** 命中后写给目标的 invulnerableTime（ES 的打后无敌帧钩子；0=连段友好，默认）。 */
    public int postAttackInvuln() { return postAttackInvuln; }

    /** 本次执行的帧表（无状态模板——运行态由调用方 new 一个 FrameRunner）。 */
    public List<FrameRunner.Frame<com.klze.colossus.entity.ColossusBossEntity>> frames() { return frames; }

    /** 便捷：以本招式帧表建一个执行器。 */
    public FrameRunner<com.klze.colossus.entity.ColossusBossEntity> newRunner() {
        FrameRunner.Builder<com.klze.colossus.entity.ColossusBossEntity> b = FrameRunner.builder();
        for (FrameRunner.Frame<com.klze.colossus.entity.ColossusBossEntity> f : frames) {
            b.add(f); // 原样搬帧：走 between 重建会把持续帧的 period 丢掉（退化成一次性帧且无声）
        }
        return b.build();
    }
}
