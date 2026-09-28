package com.klze.colossus.state;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 确定性帧表执行器——纯逻辑、零 MC 依赖（可进 StateSelfTest）。
 *
 * <p>裁决依据（第二轮调研）：Alex's Caves Forsaken 在 1.20.1 上用
 * "tick ∈ [a,b] 窗口"做命中判定而非单帧等值——双端各自计 tick 时，
 * 窗口能吸收偶发的 tick 延迟错位，而单帧判等一旦错过就永久漏判。
 *
 * <p>语义：
 * <ul>
 *   <li>{@link #add(int, BiConsumer)} 单帧：tick 恰等时触发（错过不补）。</li>
 *   <li>{@link #add(int, int, BiConsumer)} 窗口帧：tick 首次落入 [from,to] 时触发一次。</li>
 *   <li>{@link Builder#repeating} 持续帧：窗口内每 period 帧复触发（火墙/吐息/毒池这类
 *       "区域一直在那儿"的招）。判定式 {@code (tick - from) % period == 0}，
 *       所以 lag 跳帧只会**少触发**、不会补触发、也不会同 tick 双触发。</li>
 *   <li>每个一次性 entry 至多触发一次；{@link #advance} 按添加序遍历，
 *       把本 tick 应触发的帧按注册顺序交给消费者（消费者自行决定是否消费窗口外的帧——
 *       本类只按 from/to 判定，不做"跳到窗口之后"的补偿执行）。</li>
 *   <li>tick 必须单调不减地传入；倒退输入被忽略（防重放）。</li>
 * </ul>
 *
 * @param <C> 触发时的上下文类型（框架里是 ColossusBossEntity）
 */
public final class FrameRunner<C> {

    /**
     * 窗口形态的唯一判据（null=合法）。<b>Java DSL 与 datapack 必须同调这一处</b>——
     * 轮 6 P2#2：JSON 侧当时只查了 to>=from，`{"between":[9,2]}` 会静默成死帧
     * （回执 0 错、这招永远没伤害），而 DSL 侧两条都拒。
     *
     * @param period 0=一次性帧；持续帧必须 >=1
     */
    public static String windowError(int from, int to, int period) {
        if (from < 1) return "frame " + from + ": frames start at 1";
        if (to < from) return "window [" + from + "," + to + "]: to < from";
        // period==0 只表示"不是持续帧"——窗口帧（to>from）同样用 0，这里绝不能反过来禁掉
        if (period < 0) return "period " + period + ": negative";
        return null;
    }

    /**
     * 一帧：[from,to] 窗口 + 周期 + 触发回调（参数 = 上下文 + 触发 tick）。
     * {@code to==from} 即单帧；{@code period>0} 即持续帧（窗口内每 period 触发一次）。
     */
    public record Frame<C>(int from, int to, int period, BiConsumer<C, Integer> action) {

    /** 一次性帧（单帧或窗口帧）。 */
        public Frame(int from, int to, BiConsumer<C, Integer> action) {
            this(from, to, 0, action);
        }

        /** 持续帧：窗口判据同 {@link #windowError}，另加"period 必须 >=1"（这条只属于持续帧）。 */
        public static <C> Frame<C> repeating(int from, int to, int period, BiConsumer<C, Integer> action) {
            String bad = windowError(from, to, period);
            if (bad == null && period < 1) bad = "repeating frame needs period >= 1, got " + period;
            if (bad != null) throw new IllegalArgumentException(bad);
            return new Frame<>(from, to, period, action);
        }

        public boolean repeating() {
            return this.period > 0;
        }
    }

    private final List<Frame<C>> frames;
    private final boolean[] fired;
    /** 帧表指纹（见 {@link #framesDigest()}）——构造时算一次，续播时整份快照的合法性靠它。 */
    private final long framesDigest;
    private int lastTick = Integer.MIN_VALUE;

    private FrameRunner(List<Frame<C>> frames) {
        this.frames = frames;
        this.fired = new boolean[frames.size()];
        this.framesDigest = digestOf(frames);
    }

    public static <C> Builder<C> builder() { return new Builder<>(); }

    /** 帧表构建器（注册序即触发序）。 */
    public static final class Builder<C> {
        private final List<Frame<C>> frames = new ArrayList<>();

        /** 单帧：第 tick 帧触发。 */
        public Builder<C> at(int tick, BiConsumer<C, Integer> action) {
            frames.add(new Frame<>(tick, tick, action));
            return this;
        }

        /** 窗口帧：首次进入 [fromInclusive, toInclusive] 时触发一次。 */
        public Builder<C> between(int fromInclusive, int toInclusive, BiConsumer<C, Integer> action) {
            if (toInclusive < fromInclusive) {
                throw new IllegalArgumentException("window to < from: " + fromInclusive + ">" + toInclusive);
            }
            frames.add(new Frame<>(fromInclusive, toInclusive, action));
            return this;
        }

        /** 持续帧：tick 在 [fromInclusive,toInclusive] 内每 period 帧触发一次（from 那帧必触发）。 */
        public Builder<C> repeating(int fromInclusive, int toInclusive, int period,
                                    BiConsumer<C, Integer> action) {
            frames.add(Frame.repeating(fromInclusive, toInclusive, period, action));
            return this;
        }

        /** 原样收下一帧（含 period）。{@code MoveDef.newRunner()} 必须走这条——
         *  用 at/between 重建会把持续帧的 period 静默丢掉，退化成一次性帧。 */
        public Builder<C> add(Frame<C> frame) {
            frames.add(frame);
            return this;
        }

        public FrameRunner<C> build() {
            // 登记期就拒（同"坏窗口在登记期抛"那条纪律）。<b>超界的真实后果是别名，不是截断</b>
            // （审查轮 22 P3-3）：`firedBitmap()` 与 `resumeFrom()` 都用 `1L << i`，而 Java 的 long
            // 移位<b>取模 64</b> ⇒ 第 65 帧（i=64）不是"存不下"，是<u>写到第 1 帧的 bit 0 上</u>：
            // 一次恢复会同时"重放 1 号"和"缴械 65 号"。放宽这道闸的人必须看见这句话。
            // datapack 侧同值拒现在直接引这个常量（`MoveCodec` 不再自备第二个 64），两条入口一套规则。
            if (frames.size() > MAX_PERSISTABLE_FRAMES) {
                throw new IllegalArgumentException("frame table has " + frames.size()
                        + " entries, over the " + MAX_PERSISTABLE_FRAMES
                        + " that a persistence bitmap can hold (bits alias mod 64); split the move or drop frames");
            }
            return new FrameRunner<>(List.copyOf(frames));
        }
    }

    /**
     * 推进到 tick，返回本 tick 实际触发的帧数。
     * 同一 tick 重复调用只触发一次（幂等保护，配合 AttackState 每 tick 恰好 advance）。
     */
    public int advance(C ctx, int tick) {
        if (tick <= lastTick) return 0;
        lastTick = tick;
        int count = 0;
        for (int i = 0; i < frames.size(); i++) {
            Frame<C> f = frames.get(i);
            if (tick < f.from()) continue;          // 未到：等后面 tick
            if (f.repeating()) {
                if (tick > f.to()) continue;        // 出窗即止：持续帧同样不做补偿触发
                if ((tick - f.from()) % f.period() == 0) {
                    f.action().accept(ctx, tick);
                    count++;
                }
                continue;
            }
            if (fired[i]) continue;
            fired[i] = true;                        // 进入即消费（含"tick>to 已错过"——不补偿触发）
            if (tick <= f.to()) {
                f.action().accept(ctx, tick);
                count++;
            }
        }
        return count;
    }

    public int frameCount() { return frames.size(); }

    public List<Frame<C>> frames() { return frames; }

    // ==================== 时间线快照 / 续播（v12a 定下的形态）====================
    //
    // 取证结论（`深挖__BOSS引擎调研v12a__施法时间线续播取证.md`）：全库没有一家把"帧时间线"
    // 本身持久化；活下来的样本存的都是<b>招内相对 tick + 阶段</b>（FDLib AttackChain、首领崛起
    // AttackPhase/AttackAnimtime），绝对 gameTime 只用来判"这份存档还新不新"。
    // 所以这里存的不是"世界时刻"而是 (招内 tick, 已触发位图)——把它写成绝对时刻的那一版
    // 会得出"卸载十秒后这招早该结束"，而玩家预期是"从断掉处继续放完"。

    /** 位图只有一个 long 的宽度，所以这既是快照的上界、也是 {@link Builder#build()} 的上界。 */
    public static final int MAX_PERSISTABLE_FRAMES = 64;

    /**
     * 已触发位图：第 i 位＝{@code frames().get(i)} 这个<b>一次性</b>帧是否已被消费。
     * 持续帧不在位图语义里（它是否触发是 tick 的纯函数，位图设了也不影响它）。
     */
    public long firedBitmap() {
        long bits = 0L;
        for (int i = 0; i < fired.length; i++) {
            if (fired[i]) bits |= 1L << i;
        }
        return bits;
    }

    /** 已经推进到的招内 tick（从未 advance 过＝{@link Integer#MIN_VALUE}）。 */
    public int lastTick() { return this.lastTick; }

    /**
     * 从一份快照续播。语义只有一条：<b>不补偿、不重放</b>，而且<b>整份快照要么被接受要么作废</b>。
     *
     * <p> {@code tickAt} 是存档里的"招内已跑 tick"，{@code bitmap} 是当时的已触发位图，
     * {@code savedDigest} 是存档里那份帧表的指纹（{@link #framesDigest()} 的旧值）。
     * 三者都可能来自被手改过的存档，所以先做两道<b>不改状态</b>的拒绝：
     * <ul>
     *   <li><b>指纹不符</b>（轮 23 P2-1）：位图按<b>下标</b>解释每一位，而下标的定义来自注册序。
     *       datapack 重载把一招收窄/插入了一帧，旧位图第 k 位指的就是<b>另一发伤害帧</b>——
     *       位图半侧改成 {@code ||} 之后，"多放"那一半没了，代价是"少放"那一半变成
     *       <b>不可恢复的永久静音</b>（同一 runner 上再恢复多少次都解不开）。
     *       所以这里整份作废、让调用方落 idle，而不是硬播。</li>
     *   <li><b>越界位</b>（轮 22 §4.5）：{@code 1L << i} 的移位是模 64 的，帧表只有 N 位时
     *       第 N 位以上的那些位<b>根本不是这张表的</b>；照单全收就等于"一位存档把整招缴械"。
     *       {@code N == 64} 时必须让路——{@code -1L << 64} 会别名回 {@code -1L << 0}。</li>
     * </ul>
     * 通过两道闸之后才做两件事：窗口已过的帧一律记成"已消费"（那正是 {@code advance} 当时会做的，
     * 不这么做就会在续播第一个 tick 上重放一次伤害），以及位图里"窗口还没到"的位提前记成已消费
     * （宁少不发两遍）。持续帧两边都不影响：它出不出帧只由 {@code (tick - from) % period} 决定。
     *
     * @return {@code null}＝已续播；否则给出一条能直接进日志的拒绝理由（状态<b>未</b>被改动）
     */
    public String resumeFrom(int tickAt, long bitmap, long savedDigest) {
        String bad = digestGate(savedDigest, this.framesDigest);
        if (bad == null) bad = bitGate(bitmap, frames.size());
        if (bad != null) return bad;
        this.lastTick = Math.max(tickAt, this.lastTick); // 绝不允许倒退：倒退会重放整段窗口
        for (int i = 0; i < frames.size(); i++) {
            Frame<C> f = frames.get(i);
            if (f.repeating()) continue;               // 持续帧无状态，位图对它没意义
            boolean passed = f.from() <= tickAt;       // 窗口起点已经过了 ⇒ 当时一定已被消费
            boolean claimed = (bitmap & (1L << i)) != 0L;
            // <b>或上已有状态，不是覆盖</b>（审查轮 22 P2-1）：`lastTick` 走了 `Math.max` 而这里走覆盖，
            // 两侧不对称。反例（帧表 [0]=between(2,10)、[1]=between(20,25)）：advance 到 21 后
            // fired=[true,true]；一份<b>更旧</b>的快照 `resumeFrom(5, 0)` 会把 fired[1] 解除武装
            // （20<=5 为假、位图第 1 位为 0），于是 advance(22) 在窗口内<b>再触发一次</b>——
            // 正是本类 javadoc 那句"存档错一位＝白挨一刀"，只是换了入口。
            // tick 与位图<b>两侧都只许前进</b>；要回退整张表只有一个合法手段：换新 runner。
            this.fired[i] = this.fired[i] || passed || claimed;
        }
        return null;
    }

    /**
     * 只缴械、不续播：<b>把 {@code tickAt} 之前（含）已经过了窗口起点的一次性帧全部记成已消费</b>，
     * 位图与指纹一概不看。
     *
     * <p>它是"续播失败时的安全侧兜底"：{@link #resumeFrom} 被拒（指纹不符／越界位）之后，
     * 如果调用方还是带着这份旧时间线跑下去，{@code advance} 会<b>把整招已经打过的帧重放一遍</b>
     * （存档错一位＝白挨一刀，只是这次是整发）。宁可让这一发<b>少打几帧</b>（掉帧＝玩家少挨一刀，
     * 与全类"不补偿"的口径同向），也不让它重放。持续帧不参与（位图对它没意义，出不出帧只看 tick）。
     */
    public void disarmUpTo(int tickAt) {
        for (int i = 0; i < frames.size(); i++) {
            Frame<C> f = frames.get(i);
            if (f.repeating()) continue;
            if (f.from() <= tickAt) this.fired[i] = true;
        }
        this.lastTick = Math.max(tickAt, this.lastTick);
    }

    /**
     * 帧表指纹：把 {@code (from, to, period)} 序列压成一个 long（FNV-1a 64 位）。
     *
     * <p>它<b>不是</b>校验和也不是内容哈希——它只回答一句话："这张表的第 i 位还是不是存档时那一发"。
     * 因此只取帧序相关的三个量，不含回调引用（回调是不可比对象的 lambda，进了哈希就等于没进）。
     * 存进 NBT 的就是这一个 long（{@code colossus_frames_digest}），读回来与当前招式的
     * {@link #framesDigest()} 比；不等就整份快照作废。见 {@link #resumeFrom(int, long, long)}。
     */
    public long framesDigest() { return this.framesDigest; }

    /** 按帧序算指纹（同一序列必然同值，与回调无关）。 */
    public static <C> long digestOf(List<Frame<C>> frames) {
        long h = 0xcbf29ce484222325L; // FNV-1a 64 位偏移基
        for (Frame<C> f : frames) {
            h = mix(h, f.from());
            h = mix(h, f.to());
            h = mix(h, f.period());
        }
        return h;
    }

    private static long mix(long h, int v) {
        h ^= (v & 0xFFFFFFFFL);
        return h * 0x100000001b3L; // FNV 素数
    }

    /**
     * 续播前的<b>作废判据</b>（纯函数）：存档里那发"招内 tick"距离现在是否还合理。
     *
     * <p>存绝对时刻会在这里说谎：卸载/换维度期间游戏钟不走，用 {@code gameTime} 推"这招该结束了"
     * 会把一次正常的存档恢复判成过期（v12a 的三家对照里只有这一档语义安全）。
     * 所以 {@code elapsedTicksWhileLoaded} 由调用方给（"这一发从恢复点到现在又跑了多少 tick"），
     * 只有<b>超出整招长度</b>才拒。
     *
     * @return null＝可以续播；否则给出一条能直接进日志的理由
     */
    /** 指纹闸（纯函数）：存档里那份帧表与眼前这张是不是<b>同一张</b>。 */
    public static String digestGate(long savedDigest, long actualDigest) {
        if (savedDigest != actualDigest) {
            return "frame table digest " + Long.toHexString(savedDigest)
                    + " does not match the current move's " + Long.toHexString(actualDigest)
                    + " (the move's frame list changed since the save; refusing to replay it by index)";
        }
        return null;
    }

    /**
     * 越界位闸（纯函数）：位图里不许有"这张表根本没有的位"。
     *
     * <p>{@code 1L << i} 的移位是模 64 的，所以 {@code n < 64} 时第 n 位以上一旦被置上，
     * 要么来自被改过的存档（整招缴械），要么来自一张<b>比这张表更长</b>的旧帧表——
     * 两种都不该照单全收。{@code n == 64} 必须让路：{@code -1L << 64} 会别名回 {@code -1L << 0}，
     * 不让路就会把合法的满宽位图判成损坏。
     */
    public static String bitGate(long bitmap, int frameCount) {
        if (frameCount < MAX_PERSISTABLE_FRAMES && (bitmap & (-1L << frameCount)) != 0L) {
            return "bitmap " + bitmap + " carries bits above the " + frameCount
                    + "-entry frame table (a shifted bit aliases onto entry 0; treating the snapshot as corrupt)";
        }
        return null;
    }

    /**
     * 续播前的<b>完整</b>作废判据：时长侧（{@link #resumeRejection(int, int, int)}）
     * + 指纹侧 + 位图侧，三道闸一次问清，调用方不必自己拼顺序。
     *
     * <p>为什么必须有这个合口径的版本（第三十八批）：实体侧要在<b> push 之前</b>决定
     * "续播还是落 idle"，而 {@link #resumeFrom} 是<b>会改状态</b>的方法——把它的判据在调用方
     * 重写一遍，就是"两处规则互相指认"的老病。
     */
    public static String resumeRejection(int stateTick, int durationTicks, int elapsedTicksWhileLoaded,
                                         long bitmap, int frameCount,
                                         long savedDigest, long actualDigest) {
        String bad = digestGate(savedDigest, actualDigest);
        if (bad != null) return bad;
        bad = bitGate(bitmap, frameCount);
        if (bad != null) return bad;
        return resumeRejection(stateTick, durationTicks, elapsedTicksWhileLoaded);
    }

    public static String resumeRejection(int stateTick, int durationTicks, int elapsedTicksWhileLoaded) {
        if (durationTicks < 1) return "duration " + durationTicks + ": non-positive, nothing to resume";
        if (stateTick < 0) return "stateTick " + stateTick + ": negative";
        if (stateTick >= durationTicks) {
            return "stateTick " + stateTick + " is past duration " + durationTicks + " (attack already over)";
        }
        if (elapsedTicksWhileLoaded < 0) {
            return "elapsed " + elapsedTicksWhileLoaded + ": negative";
        }
        // <b>两段合起来也要落在整招长度内</b>（审查轮 22 P3-2）：原先各自单独和 duration 比，
        // 于是 `resumeRejection(95, 100, 100)` 放行——而那一刻这招已经走到第 195 帧、整招只有 100 帧。
        // javadoc 那句"只有超出整招长度才拒"按字面就是 `stateTick + elapsed > duration`。
        // 用 long 求和：两个 int 相加可以溢出成负数，那会把"最越界"的一档判成放行。
        long arrivedAt = (long) stateTick + (long) elapsedTicksWhileLoaded;
        if (arrivedAt > durationTicks) {
            return "stateTick " + stateTick + " + elapsed " + elapsedTicksWhileLoaded
                    + " = " + arrivedAt + " exceeds duration " + durationTicks;
        }
        return null;
    }
}
