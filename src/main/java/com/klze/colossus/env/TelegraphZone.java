package com.klze.colossus.env;

import com.klze.colossus.entity.ColossusBossEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * 危险区预告（TelegraphZone）——两形态调研后的数据形态实现
 * （BR IceSpike：区域是纯数据、由帧触发器声明，服务端只在 warn 到期做一次 AABB 结算；
 * CAT LightningArea 的"真实体形态"留给 v0.2 第二批）。
 *
 * <p>同步契约（第二十四批改过）：<b>轮廓不再是"发一次就完"的广播</b>。区域几何连同
 * 起止时刻被投进 Boss 的 {@code SynchedEntityData}（见
 * {@link com.klze.colossus.entity.ColossusBossEntity#showTelegraph}），于是
 * ①中途进场的人由 vanilla 的 {@code ServerEntity#sendPairingData} 自动补到当前快照、
 * ②换维度/重进世界后随实体一起回来、③计时用<b>绝对 gameTime</b>，晚到的人看到的是
 * "已经烧掉一半"而不是"从头再亮"。这三条正是 v11 取证点名的旧病灶
 * （旧写法：一条 {@code ZoneSync} 包 + 客户端本地倒计时，{@code mc.level == null} 即整批清空）。
 * 战斗状态本身仍零自定义包。
 * 触发时机由 {@code MoveDef} 帧表声明（BR 用 GeckoLib 关键帧指令做的事，
 * 我们的状态机帧表是 1.20.1 上的等价物）。
 */
public record TelegraphZone(double cx, double cy, double cz,
                            double radiusXZ, double radiusY,
                            int warnTicks, int colorRGB, String visual) {

    /** 结算之后轮廓还要多留这么多 tick 淡出（旧 {@code broadcastZone} 里那个字面量 +10）。 */
    public static final int FADE_TICKS = 10;

    /** 轮廓该活多久：<b>整段</b> warn 窗口 + 淡出，客户端与服务端投影响时都用这一个口径。 */
    public int lifetimeTicks() {
        return Math.max(1, this.warnTicks) + FADE_TICKS;
    }

    /**
     * 这一发的<b>结算延迟</b>（相对出招那一 tick）：排待办与重载剪枝共用这一个式子。
     *
     * <p>为什么单独成方法（轮 14 P3-6）：这两处原先各写一遍——{@code warnTicks + 1}（MoveTriggers）与
     * {@code Math.max(1, warnTicks) + 1}（实体读档剪枝），{@code warnTicks <= -1} 时两者差 1 tick，
     * "待办被判过期丢掉、轮廓按另一条式子还活着"那一档就从负数输入里爬回来了。
     * 一条规则两份实现，正是本仓反复在消灭的东西；JSON 侧另有 {@code warn >= 0} 的拒，
     * 但 DSL 那边不经过 codec，所以两条路都必须指向这里。
     */
    public int settleDelayTicks() {
        return Math.max(1, this.warnTicks + 1);
    }

    public AABB box() {
        return new AABB(cx - radiusXZ, cy - radiusY, cz - radiusXZ,
                cx + radiusXZ, cy + radiusY, cz + radiusXZ);
    }

    /** 区域内可攻击目标（存活、非旁观、非施法 BOSS 自己）。 */
    public List<LivingEntity> targets(ServerLevel level, ColossusBossEntity owner) {
        return level.getEntitiesOfClass(LivingEntity.class, box(),
                e -> e != owner && e.isAlive() && !e.isSpectator());
    }

    /** 伤害型结算（默认 visual="dust"；damage/knockback 由 ZoneEffect 携带）。
     *  前向取 <b>yBodyRot 水平投影</b>而非 getLookAngle——抬头看天时视线水平分量趋零，
     *  圈心会塌回脚下（审查 P2#11）。 */
    public static TelegraphZone damageCircle(ColossusBossEntity boss, double forward, double side,
                                             double radiusXZ, int warnTicks, int colorRGB) {
        double fx = -Math.sin(Math.toRadians(boss.yBodyRot));
        double fz = Math.cos(Math.toRadians(boss.yBodyRot));
        double cx = boss.getX() + fx * forward - fz * side;
        double cz = boss.getZ() + fz * forward + fx * side;
        double cy = boss.getY() + 0.1;
        return new TelegraphZone(cx, cy, cz, radiusXZ, 1.0, warnTicks, colorRGB, "dust");
    }

    /**
     * 落盘形态。延迟结算要能跨存档，区域就必须可序列化——存的是<b>解算后的世界坐标</b>：
     * 出招那一刻的位置才是要结算的位置，重载后 Boss 走了也不该把圈子拖走。
     *
     * <p>{@code warnTicks} 也在里面（第二十四批补）：旧写法只写几何，读回来 {@code warnTicks=0}，
     * 于是 {@link #lifetimeTicks()} 在重载路径上少 30 tick——轮廓先消失、伤害后落地。
     * 一份 tag 形状只此一处，客户端投影与待办队列都复用它，不留第二套序列化。
     */
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("cx", this.cx);
        tag.putDouble("cy", this.cy);
        tag.putDouble("cz", this.cz);
        tag.putDouble("rXZ", this.radiusXZ);
        tag.putDouble("rY", this.radiusY);
        tag.putInt("warn", this.warnTicks);
        tag.putInt("color", this.colorRGB);
        tag.putString("visual", this.visual);
        return tag;
    }

    public static TelegraphZone fromTag(CompoundTag tag) {
        return new TelegraphZone(tag.getDouble("cx"), tag.getDouble("cy"), tag.getDouble("cz"),
                tag.getDouble("rXZ"), tag.getDouble("rY"), tag.getInt("warn"), tag.getInt("color"),
                tag.getString("visual"));
    }

    /** 换渲染样式（"dust" 粒子默认 / "ring" 线框 / 第三方注册键）。 */
    public TelegraphZone withVisual(String visual) {
        return new TelegraphZone(cx, cy, cz, radiusXZ, radiusY, warnTicks, colorRGB, visual);
    }

    /**
     * 把这块区域投给所有能看见 Boss 的客户端（帧触发器里调用），寿命＝{@link #lifetimeTicks()}。
     *
     * <p>旧名字是 {@code broadcast(boss)}——它发一条一次性广播就撒手，所以中途进场、
     * 重进世界、换维度都看不见；现在改成登记进 Boss 的同步数据，由 vanilla 的追踪器补包。
     *
     * @return false＝Boss 的轮廓投影已满（{@code MAX_ACTIVE_TELEGRAPHS}），这一条<b>没</b>登记上。
     *         带伤害的帧必须据此放弃整发，别留一发没预警的结算。
     */
    public boolean show(ColossusBossEntity boss) {
        return boss.showTelegraph(this, this.lifetimeTicks()) >= 0;
    }
}
