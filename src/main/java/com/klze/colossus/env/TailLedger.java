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
     * 记/换一条尾段。<b>同键覆盖</b>＝幂等；{@code rate<=0} 或 {@code end<=start} 的不记。
     * 溢出时逐出 {@code end} 最大（衰减最慢＝占账最久）的那条，而不是插入序最老的：
     * 最老的那条往往正是唯一合法的到期尾段。
     */
    public void book(long mirrorKey, int rate, int particleLife, long start, long end) {
        if (rate <= 0 || end <= start) return;
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
        int cap = (int) ((long) globalCeiling * TAIL_BUDGET_PERCENT / 100);
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
    }
}
