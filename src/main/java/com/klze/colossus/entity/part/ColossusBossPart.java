package com.klze.colossus.entity.part;

import com.klze.colossus.entity.ColossusBossEntity;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.PartEntity;

/**
 * 框架部件实体（第六批·分体系统）。构造事实经 Forge 47 源码取证：
 * {@code PartEntity(T parent)} 仅 3 成员，部件<b>永不注册 EntityType、永不 addEntity、
 * 永不进 level tick</b>——位置由 parent 每 tick 双侧同算，状态走 parent 的 entityData 位图
 * （{@link PartStates}）。这是"部件零包"铁律的实现形。
 *
 * <p>1.20.1 的已知边界（取证结论，勿踩）：
 * {@code isMultipartEntity/getParts} 在 Forge 侧零消费者，是 mod 间约定；
 * 原版攻击射线打不到未入 level 的部件——本类的受击入口是 parent 的
 * {@code hurtPart} 转发（特效/弹道/自定义路由），玩家直视弱点的客户端注入方案
 * 是独立的开放设计题（TF Fabric 侧 putNonPlayerEntity 无 Forge 等价物）。
 *
 * <p>位置更新现状（审查 P2#22）：parent 只在<b>服务端</b> aiStep 解算部件位
 * （受击/接触判定都在服务端，够用）；客户端部件位置当前不更新——等"弱点几何判定
 * （HitSolver×部件世界位，双端）"落地时一并双侧化，别提前背开销。
 */
public class ColossusBossPart<P extends ColossusBossEntity> extends PartEntity<P> {

    private final int partIndex;
    private final Vec3 localOffset; // 相对脚底、随 yBodyRot 旋转的锚点（复用 TableSampler.toWorld）
    private final float width;
    private final float height;

    private boolean active = true;

    public ColossusBossPart(P parent, int partIndex, Vec3 localOffset, float width, float height) {
        super(parent);
        this.partIndex = partIndex;
        this.localOffset = localOffset;
        this.width = width;
        this.height = height;
        this.setNoGravity(true);
        this.refreshDimensions();
    }

    public int partIndex() { return partIndex; }
    public Vec3 localOffset() { return localOffset; }

    // ---------------- 失活 = 尺寸 0（六样本一致：不用删除表达存活态） ----------------

    public boolean isPartActive() { return active; }

    public void setPartActive(boolean on) {
        if (this.active == on) return;
        this.active = on;
        this.refreshDimensions();
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return active ? EntityDimensions.scalable(width, height) : EntityDimensions.scalable(0f, 0f);
    }

    @Override
    public boolean canChangeDimensions() { return false; }

    // ---------------- 受击路由：单向 part → parent，无回环（取证：无需递归防护） ----------------

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!active) return false;
        return getParent().hurtPart(this, source, amount);
    }

    /** 弱点倍率钩子（首领崛起 Netherite 形：部件侧生效，parent 不重复乘——只乘一次的铁律）。 */
    public float damageMultiplier() {
        return 1.0f;
    }

    /** 转发给 parent 时附带的击退方向（默认从 parent 指向外；AC 的三分解简化形）。 */
    public Vec3 knockbackOrigin() {
        return getParent().position();
    }

    // ---------------- 零网络/零存档约束（取证硬规矩） ----------------

    @Override
    public boolean isPickable() { return active; }

    @Override
    public boolean canBeCollidedWith() { return false; } // 部件不推玩家——接触伤害走显式结算

    @Override
    public boolean shouldBeSaved() { return false; }

    @Override
    protected void defineSynchedData() { /* 部件无自有同步数据：状态全挂 parent 位图 */ }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) { /* 永不入档 */ }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) { /* 永不入档 */ }

    @Override
    public void baseTick() { /* 不随 level tick——位置由 parent 双侧同算（Cataclysm 的死代码教训） */ }

    /** 客户端特效便捷口：在 parent 视角的世界锚点位放粒子。 */
    public void spawnClientParticle(ParticleOptions type, int count, double spread) {
        Vec3 world = com.klze.colossus.anim.TableSampler.toWorld(getParent(), localOffset);
        level().addParticle(type, true,
                world.x + (random.nextDouble() - 0.5) * spread,
                world.y + (random.nextDouble() - 0.5) * spread,
                world.z + (random.nextDouble() - 0.5) * spread,
                0, 0, 0);
    }
}
