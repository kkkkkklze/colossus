package com.klze.colossus.anim;

import com.klze.colossus.entity.ColossusBossEntity;
import com.klze.colossus.move.MoveDef;

/**
 * 动画后端 SPI（服务端侧触发面）。
 *
 * <p>裁决依据（v4 + GL4 一手源码取证）：GeckoLib4 无服务端动画时钟、无 C→S 通道，
 * 15 个内置 packet 全是 S→C，且<b>没有"动画播完"事件</b>（{@code hasAnimationFinished()} 只是客户端本地查询）——
 * 所以框架的帧表是权威时间线，动画后端<b>只是显示层</b>，永不反向决定判定。
 *
 * <p>两种适配器形态（GL4 样本各有一半）：
 * ①<b>本接口的命令式</b>＝服务端出招时点名动画（{@code triggerAnim} 形，会走 GL 自带的 {@code geckolib:main} 通道）；
 * ②<b>客户端读同步数据</b>＝适配器不接本接口，客户端 predicate 里比较
 * {@link ColossusBossEntity#attackSequence()} 是否变化来重启控制器（OrdertoCook/dumbcat 都用这个，零新包）。
 * ②比①省一条通道，且<b>连放同一招也能重启动画</b>——这正是 {@code attackSequence()} 存在的原因：
 * 只看 {@code attackIndex()} 的话同招二连不改变值，客户端不会重播。
 *
 * <p>注册时机：mod 构造/CommonSetup（服务端逻辑线程外不再变更）。
 */
public interface ColossusAnimBackend {

    /** 默认：什么都不做（无动画库工程/单元测试）。 */
    ColossusAnimBackend NOOP = new ColossusAnimBackend() {
        @Override public void onAttackStart(ColossusBossEntity boss, MoveDef move) {}
    };

    /** 出招瞬间（框架已写好 ATTACK_INDEX 同步数据）。GL4 形实现 = {@code boss.triggerAnim("main", move.animName())}。 */
    void onAttackStart(ColossusBossEntity boss, MoveDef move);

    /** 阶段过场开始（到点换相位动画/免伤姿态）。 */
    default void onPhaseChangeStart(ColossusBossEntity boss, int targetPhase) {}

    /** 死亡演出开始。 */
    default void onDeathStart(ColossusBossEntity boss) {}

    /** 脱战/读档兜底：状态栈被整体清空。 */
    default void onStatesCleared(ColossusBossEntity boss) {}
}
