package com.klze.colossus.move;

import com.klze.colossus.entity.ColossusBossEntity;

/**
 * 语义触发帧：在招式的第 tick 帧执行的一件事（伤害判定/音效/客户端事件/任意自定义逻辑）。
 *
 * <p>这是框架对"animationTick == 24 魔数"的裁决：触发帧仍然按 tick 声明，
 * 但被集中进 {@link MoveDef} 的帧表，与前摇/后摇共享同一份时长数据，
 * 不再散落于 tick() 的 if-else 瀑布。
 */
@FunctionalInterface
public interface MoveTrigger {

    /** @param tick 当前为该状态内的第一逻辑帧起的帧号（&gt;=1） */
    void execute(ColossusBossEntity boss, int tick);
}
