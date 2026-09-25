package com.klze.colossus.bar;

/**
 * 附加资源条的<b>脏检查</b>（纯逻辑、零 MC 依赖，可进 StateSelfTest）。
 *
 * <p>为什么单独成件：DE 的同类实现（{@code ShieldedServerBossInfo.java:23}）写的是
 * {@code this.shieldPower != shieldPower}，而它的调用值是 {@code power / configMax}——
 * 配置为 0 时得 NaN，{@code NaN != NaN} 恒真 ⇒ <b>每 tick 向全体玩家发包</b>。
 * 一个浮点抖动就能把"血条旁路"变成带宽泄漏，所以这里把三件事钉死：
 * ①非有限值（NaN/±Inf）直接拒绝，且不污染上次值；
 * ②比较用 {@link Float#compare}（-0.0f 与 0.0f 视为不同值时也不放抖；语义是"位相同才不发"）；
 * ③{@link #invalidate()} 用于"可见性翻转/新玩家进视角"的补账，
 * 让快照成为唯一的重同步入口（不变量：隐藏期任意改值 0 包，转可见恰好 1 包）。
 */
public final class DirtyMeter {

    private static final float UNSET = Float.NaN;

    private float lastSent = UNSET;

    /**
     * 值是否"变了、该发一个包"。是则同时记账。
     * 非有限值一律返回 false（不发垃圾，也不置 lastSent——否则下一次真值也会被它挡住）。
     */
    public boolean changedAndRemember(float value) {
        if (!Float.isFinite(value)) return false;
        if (Float.isFinite(this.lastSent) && Float.compare(this.lastSent, value) == 0) return false;
        this.lastSent = value;
        return true;
    }

    /** 强制下一次 {@link #changedAndRemember} 一定为真（进视角/转可见/重连）。 */
    public void invalidate() {
        this.lastSent = UNSET;
    }

    /**
     * 只记账、不判脏（第二十四批补）。
     *
     * <p>为什么需要它：{@code ColossusBossEvent#pushMirrorTo} 是"给一个人补全包快照"，
     * 它发出去的值<b>就是</b>当前真值，但那里不能走 {@link #changedAndRemember}——
     * 快照与上一次记账值恰好相等时它会返回 false，而调用方已经发了包，账就对不上；
     * 反过来先 {@code invalidate()} 再读 {@link #lastSent()} 会拿到 NaN（旧写法正是这么错的）。
     * 显式 remember 把"我已经发了这个值"这件事记下来，两条不变量同时成立。
     */
    public void remember(float value) {
        if (Float.isFinite(value)) this.lastSent = value;
    }

    /** 当前记账值（NaN=从未发过）。 */
    public float lastSent() {
        return this.lastSent;
    }
}
