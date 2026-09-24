package com.klze.colossus.entity.squad;

import com.klze.colossus.anim.TableSampler;
import com.klze.colossus.entity.ColossusBossEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * squad 成员基类（第九批·v7 清单第 2 项的"钉死项"落地）。
 *
 * <p>为什么成员必须是<b>真实体</b>：1.20.1 Forge 的 PartEntity 永不入 level、射线打不到它（v5 取证），
 * 所以"玩家能瞄准、能打掉"的部位只能做成真实体——本类就是那个岗位的最小可用形：
 * 自带血池、跟着队长锚点走、受击只在一处转发、队长倒下时静默收摊。
 *
 * <p>Kraken 触手（{@code KrakenTentacleEntity:307-338,377-382,490-496,619,629-654}）的钉死项逐条对应：
 * ①<b>只存 UUID、引用懒解析</b>（{@link #resolveLeader()}，客户端永不解析）；
 * ②leader 未加载时成员<b>挂空不自杀</b>——"不在场"≠"已死"；
 * ③位移钉死：<b>注意 1.20.1 没有 {@code NoMoveControl}</b>（那是 1.20.2+ 的类，v7 样本是 NeoForge 1.21.1），
 *   这里用"空转 MoveControl + 零目标选择器 + 不可推动 + 无重力"达到同一效果；
 * ④受击<b>单点转发</b>，倍率只乘一次（两处乘=数值翻倍的经典事故）。
 */
public abstract class ColossusSquadMemberEntity extends Mob implements ColossusSquadMember {

    @Nullable
    private UUID leaderId;
    @Nullable
    private ColossusBossEntity leader;
    @Nullable
    private String memberKey;

    protected ColossusSquadMemberEntity(EntityType<? extends Mob> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        // 1.20.1 无 NoMoveControl：直接换掉 Mob 构造期装好的 MoveControl，tick() 空转
        this.moveControl = new MoveControl(this) {
            @Override
            public void tick() {
                // 位置由 customServerAiStep 的锚点解算决定，不吃寻路
            }
        };
    }

    // ==================== 成员可配的钩子 ====================

    /** 自算锚点的成员（随阶段换位的部件之类）覆写这里；返回 null＝用 squad 定义里的值。 */
    @Nullable
    protected Vec3 anchorOverride() {
        return null;
    }

    /**
     * 局部锚点（随队长朝向解算，与部件同一口径）。<b>每次现查定义表</b>，不在实体里存副本——
     * 审查轮 5 P1-1：注入式副本不入 NBT，任何存读档/区块重载之后成员会全部贴到队长原点，
     * 而血条与伤害转发照常，属于最难查的那类位置错乱。定义表才是锚点的唯一来源。
     */
    protected Vec3 anchorOffset() {
        Vec3 over = anchorOverride();
        if (over != null) return over;
        if (this.leader != null) {
            SquadManager.MemberDef def = this.leader.squad().defFor(memberKey());
            if (def != null) return def.localOffset();
        }
        return Vec3.ZERO; // 无队长/无定义：本 tick 不钉位（见 customServerAiStep 的 leader 门）
    }

    /** 是否每 tick 钉在队长锚点上（false＝自由走动的仆从，只共享 credit 与血条）。 */
    protected boolean followsLeaderAnchor() {
        return true;
    }

    /** 成员吃到的伤害里转发给队长的比例（0＝成员自吸；DBE"打腿扣本体血"形）。 */
    protected float damageForwardRatio() {
        return 0.0f;
    }

    /** 直击成员的伤害倍率（成员版弱点倍率；只在这里乘一次）。 */
    protected float incomingDamageScale() {
        return 1.0f;
    }

    // ==================== 队长引用（UUID 懒解析） ====================

    @Override
    @Nullable
    public UUID squadLeaderId() {
        return this.leaderId;
    }

    @Override
    public void setSquadLeader(UUID leaderId) {
        this.leaderId = leaderId;
        this.resolveLeader();
    }

    /** 由 {@link SquadManager} 补员时写入。 */
    public void setMemberKey(String key) {
        this.memberKey = key;
    }

    @Override
    public String memberKey() {
        return this.memberKey == null ? "" : this.memberKey;
    }

    @Nullable
    public ColossusBossEntity leader() {
        return this.leader;
    }

    /** Kraken 快路径三条件：同 UUID + 双方在场状态一致时才跳过解析。 */
    private void resolveLeader() {
        if (!(this.level() instanceof ServerLevel server)) {
            return; // 客户端永不解析
        }
        if (this.leaderId == null) {
            this.leader = null;
            return;
        }
        if (this.leader != null && this.leader.getUUID().equals(this.leaderId)
                && this.leader.isRemoved() == this.isRemoved()) {
            return;
        }
        Entity e = server.getEntity(this.leaderId);
        this.leader = e instanceof ColossusBossEntity b ? b : null;
    }

    @Override
    public void baseTick() {
        super.baseTick();
        this.resolveLeader();
    }

    /**
     * 1.20.1 的 {@code Mob.serverAiStep()} 是 **final**（{@code Mob:742}），要挂服务端每 tick 逻辑
     * 只能覆 {@code customServerAiStep()}（与 {@link ColossusBossEntity} 改挂 aiStep 是同一族坑）。
     */
    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (this.leader == null) {
            return; // 队长未加载：挂空不自杀（"不在场"≠"已死"），位置也保持不动
        }
        SquadManager squad = this.leader.squad();
        String key = memberKey();
        if (!key.isEmpty() && squad.defFor(key) != null && !squad.isCurrent(key, this.getUUID())) {
            // 账上已经是别的那具了＝我是被宽限期到点后顶替掉的旧成员（它当时只是没加载）。
            // 不自清就是"肩上叠两具、血条只算账内那具、收摊广播漏掉孤儿"（审查轮 5 P1-3）。
            // 只对"队长确实有这条定义"的 key 动手：外部自挂的成员（没进 defs）不受此门管辖。
            this.discard();
            return;
        }
        if (!followsLeaderAnchor() || !this.leader.isAlive()) {
            return;
        }
        Vec3 target = TableSampler.toWorld(this.leader, anchorOffset());
        this.setPos(target.x, target.y, target.z);
        this.setYRot(this.leader.getYRot());
        this.yBodyRot = this.leader.yBodyRot;
    }

    // ==================== 受击与结算（单点转发） ====================

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!this.level().isClientSide && this.leader != null
                && source.getEntity() instanceof LivingEntity attacker
                && this.level() instanceof ServerLevel server) {
            this.leader.squad().forwardContribution(server, attacker); // 打成员也算打 Boss
        }
        boolean hit = super.hurt(source, amount * incomingDamageScale());
        if (!this.level().isClientSide && hit && this.leader != null && this.damageForwardRatio() > 0.0f) {
            this.leader.hurt(source, amount * this.damageForwardRatio());
        }
        return hit;
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!this.level().isClientSide && this.leader != null) {
            this.leader.squad().onMemberDefeated(memberKey(), this.getUUID()); // 带 id 才允许销账
        }
    }

    /** 队长倒下时静默收摊；重生排期由队长侧 {@code SquadManager.notifyLeaderDeath()} 统一关掉。 */
    @Override
    public void onLeaderDefeated() {
        if (this.isRemoved() || !this.isAlive()) {
            return;
        }
        this.setHealth(0.0f);
        this.die(this.damageSources().genericKill());
    }

    @Override
    public float barRatio() {
        float max = this.getMaxHealth();
        return max <= 0.0f ? 0.0f : Math.max(0.0f, this.getHealth()) / max;
    }

    // ==================== 位移钉死 ====================

    @Override
    protected void registerGoals() {
        // 零目标选择器：成员是"会长大的炮台"，不是会自己找人打的怪
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    // ==================== NBT ====================

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.leaderId != null) tag.putUUID("colossus_leader", this.leaderId);
        if (this.memberKey != null) tag.putString("colossus_member_key", this.memberKey);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("colossus_leader")) this.leaderId = tag.getUUID("colossus_leader");
        if (tag.contains("colossus_member_key")) this.memberKey = tag.getString("colossus_member_key");
        this.resolveLeader();
    }
}
