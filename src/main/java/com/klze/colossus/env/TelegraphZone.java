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
 * <p>同步契约：广播一条 {@code ZoneSync}（世界坐标+半径+颜色+时长）给追踪玩家，
 * 客户端按 visual 画轮廓粒子圈；战斗状态本身仍零包。
 * 触发时机由 {@code MoveDef} 帧表声明（BR 用 GeckoLib 关键帧指令做的事，
 * 我们的状态机帧表是 1.20.1 上的等价物）。
 */
public record TelegraphZone(double cx, double cy, double cz,
                            double radiusXZ, double radiusY,
                            int warnTicks, int colorRGB, String visual) {

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
     */
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("cx", this.cx);
        tag.putDouble("cy", this.cy);
        tag.putDouble("cz", this.cz);
        tag.putDouble("rXZ", this.radiusXZ);
        tag.putDouble("rY", this.radiusY);
        tag.putInt("color", this.colorRGB);
        tag.putString("visual", this.visual);
        return tag;
    }

    public static TelegraphZone fromTag(CompoundTag tag) {
        return new TelegraphZone(tag.getDouble("cx"), tag.getDouble("cy"), tag.getDouble("cz"),
                tag.getDouble("rXZ"), tag.getDouble("rY"), 0, tag.getInt("color"),
                tag.getString("visual"));
    }

    /** 换渲染样式（"dust" 粒子默认 / "ring" 线框 / 第三方注册键）。 */
    public TelegraphZone withVisual(String visual) {
        return new TelegraphZone(cx, cy, cz, radiusXZ, radiusY, warnTicks, colorRGB, visual);
    }

    /** 广播给所有追踪玩家（在帧触发器里调用）。 */
    public void broadcast(ColossusBossEntity boss) {
        com.klze.colossus.network.ColossusPackets.broadcastZone(boss, this);
    }
}
