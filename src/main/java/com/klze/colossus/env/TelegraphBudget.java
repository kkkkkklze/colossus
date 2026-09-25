package com.klze.colossus.env;

/**
 * 危险区轮廓的<b>粒子预算</b>——纯算术，零 Minecraft 依赖。
 *
 * <p>为什么单独成一个类而不是留在 {@code client/TelegraphClient} 里（轮 18 设计偏差第 2 条）：
 * 这两道闸的判据必须在<b>最快的那道门</b>（{@code colossusSelfTest}）里能跑红。留在客户端类里，
 * 自检就要链接 {@code TelegraphClient → RingZoneRenderer → RenderType} 这条链——今天它能跑只是因为
 * {@code RenderType} 恰好只在方法体里被解析；哪天有人给渲染器加一句
 * {@code static final RenderType T = RenderType.LINES;}，红掉的是<b>整道 118 条的门</b>而不是那条断言。
 * 挪到这里之后，本类不 import 任何 MC 类型，门就再也扣不到运气上。
 *
 * <p><b>量纲</b>（这是连两轮审查都在同一处抓到的东西，写死在这儿）：
 * 成本 = <b>稳态活跃粒子数</b> = {@code 每 tick 生成率 × 粒子自身寿命}。
 * 三个"寿命"是不同的量，别混：
 * <ul>
 *   <li>{@code particleLife}：粒子自己在场上活几 tick（{@link #DUST_LIVE_TICKS} /
 *       {@link #SPARK_LIVE_TICKS}，从 vanilla 原文回读）——<b>成本用这个</b>；</li>
 *   <li>{@code remainingTicks}：这一发轮廓还剩几 tick 停止发射（{@code end - now}）——
 *       <b>公平性与"多久把圈铺满"用这个</b>；</li>
 *   <li>整发寿命 {@code end - start}：只在"这一发的总账"里才有意义，既不是成本也不是铺满窗口。</li>
 * </ul>
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
     * <p>额度不够时<b>先登记的先满足</b>（{@code TelegraphClient.ZONES} 是插入序）：按距离排序要每帧
     * 分配并排一个数组，而这条闸存在的理由正是"别为了精确公平再引入新的成本"。
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

    /**
     * 这一发<b>还剩</b>几 tick 会停止发射（{@code end - now}，钳到 {@code 0..上限}）。
     *
     * <p>钳在这里而不是让调用方各自 {@code (int)(end - start)}（轮 18 P3-4）：{@code start}/{@code end}
     * 是两个裸 long，坏存档给 {@code end - start = 2^32-1} 时 {@code (int)} 截断会变成<b>负数</b>，
     * 于是"寿命"这侧的所有除法语义全废。上限取 {@code TelegraphZone.MAX_WARN_TICKS + FADE_TICKS}
     * ＝服务端能登记的最大值，再多就是坏数据。
     */
    public static int remainingTicks(long endGameTime, long nowGameTime) {
        long remaining = endGameTime - nowGameTime;
        if (remaining <= 0L) return 0;
        return (int) Math.min(remaining, com.klze.colossus.env.TelegraphZone.MAX_WARN_TICKS
                + com.klze.colossus.env.TelegraphZone.FADE_TICKS);
    }

    /** 视觉密度：圆周长 → 想要的槽位数（{@code 8..96}；NaN/0 走下限）。 */
    public static int ringSlotCount(double circumference) {
        double want = circumference * SLOTS_PER_BLOCK;
        if (!(want > MIN_SLOTS)) return MIN_SLOTS;
        return (int) Math.min(want, MAX_SLOTS);
    }

    /**
     * 一条轮廓这一 tick 的预算。
     *
     * @param circumference  圆周长（决定想要的密度）
     * @param remainingTicks 这一发<b>还剩</b>几 tick 停止发射（不是整发寿命，见类注释）
     * @param particleLife   {@link #particleLifeTicks} 的结果
     * @param globalRemaining 全局天花板还剩多少；≤0 ⇒ 返回 {@link Outline#NONE}（本条不画）
     */
    public static Outline plan(double circumference, int remainingTicks, int particleLife, int globalRemaining) {
        int life = Math.max(1, particleLife);
        int window = Math.min(life, Math.max(1, remainingTicks)); // 多久把圈铺满：粒子寿命与剩余时间里短的那个
        int want = ringSlotCount(circumference);
        int byOutline = Math.max(1, MAX_LIVE_PER_OUTLINE / life); // 定义反解：率 = 存活上限 / 粒子寿命
        int byGlobal = Math.max(0, globalRemaining / life);       // 剩余额度折算成"每 tick 几个"
        int budget = Math.min(byOutline, byGlobal);
        if (budget <= 0) return Outline.NONE;
        int need = (want + window - 1) / window;                  // 铺满 want 个槽位所需的最小率
        int rate = Math.min(Math.max(need, 1), budget);
        // 预算铺不满想要的密度时就少画几个角位，而不是画一整圈的 1/5 弧——"合不上圈"的根因在这里：
        // 槽位数必须与预算一起降，否则玩家看到的是几段螺旋而不是一圈（轮 18 P2-1 的反方向那一半）。
        int slots = Math.min(want, Math.max(rate, rate * window));
        if (rate > slots) rate = Math.max(1, slots);              // 同一 tick 不重复同一个角位
        // 峰值存活数＝率 × 实际发射的时长（粒子寿命与剩余时间里的短的那个）：
        // 还剩 2 tick 的旧圈不该和刚登记的圈占同样多的额度（轮 18 P2-1 的公平性那一半）。
        return new Outline(slots, rate, rate * window);
    }

    private TelegraphBudget() {}
}
