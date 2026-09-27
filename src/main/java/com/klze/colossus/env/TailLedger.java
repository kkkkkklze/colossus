package com.klze.colossus.env;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 危险区粒子的<b>尾段账本</b>——按"哪一条圈"记账的纯容器（轮 20 P1-1 之后从客户端里搬出来）。
 *
 * <p>为什么单独成类、还刻意做成不 import 任何 MC 类型：账本的两条性质<b>必须能在最快的门
 * （{@code colossusSelfTest}）里跑红</b>。它们住在 {@code TelegraphClient} 时就跑不了
 * （那道门链接不了渲染层），而 P1 恰好就是"记账性质错了但没有任何门看得见"——
 * {@code runGameTestServer} 是无头服务端，客户端渲染路径四条门一条都不执行。
 *
 * <p>两条性质：
 * <ol>
 *   <li><b>幂等</b>：一条圈（同一个 {@code mirrorKey}）在账本里最多一份账。
 *       上一版用无键队列，而客户端的 {@code dropOwner} 在"每次投影换实例"这条<b>常态</b>路径上
 *       也会被调用 ⇒ 一条圈每 publish 一次多一份账，账面变成 {@code (1 + 重投次数) × 峰值}，
 *       全局额度被幽灵撑爆，被饿死的恰好是最新登记的那一发（最需要被看见的预警）。</li>
 *   <li><b>装饰让路给安全件</b>：尾段（上一发的残影）合计只准占全局上限的
 *       {@value #TAIL_BUDGET_PERCENT}%"，超出部分不再占账。在途圈的预警是安全件，
 *       残影是装饰件——优先级不能反。</li>
 * </ol>
 */
public final class TailLedger {

    /** 尾段合计可占全局上限的百分比（轮 20 设计偏差第 1 条）。 */
    public static final int TAIL_BUDGET_PERCENT = 25;
    /** 账本条目数上限：防"每 tick 换一颗圈"这类异常输入把它撑大。 */
    public static final int MAX_ENTRIES = 64;

    /** 一条尾段：谁（rate）、活多久（particleLife）、从哪到哪（start/end）在发射。 */
    public record Tail(int rate, int particleLife, long start, long end) {}

    private final LinkedHashMap<Long, Tail> entries = new LinkedHashMap<>();

    /**
     * "这条圈真实发射到什么程度"的记录：[0]=历史<b>峰值</b>率 [1]=最后一次真发射的 tick。
     *
     * <p>为什么必须由账本自己存（轮 21 P2-3）：这两个事实原先寄居在 {@code TelegraphClient.Live}
     * 的 {@code lastRate} 上，而 {@code Live} <b>每次投影换实例都会重建</b>，并且 {@code plan.empty()}
     * 那一支还会把它显式清 0——于是"额度被别人抢光的那几 tick"里真撒过的粒子永久掉账，
     * 而那恰恰是最需要记账的时刻。峰值改存这里之后：漏记被堵住，窗口又能收窄到
     * {@code lastEmit + 1}（玩家走远后不再按名义 end 虚高）。
     */
    private final LinkedHashMap<Long, long[]> emissions = new LinkedHashMap<>();
    /**
     * 发射事实表的上界。<b>public 是给断言引的</b>（原先 private，测试只能把 128 写成字面量，
     * 常量与测试之间没有链接——审查轮 22 P3-6）。
     *
     * <p>推导（这条欠账原来只在提交信息里）：键空间是 {@code (bossId, viewId)}，
     * 单 Boss 的在途硬上界 {@code ColossusBossEntity.HARD_MAX_TELEGRAPHS = 32}，同屏 Boss 数框架不设限
     * ⇒ <b>4 个满配 Boss 正好踩线</b>。踩线时逐出"最后一次发射最老"的那些，它们的粒子早已散完
     * （{@code tailAlive} 本来就归零），所以丢它们不撒谎——这个论证必须跟着常量走，
     * 否则下一个人只会看到一个 128。
     */
    public static final int MAX_EMISSIONS = 128;

    /** 这条圈这一 tick 真的撒了 {@code rate} 个（记账的事实来源，不记就等于没有余晖）。 */
    public void noteEmission(long mirrorKey, int rate, long tick) {
        if (rate <= 0) return;
        long[] em = emissions.get(mirrorKey);
        if (em == null) {
            while (emissions.size() >= MAX_EMISSIONS) {
                // 逐出<b>最后一次发射时刻最老</b>的那条，不是插入序最老的（审查轮 22 §0 第 1 行确认这个方向）：
                // 插入序最老的完全可能仍在长预警发射中（warn=1200 的那一发），把它挤掉就等于
                // "这条圈退休后一帧都没记账"——而尾段漏记正是轮 20 P1 那一族里唯一<b>低报</b>（危险方向）
                // 的形态。按 lastEmit 逐出则只会丢掉"早就停发"的那些，它们的粒子已经散完，
                // tailAlive 本来就是 0，丢了不撒谎。
                // 上界 128 > 硬上界 32×同屏 Boss 数的常态，触发它需要异常输入，但触发时不能挑掉还在画的。
                long oldestKey = -1L;
                long oldestTick = Long.MAX_VALUE;
                for (Map.Entry<Long, long[]> e : emissions.entrySet()) {
                    if (e.getValue()[1] < oldestTick) {
                        oldestTick = e.getValue()[1];
                        oldestKey = e.getKey();
                    }
                }
                if (oldestKey < 0L) break;
                emissions.remove(oldestKey);
            }
            em = new long[]{rate, tick};
            emissions.put(mirrorKey, em);
        } else {
            em[0] = Math.max(em[0], rate);   // 峰值，不是最后一 tick 的值
            em[1] = Math.max(em[1], tick);
        }
    }

    /**
     * 退休一条圈：按账本自己记的发射事实生成尾段（同键覆盖＝幂等）。
     * 从没真发射过的圈<b>不记</b>——那是"圈登记了但一 tick 都没画"的形态，记了就是幽灵账。
     *
     * <p><b>本方法只负责"生成"，不负责"撤销别人的账"</b>：同一个键被调第二次（到期清扫与
     * 真离场两条路都走它）时发射事实已被第一次消费掉，这里返回 false 且<b>不动已入账的尾段</b>。
     * 那是对的——粒子确实还在世界里，账要继续记；而"这条圈又活了"的撤销只由 {@link #cancel}
     * 在重新入库那一点做（{@code TelegraphClient} 的 put 前），一个职责一个入口
     * （轮 20 P1-1 拆"一名两义"的同一口径，不许在这里合回去）。
     *
     * @param nominalEnd 快照里的名义到期时刻（真实发射终点只会更早，不会更晚）
     * @return 是否真的生成/替换了一条尾段
     */
    public boolean retire(long mirrorKey, int particleLife, long start, long nominalEnd) {
        long[] em = emissions.remove(mirrorKey);
        if (em == null) return false;
        // <b>记过发射就不许被时间量算出零长度窗口</b>（审查轮 22 P2-4）：`em[1]` 是客户端本地 tick，
        // `start` 是服务端登记时刻——{@code TelegraphClient} 明确允许"本地钟落后不超过一个寿命"照样画，
        // 于是 `min(名义 end, em[1]+1)` 可以 <= start，`book` 的 `end <= start` 那一支整条拒记，
        // 而发射事实已经在上一行被消费掉了 ⇒ <b>永久性低报</b>（粒子真撒过、账上一条没有）。
        // "要不要记"只由 `em == null`（从没发射过）那一处判；这里只保证已发生的事实一定能落成窗口。
        long end = Math.min(nominalEnd, Math.max(em[1] + 1L, start + 1L));
        return book(mirrorKey, (int) em[0], particleLife, start, end);
    }

    /** 仅供测试/诊断：这条圈有尾段在账上吗（轮 21 P2-4：逐出策略必须有能断言的口子）。 */
    public boolean contains(long mirrorKey) {
        return entries.containsKey(mirrorKey);
    }

    /** 不截断的余晖合计（用来把"真实残影"与"账面占用"两个量分开钉）。 */
    public int uncappedSum(long now) {
        int sum = 0;
        for (Tail tail : entries.values()) {
            sum += TelegraphBudget.tailAlive(tail.rate(), tail.particleLife(), tail.start(), tail.end(), now);
        }
        return sum;
    }

    /**
     * 记/换一条尾段。<b>同键覆盖</b>＝幂等；{@code rate<=0} 或 {@code end<=start} 的不记并返回 false。
     * 溢出时逐出 {@code end} 最大（衰减最慢＝占账最久）的那条，而不是插入序最老的：
     * 最老的那条往往正是唯一合法的到期尾段。
     *
     * <p>返回值必须由 {@link #retire} 原样交出（审查轮 22 P3-5）：原先 {@code retire} 用
     * {@code entries.containsKey(mirrorKey)} 反推"我记上了没有"，而同键覆盖语义下这条判据
     * 会被<b>上一轮留下的账</b>骗到（被拒入账却仍返回 true）。让调用方拿到真话只有一条路：
     * 让入账点自己说。
     */
    private boolean book(long mirrorKey, int rate, int particleLife, long start, long end) {
        if (rate <= 0 || end <= start) return false;
        entries.put(mirrorKey, new Tail(rate, particleLife, start, end));
        while (entries.size() > MAX_ENTRIES) {
            long worstKey = -1L;
            long worstEnd = Long.MIN_VALUE;
            for (Map.Entry<Long, Tail> e : entries.entrySet()) {
                if (e.getValue().end() > worstEnd) {
                    worstEnd = e.getValue().end();
                    worstKey = e.getKey();
                }
            }
            if (worstKey < 0L) break;
            entries.remove(worstKey);
        }
        // <b>入账之后还要看它有没有被这一批的溢出逐出</b>：新来的那条如果 end 最大，
        // 上面那个 while 会立刻把它自己丢掉——那时说"记上了"就是第二次说谎（轮 22 P3-5 的同一处）。
        return entries.containsKey(mirrorKey);
    }

    /** 某条圈又活了（重新进投影）：它的账改由在途那份记，尾段必须撤，否则双份。 */
    public void cancel(long mirrorKey) {
        entries.remove(mirrorKey);
    }

    /**
     * 本 tick 尾段合计应占的额度：逐条算实际存活（{@link TelegraphBudget#tailAlive}），
     * 已经散干净（返回 0）的条目顺手剔除，最后<b>截到子额度</b>。
     */
    public int bookNow(long nowGameTime, int globalCeiling) {
        // 负 ceiling 传进来时比例也要非负：否则 cap 为负 ⇒ 返回负数 ⇒ 调用方
        // "MAX_LIVE_GLOBAL - 账面"反而<b>放大</b>准入（轮 21 P3-5）
        int cap = Math.max(0, (int) ((long) Math.max(0, globalCeiling) * TAIL_BUDGET_PERCENT / 100));
        int sum = 0;
        Iterator<Map.Entry<Long, Tail>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Tail tail = it.next().getValue();
            if (tail.rate() <= 0) { it.remove(); continue; }
            int left = TelegraphBudget.tailAlive(tail.rate(), tail.particleLife(), tail.start(), tail.end(),
                    nowGameTime);
            if (left <= 0) { it.remove(); continue; }
            sum = Math.min(cap, sum + left);
        }
        return sum;
    }

    /** 当前条目数（诊断与自检用）。 */
    public int size() {
        return entries.size();
    }

    /** 整批作废：换维度/登出时粒子系统重建，留着就是幽灵账。 */
    public void clear() {
        entries.clear();
        emissions.clear(); // 两级一起清：换维度/登出后连"真发射过什么"都不该留
    }
}
