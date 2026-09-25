package com.klze.colossus.move;

import com.klze.colossus.entity.ColossusBossEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

/**
 * 内置语义触发帧工厂。判定全部在服务端结算（几何过滤 + mobAttack 伤害源），
 * 与任何动画库无关——这是六个样本工程的一致做法。
 */
public final class MoveTriggers {

    private MoveTriggers() {}

    /**
     * 扇形 AOE 判定帧：以 BOSS 身体朝向为轴、radius 为半径、arcDegrees 为张角，
     * 对玩家阵营的存活实体结算伤害并清 invulnerableTime（允许连招多帧命中）。
     */
    public static MoveTrigger arcHit(float radius, float arcDegrees, float damage, float knockback) {
        return (boss, tick) -> {
            for (LivingEntity v : candidates(boss, radius, e -> true)) {
                if (arcOffsetDeg(boss, v) <= arcDegrees / 2.0) {
                    strike(boss, v, damage, knockback);
                }
            }
        };
    }

    /** 圆形 AOE 判定帧（无方向性）。 */
    public static MoveTrigger circleHit(float radius, float damage, float knockback) {
        return (boss, tick) -> {
            for (LivingEntity v : candidates(boss, radius, e -> true)) {
                strike(boss, v, damage, knockback);
            }
        };
    }

    /** 带额外筛选的扇形判定（例如只对创造栏之外、或血量低于阈值的玩家）。 */
    public static MoveTrigger arcHit(float radius, float arcDegrees, float damage, float knockback,
                                     Predicate<LivingEntity> filter) {
        return (boss, tick) -> {
            for (LivingEntity v : candidates(boss, radius, filter)) {
                if (arcOffsetDeg(boss, v) <= arcDegrees / 2.0) {
                    strike(boss, v, damage, knockback);
                }
            }
        };
    }

    /** 音效帧（服务端广播，走实体位置衰减）。 */
    public static MoveTrigger sound(ResourceLocation soundId, float volume, float pitch) {
        return (boss, tick) -> {
            SoundEvent ev = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(soundId);
            boss.playSound(ev, volume, pitch);
        };
    }

    /** 复用原版 SoundEvent。 */
    public static MoveTrigger sound(SoundEvent event, float volume, float pitch) {
        return (boss, tick) -> boss.playSound(event, volume, pitch);
    }

    /**
     * 客户端演出事件帧：向所有追踪该实体的玩家发送类型化事件名（粒子/刀光/震屏等），
     * 由 {@link ColossusBossEntity#onBossVisualEvent} 分发。战斗状态本身仍零自定义包。
     */
    public static MoveTrigger event(String eventId) {
        return (boss, tick) -> boss.sendBossVisualEvent(eventId);
    }

    /**
     * 危险区预告帧：把 telegraph 区登记进 Boss 的同步数据（客户端画轮廓），warnTicks 后由实体
     * 延迟队列对区域内实体执行 effect——BR IceSpike 的"数据形态"，触发时机由帧表声明。
     *
     * <p>容量是要算账的：投影上限 {@code ColossusBossEntity.maxActiveTelegraphs()}（默认 8）
     * 与待办队列上限 32 各自独立，而"投影满 ⇒ 整发放弃"是有意选的方向 ⇒ 挂在
     * {@code repeating(from,to,period)} 上时同时在地的圈数约
     * {@code ceil((warnTicks + TelegraphZone.FADE_TICKS) / period)}，超出的那些<b>一招都不会落</b>
     * （日志每 100 tick 至多一条，腾出位子时报累计条数）。高频圈请加长 period 或按 Boss 抬上限。
     *
     * <p>轮廓与结算<b>两套生命周期各走各的</b>：结算在 {@code scheduleWork} 的待办队列里
     * （可跨存档），可视态在 {@code DATA_TELEGRAPHS} 的投影里（可补包、可重放）。
     * 二者都由 Boss 自己持有，所以中途进场/换维度/重载都不会看见"只有一半"的演出。
     *
     * <p>注意 zone 与 effect 都是"出招时"由 boss 现算的工厂函数——
     * 招式表是实例级惰性构建的静态数据，落点必须每次施放重算。
     *
     * <pre>{@code
     * .at(10, MoveTriggers.telegraph(
     *         b -> TelegraphZone.damageCircle(b, 4.0, 0.0, 6.0, 30, 0xFF4040),
     *         b -> ZoneEffect.damageOnly(6.0f, 0.8f).and(ZoneEffect.freeze(60))))}</pre>
     */
    public static MoveTrigger telegraph(
            java.util.function.Function<ColossusBossEntity, com.klze.colossus.env.TelegraphZone> zoneFn,
            java.util.function.Function<ColossusBossEntity, com.klze.colossus.env.ZoneBurst> burstFn) {
        return (boss, tick) -> {
            var zone = zoneFn.apply(boss);
            var burst = burstFn.apply(boss);
            // 画圈与排队绑成一次原子操作，两个方向都不许单边成立：
            //   投影已满 → 整发放弃（宁可少一招，不发没预警的伤害）
            //   队列已满 → 把刚登记的圈撤掉（不留一块永远不炸的假警告）
            int view = boss.showTelegraph(zone, zone.lifetimeTicks());
            if (view < 0) return;
            // 排的是数据不是闭包：warn 期间即使区块卸载/Boss 被重载，这一发照样会结算
            if (!boss.scheduleWork(zone.warnTicks() + 1, com.klze.colossus.env.ZoneWork.KIND,
                    com.klze.colossus.env.ZoneWork.encode(zone, burst))) {
                boss.hideTelegraph(view);
            }
        };
    }

    /** 速记：以 BOSS 朝向前方 forward 格、半径 radius 的伤害圈。 */
    public static MoveTrigger telegraphDamage(double forward, double radius, int warnTicks,
                                              float damage, float knockback, int colorRGB) {
        return telegraph(
                b -> com.klze.colossus.env.TelegraphZone.damageCircle(b, forward, 0, radius, warnTicks, colorRGB),
                b -> new com.klze.colossus.env.ZoneBurst(damage, knockback, 0));
    }

    /**
     * 破块帧（Naga/Yeti 形）：朝向前方砸碎 width×height 断面的可破坏方块。
     * 走 {@link com.klze.colossus.env.ArenaBlockAccess}——mobGriefing/硬度带/豁免 tag 三闸门。
     */
    public static MoveTrigger breakAhead(double forward, double width, double height, boolean dropItems) {
        return (boss, tick) -> {
            if (!boss.level().isClientSide) {
                com.klze.colossus.env.ArenaBlockAccess.breakAhead(boss, forward, width, height, dropItems);
            }
        };
    }

    /**
     * 扫掠判定帧（TACZ 配方的近战挥击形）：从 BOSS 视线起点沿看向前进 length 格的
     * 线段上结算伤害——单帧大位移也不漏目标，按由近及远穿透。适合冲刺斩/吐息/落雷引导。
     */
    public static MoveTrigger sweepHit(double length, float damage, float knockback) {
        return sweepHit(length, damage, knockback, v -> true);
    }

    public static MoveTrigger sweepHit(double length, float damage, float knockback,
                                        java.util.function.Predicate<LivingEntity> filter) {
        return (boss, tick) -> {
            Vec3 from = boss.getEyePosition();
            Vec3 to = from.add(boss.getLookAngle().scale(length));
            for (HitSolver.Target t : HitSolver.sweep(boss.level(), boss, from, to, 0.6, filter)) {
                strike(boss, t.entity(), damage, knockback);
            }
        };
    }

    /**
     * 带参演出 cue 帧（震屏/闪光等）：位置与数据都"出招时现算"。
     * 例：{@code MoveTriggers.cue(ScreenShakeCue.TYPE, (b,t) -> b.position(), (b,t) -> new ScreenShakeCue.Data(6f,20,24f))}
     */
    public static <D> MoveTrigger cue(com.klze.colossus.fx.CueType.Type<D> type,
                                      java.util.function.BiFunction<ColossusBossEntity, Integer, Vec3> posFn,
                                      java.util.function.BiFunction<ColossusBossEntity, Integer, D> dataFn) {
        return (boss, tick) -> com.klze.colossus.network.ColossusPackets.sendCue(
                boss, type, posFn.apply(boss, tick), dataFn.apply(boss, tick));
    }

    /**
     * 只画危险区、不结算伤害的预告帧：给<b>持续招</b>用——伤害由
     * {@link com.klze.colossus.move.MoveSetBuilder.MoveBuilder#repeating} 的复触发帧逐轮结算，
     * 若这里再用 {@link #telegraph} 就会额外挂一次"warnTicks 后爆发"，同一块地被打两遍。
     *
     * <p>区域的存活时长是 {@code warnTicks + TelegraphZone.FADE_TICKS}（淡出那半秒也算在内），
     * 所以用法是把 warnTicks 配成窗口长度：{@code repeating(20,80,10, circleHit(...))} 配
     * {@code at(20, telegraphVisual(b -> damageCircle(..., warn=60 ...)))}。
     */
    public static MoveTrigger telegraphVisual(
            java.util.function.Function<ColossusBossEntity, com.klze.colossus.env.TelegraphZone> zoneFn) {
        return (boss, tick) -> zoneFn.apply(boss).show(boss);
    }

    // ------------------------------------------------------------------

    private static List<LivingEntity> candidates(ColossusBossEntity boss, float radius,
                                                 Predicate<LivingEntity> extra) {
        return boss.level().getEntitiesOfClass(
                LivingEntity.class,
                boss.getBoundingBox().inflate(radius),
                v -> v != boss && v.isAlive() && !v.isSpectator()
                        && v.position().closerThan(boss.position(), radius)
                        && extra.test(v));
    }

    /** 目标相对身体朝向的偏离角（0=正前方）。用水平视线向量算，规避 yBodyRot 与 atan2 的约定错位。 */
    private static double arcOffsetDeg(ColossusBossEntity boss, Entity v) {
        double fx = -Math.sin(Math.toRadians(boss.yBodyRot));
        double fz = Math.cos(Math.toRadians(boss.yBodyRot));
        double dx = v.getX() - boss.getX();
        double dz = v.getZ() - boss.getZ();
        double len = Math.hypot(dx, dz);
        if (len < 1.0e-3) return 0.0;
        double dot = (fx * dx + fz * dz) / len;
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
    }

    private static void strike(ColossusBossEntity boss, LivingEntity v, float damage, float knockback) {
        if (v.hurt(boss.damageSources().mobAttack(boss), damage)) {
            // 打后无敌帧由招式的 postInvuln 决定（默认 0=允许连段多帧命中；调大=防其他攻击插队）
            v.invulnerableTime = boss.postHitInvulnerability();
            if (knockback > 0) {
                v.knockback(knockback, boss.getX(), boss.getZ());
            }
        }
    }

    /**
     * 接触去重包装（DBE ContactKey 形）：同一攻击实例内，同 tag 的**整帧**只执行一次。
     * 粒度（审查 P2#9）：{@link #once}＝整帧一次（第一个满足几何条件的目标独享）；
     * "每人一次"用 {@link #arcHitContacted}；<b>持续区逐轮复判</b>用
     * {@code .repeating(from,to,period, ...)} 帧（第十批已落，见
     * {@link com.klze.colossus.move.MoveSetBuilder.MoveBuilder#repeating}）——
     * 一次性 entry 仍然至多触发一次，别指望 {@code between} 重复执行。
     */
    public static MoveTrigger once(String contactTag, MoveTrigger inner) {
        return (boss, tick) -> {
            if (boss.claimContact(contactTag)) {
                inner.execute(boss, tick);
            }
        };
    }

    /**
     * 逐目标接触去重的伤害帧：整窗口对同一实体至多结算一次——
     * "弱点帧+补刀帧共享键"这类双帧招式的正解（每人只吃其中先到的一帧）。
     */
    public static MoveTrigger arcHitContacted(String contactTag, float radius, float arcDegrees,
                                              float damage, float knockback) {
        return (boss, tick) -> {
            for (LivingEntity v : candidates(boss, radius, e -> true)) {
                if (arcOffsetDeg(boss, v) <= arcDegrees / 2.0
                        && boss.claimContact(contactTag + "#" + v.getId())) {
                    strike(boss, v, damage, knockback);
                }
            }
        };
    }
}
