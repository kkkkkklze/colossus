package com.klze.colossus.state;

/**
 * 栈内活动状态的包装：持有状态实例与本地 tick 计数。
 * tick 初值 0；负值窗口表示"转场中"（动画先行、逻辑未开跑）。
 */
public final class ActiveState<E> {

    private final State<E> state;
    private int tick;

    ActiveState(State<E> state, int startTick) {
        this.state = state;
        this.tick = startTick;
    }

    public State<E> state() { return state; }

    /** 当前帧号；tick &lt; 0 为转场窗口，== 0 为本状态的第一逻辑帧。 */
    public int tick() { return tick; }

    /** 自第一逻辑帧起已持续的 tick 数（转场窗口返回 0）。 */
    public int elapsed() { return Math.max(0, tick); }

    void advance() { tick++; }

    @Override
    public String toString() {
        return state.name() + "@" + tick;
    }
}
