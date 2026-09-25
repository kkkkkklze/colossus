package com.klze.colossus.gecko;

import com.klze.colossus.entity.ColossusBossEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * GeckoLib4 适配基类（addon 侧，核心不依赖 GL）。
 *
 * <p>触发档取 v9 取证的 (c) 族：<b>读已同步的实体数据</b>驱动控制器，
 * 不引 GL 自带的 {@code geckolib:main} 通道。关键判据是
 * {@link ColossusBossEntity#attackSequence()} 而<b>不是</b>招式 index——
 * 连放同一招时 index 不变，只有序号递增；样本（OrdertoCook 的 {@code ACTION_STATE}、
 * dumbcat 的 {@code HURT_SEQ}）全都用递增计数，理由同一条：<b>同值不广播</b>。
 *
 * <p>服务端权威时间线不变：本类只在客户端解动画，
 * <b>永不</b>用 {@code hasAnimationFinished()} 反向决定判定（GL4 无 C→S 通道）。
 * {@code RawAnimation} 按名字缓存——每次 predicate 现 new 是 GL 文档点名的浪费。
 */
public abstract class GeoColossusEntity extends ColossusBossEntity implements GeoEntity {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final Map<String, RawAnimation> animTable = new HashMap<>();

    private int lastSeenSeq = -1;

    protected GeoColossusEntity(EntityType<? extends ColossusBossEntity> type, Level level) {
        super(type, level);
    }

    // ==================== 内容侧只需给这三样 ====================

    /** 招式名 → GL 动画名（默认直接用 MoveDef.animName()，够用；要改名规则就覆写）。 */
    /** 改名规则钩子（默认用服务端下发的 animName，即 {@code MoveDef.animName()}）。 */
    protected String animForMove(String syncedAnimName) {
        return syncedAnimName;
    }

    /** 待机/移动/死亡三条环境动画名。 */
    protected abstract String idleAnim();

    protected abstract String walkAnim();

    protected abstract String deathAnim();

    // ==================== GL4 三件必覆写 ====================

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar registrar) {
        registrar.add(new AnimationController<>(this, "main", 5, this::mainController));
        registrar.add(new AnimationController<>(this, "movement", 0, this::movementController));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    @Override
    public double getTick(Object rawTick) {
        return rawTick instanceof Number n ? n.doubleValue() : 0.0d;
    }

    // ==================== 控制器谓词 ====================

    private PlayState mainController(AnimationState<GeoColossusEntity> state) {
        if (this.deathTick() > 0) {
            this.lastSeenSeq = this.attackSequence();
            return state.setAndContinue(raw(this.deathAnim()));
        }
        // 轮 7 P1-1：currentAttack() 是服务端权威对象，客户端恒 null——控制器跑在渲染路径上，
        // 拿它当门会让出招动画永不播。轮 8 P2 再把门的语义挪到 isAttacking()：
        // 门认<b>招式 id</b>（服务端保证非空），动画名只是取动画的键，两个职责不要混在一个字段上。
        if (!this.isAttacking()) {
            this.lastSeenSeq = this.attackSequence();
            return PlayState.STOP;
        }
        String anim = this.attackAnimName();
        int seq = this.attackSequence();
        if (seq != this.lastSeenSeq) {
            // 新的一次施法：强制复位再起播，否则同招二连会在原地续播上一轮剩余帧
            this.lastSeenSeq = seq;
            state.getController().forceAnimationReset();
        }
        return state.setAndContinue(raw(animForMove(anim))); // 服务端下发的动画名，双端同一个键
    }

    private PlayState movementController(AnimationState<GeoColossusEntity> state) {
        if (this.isAttacking() || this.deathTick() > 0) return PlayState.STOP;
        boolean moving = this.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4;
        return state.setAndContinue(raw(moving ? this.walkAnim() : this.idleAnim()));
    }

    private RawAnimation raw(String name) {
        return this.animTable.computeIfAbsent(name, n -> RawAnimation.begin().thenPlay(n));
    }
}
