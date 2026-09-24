package com.klze.colossus.state;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 栈式状态控制器——BOSS 的调度内核，语义对齐"动画即状态机"但用字符串状态名做协议。
 *
 * <p>设计裁决（见 docs/DESIGN.md §2.1）：
 * <ul>
 *   <li>栈顶为 active；{@code isIdle()} 时 vanilla AI（Goal 体系）接管。</li>
 *   <li>不可打断的状态（转场/死亡）拒绝普通 push，{@link #forcePush} 仅框架内部使用。</li>
 *   <li>{@link #pushWithTransition} 用负 timer 表达动画先行窗口。</li>
 * </ul>
 *
 * <p>非线程安全：只在服务端单线程（serverAiStep）驱动。
 */
public final class StateController<E> {

    private final Deque<ActiveState<E>> stack = new ArrayDeque<>();
    private final E entity;

    public StateController(E entity) {
        this.entity = entity;
    }

    public E entity() { return entity; }

    /** 压入新状态；栈顶不可打断时静默拒绝，返回是否成功。 */
    public boolean push(State<E> state) {
        ActiveState<E> top = stack.peek();
        if (top != null && !top.state().isInterruptable(entity)) {
            return false;
        }
        begin(state, 0);
        return true;
    }

    /** 压入并预留 transitionTicks 的转场窗口（tick 从 -transitionTicks 起算）。 */
    public boolean pushWithTransition(State<E> state, int transitionTicks) {
        ActiveState<E> top = stack.peek();
        if (top != null && !top.state().isInterruptable(entity)) {
            return false;
        }
        begin(state, -Math.max(0, transitionTicks));
        return true;
    }

    /** 无条件压栈（打断当前栈顶）。框架死亡流程专用。 */
    public void forcePush(State<E> state) {
        ActiveState<E> top = stack.peek();
        if (top != null) {
            stack.pop();
            top.state().onEnd(entity);
        }
        begin(state, 0);
    }

    /** 替换栈顶（空栈等价于 push）。 */
    public void replaceActive(State<E> state) {
        ActiveState<E> top = stack.poll();
        if (top != null) {
            top.state().onEnd(entity);
        }
        begin(state, 0);
    }

    /** 清空整栈（脱战/读档兜底）。 */
    public void endAll() {
        while (!stack.isEmpty()) {
            ActiveState<E> top = stack.pop();
            top.state().onEnd(entity);
        }
    }

    /** 驱动一 tick：先推进转场计数，再回调 onTick；END 即出栈。 */
    public void tick() {
        ActiveState<E> top = stack.peek();
        if (top == null) return;
        top.advance();
        if (top.tick() <= 0) {
            // 转场窗口（含 tick==0 的动画首帧）不跑逻辑，等动画就位。
            return;
        }
        if (top.state().onTick(entity, top) == State.Result.END) {
            // 防御：END 的回调链（onEnd/自定义触发器）可能已换栈——只有 top 仍是栈顶才弹（审查 P2#6）
            if (stack.peek() == top) {
                stack.pop();
            }
            top.state().onEnd(entity);
        }
    }

    public ActiveState<E> active() { return stack.peek(); }

    public boolean isIdle() { return stack.isEmpty(); }

    /** 当前是否处于转场窗口（tick<=0）。 */
    public boolean isTransitioning() {
        ActiveState<E> top = stack.peek();
        return top != null && top.tick() <= 0;
    }

    /** 栈内是否存在某状态类（含底层，如死亡状态下叠的特效状态）。 */
    public boolean isAny(Class<? extends State<E>> type) {
        for (ActiveState<E> a : stack) {
            if (type.isInstance(a.state())) return true;
        }
        return false;
    }

    /** 只读快照（调试/日志用），栈顶在前。 */
    public List<ActiveState<E>> snapshot() { return List.copyOf(stack); }

    private void begin(State<E> state, int startTick) {
        ActiveState<E> a = new ActiveState<>(state, startTick);
        stack.push(a);
        state.onStart(entity);
    }
}
