package com.klze.colossus.move;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 不可变招式集合 + 加权选招器。
 * 选招 = 过滤（阶段/距离/谓词/冷却）→ 上下文权重 → 加权随机。
 * 没有中央 if-else 瀑布：一切是数据。
 */
public final class MoveSet {

    private final List<MoveDef> moves;
    private final com.klze.colossus.entity.ColossusBossEntity boss;

    MoveSet(com.klze.colossus.entity.ColossusBossEntity boss, List<MoveDef> moves) {
        this.boss = boss;
        this.moves = List.copyOf(moves);
        // 表里每条都挂上不小于表长的历史窗口 ＝ 作者其实想要的是"轮换"而不是"防重复"。
        // 引擎有保底（见 pick），不会因此空转，但这条设计意图值得说一句——静默兜底最容易让人
        // 一辈子没发现自己写的窗口等于禁用。
        if (!this.moves.isEmpty() && this.moves.stream()
                .allMatch(m -> m.notRecent() >= this.moves.size())) {
            com.klze.colossus.Colossus.LOGGER.warn("每张招都挂 notRecent>=表长({})——选招将长期依赖"
                    + "「挡空后放开历史门」的保底；要真轮换请显式设计冷却/权重", this.moves.size());
        }
    }

    public List<MoveDef> moves() { return moves; }

    /**
     * Java DSL 为底 + datapack 同名覆盖（第十四批）。
     *
     * <p>覆盖而非并存：同名两条都进表的话，{@code byId} 取首个＝谁先注册看运气，
     * 而整合包作者改 JSON 的本意就是"我要换掉这一招"。覆盖必然打日志——不留静默改命。
     */
    public static MoveSet merge(com.klze.colossus.entity.ColossusBossEntity boss,
                                List<MoveDef> javaDefs, List<MoveDef> dataDefs) {
        java.util.Map<ResourceLocation, MoveDef> byId = new java.util.LinkedHashMap<>();
        for (MoveDef m : javaDefs) byId.put(m.id(), m);
        for (MoveDef m : dataDefs) {
            if (byId.put(m.id(), m) != null) {
                com.klze.colossus.Colossus.LOGGER.warn(
                        "datapack move {} overrides the Java-defined move with the same id", m.id());
            }
        }
        return new MoveSet(boss, new ArrayList<>(byId.values()));
    }

    public MoveDef byId(ResourceLocation id) {
        for (MoveDef m : moves) {
            if (m.id().equals(id)) return m;
        }
        return null;
    }

    /** 加权随机选招；全部不可用时返回 empty。cooldownLeft 由实体提供。 */
    public java.util.Optional<MoveDef> pick(AttackContext ctx, ToIntFunction<ResourceLocation> cooldownLeft, RandomSource random) {
        return pick(ctx, cooldownLeft, random, false);
    }

    /**
     * @param ignoreHistory 第二遍（保底）：<b>只</b>忽略历史门。
     *
     * <p>为什么必须有这层（审查轮 10 F1）：环形窗口只由"又出了一次招"推进，光等待不消解封锁，
     * 所以引擎看不见历史门时，挡空就是<b>不随时间愈合</b>的空窗——示范表实测能干站 70~140 tick，
     * 每条招都挂上不小于表长的窗口时是永久死锁。保底只放开历史门：
     * 阶段门/距离门/自定义谓词/冷却/权重≤0 照样硬拒，不会把"阶段未到"的招放出来。
     */
    private java.util.Optional<MoveDef> pick(AttackContext ctx, ToIntFunction<ResourceLocation> cooldownLeft,
                                             RandomSource random, boolean ignoreHistory) {
        List<MoveDef> pool = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        long total = 0; // long 累加：两个大权重 int 相加会溢出成负数炸 nextInt（审查 P2#10）
        for (MoveDef m : moves) {
            // 每个候选换成带 candidate 的那一份——not_recent / recent_band 都要知道"正在评价谁"。
            // 说准一点（轮 10）：产线 ctx 的 candidate 恒为空，所以这里<b>每候选确实会新建一个
            // record</b>，不是零分配；短命对象由逃逸分析吃掉，真要省得把 ctx 改成可变结构，不值得。
            AttackContext ctxM = ctx.withCandidate(m);
            if (!m.available(ctxM)) continue;
            if (!ignoreHistory && m.blockedByHistory(ctxM)) continue;
            if (cooldownLeft.applyAsInt(m.id()) > 0) continue;
            int w = m.weight(ctxM);
            if (w <= 0) continue;
            pool.add(m);
            weights.add(w);
            total += w;
        }
        if (pool.isEmpty()) {
            if (!ignoreHistory && !moves.isEmpty()) {
                com.klze.colossus.Colossus.LOGGER.debug(
                        "move set empty with history gates on — retrying ignoring notRecent");
                return pick(ctx, cooldownLeft, random, true);
            }
            return java.util.Optional.empty();
        }
        // 1.20.1 RandomSource 无 nextLong(bound)——权重实际远小于 int 域，钳位后走 nextInt
        int roll = random.nextInt((int) Math.min(total, Integer.MAX_VALUE - 1L));
        for (int i = 0; i < pool.size(); i++) {
            roll -= weights.get(i);
            if (roll < 0) return java.util.Optional.of(pool.get(i));
        }
        return java.util.Optional.of(pool.get(pool.size() - 1));
    }
}
