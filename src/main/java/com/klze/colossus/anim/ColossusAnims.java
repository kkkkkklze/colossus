package com.klze.colossus.anim;

import com.klze.colossus.Colossus;
import com.klze.colossus.entity.ColossusBossEntity;
import com.klze.colossus.move.MoveDef;

/**
 * 动画后端的全局挂载点（单例足够——一个引擎进程一个动画库）。
 * 后端抛错只吞日志、不炸战斗（显示层错误不该有杀伤力）。
 */
public final class ColossusAnims {

    private static ColossusAnimBackend backend = ColossusAnimBackend.NOOP;

    private ColossusAnims() {}

    /** mod 装配期调用（CommonSetup 或构造器）。传 null 视同 NOOP。 */
    public static void setBackend(ColossusAnimBackend value) {
        backend = value == null ? ColossusAnimBackend.NOOP : value;
        Colossus.LOGGER.info("Colossus anim backend: {}", backend.getClass().getName());
    }

    public static ColossusAnimBackend backend() { return backend; }

    public static void fireAttackStart(ColossusBossEntity boss, MoveDef move) {
        try {
            backend.onAttackStart(boss, move);
        } catch (Throwable t) {
            Colossus.LOGGER.warn("anim backend onAttackStart failed for {}", move.id(), t);
        }
    }

    public static void firePhaseChangeStart(ColossusBossEntity boss, int targetPhase) {
        try {
            backend.onPhaseChangeStart(boss, targetPhase);
        } catch (Throwable t) {
            Colossus.LOGGER.warn("anim backend onPhaseChangeStart failed", t);
        }
    }

    public static void fireDeathStart(ColossusBossEntity boss) {
        try {
            backend.onDeathStart(boss);
        } catch (Throwable t) {
            Colossus.LOGGER.warn("anim backend onDeathStart failed", t);
        }
    }
}
