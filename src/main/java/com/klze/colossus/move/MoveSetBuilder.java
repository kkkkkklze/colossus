package com.klze.colossus.move;

import com.klze.colossus.entity.ColossusBossEntity;
import com.klze.colossus.state.FrameRunner;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * 招式表构建器：一个 BOSS 的全部招式在此声明式登记。
 *
 * <pre>{@code
 * protected void registerMoves(MoveSetBuilder m) {
 *     m.move("smash").duration(40).cooldown(80).phase(0, 3).range(7)
 *      .between(24, 26, MoveTriggers.arcHit(6.5f, 90, 7f, 0.4f)) // 窗口判定帧
 *      .at(24, MoveTriggers.event("smash_ring"));                 // 演出帧（单帧够准）
 * }
 * }</pre>
 *
 * v0.3 计划：同结构走 datapack JSON（字段已按 Codec 友好排布）。
 */
public final class MoveSetBuilder {

    private final ResourceLocation bossId; // 招式 id 前缀 = BOSS 注册 id
    private final ColossusBossEntity boss;
    private final List<MoveDef> built = new ArrayList<>();
    private int created = 0; // move() 计数：忘调 done() 的招静默不入表是缺招事故（第四轮 P3#6）

    public MoveSetBuilder(ColossusBossEntity boss, ResourceLocation bossId) {
        this.boss = boss;
        this.bossId = bossId;
    }

    /** 收口：产出不可变招式表。 */
    public MoveSet build() {
        if (created != built.size()) {
            throw new IllegalStateException("boss " + bossId + ": " + created
                    + " move(s) started but only " + built.size() + " done() — 有招式忘了 .done()");
        }
        return new MoveSet(boss, new ArrayList<>(built));
    }

    public MoveBuilder move(String name) {
        created++;
        return new MoveBuilder(this, new ResourceLocation(bossId.getNamespace(), name));
    }

    public MoveBuilder move(ResourceLocation id) {
        created++;
        return new MoveBuilder(this, id);
    }

    void add(MoveDef def) { built.add(def); }

    /** 单条招式的流式构建面。 */
    public static final class MoveBuilder {
        private final MoveSetBuilder owner;
        private final ResourceLocation id;
        private int duration = 20;
        private int cooldown = 20;
        private int minPhase = 0;
        private int maxPhase = Integer.MAX_VALUE;
        private float range = -1f;
        private String animName;
        private ToIntFunction<AttackContext> weightFn = ctx -> 1;
        private Predicate<AttackContext> extraCheck = ctx -> true;
        private int postInvuln = 0;
        private final List<FrameRunner.Frame<ColossusBossEntity>> frames = new ArrayList<>();

        MoveBuilder(MoveSetBuilder owner, ResourceLocation id) {
            this.owner = owner;
            this.id = id;
            this.animName = id.getPath();
        }

        /** 总时长（逻辑 tick）。 */
        public MoveBuilder duration(int ticks) { this.duration = Math.max(1, ticks); return this; }
        /** 释放后的全局冷却。 */
        public MoveBuilder cooldown(int ticks) { this.cooldown = Math.max(0, ticks); return this; }
        /** 阶段门 [min,max)。 */
        public MoveBuilder phase(int min, int max) { this.minPhase = min; this.maxPhase = max; return this; }
        /** 目标距离门（≤range 格才可选）。 */
        public MoveBuilder range(float blocks) { this.range = blocks; return this; }
        /** 客户端动画名，默认取招式 path。 */
        public MoveBuilder anim(String name) { this.animName = name; return this; }
        /** 上下文权重函数（距离/阶段自适应选招）。 */
        public MoveBuilder weight(ToIntFunction<AttackContext> fn) { this.weightFn = fn; return this; }
        /** 固定权重。 */
        public MoveBuilder weight(int constant) { this.weightFn = ctx -> constant; return this; }
        /** 附加准入谓词。 */
        public MoveBuilder requires(Predicate<AttackContext> check) { this.extraCheck = this.extraCheck.and(check); return this; }

        /** 命中后写给目标的 invulnerableTime（打后无敌帧；默认 0 允许本招多帧连击）。 */
        public MoveBuilder postInvuln(int ticks) { this.postInvuln = Math.max(0, ticks); return this; }

        /** 单帧：第 tick 帧挂一个语义触发器（演出/音效用；判定建议用 {@link #between}）。 */
        public MoveBuilder at(int tick, MoveTrigger trigger) {
            return between(tick, tick, trigger);
        }

        /**
         * 窗口帧：tick 首次落入 [from,to] 时触发一次（Forsaken 式抗漂移判定）。
         * 错过窗口的帧不补偿执行——宁可漏一帧也不在错误时机打出伤害。
         * 帧号在<b>登记期</b>校验（审查 P1#3/P2#8：若推迟到出招建 runner 时才抛，
         * 异常正好落在 serverAiStep 里，持久 Boss 会崩溃循环）。
         */
        public MoveBuilder between(int from, int to, MoveTrigger trigger) {
            if (from < 1 || to < from) {
                throw new IllegalArgumentException(
                        "move " + id + ": bad frame window [" + from + "," + to + "] (frames start at 1)");
            }
            // duration 越界检查放到 done()——作者常先写 .between 再写 .duration（回归审查 P3#10 的 fluent 陷阱）
            frames.add(new FrameRunner.Frame<>(from, to, (boss, tick) -> trigger.execute(boss, tick)));
            return this;
        }

        /**
         * 持续帧（火墙/吐息/毒池形）：tick 在 [from,to] 内每 period 帧复触发一次。
         *
         * <p>这是"危险区每 tick 复判"的正解，也是审查轮 2 里撤回的 {@code once} 承诺的兑现位——
         * 触发器本身**不需要新类型**：{@code MoveTriggers.circleHit} 复触发＝持续掉血，
         * {@code arcHitContacted} 复触发＝区域内每人整招至多一次。
         * 即"新维度做成数据（帧参数），不做新子类"。
         */
        public MoveBuilder repeating(int from, int to, int period, MoveTrigger trigger) {
            if (from < 1 || to < from) {
                throw new IllegalArgumentException(
                        "move " + id + ": bad repeating window [" + from + "," + to + "] (frames start at 1)");
            }
            frames.add(FrameRunner.Frame.repeating(from, to, period,
                    (boss, tick) -> trigger.execute(boss, tick)));
            return this;
        }

        public MoveSetBuilder done() {
            for (FrameRunner.Frame<ColossusBossEntity> f : frames) {
                if (f.from() > duration) {
                    throw new IllegalArgumentException("move " + id + ": frame " + f.from()
                            + " exceeds duration " + duration + " (would never fire)");
                }
            }
            owner.add(new MoveDef(id, duration, cooldown, minPhase, maxPhase, range,
                    animName, weightFn, extraCheck, postInvuln, List.copyOf(frames)));
            return owner;
        }

        /** 直接终结构建并返回整套招式表（单招式 BOSS 的速记）。 */
        public MoveSet andBuild() {
            done();
            return owner.build();
        }
    }
}
