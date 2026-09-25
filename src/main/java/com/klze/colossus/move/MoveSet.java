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

    /**
     * 设计意图提示的去重表（轮 11）。
     *
     * <p>构造发生在 {@code ColossusBossEntity#moveSet()} 的<b>惰性</b>路径上：不做去重，
     * 一农场 Boss 就会刷出 N 条一模一样的 warn（同文件里建表失败那条日志特意做了"只报一次"，
     * 这条不能更吵）。因此必须声明在构造器之前——字段初始化器要跑在那段代码之前。
     */
    private static final java.util.Set<String> FULLY_GATED_WARNED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    MoveSet(com.klze.colossus.entity.ColossusBossEntity boss, List<MoveDef> moves) {
        this.boss = boss;
        this.moves = List.copyOf(moves);
        String warnKey = boss != null ? String.valueOf(boss.getBossId()) : "<no-boss>";
        // 表里每条都挂上不小于表长的历史窗口 ＝ 作者其实想要的是"轮换"而不是"防重复"。
        // 引擎有保底（见 pick），不会因此空转，但这条设计意图值得说一句——静默兜底最容易让人
        // 一辈子没发现自己写的窗口等于禁用。
        if (!this.moves.isEmpty() && this.moves.stream()
                .allMatch(m -> m.notRecent() >= this.moves.size())
                && FULLY_GATED_WARNED.add(warnKey)) {
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

    /** 一轮过滤的产出：候选池、对应权重、总权重，以及"有几条是被历史门挡下的"。 */
    private record CandidatePool(List<MoveDef> pool, List<Integer> weights, long total, int historyBlocked) {}

    /**
     * 加权随机选招；全部不可用时返回 empty。cooldownLeft 由实体提供。
     *
     * <p>保底那一遍只放开<b>历史门</b>（审查轮 10 F1）：环形窗口只由"又出一次招"推进、
     * 光等待不消解封锁，所以引擎看不见历史门时挡空就是<b>不随时间愈合</b>的空窗——
     * 示范表实测能干站 70~140 tick，每条招都挂不小于表长的窗口时是永久死锁。
     * 阶段门/距离门/自定义谓词/冷却/权重≤0 第二遍照样硬拒，不会把"阶段未到"的招放出来。
     */
    public java.util.Optional<MoveDef> pick(AttackContext ctx, ToIntFunction<ResourceLocation> cooldownLeft,
                                            RandomSource random) {
        CandidatePool first = collect(ctx, cooldownLeft, false);
        // 只放开<b>真的被历史门挡住过</b>的情形：全表在 CD 或距离不对时挡空是常态（战斗里最常见），
        // 无条件重试就是每 tick 白跑一遍全表 + 让那句 debug 日志在跟历史无关的时候说谎（轮 11 #2）
        if (first.pool().isEmpty() && first.historyBlocked() > 0) {
            com.klze.colossus.Colossus.LOGGER.debug(
                    "move set empty with {} move(s) blocked by notRecent — retrying ignoring history",
                    first.historyBlocked());
            first = collect(ctx, cooldownLeft, true);
        }
        if (first.pool().isEmpty()) return java.util.Optional.empty();
        // 1.20.1 RandomSource 无 nextLong(bound)——权重实际远小于 int 域，钳位后走 nextInt
        int roll = random.nextInt((int) Math.min(first.total(), Integer.MAX_VALUE - 1L));
        for (int i = 0; i < first.pool().size(); i++) {
            roll -= first.weights().get(i);
            if (roll < 0) return java.util.Optional.of(first.pool().get(i));
        }
        return java.util.Optional.of(first.pool().get(first.pool().size() - 1));
    }

    /** 一趟过滤。ignoreHistory 只放开历史门：阶段/距离/自定义谓词/冷却/权重≤0 照样硬拒。 */
    private CandidatePool collect(AttackContext ctx, ToIntFunction<ResourceLocation> cooldownLeft,
                                  boolean ignoreHistory) {
        List<MoveDef> pool = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        long total = 0;
        int historyBlocked = 0;
        // long 累加：两个大权重 int 相加会溢出成负数炸 nextInt（审查 P2#10）
        for (MoveDef m : moves) {
            // 每个候选换成带 candidate 的那一份——not_recent / recent_band 都要知道"正在评价谁"。
            // 说准一点（轮 10）：产线 ctx 的 candidate 恒为空，所以这里<b>每候选确实会新建一个
            // record</b>，不是零分配；短命对象由逃逸分析吃掉，真要省得把 ctx 改成可变结构，不值得。
            AttackContext ctxM = ctx.withCandidate(m);
            if (!m.available(ctxM)) continue;
            if (!ignoreHistory && m.blockedByHistory(ctxM)) { historyBlocked++; continue; }
            if (cooldownLeft.applyAsInt(m.id()) > 0) continue;
            int w = m.weight(ctxM);
            if (w <= 0) continue;
            pool.add(m);
            weights.add(w);
            total += w;
        }
        return new CandidatePool(pool, weights, total, historyBlocked);
    }
}
