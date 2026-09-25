package com.klze.colossus.env;

/**
 * 危险区轮廓的<b>粒子预算</b>——纯算术，零 Minecraft 依赖。
 *
 * <p>为什么单独成一个类而不是留在 {@code client/TelegraphClient} 里（轮 18 设计偏差第 2 条）：
 * 这两道闸的判据必须在<b>最快的那道门</b>（{@code colossusSelfTest}）里能跑红。留在客户端类里，
 * 自检就要链接 {@code TelegraphClient → RingZoneRenderer → RenderType} 这条链——今天它能跑只是因为
 * {@code RenderType} 恰好只在方法体里被解析；哪天有人给渲染器加一句
 * {@code static final RenderType T = RenderType.LINES;}，红掉的是<b>整道门（现 128 条）</b>而不是那条断言。
 * 挪到这里之后，本类不 import 任何 MC 类型，门就再也扣不到运气上。
 *
 * <p><b>量纲</b>（这条轴连错四轮，写死在这儿）：成本 = <b>场上峰值存活数</b> =
 * {@code 每 tick 生成率 × 粒子自身寿命}。四个时间量必须分开，别混：
 * <ul>
 *   <li>{@code particleLife}：粒子自己在场上活几 tick（{@link #DUST_LIVE_TICKS} /
 *       {@link #SPARK_LIVE_TICKS}，从 vanilla 原文回读）——<b>成本与覆盖都除以它</b>；</li>
 *   <li>{@code elapsed}（{@code now - start}）：这颗圈<b>已经</b>撒了几 tick——决定当前真实存活
 *       （{@code min(粒子寿命, elapsed) × 率}），所以记账用"率 × 粒子寿命"是<b>峰值</b>口径，
 *       在出生的头几 tick 会略微高报，这个方向的误差是安全的；</li>
 *   <li>{@code remaining}（{@code end - now}）：还剩几 tick 停止发射——<b>不进成本</b>。
 *       轮 19 P2-1 抓到的正是把它当成本因子：那数的是"还要撒几个"，而只剩 1 tick 的大圈
 *       实际还占着 197 个场上粒子，记成 5 个就把全局闸绕过去了（低报 39 倍）；</li>
 *   <li>整发寿命 {@code end - start}：只用于到期与淡出（{@code TelegraphZone.lifetimeTicks()}）。</li>
 * </ul>
 * 另一个好处：率与槽位现在都是<b>每发常量</b>，不再逐 tick 变化 ⇒ 轮转的相位不会跳、
 * 角位映射不会重排（轮 19 P2-1 的副产物一并消失）。
 */
public final class TelegraphBudget {

    /** 每格圆弧铺几个槽位＝轮廓的视觉密度（表现选择，不是成本）。 */
    public static final double SLOTS_PER_BLOCK = 1.5D;
    public static final int MIN_SLOTS = 8;
    public static final int MAX_SLOTS = 96;

    /** 单条轮廓稳态允许的活跃粒子数。 */
    public static final int MAX_LIVE_PER_OUTLINE = 240;
    /**
     * 全部在途轮廓合计的活跃粒子天花板。
     *
     * <p>必须有：本版本 vanilla <b>没有</b>任何总量兜底——{@code ParticleEngine:74} 的
     * {@code MAX_PARTICLES_PER_LAYER} 声明后无人使用，{@code ParticleEngine#add(:326-337)} 只对
     * {@code getParticleGroup()} 非空的粒子查容量（dust/END_ROD 都不在任何 group 里），
     * 而框架用 {@code force=true} 又短路掉了 {@code LevelRenderer:2509-2514} 那两道事实上的闸。
     *
     * <p>额度不够时<b>按"最近一次投影变化"的顺序满足</b>（{@code TelegraphClient.ZONES} 是插入序，
     * 而每次快照变化都会先 {@code dropOwner} 再整批重插 ⇒ 这个顺序表达的是"最近被重投"，
     * <b>不是</b>"最先登记"——轮 19 P3-7 把先前那句"先登记的先满足"改准）。按距离排序要每帧分配
     * 并排一个数组，而这条闸存在的理由正是"别为了精确公平再引入新的成本"。
     */
    public static final int MAX_LIVE_GLOBAL = 2000;

    /**
     * dust 粒子在 scale=1.2 下的寿命上界。原文：{@code DustParticleBase:26-27}
     * {@code i = (int)(8.0 / (nextDouble()*0.8 + 0.2))}（⇒ i ∈ [8,40]）、
     * {@code lifetime = (int)max(i * getScale(), 1)}，本框架传 1.2f ⇒ <b>9..48</b>（40 万次采样均值 18.29）。
     */
    public static final int DUST_LIVE_TICKS = 48;
    /** END_ROD 的寿命上界：{@code EndRodParticle:16} 的 {@code 60 + nextInt(12)} ⇒ 60..71。 */
    public static final int SPARK_LIVE_TICKS = 71;

    /** 这一发的预算结果：画几个角位、每 tick 补几个、稳态占几个活跃粒子。 */
    public record Outline(int slots, int ratePerTick, int liveCost) {
        public static final Outline NONE = new Outline(0, 0, 0);
        public boolean empty() { return this.slots <= 0 || this.ratePerTick <= 0; }
    }

    /**
     * 唯一会拿到 END_ROD（{@link #SPARK_LIVE_TICKS}）的样式名。轮 18 P3-2：这个键原先只有消费点
     * （{@code TelegraphClient.particleFor} 里一个裸字面量），既不在词汇表里也不在文档里，
     * 想用它只能去读客户端源码。现在它是<b>公开词表项</b>：{@code TelegraphZone} 的 javadoc、
     * DESIGN §6.3 的 {@code visual} 那一行都指向这里。
     */
    public static final String SPARK_VISUAL = "spark";

    /**
     * 样式名 → 粒子自身寿命。只有 {@link #SPARK_VISUAL} 拿 END_ROD（{@link #SPARK_LIVE_TICKS}），
     * <b>其余一律 dust</b>（含未知名字）——与 {@code TelegraphClient.particleFor} 同一口径，
     * 也让"拼错样式名"不再是"静默选到贵 3.6 倍的那一档"（均值 65.5 对 18.3）。
     */
    public static int particleLifeTicks(String visual) {
        return SPARK_VISUAL.equals(visual) ? SPARK_LIVE_TICKS : DUST_LIVE_TICKS;
    }

    // 轮 19 P3-2/P3-4 一并解决：原先这里有个 remainingTicks(end, now) 把 span 钳到
    // MAX_WARN_TICKS + FADE_TICKS，用来防 (int)(end - start) 截断成负数。签名改掉之后
    // <b>没有任何地方再把 span 转成 int</b>（progress 走 double 除法、到期比较走 long 减法），
    // 那道钳与它"1210 就是最大值"的假口径一起删掉——showTelegraph 允许调用方给更长寿命，
    // 那句话与它冲突（轮 19 P3-2）。截断危险由"不存在转换"消除，而不是由一个数字上限掩盖。

    /**
     * 一颗<b>已经停止发射</b>的圈（轮廓到点被移出投影表）此刻还占着多少活跃粒子。
     *
     * <p>为什么需要它（轮 19 遗留的 post-mortem 滞留）：轮廓条目在 {@code end} 那一 tick 就没了，
     * 但它此前撒下的粒子还要再活最多 {@code 粒子寿命 - 1} tick。如果账本跟着条目一起清零，
     * "合计 ≤ 2000"这句就只在"圈还活着"的那段成立——高频出招时（一圈接一圈，每圈都在尾段留一堆
     * 余晖）真实场上量会高于账面值。本函数给出那段<b>线性衰减</b>的尾巴，客户端把它继续记账。
     *
     * @param ratePerTick   这颗圈生前的生成率
     * @param particleLife  粒子自身寿命
     * @param startGameTime 发射开始的绝对时刻
     * @param endGameTime   发射<b>停止</b>的绝对时刻（轮廓到期）
     * @param nowGameTime   当前时刻
     */
    public static int tailAlive(int ratePerTick, int particleLife,
                                long startGameTime, long endGameTime, long nowGameTime) {
        if (ratePerTick <= 0) return 0;
        long life = Math.max(1, particleLife);
        // 还活着的粒子来自区间 [now - life, now) 内的发射，与 [start, end) 求交：
        long from = Math.max(startGameTime, nowGameTime - life);
        long to = Math.min(endGameTime, nowGameTime);
        long span = to - from;
        if (span <= 0L) return 0;
        return ratePerTick * (int) Math.min(span, life);
    }

    /** 一条轮廓密度：圆周长 → 想要的槽位数（{@code 8..96}；NaN/0 走下限）。 */
    public static int ringSlotCount(double circumference) {
        double want = circumference * SLOTS_PER_BLOCK;
        if (!(want > MIN_SLOTS)) return MIN_SLOTS;
        return (int) Math.min(want, MAX_SLOTS);
    }

    /**
     * 一条轮廓这一 tick 的预算。<b>刻意不接受"剩余时间"这类会变的时间量</b>（轮 19 P2-1 的修法）：
     * 上一版把成本记成 {@code 率 × min(粒子寿命, 剩余 tick)}，那数的是"这颗圈<b>还要</b>撒几个"，
     * 而闸要限的是"场上<b>有</b>几个"——两者只差一个时间方向（未来 vs 过去），
     * 于是只剩 1 tick 的大圈被记 5 个、实际场上站着 197 个（低报 39 倍），全局闸形同没有。
     * 现在率、槽位、成本三个量都只依赖<b>每发常量</b>（周长、粒子寿命），
     * 因此"记的账"与"峰值存活"是同一个式子，且逐 tick 不变（自检正是钉这条不变性）。
     *
     * <p>顺带修掉两个副作用：槽位不再逐 tick 收缩（轮 19 P2-1 的"相位跳变"与"角位映射重排"随之消失）；
     * {@code byGlobal} 的除数与入账的乘数<b>同一个量</b>（都是粒子寿命），不再一个用 life 一个用 window。
     *
     * @param circumference   圆周长（决定想要的密度）
     * @param particleLife    {@link #particleLifeTicks} 的结果（<b>粒子</b>自己活几 tick）
     * @param startGameTime   这一发开始发射的绝对时刻
     * @param endGameTime     停止发射的绝对时刻（两者一起给"一生峰值"与覆盖窗口）
     * @param globalRemaining 全局天花板还剩多少；≤0 ⇒ 返回 {@link Outline#NONE}（本条不画）
     */
    public static Outline plan(double circumference, int particleLife,
                               long startGameTime, long endGameTime, int globalRemaining) {
        int life = Math.max(1, particleLife);
        int window = (int) Math.min((long) life, Math.max(1L, emissionSpan(startGameTime, endGameTime)));
        int want = ringSlotCount(circumference);
        int byOutline = MAX_LIVE_PER_OUTLINE / life; // 定义反解：率 = 存活上限 / 粒子寿命（<b>不</b>加地板 1）
        int byGlobal = Math.max(0, globalRemaining) / life;
        int budget = Math.min(byOutline, byGlobal);
        // 付不起一个 tick 一发（粒子寿命比上限还长）就不画，而不是"至少发一个"——后者会让
        // 单条就超上限（life=300 时 1×300 > 240）。今天 particleLifeTicks 只可能给 48/71 所以不显形，
        // 但这条边界必须有判据（轮 19 P2-2 第③条）。
        if (budget <= 0) return Outline.NONE;
        // 覆盖要求：每个角位都要在"上一发还没消失"之前被重新照亮 ⇒ slots / 率 <= min(粒子寿命, 发射窗口)。
        int need = (want + window - 1) / window;
        int rate = Math.min(Math.max(need, 1), budget);
        int slots = Math.min(want, rate * window);
        // 记账＝这颗圈<b>一生里最多同时</b>占用的粒子数：率 × min(粒子寿命, 发射窗口)。
        // 用发射窗口而不是"剩余 tick"（轮 19 P2-1）：剩余会逐 tick 变小，那数的是"还要撒几个"；
        // 用满粒子寿命也不对（轮 19 尾段断言逼出来的）：warn=0 的圈只发射 11 tick，
        // 记 48 会把短命圈虚报 4 倍多、把全局额度吃光。<b>一生峰值</b>既不会低估也不会虚高。
        return new Outline(slots, rate, rate * window);
    }

    /**
     * 发射窗口（{@code end - start}，非负、并在转 int 之前钳住）。
     * 轮 19 P3-4 的 {@code (int)} 截断危险在这里消除：先钳再转，而不是靠调用方记得兜。
     */
    public static long emissionSpan(long startGameTime, long endGameTime) {
        long span = endGameTime - startGameTime;
        if (span <= 0L) return 0L;
        return Math.min(span, MAX_SPAN_TICKS);
    }

    /** 发射窗口的安全上界：只用于防止 long→int 截断，不是"业务上最大的圈"。 */
    public static final long MAX_SPAN_TICKS = 1_000_000L;

    /**
     * 一颗"<b>还在发射</b>"的圈此刻实际占几个粒子：过去 {@code 粒子寿命} tick 内撒的那些还没消失
     * （出生之前没有），即 {@code 率 × min(粒子寿命, now - start)}。与 {@code liveCost}（一生峰值）
     * 同一个式子在 {@link #tailAlive} 里复用，所以两处不会各写一份算错。
     */
    public static int liveNow(int ratePerTick, int particleLife, long startGameTime, long endGameTime,
                              long nowGameTime) {
        return tailAlive(ratePerTick, particleLife, startGameTime, endGameTime, nowGameTime);
    }

    private TelegraphBudget() {}
}
