package com.klze.colossus.env;

import com.klze.colossus.entity.ColossusBossEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/**
 * 危险区到期结算逻辑（在 warnTicks 后由实体延迟队列执行一次）。
 */
@FunctionalInterface
public interface ZoneEffect {

    void apply(ColossusBossEntity boss, ServerLevel level, TelegraphZone zone);

    /** 空结算（{@link ZoneBurst} 只配了 freeze 或只配了 damage 时的另一半）。 */
    static ZoneEffect nothing() {
        return (boss, level, zone) -> { };
    }

    /** 纯伤害（+可选从区心向外的击退）。 */
    static ZoneEffect damageOnly(float damage, float knockback) {
        return (boss, level, zone) -> {
            for (LivingEntity v : zone.targets(level, boss)) {
                if (v.hurt(boss.damageSources().mobAttack(boss), damage)) {
                    v.invulnerableTime = 0;
                    if (knockback > 0) {
                        v.knockback(knockback, zone.cx(), zone.cz());
                    }
                }
            }
        };
    }

    /**
     * 结霜（1.20.1 实测语义，审查 P2#12：get/setTicksFrozen 双端同步没问题，但
     * 非粉雪环境每 tick 自动 -2，且冻伤只在 ≥140t 后按 40t 节奏结算）——
     * 这是"减速+屏幕结霜"型惩罚，不是定身；要定身用 Slowness/自定义 hold 区（v0.3）。
     */
    static ZoneEffect freeze(int ticks) {
        return (boss, level, zone) -> {
            for (LivingEntity v : zone.targets(level, boss)) {
                v.setTicksFrozen(Math.max(v.getTicksFrozen(), ticks));
            }
        };
    }

    /** 组合多个结算。 */
    default ZoneEffect and(ZoneEffect other) {
        return (boss, level, zone) -> {
            this.apply(boss, level, zone);
            other.apply(boss, level, zone);
        };
    }
}
