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
    private int lastTick = Integer.MIN_VALUE;

    private FrameRunner(List<Frame<C>> frames) {
        this.frames = frames;
        this.fired = new boolean[frames.size()];
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

        public FrameRunner<C> build() { return new FrameRunner<>(List.copyOf(frames)); }
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
}
