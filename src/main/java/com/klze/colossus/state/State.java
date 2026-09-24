package com.klze.colossus.state;

/**
 * 分层状态机的一个状态。与 Minecraft 完全解耦，可独立单测。
 *
 * <p>语义约定（对齐首领崛起 state 包验证过的模式）：
 * <ul>
 *   <li>{@link #onTick} 每 tick 由 {@link StateController} 调用，返回 {@link Result#END} 即出栈。</li>
 *   <li>{@link ActiveState#tick()} 从 0 开始计数；<b>负值表示转场窗口</b>（动画过渡期，
 *       状态可以据此跳过逻辑，见 {@link StateController#pushWithTransition}）。</li>
 *   <li>{@link #isInterruptable} 返回 false 时，普通 {@code push} 无效——
 *       阶段过场、死亡演出等"不可打断的技能"用它实现。</li>
 * </ul>
 *
 * @param <E> 宿主类型（框架里是 ColossusBossEntity）
 */
public interface State<E> {

    /** onTick 的返回值：继续驻留 / 本状态结束出栈。 */
    enum Result { CONTINUE, END }

    /** 入栈时回调一次。 */
    default void onStart(E entity) {}

    /** 每 tick 调用。返回 END 时先出栈再走 {@link #onEnd}。 */
    Result onTick(E entity, ActiveState<E> self);

    /** 出栈时回调一次（含 endAll 强制清空）。 */
    default void onEnd(E entity) {}

    /** 是否允许被新状态打断（栈顶为 false 时 push 被拒绝）。 */
    default boolean isInterruptable(E entity) { return true; }

    /** 调试与日志名，默认取实现类简名。 */
    default String name() { return getClass().getSimpleName(); }
}
