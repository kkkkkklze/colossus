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
        return new MoveSet(boss, new ArrayList<>(builtDefs()));
    }

    /**
     * 只取 DSL 产出的定义列表（datapack 合并路径用——同一套校验，不另开一条无检查的口子）。
     */
    public List<MoveDef> builtDefs() {
        if (created != built.size()) {
            throw new IllegalStateException("boss " + bossId + ": " + created
                    + " move(s) started but only " + built.size() + " done() — 有招式忘了 .done()");
        }
        return List.copyOf(built);
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
        private int notRecent = 0;
        private int postInvuln = 0;
        private final List<FrameRunner.Frame<ColossusBossEntity>> frames = new ArrayList<>();

        MoveBuilder(MoveSetBuilder owner, ResourceLocation id) {
            this.owner = owner;
            this.id = id;
            this.animName = id.getPath();
        }

        /**
         * 总时长（逻辑 tick）。<b>0 与负数直接拒</b>（轮 13 P2-2）：旧写法静默 {@code Math.max(1, ticks)}，
         * 作者写错一个 0 得到的是一招"只有一帧"的招，而不是报错——JSON 侧同字段一直是拒的。
         * 值域的正主仍是 {@link MoveDef} 构造器（两条入口共用），这里只是把报错点搬到作者那一行。
         */
        public MoveBuilder duration(int ticks) {
            if (ticks < 1) {
                throw new IllegalArgumentException("move " + id + ": duration must be >= 1 tick, got " + ticks
                        + " (a zero-length move is never what you meant to write)");
            }
            this.duration = ticks;
            return this;
        }
        /** 释放后的全局冷却。 */
        public MoveBuilder cooldown(int ticks) { this.cooldown = Math.max(0, ticks); return this; }
        /**
         * 阶段门 {@code [min,max)}。{@code max <= min}（含 {@code phase(3,1)} 这种写反的）当场拒：
         * 那是一条恒假门，招式永远不可选且<b>一点日志都没有</b>——选招路径把"不可用"当常态。
         */
        public MoveBuilder phase(int min, int max) {
            if (max <= min) {
                throw new IllegalArgumentException("move " + id + ": phase band must satisfy max > min, got ["
                        + min + "," + max + ") — that is a permanently-unsatisfiable gate");
            }
            this.minPhase = min;
            this.maxPhase = max;
            return this;
        }
        /** 目标距离门（≤range 格才可选）。 */
        public MoveBuilder range(float blocks) { this.range = blocks; return this; }
        /** 客户端动画名，默认取招式 path。 */
        /** 动画名不许空：值域判据的正主是 {@link MoveDef} 构造器（DSL/JSON 同一个闸门），
         *  这里只是把报错点提前到<b>作者自己那一行</b>，消息里带招式名，省得去栈里找。 */
        public MoveBuilder anim(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("move " + id + ": anim name must be non-blank"
                        + "（客户端拿它当取动画的键，空串＝静默不播）");
            }
            this.animName = name;
            return this;
        }
        /**
         * 上下文权重函数（距离/阶段自适应选招）。
         *
         * <p><b>必须是 {@code ctx} 的纯函数</b>——{@link MoveSet#pick} 在保底那一遍会对<b>同一张表</b>
         * 再求值一次，带状态的实现（读随机数、读外部可变字段）会让两遍结论毫无关系，
         * 那句"被历史门挡空"的日志与"重试池必非空"的前提就都不成立了（轮 13 P3-5）。
         */
        public MoveBuilder weight(ToIntFunction<AttackContext> fn) { this.weightFn = fn; return this; }
        /** 固定权重。 */
        public MoveBuilder weight(int constant) { this.weightFn = ctx -> constant; return this; }
        /**
         * 附加准入谓词（同样<b>必须是对 {@code ctx} 的纯函数</b>，理由见 {@link #weight(ToIntFunction)}）。
         *
         * <p>注意它是引擎<b>看不见</b>的门：整表都挂这种谓词时，{@link MoveSet#pick} 的保底放不开
         * （保底只放开历史门）。要"最近用过就别选我"请走 {@link #notRecent(int)}，那是数据字段。
         */
        public MoveBuilder requires(Predicate<AttackContext> check) { this.extraCheck = this.extraCheck.and(check); return this; }

        /**
         * 历史准入：最近 {@code window} 次出招里放过本招，这次就不选（防背板）。
         *
         * <p>与 {@code requires(ctx -> !ctx.usedRecently(n))} 的区别是这条<b>引擎看得见</b>：
         * {@link MoveSet#pick} 在整表被历史挡空时会忽略历史门再选一次。环形窗口只由出招推进、
         * 等待不消解，看不见它的引擎会让 Boss 出现不随时间愈合的空窗甚至永久死锁（审查轮 10 F1）。
         *
         * <p>跨招互斥（"刚放过 A 才准放 B"）必须用 {@code requires(ctx -> !ctx.boss().usedRecently(A, n))}
         * 这种<em>指名别的招</em>的写法。本方法的 {@code ctx.usedRecently(n)} 读的就是<b>候选自己</b>，
         * 与它同义，所以再叠一道只会把这一招锁得更死：{@code n <= window} 时是恒假门，
         * {@code n > window} 时是"比本方法更严的门"（轮 12 F1：§2.2 的样例就自相矛盾过——
         * 上一版这里写成"恒假"是说过头了，两种情形都算清楚才是实话）。
         */
        public MoveBuilder notRecent(int window) {
            if (window < 1 || window > MoveHistory.SLOTS) {
                throw new IllegalArgumentException("move " + id + ": notRecent window must be 1.."
                        + MoveHistory.SLOTS + ", got " + window);
            }
            this.notRecent = window;
            return this;
        }

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
            String bad = FrameRunner.windowError(from, to, 0);
            if (bad != null) {
                throw new IllegalArgumentException("move " + id + ": " + bad);
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
            String bad = FrameRunner.windowError(from, to, period);
            if (bad != null) {
                throw new IllegalArgumentException("move " + id + ": " + bad);
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
                    animName, weightFn, extraCheck, notRecent, postInvuln, List.copyOf(frames)));
            return owner;
        }

        /** 直接终结构建并返回整套招式表（单招式 BOSS 的速记）。 */
        public MoveSet andBuild() {
            done();
            return owner.build();
        }
    }
}
