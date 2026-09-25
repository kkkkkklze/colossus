package com.klze.colossus.move;

import net.minecraft.resources.ResourceLocation;

/**
 * 最近若干次出招的环形历史（第二十批引入，第二十一批从实体里抽成纯件）。
 *
 * <p>为什么抽出来：留在实体里就只能靠真机 GameTest 观测，而它的核心语义
 * （低位＝最新、容量＝格数、"0 是空槽不是某招"、溢出丢最老那格）**全是纯算术**——
 * 抽出来才能进 {@code StateSelfTest} 被逐条钉住（审查轮 10 F4 的批评正是"整环查询对方向与
 * 容量都不敏感，删掉 +1 也不会红"）。
 *
 * <p>形状：{@value #SLOTS} 格 × {@value #BITS} 位正好铺满一个 long 的 64 位，
 * 于是 {@code (ring << BITS) | v} 的溢出天然丢掉最老一格，不需要取模或搬移。
 *
 * <p>槽的值域是 {@code 1..128}（{@code path.hashCode() & 0x7F} 再 +1）：
 * <b>0 必须是空槽</b>，否则 path hash 恰好为 0 的那一招（实测 {@code colossus:big}、
 * {@code colossus:aon}）在环还是空的时候就被读成"刚用过"，{@code notRecent} 会把它永久锁死。
 * 代价是 1/128 误判，方向只会"多禁用一次"。
 */
public final class MoveHistory {

    /** 记住最近几次出招。 */
    public static final int SLOTS = 8;
    private static final int BITS = 8;
    private static final long SLOT_MASK = (1L << BITS) - 1L;

    /** 值域上界（+1 之后）。必须严格小于 {@code 1L << BITS}，否则一个值会溅进相邻格。 */
    private static final long VALUE_MASK = 0x7FL;

    /**
     * 槽宽、值域与格数的关系是否自洽。轮 10 F4 的原始诉求："日后有人把 VALUE_MASK 抬成 0xFF
     * 又留着 +1，值域变成 1..256，256 会溅进相邻格 ⇒ 出现<b>该禁却没禁</b>的假阴性
     * （比多禁一次更糟——那个方向至少还在 tell 的范围内）。"
     *
     * <p><b>实现选择的诚实交代</b>（轮 11 抓出来的过度声称）：原先这里写的是 {@code static {}} 断言，
     * 但三个操作数都是编译期常量，javac 把 {@code if (false)} 整段折掉——反编译出的 class 里
     * 根本没有 {@code <clinit>}，它不是运行期保险丝。改成这个方法、由 {@code StateSelfTest} 钉住：
     * 真正防回归的是"改坏常数就自检红"，不是这段代码自己会炸。
     */
    public static boolean layoutSane() {
        return VALUE_MASK + 1 < (1L << BITS) && BITS * SLOTS == 64;
    }

    private long ring;

    /** 一次出招入环（最新的一格在低位）。 */
    public void record(ResourceLocation id) {
        this.ring = (this.ring << BITS) | valueOf(id);
    }

    /**
     * 最近 {@code withinLast} 次里是否出现过该招。
     *
     * <p>这里<b>不</b>做"越界即拒"：它跑在选招路径上（每 tick），抛异常等于把数据错误炸进战斗。
     * 需要硬约束的场合用 {@code MoveSetBuilder.MoveBuilder#notRecent}——那条在登记期就拒。
     */
    public boolean usedRecently(ResourceLocation id, int withinLast) {
        int n = Math.min(Math.max(1, withinLast), SLOTS);
        long v = valueOf(id);
        for (int i = 0; i < n; i++) {
            if (((this.ring >>> (BITS * i)) & SLOT_MASK) == v) return true;
        }
        return false;
    }

    /** 观测面：原始环（测试与诊断用，低位＝最新）。 */
    public long snapshot() { return this.ring; }

    /** 读档回填（只认 long，形状由 {@link #record} 保证）。 */
    public void restore(long snapshot) { this.ring = snapshot; }

    /** 清空（例如换阶段后不想带着旧账）。 */
    public void clear() { this.ring = 0L; }

    /**
     * 该招在环里的槽值。只数 path 段（同 ns 下 {@code colossus:smash} 与 {@code smash} 视为同一招），
     * 并刻意 +1 把 0 留给空槽 ⇒ 值域 {@code 1..128}，任何招都不会等于 0（空槽与"某招"不可能撞值）。
     */
    public static long valueOf(ResourceLocation id) {
        return (id.getPath().hashCode() & VALUE_MASK) + 1L; // +1：0 留给空槽
    }
}
