package com.klze.colossus.entity;

import com.klze.colossus.Colossus;
import com.klze.colossus.ColossusConfig;
import com.klze.colossus.bar.ColossusBossEvent;
import com.klze.colossus.fight.ContactBook;
import com.klze.colossus.fight.EngagementTracker;
import com.klze.colossus.fight.ScalingStrategy;
import com.klze.colossus.move.AttackContext;
import com.klze.colossus.move.MoveDef;
import com.klze.colossus.move.MoveSet;
import com.klze.colossus.move.MoveSetBuilder;
import com.klze.colossus.network.ColossusPackets;
import com.klze.colossus.progress.BossDefinition;
import com.klze.colossus.progress.BossKillBoard;
import com.klze.colossus.state.ColossusStateGoal;
import com.klze.colossus.state.StateController;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Colossus 框架的 Boss 基类——所有单 Boss 样板的收敛点。
 *
 * <p>子类只需要实现两件事：
 * <ol>
 *   <li>{@link #getBossId()} —— Boss 种类 id（血条 key、击杀板 key、招式 id 前缀都挂在它上面）；</li>
 *   <li>{@link #registerMoves(MoveSetBuilder)} —— 招式表（时长/冷却/阶段门/触发帧）。</li>
 * </ol>
 *
 * <p>基类固化的公共管线（每条都来自源码研究的验证结论，见 docs/DESIGN.md）：
 * <ul>
 *   <li>状态栈调度（空闲时 vanilla Goal 接管，忙碌时 MOVE/LOOK 归状态）；</li>
 *   <li>血量阈值一次性阶段闸门 + 过场全免伤 + 换阶段清冷却；</li>
 *   <li>死亡延迟结算（演出先行，击杀板/共享 credit 在演完后发）；</li>
 *   <li>参战名单与击杀 credit（hurt 收集、TTL 剔除）；</li>
 *   <li>人数缩放（sqrt 曲线，血量百分比回填，策略可换）；</li>
 *   <li>血条 renderType 旁路、音乐幂等开关、类型化客户端事件。</li>
 * </ul>
 *
 * <p>同步契约：{@code PHASE / ATTACK_INDEX / ATTACK_TICK / DEATH_TICK / ACTIVATED}
 * 全走 entityData——战斗状态零自定义包。
 */
public abstract class ColossusBossEntity extends Monster {

    // ---------------- 同步数据（客户端渲染的唯一事实源） ----------------

    private static final EntityDataAccessor<Integer> DATA_PHASE =
            SynchedEntityData.defineId(ColossusBossEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ATTACK_INDEX =
            SynchedEntityData.defineId(ColossusBossEntity.class, EntityDataSerializers.INT); // -1 = 无招式
    private static final EntityDataAccessor<Integer> DATA_ATTACK_TICK =
            SynchedEntityData.defineId(ColossusBossEntity.class, EntityDataSerializers.INT);
    /**
     * 出招序号（每次 {@code beginAttack} +1，**只增不清零**）。
     *
     * <p>为什么要有它：动画侧要能分辨"新一次施法"，而 {@code DATA_ATTACK_INDEX} 在
     * <b>连续放同一招</b>时不变 → 靠"值变了没"驱动的客户端不会重启动画，第二下原地卡住。
     * 这是 GL4 取证（清单 2 多部件段）点名的同款事故：「同值不广播，布尔旗标在多人下会漏第二次触发」，
     * 正解是<b>递增序号</b>。OrdertoCook 的 {@code ACTION_STATE} 与 dumbcat 的 {@code HURT_SEQ} 都是这个形状。
     */
    private static final EntityDataAccessor<Integer> DATA_ATTACK_SEQ =
            SynchedEntityData.defineId(ColossusBossEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_DEATH_TICK =
            SynchedEntityData.defineId(ColossusBossEntity.class, EntityDataSerializers.INT);
    /**
     * 附加资源条的<b>权威值在实体同步数据里</b>（§0.4 零包法则：战斗状态不走自定义包）。
     * 血条那条通道只是表现层镜像——见 {@link com.klze.colossus.bar.ColossusBossEvent#syncShield}。
     */
    private static final EntityDataAccessor<Float> DATA_SHIELD =
            SynchedEntityData.defineId(ColossusBossEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_ACTIVATED =
            SynchedEntityData.defineId(ColossusBossEntity.class, EntityDataSerializers.BOOLEAN);
    /** 部件状态位图（3bit×21：active/damaged/dead）——部件"零包"的唯一载体（PartStates 编解码）。 */
    private static final EntityDataAccessor<Long> DATA_PART_BITS =
            SynchedEntityData.defineId(ColossusBossEntity.class, EntityDataSerializers.LONG);

    // ---------------- 缩放 AttributeModifier（固定 UUID，命名可查） ----------------

    private static final UUID SCALE_HP_ID = UUID.fromString("3f2f2c61-28d7-4f70-9c9b-7d14a4c0b001");
    private static final UUID SCALE_DMG_ID = UUID.fromString("3f2f2c61-28d7-4f70-9c9b-7d14a4c0b002");

    // ---------------- 组合件 ----------------

    private final StateController<ColossusBossEntity> stateController = new StateController<>(this);
    private final EngagementTracker engagement = new EngagementTracker();
    private final ContactBook contacts = new ContactBook();
    private final Map<ResourceLocation, Integer> cooldowns = new java.util.HashMap<>();

    @Nullable private MoveSet moveSet;
    @Nullable private ColossusBossEvent bossEvent;

    /** 延迟工作队列（unusualend queueServerWork 模式）：telegraph 到期结算等都靠它。 */
    private final java.util.Deque<ScheduledWork> workQueue = new java.util.ArrayDeque<>();

    private record ScheduledWork(int dueTick, Runnable action) {}

    /** N tick 后在服务端 aiStep 里执行一次（实体先消失则不执行）。 */
    public void scheduleWork(int delayTicks, Runnable action) {
        this.workQueue.add(new ScheduledWork(this.tickCount + Math.max(1, delayTicks), action));
    }

    private void drainWork() {
        while (!this.workQueue.isEmpty() && this.workQueue.peekFirst().dueTick() <= this.tickCount) {
            this.workQueue.pollFirst().action().run();
        }
    }

    private boolean deathPending = false;
    private boolean deathResolved = false;
    private boolean phaseLock = false;
    private boolean[] gatesFired = null;
    /** 读档恢复：演出中被卸载的 Boss 重载后在首个 aiStep 续上死亡流程（审查 P1）。 */
    private boolean resumeDeathPending = false;

    private int lastScalePlayers = -1;
    private int scaleCooldown = 0;
    private boolean musicLatch = false;

    protected ColossusBossEntity(EntityType<? extends ColossusBossEntity> type, Level level) {
        super(type, level);
        this.xpReward = 0;
    }

    // ==================== 子类契约 ====================

    /** Boss 种类 id（如 {@code colossus:example}）。 */
    public abstract ResourceLocation getBossId();

    /** 声明招式表（构造后惰性调用一次）。 */
    protected abstract void registerMoves(MoveSetBuilder builder);

    // ---------------- 可调钩子（全部有保守默认值） ----------------

    /** 阶段血量阈值（升序无关，按下标 i 升到 phase i+1）。默认两段：2/3、1/3。 */
    protected float[] phaseThresholds() { return new float[]{2.0f / 3.0f, 1.0f / 3.0f}; }

    /** 阶段过场总时长（tick）。 */
    protected int phaseTransitionTicks() { return 60; }

    /** 死亡演出时长（tick），演完才结算+原版死亡。 */
    protected int deathAnimationTicks() { return 100; }

    /** 单次伤害上限（DPS 帽的第一道；配置 0 = 不限）。 */
    protected float maxDamagePerHit() {
        float v = ColossusConfig.MAX_DAMAGE_PER_HIT.get().floatValue();
        return v > 0 ? v : -1f;
    }

    /** 人数缩放策略（可被配置整体关闭）。 */
    protected ScalingStrategy scaling() {
        if (!ColossusConfig.ENABLE_SCALING.get()) return ScalingStrategy.NONE;
        return ScalingStrategy.sqrt(ColossusConfig.SCALING_SHARE.get());
    }

    /** 战斗主题曲：默认取 BossDefinition 注册的音乐（种类 id 是连接键——审查 P2#21 接线）。 */
    @Nullable
    protected ResourceLocation bossMusic() {
        BossDefinition def = BossDefinition.get(this.getBossId());
        return def == null ? null : def.music();
    }

    /** 进身 goal 的保持距离（格）——选招距离门由 MoveDef.range 各自声明。 */
    public float approachReach() { return 4.0f; }

    /** 血条颜色/样式。 */
    protected ColossusBossEvent createBossEvent() {
        ColossusBossEvent event = new ColossusBossEvent(
                Component.translatable(getType().getDescriptionId()),
                BossEvent.BossBarColor.PURPLE,
                BossEvent.BossBarOverlay.PROGRESS);
        event.setDarkenScreen(true);
        return event;
    }

    /** 客户端收到 {@code sendBossVisualEvent} 的派发点（仅客户端调用）。 */
    public void onBossVisualEvent(String eventId) { /* 默认无操作，子类/渲染层订阅 */ }

    // ==================== 状态栈 ====================

    public StateController<ColossusBossEntity> getStateController() { return stateController; }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // 1 号优先级：状态机忙碌时锁住 MOVE/LOOK（占锁，不驱动——驱动在 serverAiStep）
        this.goalSelector.addGoal(1, new ColossusStateGoal(this));
        // 2 号：空闲进身
        this.goalSelector.addGoal(2, new BossApproachGoal(this, 1.0));
        // 3 号：空闲漫步（接敌前不呆站）
        this.goalSelector.addGoal(4, new RandomStrollGoal(this, 0.6));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers());
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    // ==================== 主循环 ====================

    @Override
    public void aiStep() {
        super.aiStep(); // 先跑 goal/target selector（Mob#serverAiStep 是 final，不覆盖它）
        if (!this.isEffectiveAi()) return;

        if (this.resumeDeathPending) {
            this.resumeDeathPending = false;
            this.stateController.forcePush(new DeathState(this, this.damageSources().generic()));
        }
        this.stateController.tick();
        this.drainWork();
        tickCooldowns();

        if (!(this.level() instanceof ServerLevel server)) return;
        this.engagement.tick(server);
        this.positionParts(); // 部件锚点每服务端 tick 解算（接触/受击用）

        if (this.bossEvent != null && !this.deathPending) {
            // 死亡演出期不回填进度（否则每 tick 把 0 覆回 1/max）
            this.bossEvent.setProgress(net.minecraft.util.Mth.clamp(this.barProgress(), 0f, 1f));
        }
        tickMusic(server);
        tickScaling(server);

        if (this.deathPending || this.stateController.isTransitioning()) return;

        this.tickShieldRegen(server);
        this.tickSessionAndSquad(server);
        if (this.stateController.isIdle()) {
            checkPhaseGates();
            trySelectAttack();
        }
    }

    // ---------------- 选招 ----------------

    private void trySelectAttack() {
        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive() || target.isSpectator()) return;
        AttackContext ctx = new AttackContext(this, target, this.distanceToSqr(target));
        this.moveSet().pick(ctx, this::cooldownLeft, this.random)
                .ifPresent(move -> this.stateController.push(new AttackState(move)));
    }

    /**
     * 剧情/调试/回归测试用<b>强制出招</b>（仅服务端）：跳过加权选招与准入谓词（含 CD），
     * 但仍然走同一条 {@link AttackState} 管线——帧表、出招序号、不可打断闸门、接触集清理全部照常生效。
     *
     * <p>框架为什么要开这个口：没有它，"连放同一招第二下动画不重启"这类只有真机才暴露的问题
     * 根本无法写回归桩（选招是带随机的）。返回 false＝正在施法中／无此招／客户端调用。
     */
    public boolean forceMove(ResourceLocation moveId) {
        if (this.level().isClientSide) return false;
        MoveDef move = this.moveSet().byId(moveId);
        if (move == null || !this.stateController.isIdle()) return false;
        this.stateController.push(new AttackState(move));
        return true;
    }

    // ---------------- 阶段闸门 ----------------

    private void checkPhaseGates() {
        float[] thresholds = phaseThresholds();
        if (this.gatesFired == null) this.gatesFired = new boolean[thresholds.length];
        float ratio = this.getHealth() / this.getMaxHealth();
        for (int i = 0; i < thresholds.length; i++) {
            int targetPhase = i + 1;
            if (this.getPhase() < targetPhase && ratio <= thresholds[i]
                    && (this.gatesFired.length > i) && !this.gatesFired[i]) {
                this.gatesFired[i] = true;
                this.stateController.forcePush(new PhaseChangeState(this, targetPhase));
                return; // 一次只进一个阶段
            }
        }
    }

    /** 供 {@link PhaseChangeState} 调用：真正改阶段（同步广播给客户端）。 */
    void applyPhase(int phase) {
        this.entityData.set(DATA_PHASE, phase);
    }

    public int getPhase() { return this.entityData.get(DATA_PHASE); }

    /** 阶段门控谓词（招式 requires() 里可用）。 */
    public boolean phaseAtLeast(int phase) { return getPhase() >= phase; }

    public void setPhaseLock(boolean locked) { this.phaseLock = locked; }

    public boolean isPhaseLocked() { return this.phaseLock; }

    /** 换阶段清全部冷却（Ignis 的 resetAttacks 语义）。 */
    public void resetAttacks() { this.cooldowns.clear(); }

    // ---------------- 冷却 ----------------

    private void tickCooldowns() {
        this.cooldowns.entrySet().removeIf(e -> {
            e.setValue(e.getValue() - 1);
            return e.getValue() <= 0;
        });
    }

    public int cooldownLeft(ResourceLocation moveId) {
        return this.cooldowns.getOrDefault(moveId, 0);
    }

    // ---------------- 人数缩放（dumbcat 模式：变更才重算 + 百分比回填） ----------------

    private void tickScaling(ServerLevel server) {
        if (--this.scaleCooldown > 0) return;
        this.scaleCooldown = 10;
        int players = countEngagedPlayers(server);
        if (players == this.lastScalePlayers) return;
        applyScaling(players);
        this.lastScalePlayers = players;
    }

    private int countEngagedPlayers(ServerLevel server) {
        AABB box = this.getBoundingBox().inflate(40, 20, 40);
        List<ServerPlayer> nearby = server.getEntitiesOfClass(ServerPlayer.class, box,
                p -> p.isAlive() && !p.isSpectator() && !p.isCreative());
        return Math.max(1, nearby.size());
    }

    private void applyScaling(int players) {
        float ratio = this.getMaxHealth() > 0 ? this.getHealth() / this.getMaxHealth() : 1f;
        ScalingStrategy s = this.scaling();
        double hpMul = s.healthMultiplier(players) * ColossusConfig.GLOBAL_HEALTH_MULTIPLIER.get();
        double dmgMul = s.damageMultiplier(players) * ColossusConfig.GLOBAL_DAMAGE_MULTIPLIER.get();
        setOrRemove(this.getAttribute(Attributes.MAX_HEALTH), SCALE_HP_ID, hpMul - 1.0);
        setOrRemove(this.getAttribute(Attributes.ATTACK_DAMAGE), SCALE_DMG_ID, dmgMul - 1.0);
        if (this.getMaxHealth() > 0) {
            this.setHealth(Math.max(1.0f, ratio * this.getMaxHealth())); // 百分比回填
        }
    }

    private static void setOrRemove(@Nullable AttributeInstance instance, UUID id, double amount) {
        if (instance == null) return;
        instance.removeModifier(id);
        if (Math.abs(amount) > 1.0e-6) {
            instance.addTransientModifier(new AttributeModifier(id, "colossus_boss_scale",
                    amount, AttributeModifier.Operation.MULTIPLY_TOTAL));
        }
    }

    // ---------------- 音乐（服务端幂等布尔，客户端单例） ----------------

    private void tickMusic(ServerLevel server) {
        ResourceLocation music = this.bossMusic();
        if (music == null) return;
        boolean want = this.entityData.get(DATA_ACTIVATED)
                && !this.deathPending
                && this.bossEvent != null && !this.bossEvent.seenPlayers().isEmpty();
        if (want == this.musicLatch) return;
        this.musicLatch = want;
        ColossusPackets.sendToPlayers(trackerPlayers(), ColossusPackets.musicToggle(music.toString(), want));
    }

    // ==================== 招式同步帧（客户端动画读这里） ====================

    /**
     * 惰性构建招式表：Java DSL 为底、datapack 同名覆盖，并按 {@link MoveDataRegistry#revision()}
     * 决定要不要重建实例（第十四批·表/实例分离）。
     *
     * <p>热替换语义（DBE GraphRuntimeReloader 的等价简化）：换表只影响<b>下一次选招</b>；
     * 正在播的招由 {@code AttackState} 自己持着旧 {@link MoveDef}，帧表不会中途换脸。
     * registerMoves 只跑一次（DSL 是每实体一次性的），合并则每次换表都做。
     */
    @Nullable private java.util.List<MoveDef> javaMoves;
    private long moveDataRevision = -1L;

    public final MoveSet moveSet() {
        long rev = com.klze.colossus.move.MoveDataRegistry.revision();
        if (this.moveSet == null || this.moveDataRevision != rev) {
            if (this.javaMoves == null) {
                MoveSetBuilder builder = new MoveSetBuilder(this, this.getBossId());
                this.registerMoves(builder);
                this.javaMoves = builder.builtDefs();
            }
            this.moveSet = MoveSet.merge(this, this.javaMoves,
                    com.klze.colossus.move.MoveDataRegistry.defsFor(this.getBossId()));
            this.moveDataRevision = rev;
        }
        return this.moveSet;
    }

    void beginAttack(MoveDef move) {
        this.contacts.clear(); // 每次出招是全新接触集（DBE 窗口语义）
        this.entityData.set(DATA_ATTACK_INDEX, this.moveSet().indexOf(move.id()));
        this.entityData.set(DATA_ATTACK_TICK, 0);
        this.entityData.set(DATA_ATTACK_SEQ, this.entityData.get(DATA_ATTACK_SEQ) + 1); // 连放同招也要能重启动画
        this.cooldowns.put(move.id(), move.cooldownTicks() + move.duration());
        this.setActivated(true);
    }

    /** 认领接触键（同招内至多消费一次）——MoveTriggers.once/arcHitContacted 的后端。 */
    public boolean claimContact(String tag) {
        return this.contacts.claim(tag);
    }

    /** 命中后写给目标的 invulnerableTime（招式声明，默认 0=连段友好）。 */
    public int postHitInvulnerability() {
        MoveDef m = currentAttack();
        return m == null ? 0 : m.postAttackInvuln();
    }

    void syncAttackTick(int tick) {
        this.entityData.set(DATA_ATTACK_TICK, tick);
    }

    void syncAttackNone() {
        this.entityData.set(DATA_ATTACK_INDEX, -1);
        this.entityData.set(DATA_ATTACK_TICK, 0);
    }

    void endAttack(MoveDef move) {
        this.syncAttackNone(); // 冷却在 beginAttack 已预扣（cooldown+duration）
    }

    /**
     * 客户端：当前招式（按同步索引解析；-1 返回 null）。
     * ATTACK_INDEX 与 §0.2 的关系说清楚（审查 P2#21）：索引<b>只作实时显示解析</b>
     * （双端从同一份静态 MoveSet 解析、注册序天然一致），不进存档不进网络协议字段语义——
     * 一切持久化（日志/回归断言/未来的 JSON）都用 id 字符串。
     */
    @Nullable
    public MoveDef currentAttack() {
        int idx = this.entityData.get(DATA_ATTACK_INDEX);
        if (idx < 0) return null;
        var moves = this.moveSet().moves();
        return idx < moves.size() ? moves.get(idx) : null;
    }

    public int attackTick() { return this.entityData.get(DATA_ATTACK_TICK); }

    /**
     * 出招序号（单调递增，跨招式不清零）。<b>动画后端的重启判据应当用它，而不是 index/tick</b>：
     * {@code if (seq != lastSeq) { lastSeq = seq; controller.forceAnimationReset(); }}。
     * 服务端永不反向查询客户端"动画播完没"（GL4 无 C→S 通道，取证已判负），
     * 招式何时结束由帧表的 duration 决定。
     */
    public int attackSequence() { return this.entityData.get(DATA_ATTACK_SEQ); }

    /** 客户端动画进度 [0,1]（无动画后端时也有用：HUD 读条等）。 */
    public float attackProgress() {
        MoveDef m = currentAttack();
        return m == null ? 0f : Math.min(1f, this.attackTick() / (float) m.duration());
    }

    /** 战斗激活后不再自然消失。 */
    public void setActivated(boolean on) { this.entityData.set(DATA_ACTIVATED, on); }

    public boolean isActivated() { return this.entityData.get(DATA_ACTIVATED); }

    // ==================== 伤害与目标 ====================

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.deathPending || this.phaseLock) return false;
        return applyBodyHit(source, amount);
    }

    /** hurt/hurtPart 共用的落伤体（单次伤害上限 + 参战收集——从 hurt() 抽出防双实现漂移）。 */
    private boolean applyBodyHit(DamageSource source, float amount) {
        float scale = 1.0f;
        float raw = amount;
        boolean willLand = false; // "未被拒收"判据（第四轮 P2#1）：分流不能押在 hurt 返回值上——
        if (!this.level().isClientSide) {
            willLand = this.invulnerableTime == 0 || source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY);
            if (this.maxDamagePerHit() > 0 && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
                raw = Math.min(amount, this.maxDamagePerHit());
            }
            scale = net.minecraft.util.Mth.clamp(this.incomingDamageScale(source), 0.0f, 1.0f);
            amount = raw * scale; // 差额=分流额：无除法，scale=0 也安全
        }
        float toBody = amount;
        if (!this.level().isClientSide && amount > 0.0f && this.maxShield() > 0.0f) {
            // 护盾吃在护壳分流<b>之后</b>：壳先按比例分流，剩下的才轮到盾吸收（两条机制不叠乘）
            float soaked = Math.min(this.entityData.get(DATA_SHIELD), toBody);
            if (soaked > 0.0f) {
                this.lastShieldHitGameTime = this.level().getGameTime();
                toBody -= soaked;
                float left = this.entityData.get(DATA_SHIELD) - soaked;
                this.setShield(left);
                if (left <= 0.0f) onShieldBroken(source);
            }
        }
        boolean hit = super.hurt(source, toBody);
        if (!this.level().isClientSide && willLand && scale < 1.0f) {
            // 落伤被护甲吃到 0 / scale=0 时 hurt 返回 false，但那一刀确实"打在壳上"——
            // 护壳必须照常掉，否则"完全免伤"=永远破不了壳的死局（第四轮审查 P2#1）
            divertToProtection(raw * (1.0f - scale));
        }
        if (willLand && !this.level().isClientSide) {
            // 记账判据用 willLand 而不是 hit：被盾整个吃掉、或被护甲吃到 0 的一刀，
            // 玩家照样是在"打 Boss"——押在 hurt 返回值上会让纯吸收的攻击不进参战账
            this.setActivated(true);
            if (source.getEntity() instanceof ServerPlayer p) {
                this.engagement.onContribution(p);
            } else if (source.getDirectEntity() instanceof ServerPlayer p) {
                this.engagement.onContribution(p);
            }
        }
        return hit;
    }

    // ---------------- 部件弱点：护壳分流（服务端权威，零客户端注入） ----------------

    /**
     * 本体受击倍率——有"护壳部件"存活时返回 <1（示例：核心在位吃 30%）。
     * 设计裁决（v5 取证）：Forge 1.20.1 射线打不到 PartEntity——玩家"可瞄准的部件"
     * 应做成真实体 squad（Kraken 触手先例）；PartEntity 岗位的部件走本钩数的
     * 间接弱点路径：打本体 → 分流击破 → 减伤消失。
     */
    protected float incomingDamageScale(DamageSource source) {
        return 1.0f;
    }

    /** 部件 index 的护壳击破阈值（累计分流伤害；<=0 = 不参与分流）。 */
    protected float partBreakThreshold(int partIndex) {
        return 0f;
    }

    /** 护壳被击碎（子类可做播报/换阶段；默认清 ACTIVE+置 DEAD 并同步）。 */
    protected void onProtectionBroken(int partIndex) {
        sendBossVisualEvent("colossus:protection_broken_" + partIndex);
    }

    private void divertToProtection(float diverted) {
        for (var part : this.parts) {
            int idx = part.partIndex();
            if (!part.isPartActive() || this.partBreakThreshold(idx) <= 0) continue;
            float acc = this.partDamage.merge(idx, diverted, Float::sum);
            this.setPartFlag(idx, com.klze.colossus.entity.part.PartStates.FLAG_DAMAGED,
                    acc > this.partBreakThreshold(idx) * 0.5f);
            if (acc >= this.partBreakThreshold(idx)) {
                this.partDamage.remove(idx);
                this.setPartFlag(idx, com.klze.colossus.entity.part.PartStates.FLAG_ACTIVE, false);
                this.setPartFlag(idx, com.klze.colossus.entity.part.PartStates.FLAG_DEAD, true);
                part.setPartActive(false);
                this.onProtectionBroken(idx);
            }
            return; // 只分给第一个在册护壳
        }
    }

    // ==================== 分体系统（第六批） ====================

    private final java.util.List<com.klze.colossus.entity.part.ColossusBossPart<?>> parts =
            new java.util.ArrayList<>();
    /** 护壳击破账（partIndex → 累计分流伤害；服务端私有，位图才是同步面）。 */
    private final java.util.Map<Integer, Float> partDamage = new java.util.HashMap<>();

    /** 子类构造器里登记部件（双侧同构造——部件永不入 level，见 ColossusBossPart 类注）。 */
    protected void registerPart(com.klze.colossus.entity.part.ColossusBossPart<?> part) {
        this.parts.add(part);
        this.setPartFlag(part.partIndex(),
                com.klze.colossus.entity.part.PartStates.FLAG_ACTIVE, true);
    }

    // ---------------- 部件位图（entityData 唯一载体；双端经 onSyncedDataUpdated 回放） ----------------

    public long partBits() {
        return this.entityData.get(DATA_PART_BITS);
    }

    public boolean partFlag(int partIndex, int flag) {
        return com.klze.colossus.entity.part.PartStates.hasFlag(this.partBits(), partIndex, flag);
    }

    public boolean partActive(int partIndex) {
        return this.partFlag(partIndex, com.klze.colossus.entity.part.PartStates.FLAG_ACTIVE);
    }

    /** 服务端置位（客户端经同步回放，见 onSyncedDataUpdated）。 */
    public void setPartFlag(int partIndex, int flag, boolean on) {
        this.entityData.set(DATA_PART_BITS,
                com.klze.colossus.entity.part.PartStates.withFlag(this.partBits(), partIndex, flag, on));
    }

    /** 位图变化 → 把 ACTIVE 位回放到部件实例（两侧同逻辑；客户端额外的视觉钩子覆写这里）。 */
    public void onPartFlagsChanged(long bits) {
        for (var part : this.parts) {
            boolean on = com.klze.colossus.entity.part.PartStates.hasFlag(bits, part.partIndex(),
                    com.klze.colossus.entity.part.PartStates.FLAG_ACTIVE);
            if (part.isPartActive() != on) part.setPartActive(on);
        }
    }

    public java.util.List<com.klze.colossus.entity.part.ColossusBossPart<?>> colossusParts() {
        return java.util.Collections.unmodifiableList(this.parts);
    }

    /** 部件受击入口（单向转发，无回环）。倍率只在部件侧乘一次（数值铁律）。 */
    public boolean hurtPart(com.klze.colossus.entity.part.ColossusBossPart<?> part,
                            DamageSource source, float amount) {
        if (!partGate(part, source, amount)) return false;
        return applyBodyHit(source, amount * part.damageMultiplier());
    }

    /** 伤害闸门链（Hydra 序：免伤态→自定义门）。子类可叠姿态/相位条件。 */
    protected boolean partGate(com.klze.colossus.entity.part.ColossusBossPart<?> part,
                               DamageSource source, float amount) {
        return !this.deathPending && !this.phaseLock && part.isPartActive();
    }

    /** 部件位置：parent 每服务端 tick 用局部锚点×身体朝向解算（零包，双侧可推）。 */
    private void positionParts() {
        for (var part : this.parts) {
            if (!part.isPartActive()) continue;
            part.setPos(com.klze.colossus.anim.TableSampler.toWorld(
                    this, part.localOffset()));
        }
    }

    // Forge IForgeEntity 默认方法：仅 mod 间约定（Forge 零消费者，取证），自家遍历用。
    @Override
    public boolean isMultipartEntity() {
        return !this.parts.isEmpty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public net.minecraftforge.entity.PartEntity<?>[] getParts() {
        return this.parts.toArray(new net.minecraftforge.entity.PartEntity[0]);
    }

    /** 死亡演出期间的控制效果免疫（防"死亡动画里被冻住"的怪状态）。 */
    @Override
    public boolean canBeAffected(MobEffectInstance effect) {
        if (this.deathPending) return false;
        return super.canBeAffected(effect);
    }

    /** 面朝目标（AttackState 每 tick 调；锁身体不锁头，允许动画错位感）。 */
    public void lookAtTarget(LivingEntity target) {
        double dx = target.getX() - this.getX();
        double dz = target.getZ() - this.getZ();
        if (dx * dx + dz * dz < 1.0e-2) return;
        this.setYRot((float) (Math.atan2(dz, dx) * 180.0F / Math.PI) - 90.0F);
        this.yBodyRot = this.getYRot();
        this.yHeadRot = this.getYRot();
    }

    // ==================== 死亡管线 ====================

    @Override
    public void die(DamageSource source) {
        if (!this.deathPending) {
            // 钉住血量挂起死亡——原版死亡序列延迟到演出结束（六样本共同刚需）
            this.deathPending = true;
            this.setHealth(1.0f);
            this.stateController.forcePush(new DeathState(this, source));
            return;
        }
        if (!this.deathResolved) return;
        super.die(source);
    }

    /** {@link DeathState} 驱动。 */
    void syncDeathTick(int tick) { this.entityData.set(DATA_DEATH_TICK, tick); }

    /** 死亡演出帧号（客户端渲染层驱动死亡动画用）。 */
    public int deathTick() { return this.entityData.get(DATA_DEATH_TICK); }

    /** 服务端死亡挂起标记（transient，不上网络）——客户端死亡态判据用 {@code deathTick()>0}。 */
    public boolean isDeathPending() { return this.deathPending; }

    /** 演出开场副作用：血条清零并隐藏/停音乐/清延迟队列（渲染层读 DEATH_TICK）。 */
    void onDeathSequenceStart() {
        this.workQueue.clear(); // 死亡后不再结算旧的 telegraph/延迟动作（审查 P2）
        if (this.bossEvent != null) {
            this.bossEvent.setProgress(0f);
            this.bossEvent.setVisible(false);
        }
        ResourceLocation music = this.bossMusic();
        if (music != null && this.musicLatch) {
            this.musicLatch = false;
            ColossusPackets.sendToPlayers(trackerPlayers(), ColossusPackets.musicToggle(music.toString(), false));
        }
    }

    private List<ServerPlayer> trackerPlayers() {
        if (this.bossEvent == null) return List.of();
        return List.copyOf(this.bossEvent.seenPlayers());
    }

    /** 演出结束：击杀板/共享 credit/挑战计数/首杀播报/战利品交付，然后放行原版死亡序列。
     *  注意：直接 super.die——绝不能再走 this.die（deathPending 仍为 true，会二次挂起成死循环）。 */
    void resolveDeath(DamageSource source) {
        this.deathResolved = true;
        if (this.level() instanceof ServerLevel server) {
            BossKillBoard board = BossKillBoard.get(server);
            boolean firstKill = board.recordKill(this.getBossId());
            if (firstKill) broadcastFirstKill(server);
            for (ServerPlayer p : this.engagement.participants(server.getServer())) {
                net.minecraft.advancements.CriteriaTriggers.PLAYER_KILLED_ENTITY
                        .trigger(p, this, source); // 参与即有 credit
                BossKillBoard.increaseChallenge(p, this.getBossId());
            }
            deliverLoot(server);
            var arena = this.arena();
            if (arena != null && arena.isActive()) arena.victory(server); // 胜利解封（第七批）
            if (!this.squad().defs().isEmpty()) this.squad().notifyLeaderDeath(server); // 收摊（第九批）
        }
        // deathPending 保持 true：原版死亡序列（deathTime→remove）期间仍拒绝一切伤害
        this.setHealth(0.0f);
        super.die(source);
    }

    // ==================== 战利品管线（v0.2 第五批，TF 缓冲入箱形） ====================

    /** 交付策略。默认原版即时掉落；剧情大 Boss 建议 INTO_CHEST（开箱仪式感+防丢失）。 */
    protected com.klze.colossus.loot.LootDelivery lootDelivery() {
        return com.klze.colossus.loot.LootDelivery.DROP_NOW;
    }

    /** INTO_CHEST 用哪个容器方块（可换成 mod 主题箱）。null = 禁用放箱、退化为落地。 */
    @Nullable
    protected net.minecraft.world.level.block.Block lootChestBlock() {
        return net.minecraft.world.level.block.Blocks.CHEST;
    }

    /** 死亡战利品缓冲（27 格，随实体 NBT 持久化——防烧/防过期/防崩档）。 */
    @Nullable
    private net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> deathLootBuffer;

    /** 交付策略生效值：未实现的策略（INTO_BAG=v0.3）警告并回落 DROP_NOW——绝不静默吞 loot（审查 P1#4）。 */
    private com.klze.colossus.loot.LootDelivery effectiveDelivery() {
        com.klze.colossus.loot.LootDelivery d = this.lootDelivery();
        if (d == com.klze.colossus.loot.LootDelivery.INTO_BAG) {
            Colossus.LOGGER.warn("{} requests INTO_BAG loot but it is not implemented in v0.2; falling back to DROP_NOW",
                    this.getBossId());
            return com.klze.colossus.loot.LootDelivery.DROP_NOW;
        }
        return d;
    }

    @Override
    protected boolean shouldDropLoot() {
        // INTO_CHEST：接管交付，禁掉原版落地（防双落）
        return this.effectiveDelivery() == com.klze.colossus.loot.LootDelivery.DROP_NOW
                && super.shouldDropLoot();
    }

    /** 演出开场：INTO_CHEST 策略下立刻 roll 一次入缓冲（TF 时序：先 roll 后演）。 */
    void rollDeathLoot(DamageSource source) {
        if (this.effectiveDelivery() != com.klze.colossus.loot.LootDelivery.INTO_CHEST) return;
        if (this.deathLootBuffer != null) return; // 读档续演：沿用已持久化的缓冲，不重 roll
        if (!(this.level() instanceof ServerLevel server)) return;
        ResourceLocation lootId = this.getLootTable();
        if (lootId == null) return;
        net.minecraft.world.level.storage.loot.LootTable table =
                server.getServer().getLootData().getLootTable(lootId);
        if (table == null) return;
        net.minecraft.world.level.storage.loot.LootParams.Builder ctx =
                new net.minecraft.world.level.storage.loot.LootParams.Builder(server)
                        .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.THIS_ENTITY, this)
                        .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN, this.position())
                        .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.DAMAGE_SOURCE, source)
                        .withOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.KILLER_ENTITY, source.getEntity())
                        .withOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.DIRECT_KILLER_ENTITY, source.getDirectEntity());
        if (source.getEntity() instanceof ServerPlayer p) {
            ctx.withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.LAST_DAMAGE_PLAYER, p)
                    .withLuck(p.getLuck());
        }
        java.util.List<net.minecraft.world.item.ItemStack> rolled = new java.util.ArrayList<>();
        table.getRandomItems(
                ctx.create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.ENTITY),
                this.random.nextLong(), rolled::add);
        this.deathLootBuffer = net.minecraft.core.NonNullList.createWithCapacity(27);
        for (net.minecraft.world.item.ItemStack stack : rolled) {
            if (stack.isEmpty()) continue;
            if (this.deathLootBuffer.size() < 27) {
                this.deathLootBuffer.add(stack.copy());
            } else {
                // 溢出兜底当场落地（TF 在此处有 off-by-one 静默丢第 28 堆——我们不复制这个洞）
                dropOverflow(server, stack);
            }
        }
    }

    /** 兜底落地：延长寿命 + 免拾取延迟（TF 形）。 */
    private void dropOverflow(ServerLevel server, net.minecraft.world.item.ItemStack stack) {
        net.minecraft.world.entity.item.ItemEntity it = this.spawnAtLocation(stack);
        if (it != null) {
            it.setExtendedLifetime();
            // 拾取延迟用原版默认（1.20.1 无公开 setter；死亡演出 100t+，玩家来得及）
        }
    }

    /** 演出结束：缓冲灌进尸体处箱；放不下去退化为落地，绝不静默丢。 */
    private void deliverLoot(ServerLevel server) {
        if (this.deathLootBuffer == null || this.deathLootBuffer.isEmpty()) return;
        net.minecraft.world.level.block.Block chest = this.lootChestBlock();
        java.util.List<net.minecraft.world.item.ItemStack> pendingList = new java.util.ArrayList<>();
        for (net.minecraft.world.item.ItemStack stack : this.deathLootBuffer) {
            if (!stack.isEmpty()) pendingList.add(stack);
        }
        net.minecraft.world.item.ItemStack[] pending =
                pendingList.toArray(new net.minecraft.world.item.ItemStack[0]);
        this.deathLootBuffer = null;
        if (pending.length == 0) return;

        boolean placed = false;
        if (chest != null) {
            BlockPos pos = findChestSpot(server);
            if (pos != null) {
                server.setBlock(pos, chest.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                if (server.getBlockEntity(pos) instanceof net.minecraft.world.WorldlyContainer container) {
                    for (int i = 0; i < pending.length && i < container.getContainerSize(); i++) {
                        container.setItem(i, pending[i]);
                    }
                    placed = true;
                    // 超出容器容量的尾部仍要落地
                    for (int i = container.getContainerSize(); i < pending.length; i++) {
                        dropOverflow(server, pending[i]);
                    }
                } else {
                    server.removeBlock(pos, false); // 没成容器就还原，走落地兜底
                }
            }
        }
        if (!placed) {
            for (net.minecraft.world.item.ItemStack stack : pending) {
                dropOverflow(server, stack);
            }
        }
    }

    /** 尸体脚下向上找可放箱位（最多抬 3 格）。 */
    @Nullable
    private BlockPos findChestSpot(ServerLevel server) {
        BlockPos.MutableBlockPos cursor =
                new BlockPos.MutableBlockPos(this.getX(), this.getY(), this.getZ());
        for (int up = 0; up <= 3; up++) {
            BlockState existing = server.getBlockState(cursor);
            if (existing.canBeReplaced()) return cursor.immutable();
            cursor.move(net.minecraft.core.Direction.UP);
        }
        return null;
    }

    /** 首杀全服播报（Cataclysm CMWorldData 形：KillBoard 权威、actionbar 广播）。 */
    private void broadcastFirstKill(ServerLevel server) {
        ResourceLocation id = this.getBossId();
        Component msg = Component.translatable(
                "colossus.first_kill." + id.getNamespace() + "." + id.getPath(), this.getName());
        for (ServerPlayer p : server.getServer().getPlayerList().getPlayers()) {
            if (!p.isSpectator()) p.displayClientMessage(msg, true);
        }
    }

    // ==================== 附加资源条（护盾/能量，第十一批） ====================

    /** 上限（0＝无护盾）。内容侧自行决定它是常量还是随相位变（示范 Boss 就是相位门控）。 */
    protected float maxShield() { return 0.0f; }

    /** 受击后隔多久开始回充（0＝不回充）。 */
    protected int shieldRegenDelayTicks() { return 0; }

    /** 回充速率（点/tick）。 */
    protected float shieldRegenPerTick() { return 0.0f; }

    public float shield() { return this.entityData.get(DATA_SHIELD); }

    /**
     * 设定护盾：服务端钳进 {@code [0, maxShield()]}、非有限值当 0 处理，并刷新血条镜像。
     * "NaN 从源头挡"是取证点名的事故（DE 用 {@code power/configMax}，配置 0 → NaN →
     * {@code NaN != NaN} 恒真 → 每 tick 向全体玩家发包）。
     */
    public void setShield(float value) {
        float max = Math.max(0.0f, this.maxShield());
        float clamped = Float.isFinite(value) ? net.minecraft.util.Mth.clamp(value, 0.0f, max) : 0.0f;
        if (Float.compare(this.entityData.get(DATA_SHIELD), clamped) == 0) return;
        this.entityData.set(DATA_SHIELD, clamped);
        if (this.bossEvent != null && max > 0.0f) this.bossEvent.syncShield(clamped / max);
    }

    /** 破盾回调（默认只广播演出事件；内容侧可在此挂无敌帧/相位推进/狂暴）。 */
    protected void onShieldBroken(DamageSource source) {
        this.sendBossVisualEvent("colossus:shield_break");
    }

    /** -1＝从未受击。<b>不能用 Long.MIN_VALUE 当哨兵</b>：{@code gameTime - MIN_VALUE} 会溢出成负数，
     *  于是"< 回充延迟"恒真、护盾永远回不起来（护盾桩第一条红就是它）。 */
    private long lastShieldHitGameTime = -1L;

    /** 每服务端 tick：脱战回充（护盾条的形状取自 DE，但走本框架的零包法则）。 */
    private void tickShieldRegen(ServerLevel server) {
        float max = this.maxShield();
        if (max <= 0.0f || this.shieldRegenPerTick() <= 0.0f || this.shield() >= max) return;
        if (this.lastShieldHitGameTime >= 0L
                && server.getGameTime() - this.lastShieldHitGameTime < this.shieldRegenDelayTicks()) return;
        this.setShield(this.shield() + this.shieldRegenPerTick());
    }

    // ==================== 竞技场会话 & squad（第七批） ====================

    /** 声明式竞技场（null=无会话）。封路/纠偏/团灭复位全由 ArenaSession 代管。 */
    @Nullable
    protected com.klze.colossus.env.ArenaSession.Spec arenaSpec() { return null; }

    /** squad 装配点：子类 {@code squad.add(new MemberDef(...))} 若干次。 */
    protected void registerSquad(com.klze.colossus.entity.squad.SquadManager squad) {}

    @Nullable private com.klze.colossus.env.ArenaSession arena;
    @Nullable private com.klze.colossus.entity.squad.SquadManager squadManager;

    public com.klze.colossus.entity.squad.SquadManager squad() {
        if (this.squadManager == null) {
            this.squadManager = new com.klze.colossus.entity.squad.SquadManager(this);
            this.registerSquad(this.squadManager);
        }
        return this.squadManager;
    }

    @Nullable
    public com.klze.colossus.env.ArenaSession arena() {
        var spec = this.arenaSpecCached();
        if (spec == null) return null;
        if (this.arena == null) this.arena = new com.klze.colossus.env.ArenaSession(this, spec);
        return this.arena;
    }

    /**
     * {@link #arenaSpec()} 只在首次探测时调一次（审查轮 5 P3-7）：
     * arena() 每 tick 都被调，而示范 Boss 的 spec 每次新建 List+Vec3+record＝每 Boss 每 tick 一份垃圾。
     * 探针标志而非"null 即未探测"，是为了让返回 null 的子类也只算一次。
     */
    @Nullable private com.klze.colossus.env.ArenaSession.Spec arenaSpecCache;
    private boolean arenaSpecProbed;

    @Nullable
    private com.klze.colossus.env.ArenaSession.Spec arenaSpecCached() {
        if (!this.arenaSpecProbed) {
            this.arenaSpecProbed = true;
            this.arenaSpecCache = this.arenaSpec();
        }
        return this.arenaSpecCache;
    }

    private void tickSessionAndSquad(ServerLevel server) {
        var arena = this.arena();
        if (arena != null) {
            if (!arena.isActive() && this.isActivated() && !this.deathPending) {
                arena.begin(server);
            }
            if (arena.isActive()) arena.tick(server);
        }
        var squad = this.squad();
        if (!squad.defs().isEmpty()) {
            if (this.isActivated() && !this.deathPending) {
                squad.spawnMissing(server); // 幂等补员
                squad.tick(server);
            }
        }
    }

    // ==================== 血条装配 ====================

    /** 懒建血条；渲染/HUD 侧的框架句柄。 */
    @Nullable
    public ColossusBossEvent bossBar() { return this.bossEvent; }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        if (this.level().isClientSide) return;
        if (this.bossEvent == null) {
            this.bossEvent = this.createBossEvent();
            // 死亡演出期懒建（读档续演/晚入场）：血条直接建成"已清零+隐藏"，
            // 否则满血条挂着且死亡期不回填（回归审查 P3#5）
            if (this.deathPending) {
                this.bossEvent.setProgress(0f);
                this.bossEvent.setVisible(false);
            }
        }
        this.bossEvent.addPlayer(player);
        // 后入场/重连补发音乐现状（审查 P2#17：latch 只在变化时发包，晚到者永远听不到）
        ResourceLocation music = this.bossMusic();
        if (music != null && this.musicLatch) {
            ColossusPackets.sendToPlayer(ColossusPackets.musicToggle(music.toString(), true), player);
        }
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        if (this.bossEvent != null) {
            this.bossEvent.removePlayer(player);
        }
    }

    /** 参战名单（击杀结算/奖励/成就的权威来源）。 */
    public EngagementTracker engagement() { return this.engagement; }

    /**
     * 血条进度所有权（修 Kraken 的"父类每 tick 硬 setProgress、子类同 tick 覆盖"脆弱点）：
     * 多实体共一条 bar 的 Boss（触手均值、段位读数）覆写这里，而不是在子类 tick 里再 set。
     */
    protected float barProgress() {
        if (this.squadManager != null && !this.squadManager.defs().isEmpty()) {
            return this.squadManager.barProgress(); // squad 均值制（分母=定义总数+本体，防回弹）
        }
        return this.getMaxHealth() > 0 ? this.getHealth() / this.getMaxHealth() : 0f;
    }

    // ==================== 演出事件 ====================

    /** 向追踪玩家广播类型化客户端事件（粒子/刀光/震屏的派发源）。 */
    public void sendBossVisualEvent(String eventId) {
        if (this.level() instanceof ServerLevel) {
            ColossusPackets.sendToTrackers(ColossusPackets.visualEvent(this.getId(), eventId), this);
        }
    }

    // ==================== 持久化与同步 ====================

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_PHASE, 0);
        this.entityData.define(DATA_ATTACK_INDEX, -1);
        this.entityData.define(DATA_ATTACK_TICK, 0);
        this.entityData.define(DATA_ATTACK_SEQ, 0);
        this.entityData.define(DATA_SHIELD, 0.0f);
        this.entityData.define(DATA_DEATH_TICK, 0);
        this.entityData.define(DATA_ACTIVATED, false);
        this.entityData.define(DATA_PART_BITS, 0L);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_PART_BITS.equals(key)) {
            this.onPartFlagsChanged(this.partBits());
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("colossus_phase", this.getPhase());
        tag.putBoolean("colossus_activated", this.isActivated());
        tag.putBoolean("colossus_dying", this.deathPending && !this.deathResolved);
        tag.putLong("colossus_part_bits", this.partBits()); // entityData 不落盘——位图自管持久化
        if (this.arena != null) {
            CompoundTag arenaTag = new CompoundTag();
            this.arena.save(arenaTag);
            tag.put("colossus_arena", arenaTag);
        }
        if (this.squadManager != null) {
            CompoundTag squadTag = new CompoundTag();
            this.squadManager.save(squadTag);
            tag.put("colossus_squad", squadTag);
        }
        if (!this.partDamage.isEmpty()) {
            CompoundTag pd = new CompoundTag();
            this.partDamage.forEach((k, v) -> pd.putFloat("p" + k, v));
            tag.put("colossus_part_damage", pd); // 分流账也落盘——重载后击破进度不回滚（回归审查 P3#10）
            tag.putFloat("colossus_shield", this.entityData.get(DATA_SHIELD)); // 护盾值同样落盘（重载不清零）
        }
        if (this.getMaxHealth() > 0) {
            tag.putFloat("colossus_hp_ratio", this.getHealth() / this.getMaxHealth());
        }
        if (this.deathLootBuffer != null) {
            CompoundTag lootTag = new CompoundTag();
            net.minecraft.world.ContainerHelper.saveAllItems(lootTag, this.deathLootBuffer);
            tag.put("ColossusDeathItems", lootTag);
        }
        if (this.gatesFired != null) {
            byte[] fired = new byte[this.gatesFired.length];
            for (int i = 0; i < fired.length; i++) fired[i] = (byte) (this.gatesFired[i] ? 1 : 0);
            tag.putByteArray("colossus_gates", fired);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.entityData.set(DATA_PHASE, tag.getInt("colossus_phase"));
        this.setActivated(tag.getBoolean("colossus_activated"));
        if (tag.contains("colossus_gates")) {
            byte[] fired = tag.getByteArray("colossus_gates");
            this.gatesFired = new boolean[fired.length];
            for (int i = 0; i < fired.length; i++) this.gatesFired[i] = fired[i] != 0;
        }
        if (tag.contains("ColossusDeathItems", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            // loadAllItems 按槽位 set——必须是预分配 27 格（TF 同款做法）
            this.deathLootBuffer = net.minecraft.core.NonNullList.withSize(27,
                    net.minecraft.world.item.ItemStack.EMPTY);
            net.minecraft.world.ContainerHelper.loadAllItems(
                    tag.getCompound("ColossusDeathItems"), this.deathLootBuffer);
        }
        // 死亡演出中被卸载：续上死亡流程（deathPending 是 transient，必须显式恢复——审查 P1）
        if (tag.getBoolean("colossus_dying") && !this.level().isClientSide) {
            this.deathPending = true;
            this.resumeDeathPending = true;
        }
        // 缩放修饰符是 transient，读档即失效→原版按基础 max 截断过血量；
        // 这里立刻重挂修饰符并按保存的血量百分比回填（审查 P2"读档掉血"）
        if (tag.contains("colossus_hp_ratio") && !this.deathPending
                && this.level() instanceof ServerLevel server) {
            applyScaling(countEngagedPlayers(server));
            this.lastScalePlayers = countEngagedPlayers(server);
            float ratio = tag.getFloat("colossus_hp_ratio");
            this.setHealth(net.minecraft.util.Mth.clamp(ratio * this.getMaxHealth(), 1.0f, this.getMaxHealth()));
        } else {
            this.lastScalePlayers = -1; // 首个 aiStep 重新收敛
        }
        if (tag.contains("colossus_part_bits")) {
            this.entityData.set(DATA_PART_BITS, tag.getLong("colossus_part_bits"));
        }
        if (tag.contains("colossus_arena", net.minecraft.nbt.Tag.TAG_COMPOUND)
                && !this.level().isClientSide) {
            var arena = this.arena();
            if (arena != null) arena.load(tag.getCompound("colossus_arena"), this.level());
        }
        if (tag.contains("colossus_squad", net.minecraft.nbt.Tag.TAG_COMPOUND)
                && !this.level().isClientSide) {
            this.squad().load(tag.getCompound("colossus_squad"));
        }
        if (tag.contains("colossus_shield", net.minecraft.nbt.Tag.TAG_FLOAT)
                && !this.level().isClientSide) {
            this.setShield(tag.getFloat("colossus_shield"));
        }
        if (tag.contains("colossus_part_damage", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            CompoundTag pd = tag.getCompound("colossus_part_damage");
            this.partDamage.clear();
            for (String k : pd.getAllKeys()) {
                if (!k.startsWith("p")) continue;
                try {
                    int idx = Integer.parseInt(k.substring(1));
                    if (idx >= 0 && idx < com.klze.colossus.entity.part.PartStates.MAX_PARTS) {
                        this.partDamage.put(idx, pd.getFloat(k));
                    }
                } catch (RuntimeException ignored) {
                    // 损坏存档：StringTag 混进 NumberTag 读会 CCE——一并吞掉该键（第四轮 P3#2）
                }
            }
        }
        this.onPartFlagsChanged(this.partBits()); // 读档后部件 ACTIVE 与位图对账
        // v0.1 限制：读档后状态栈归零回 idle（整栈 NBT 持久化在 v0.2 计划）
    }

    /** 永不自然消失（boss 标准行为）。 */
    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    // ==================== 注册/属性 ====================

    /** 默认 Boss 属性盘：子类 builder 里用 {@code ColossusBossEntity.baseBossAttributes()} 起步。 */
    public static AttributeSupplier.Builder baseBossAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 200.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 8.0)
                .add(Attributes.FOLLOW_RANGE, 64.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.ARMOR, 10.0);
    }

    @Override
    @Nullable
    public net.minecraft.world.entity.SpawnGroupData finalizeSpawn(
            ServerLevelAccessor level, DifficultyInstance difficulty,
            MobSpawnType reason, @Nullable net.minecraft.world.entity.SpawnGroupData groupData,
            @Nullable CompoundTag spawnTag) {
        super.finalizeSpawn(level, difficulty, reason, groupData, spawnTag);
        // 初次落地即按在场玩家数定档（之后每 10t 动态复查）
        this.lastScalePlayers = -1;
        if (level instanceof ServerLevel server) {
            applyScaling(countEngagedPlayers(server));
            this.lastScalePlayers = countEngagedPlayers(server);
        }
        BossDefinition def = BossDefinition.get(this.getBossId());
        if (def == null) {
            Colossus.LOGGER.warn("Boss {} spawned without a BossDefinition registration", this.getBossId());
        }
        return groupData;
    }
}
