package com.klze.colossus.entity;

import com.klze.colossus.Colossus;
import com.klze.colossus.ColossusConfig;
import com.klze.colossus.bar.ColossusBossEvent;
import com.klze.colossus.env.TelegraphZone;
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
 * <p>同步契约：{@code PHASE / ATTACK_ID / ATTACK_ANIM / ATTACK_DURATION / ATTACK_TICK / ATTACK_SEQ / DEATH_TICK / ACTIVATED
 * / TELEGRAPHS}
 * 全走 entityData——战斗状态零自定义包。第二十四批起危险区轮廓也在这份清单里
 * （它原本是最后一条"发完就不管"的自定义包，见 {@link #DATA_TELEGRAPHS}）。
 */
public abstract class ColossusBossEntity extends Monster {

    // ---------------- 同步数据（客户端渲染的唯一事实源） ----------------

    private static final EntityDataAccessor<Integer> DATA_PHASE =
            SynchedEntityData.defineId(ColossusBossEntity.class, EntityDataSerializers.INT);
    /**
     * 当前招式的<b>字符串 id</b>（空串=无招式）。
     *
     * <p>这里曾经是 {@code DATA_ATTACK_INDEX}（数组下标）。轮 6 审查把它判死：
     * datapack 招式表的 {@code AddReloadListenerEvent} 只在服务端 reload 路径派发
     * （{@code ReloadableServerResources:77} ← {@code MinecraftServer:1323}/{@code WorldLoader:38}），
     * 多人客户端根本没有那张表——索引在客户端要么越界无招，要么拿<b>上一个单人世界</b>的表反解出错姿态。
     * 现在协议面只走字符串 id（§0.2 的本意），显示层要什么名字自己解析，解析不到就退化成"无动画"，
     * 而不是"动画对不上判定"。
     */
    private static final EntityDataAccessor<String> DATA_ATTACK_ID =
            SynchedEntityData.defineId(ColossusBossEntity.class, EntityDataSerializers.STRING);
    /**
     * 当前招式的<b>动画名</b>（同步）。轮 7 P1-2：这个串必须由服务端直接下发，
     * 不能让客户端从 id 派生——Java DSL 的默认 animName 是 id 的 <b>path</b>（"smash"），
     * 派生出来的是 "colossus:smash"，同一招双端两个键，GL 侧查不到就静默 stop。
     */
    private static final EntityDataAccessor<String> DATA_ATTACK_ANIM =
            SynchedEntityData.defineId(ColossusBossEntity.class, EntityDataSerializers.STRING);
    /** 当前招式时长（同步）。客户端算进度比例不再需要查表——表可能是空的。 */
    private static final EntityDataAccessor<Integer> DATA_ATTACK_DURATION =
            SynchedEntityData.defineId(ColossusBossEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ATTACK_TICK =
            SynchedEntityData.defineId(ColossusBossEntity.class, EntityDataSerializers.INT);
    /**
     * 出招序号（每次 {@code beginAttack} +1，**只增不清零**）。详见 {@link #attackSequence()}：
     * 招式 id/动画名在"连放同一招"时都不变，只有这个递增序号能告诉客户端"这是一次新的施法"。
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

    /**
     * 延迟工作队列（unusualend queueServerWork 模式），第十六批起<b>可持久化</b>：
     * 一条待办 = {@code (绝对 gameTime 到期, 工作种类, NBT 数据)}，所以能随实体存档、重载后继续到期。
     * 旧形态存的是 {@code Runnable} 且按 {@code tickCount} 计数——既写不进 NBT，
     * 重载后又把"还剩几 tick"当成"从 0 起第几 tick"，telegraph 要么凭空消失要么立刻结算。
     */
    /**
     * 按<b>到期时刻</b>排序（轮 7 P2-1）：{@code ArrayDeque} 只保证插入序，
     * "先排 60t 的长延后接一排 5t 的短延"时短的那发会被长的头阻塞。
     */
    private final java.util.PriorityQueue<DeferredWork> workQueue =
            new java.util.PriorityQueue<>(8, java.util.Comparator.comparingLong(DeferredWork::dueGameTime));

    /** 在途待办上限（本仓别处都封了条数，这里不封就是高频 telegraph 把 NBT 越写越大）。 */
    private static final int MAX_PENDING_WORK = 32;

    /** 一条待办；{@code kind} 必须能在 {@link #DEFERRED_WORKS} 里查到。 */
    public record DeferredWork(long dueGameTime, String kind, CompoundTag data) {}

    private static final Map<String, java.util.function.BiConsumer<ColossusBossEntity, CompoundTag>>
            DEFERRED_WORKS = new java.util.HashMap<>();

    static {
        registerDeferredWork(com.klze.colossus.env.ZoneWork.KIND, com.klze.colossus.env.ZoneWork::execute);
    }

    /**
     * 注册一种延迟工作（下游把"排到未来的事"挂进来；同一机制，不另开第二条路）。
     * 处理器只能吃 NBT——凡不可序列化的闭包，就别指望它跨存档。
     */
    public static void registerDeferredWork(String kind,
            java.util.function.BiConsumer<ColossusBossEntity, CompoundTag> handler) {
        if (DEFERRED_WORKS.putIfAbsent(kind, handler) != null) {
            throw new IllegalStateException("duplicate deferred work kind: " + kind);
        }
    }

    /**
     * N tick 后在服务端 aiStep 里执行一次（实体先消失则不执行）。kind 见 {@link #registerDeferredWork}。
     *
     * <p>返回 false＝队列已满被拒。调用方<b>必须</b>据此决定要不要连带做别的事：
     * telegraph 帧就是靠这个返回值把"地上的圈"和"会落下的那一发"绑成一次原子操作，
     * 否则队列满时玩家会看见一块永远不炸的假警告（第二十四批补的门）。
     */
    public boolean scheduleWork(int delayTicks, String kind, CompoundTag data) {
        if (this.workQueue.size() >= MAX_PENDING_WORK) {
            Colossus.LOGGER.warn("boss {} deferred-work queue full ({} entries) — refusing new {}",
                    this.getBossId(), this.workQueue.size(), kind);
            return false;
        }
        this.workQueue.add(new DeferredWork(this.level().getGameTime() + Math.max(1, delayTicks), kind, data));
        return true;
    }

    /** 在途待办数（诊断与回归桩用：证明"存进存档再读回来，队列没被清空"）。 */
    public int pendingWorkCount() {
        return this.workQueue.size();
    }

    private void drainWork() {
        long now = this.level().getGameTime();
        while (!this.workQueue.isEmpty() && this.workQueue.peek().dueGameTime() <= now) {
            DeferredWork w = this.workQueue.poll();
            var handler = DEFERRED_WORKS.get(w.kind());
            if (handler == null) {
                // 种类被删（mod 更新后）：丢弃并留痕，不炸存档
                Colossus.LOGGER.warn("unknown deferred work kind {} on boss {} — dropped",
                        w.kind(), this.getBossId());
                continue;
            }
            try {
                handler.accept(this, w.data());
            } catch (Throwable t) {
                // registerDeferredWork 是开放给下游的扩展点：一个坏 handler 不能把 aiStep 炸成
                // 持久 Boss 的崩溃循环（与 ColossusAnims 的 fire* 同一姿态）。待办已出队，不重试。
                Colossus.LOGGER.warn("deferred work {} threw on boss {} — dropped", w.kind(), this.getBossId(), t);
            }
        }
    }

    // ==================== 危险区投影（telegraph 的可视态，第二十四批） ====================

    /**
     * 在途轮廓的<b>同步数据</b>形态：{@code {views:[{几何…, id, start, end}]}}。
     *
     * <p>为什么从自定义包改成 entityData（v11 取证 A2 的结论，源码已复核）：
     * vanilla 在 {@code ServerEntity#sendPairingData}（{@code ServerEntity.java:237-239}）
     * 会给<b>新追踪者</b>自动补发一份 {@code ClientboundSetEntityDataPacket} 全量快照，
     * 而且 {@code sendDirtyEntityData:294} 每次发包都刷新 {@code trackedDataValues}——
     * 于是"中途进场看不见轮廓""重进世界轮廓全没""重载后伤害照落但圈不亮"三条病灶
     * 一次性消失，且一行补包代码都不用写。旧写法（{@code ZoneSync} 单发包 + 客户端本地倒计时）
     * 三条全中，其中 {@code TelegraphClient} 那句 {@code mc.level == null → clear()} 是最干净的反例。
     *
     * <p>计时用<b>绝对 gameTime</b>（{@code start}/{@code end}）而不是"还剩几 tick"：
     * 晚到的人据此算出"这块地已经烧掉一半"，而不是从 0 重新亮一遍（v11 A3 的三家对照里唯一语义安全的一档）。
     */
    private static final EntityDataAccessor<CompoundTag> DATA_TELEGRAPHS =
            SynchedEntityData.defineId(ColossusBossEntity.class, EntityDataSerializers.COMPOUND_TAG);

    /**
     * 同时在途的轮廓<b>默认</b>上限（与 {@link #MAX_PENDING_WORK} 同一动机：不封顶就是高频
     * telegraph 把同步数据越写越大）。可被 {@link #maxActiveTelegraphs()} 按实例抬高——
     * 它<b>不是</b>装饰值：投影满 ⇒ {@code MoveTriggers.telegraph} 会<b>放弃整发</b>，
     * 所以这个数字直接决定「哪几招在实战里根本不会出场」。
     *
     * <p>算式（轮 14 P2-4，写给要配高频 telegraph 的人）：把 {@code telegraph} 挂在
     * {@code repeating(from,to,period)} 上时，同时在地上的圈数约
     * {@code ceil((warnTicks + TelegraphZone.FADE_TICKS) / period)}——{@code warn=60, period=10}
     * 单单一帧就要 7 格，两帧就撞顶。要么加长 period，要么抬 {@link #maxActiveTelegraphs()}。
     */
    public static final int MAX_ACTIVE_TELEGRAPHS = 8;

    /**
     * 在途轮廓上限（子类可按 Boss 抬；先读上面那条算式再抬）。实际生效值还要过
     * {@link #telegraphCap()} 这道钳，所以覆写它<b>不能</b>把同步载荷推到出界的大小。
     *
     * <p>要"这个 Boss 不做预警"请在招式表里<b>别用 telegraph 帧</b>，不要把这里返回 0：
     * 0 会被 {@link #telegraphCap()} 抬成 1。理由是"关掉投影但仍落伤害"＝制造没预警的攻击，
     * 那比少一格容量严重得多（轮 17 设计偏差第 2 条的裁决）。
     */
    protected int maxActiveTelegraphs() { return MAX_ACTIVE_TELEGRAPHS; }

    /** {@link #maxActiveTelegraphs()} 的硬上界（一轮全量广播的是 COMPOUND_TAG，见下）。 */
    public static final int HARD_MAX_TELEGRAPHS = 32;

    /**
     * 真正用的上限：钳到 {@code 1..HARD_MAX_TELEGRAPHS}（轮 14 P3-5 加钳、轮 16 P2-4 把下界从 0 抬到 1）。
     *
     * <p>钳必须开在<b>用点</b>而不是钩子里——钩子被覆写就直接绕过钳了。这个数决定的是
     * "每次变更往全体追踪者全量广播多大的一个 tag"，抬到天上就把读侧刚补上的封顶与
     * {@code FriendlyByteBuf#readNbt} 的 2 MiB accounter 面重新打开（一条 view 约 140 B，
     * 32 条约 4.5 KiB，是合理量级；一万五千条才会撞上限，那已经不是配置错而是坏档）。
     */
    private int telegraphCap() {
        int requested = maxActiveTelegraphs();
        // 下界钳到 <b>1</b>，不是 0（轮 16 P2-4）：0 会让 `size() >= cap` 恒真 ⇒ 这个 Boss 所有带预警的
        // 招式一招都不落，而日志从"cap -1（一眼是配错）"变成"cap 0（像是框架的有意决定）"——
        // 把一次常见笔误（写 -1 表示"不限"）换成静默消失，正是本仓最反对的那种失败模式。
        int clamped = clampTelegraphCap(requested);
        if (requested != clamped && !this.telegraphCapWarned) {
            this.telegraphCapWarned = true; // 一次性：越界要响，但不能每发 telegraph 响一遍
            Colossus.LOGGER.warn("boss {} maxActiveTelegraphs() returned {} — clamped to {} (valid range 1..{})."
                    + " Negative values are lifted to 1 on purpose: 0 would silently drop every"
                    + " telegraphed attack, so it is not honoured as an off-switch."
                    + " Note what 0/1 actually means here: with cap 1 only the FIRST telegraphed move lands,"
                    + " every later one is refused until a slot frees — if you want no telegraphs at all,"
                    + " don't use telegraph frames in the move table.",
                    this.getBossId(), requested, clamped, HARD_MAX_TELEGRAPHS);
        }
        return clamped;
    }

    /** 钳位单独成<b>纯函数</b>（轮 17 P3-2）：否则"下界从 0 抬到 1"与"上界 32"这两条谁都自检不了。 */
    public static int clampTelegraphCap(int requested) {
        // 下界是 1 而不是 0：0 会让 `size() >= cap` 恒真 ⇒ 该 Boss 所有带预警的招一招不落，
        // 而日志从"cap 0（像是框架有意决定）"看起来无害，实际是静默消失（轮 16 P2-4）。
        return net.minecraft.util.Mth.clamp(requested, 1, HARD_MAX_TELEGRAPHS);
    }

    private boolean telegraphCapWarned = false;

    /** 一条在途轮廓：几何 + 起止的绝对 gameTime。{@code id} 只用来让客户端认出"还是同一块地"。 */
    public record TelegraphView(int id, TelegraphZone zone, long startGameTime, long endGameTime) {

        CompoundTag toTag() {
            CompoundTag tag = this.zone.toTag();
            tag.putInt("id", this.id);
            tag.putLong("start", this.startGameTime);
            tag.putLong("end", this.endGameTime);
            return tag;
        }

        static @Nullable TelegraphView fromTag(net.minecraft.nbt.Tag t) {
            if (!(t instanceof CompoundTag tag)) return null;
            if (!tag.contains("id", net.minecraft.nbt.Tag.TAG_INT)) return null; // 残缺：丢掉，别在渲染路径炸
            if (!tag.contains("start", net.minecraft.nbt.Tag.TAG_LONG)
                    || !tag.contains("end", net.minecraft.nbt.Tag.TAG_LONG)) return null; // 同上（轮 18 P3-4）
            // 几何缺件也丢：CompoundTag 的 getDouble/getString 对缺失返回 0/""（不抛），
            // 一份截断的 tag 就会读成"世界原点一个 1 格圈"，而同一条待办里还带着伤害
            // ——看不见的圈照样落伤，正是本仓最反对的失败模式（轮 18 P3-3）。
            if (!com.klze.colossus.env.TelegraphZone.hasRequiredKeys(tag)) return null;
            return new TelegraphView(tag.getInt("id"), TelegraphZone.fromTag(tag),
                    tag.getLong("start"), tag.getLong("end"));
        }
    }

    private final java.util.LinkedHashMap<Integer, TelegraphView> telegraphViews =
            new java.util.LinkedHashMap<>();
    private int telegraphSeq = 0;
    /** 仅客户端：上一次读到的投影<b>实例</b>，供 {@link #takeTelegraphViewsIfChanged()} 做 O(1) 判据。 */
    @Nullable private CompoundTag telegraphClientMemo;
    /** 最近的到期时刻（没有在途轮廓时＝{@link Long#MAX_VALUE}）；aiStep 只在跨过它的那一 tick 重投，热路径 O(1)。 */
    private long nextTelegraphCheck = Long.MAX_VALUE;

    /**
     * 登记一块危险区并投进同步数据（仅服务端；客户端调用返回"没登记"）。
     *
     * <p>{@code ticks} 是<b>轮廓总寿命</b>（不是"距结算还有几 tick"）。调用方<b>应当</b>直接给
     * {@link TelegraphZone#lifetimeTicks()}；轮 18 P3-9 之后这条不再只是约定——
     * 实现按 {@code max(ticks, zone.lifetimeTicks())} 兜底，所以 public 面也<b>无法</b>登记一个
     * "比结算先消失"的轮廓（原先这里是唯一能绕过 record 侧不变量的入口，GameTest 传的裸 40
     * 只是恰好等于 warn30 + FADE10）。
     *
     * @return 这一条轮廓的 id（配 {@link #hideTelegraph(int)} 提前撤），
     *         投影已满时返回 {@code -1}。调用方<b>要</b>判：伤害帧要是连预警都画不出来，
     *         就该整发放弃，而不是落一发没见过的圈（第二十四批的取舍，见
     *         {@link com.klze.colossus.move.MoveTriggers#telegraph}）。
     */
    public int showTelegraph(TelegraphZone zone, int ticks) {
        if (this.level().isClientSide || zone == null) return -1;
        if (this.deathPending) return -1; // 演出中不该再立新圈：下面那发一定会被 ZoneWork 判弃
        if (this.telegraphViews.size() >= telegraphCap()) {
            // 原先每拒一次打一行＝period=10 时约 2 行/秒/Boss（轮 14 P2-4）。改成
            // 「撞顶期间每 100 tick 至多一条 + 腾出位子时把攒下的条数一次报完」——
            // 与本仓 MoveSet 构造期 warn 同一去重姿态，但这里攒的是「被吞掉了几招」。
            this.telegraphRefusals++;
            long bucket = this.level().getGameTime() / 100L;
            if (this.telegraphFullWarnedAt != bucket) {
                this.telegraphFullWarnedAt = bucket;
                Colossus.LOGGER.warn("boss {} telegraph projection full ({} entries, cap {}) — {} zone(s) refused"
                        + " so far, each refusal also drops that attack (see MoveTriggers#telegraph)",
                        this.getBossId(), this.telegraphViews.size(), telegraphCap(),
                        this.telegraphRefusals); // 第 4 个 {}  previously 没有对应实参 ⇒ 日志会打出行面 "{}"
            }
            return -1;
        }
        if (this.telegraphRefusals > 0) {
            Colossus.LOGGER.warn("boss {} telegraph projection freed after {} zone(s) were refused",
                    this.getBossId(), this.telegraphRefusals);
            this.telegraphRefusals = 0;
            this.telegraphFullWarnedAt = Long.MIN_VALUE; // 下一次撞顶要立刻报，不等下一个 100 tick 桶（轮 14 P3-4）
        }
        long now = this.level().getGameTime();
        int id = ++this.telegraphSeq;
        // ticks 是"轮廓活多久"的第二个入口（public），不能让它绕过本批刚钉的不变量
        // "轮廓必须活得比那一发的结算久"（TelegraphZone#lifetimeTicks）：只抬高不压低，
        // 调用方给更长就照它的（淡出留白是表现选择），给短了就兜回形状口径（轮 17 P3-9）。
        int lifetime = Math.max(Math.max(1, ticks), zone.lifetimeTicks());
        this.telegraphViews.put(id, new TelegraphView(id, zone, now, now + lifetime));
        this.publishTelegraphs();
        return id;
    }

    private int telegraphRefusals = 0;
    private long telegraphFullWarnedAt = Long.MIN_VALUE;

    /** 提前撤掉一条轮廓（id 来自 {@link #showTelegraph}；不存在时静默忽略）。 */
    public void hideTelegraph(int viewId) {
        if (this.level().isClientSide || !this.telegraphViews.containsKey(viewId)) return;
        this.telegraphViews.remove(viewId);
        this.publishTelegraphs();
    }

    /**
     * 重投快照。写进 entityData 的必须是<b>新建</b>的 tag：vanilla 的脏判据是
     * {@code ObjectUtils.notEqual(newValue, oldValue)}（{@code SynchedEntityData.java:129}），
     * 原地改同一个实例会永远判成"没变"⇒ 一帧都不发。这条与 {@code getNonDefaultValues}
     * 一起构成"新追踪者补包"的前提，别图省事改成复用。
     */
    private void publishTelegraphs() {
        long now = this.level().getGameTime();
        this.telegraphViews.entrySet().removeIf(e -> e.getValue().endGameTime() <= now);
        this.nextTelegraphCheck = Long.MAX_VALUE;
        for (TelegraphView v : this.telegraphViews.values()) {
            if (v.endGameTime() < this.nextTelegraphCheck) this.nextTelegraphCheck = v.endGameTime();
        }
        CompoundTag snapshot = new CompoundTag();
        if (!this.telegraphViews.isEmpty()) {
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            for (TelegraphView v : this.telegraphViews.values()) list.add(v.toTag());
            snapshot.put("views", list);
        }
        this.entityData.set(DATA_TELEGRAPHS, snapshot); // 值相等时 set 本身就不发包（清空后不再重发）
    }

    /** 到点清理：aiStep 每 tick 只比一个 long，跨过最早到期时刻才真正重投。 */
    private void tickTelegraphs() {
        if (this.level().getGameTime() >= this.nextTelegraphCheck) this.publishTelegraphs();
    }

    /**
     * 轮廓投影的<b>条目数</b>（读同步数据本身，不是在途集合的长度）。
     * 回归桩用它钉"改了集合有没有顺手重投"：两个数不等＝忘记 publish 那一类回归。
     */
    public int telegraphProjectionCount() {
        CompoundTag snapshot = this.entityData.get(DATA_TELEGRAPHS);
        return snapshot.contains("views") ? snapshot.getList("views", net.minecraft.nbt.Tag.TAG_COMPOUND).size() : 0;
    }

    /**
     * 客户端渲染层用：自上次读取以来投影<b>换过实例</b>就返回解码结果，否则返回 {@code null}。
     *
     * <p>为什么不是"把 tag 交出去让调用方自己比引用"（轮 14 P2-3）：那个对象就是 entityData 里的
     * 活对象，而<b>两条判据都挂在它身上</b>——服务端 {@code SynchedEntityData#set} 用
     * {@code ObjectUtils.notEqual} 且 {@code CompoundTag#equals} 是<b>内容</b>比较，客户端又用
     * <b>实例</b>比较。一次原地误改会同时打掉"发包"与"重建"两条腿：服务端一帧不发、
     * 客户端那一圈集永久冻结（撤销只能靠重投），且全程零日志。判据留在实体里，出去的是解码结果。
     *
     * <p><b>读一次即消费</b>：本方法会把"这份快照已经看过"记下来，所以它是<b>渲染层的专用通道</b>——
     * HUD 插件、第三方渲染器或自检只要读一次，就把框架那一次重建吞掉了（轮廓冻结到服务端下一次
     * publish 为止）。要在别处看内容请用 {@link #telegraphViews()}（不消费）。只在客户端用。
     */
    @Nullable
    public java.util.List<TelegraphView> takeTelegraphViewsIfChanged() {
        CompoundTag snapshot = this.entityData.get(DATA_TELEGRAPHS);
        if (snapshot == this.telegraphClientMemo) return null;
        this.telegraphClientMemo = snapshot;
        return decodeTelegraphs(snapshot);
    }

    private java.util.List<TelegraphView> decodeTelegraphs(CompoundTag snapshot) {
        if (!snapshot.contains("views")) return java.util.List.of();
        net.minecraft.nbt.ListTag list = snapshot.getList("views", net.minecraft.nbt.Tag.TAG_COMPOUND);
        java.util.List<TelegraphView> out = new java.util.ArrayList<>(list.size());
        for (net.minecraft.nbt.Tag t : list) {
            TelegraphView v = TelegraphView.fromTag(t);
            if (v != null) out.add(v);
        }
        return out;
    }

    /**
     * 丢掉 {@link #takeTelegraphViewsIfChanged()} 里那份"上次读到的实例"。
     *
     * <p>为什么需要（轮 14 P2-3）：memo 住在实体上、比客户端那份名单长寿——客户端一旦重新登记同一个
     * Boss（或第三方调过 public 的 {@code TelegraphClient.clear()}），不重置就会在下一 tick 判"没变"，
     * 于是<b>在途轮廓永远回不来</b>，要等服务端下一次 publish 才救得回来。登记点必须复位它。
     */
    public void forgetTelegraphMemo() {
        this.telegraphClientMemo = null;
    }

    /** 在途轮廓解码结果（服务端诊断与投影核对用；空快照零分配）。 */
    public java.util.List<TelegraphView> telegraphViews() {
        return decodeTelegraphs(this.entityData.get(DATA_TELEGRAPHS));
    }

    /** 服务端在途条数（回归桩判据：投影响、封顶、到期清理三件事都从这一个口径读）。 */
    public int activeTelegraphCount() {
        return this.telegraphViews.size();
    }

    /** 死亡演出开始：轮廓立刻撤——待办那发已经会被 {@code ZoneWork} 判弃，留着圈就是撒谎。 */
    void dropAllTelegraphs() {
        if (this.level().isClientSide) return;
        if (!this.telegraphViews.isEmpty()) {
            this.telegraphViews.clear();
            this.publishTelegraphs();
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

    // ---------------- 招式历史（第二十批引入，环本体在 move/MoveHistory） ----------------

    /**
     * 最近出招的环形历史。为什么框架要长这一格：v10 扫遍 577 仓，<b>"最近用过的招降权/禁用"零实现</b>
     * （库内只有一招一个冷却标量），而连续放同一招正是 BOSS 战被玩家背下来的主因。
     * 形态守"新维度做成数据、不做新子类"：与 shield、part_bits 同一口径，入档就一个 long。
     *
     * <p>第二十一批把环抽成 {@link com.klze.colossus.move.MoveHistory}：它的语义（低位＝最新、
     * 容量＝格数、0 是空槽、溢出丢最老）全是纯算术，留在实体里就只能靠真机桩观测，
     * 抽出来才进得了纯逻辑自检（审查轮 10 F4 正批在此）。
     */
    private final com.klze.colossus.move.MoveHistory moveHistory =
            new com.klze.colossus.move.MoveHistory();

    /**
     * 这 N 次出招里是否出现过该招（N 上限见 {@link com.klze.colossus.move.MoveHistory#SLOTS}，
     * 越界按 {@link #moveHistory} 的口径静默钳位——要登记期就拒请用
     * {@code MoveSetBuilder.MoveBuilder#notRecent}）。
     */
    public boolean usedRecently(ResourceLocation moveId, int withinLast) {
        return this.moveHistory.usedRecently(moveId, withinLast);
    }

    /** 诊断/测试用：原始环（低位＝最新）。 */
    public long recentMoveSnapshot() { return this.moveHistory.snapshot(); }
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
        this.tickTelegraphs(); // 轮廓到期只比一个 long，跨过最早 end 才重投快照
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
            // 百分比回填。"不许抬已结算尸体的血"这条规则**只写在 setHealth 覆写里**（轮 9 收口），
            // 这里不再重复判存活——两处各写一遍迟早有一处忘改。
            this.setHealth(Math.max(1.0f, ratio * this.getMaxHealth()));
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

    /** 招式表登记失败时留一次错误日志用（同一种子只报一次，别每 tick 刷屏）。 */
    @Nullable private String moveBuildFailureLogged;

    public final MoveSet moveSet() {
        long rev = com.klze.colossus.move.MoveDataRegistry.revision();
        if (this.moveSet == null || this.moveDataRevision != rev) {
            // 轮 9 的故障隔离：本方法的运行期调用点是 trySelectAttack/forceMove（都在 aiStep 里），
            // 而 Java DSL 的 registerMoves 就在这儿第一次跑——下游写坏一条招式
            // （如 .anim("")）就会把 IAE 抛进战斗那一 tick。1.20.1 的 Level#guardEntityTick
            // 抓住 Throwable 之后是 **throw new ReportedException**（不是丢实体继续跑，
            // removeErroringEntities 默认 false），所以不做这里就会变成"玩家进战即崩服"。
            // 姿态与 drainWork / ColossusAnims.fire* 一致：吃掉、报一次、退化到上一张好表。
            try {
                if (this.javaMoves == null) {
                    MoveSetBuilder builder = new MoveSetBuilder(this, this.getBossId());
                    this.registerMoves(builder);
                    this.javaMoves = builder.builtDefs();
                }
                this.moveSet = MoveSet.merge(this, this.javaMoves,
                        com.klze.colossus.move.MoveDataRegistry.defsFor(this.getBossId()));
                this.moveDataRevision = rev;
                this.moveBuildFailureLogged = null; // 成功过就允许下次失败再报一次
            } catch (RuntimeException | StackOverflowError broken) {
                String key = this.getBossId() + "/" + broken.getClass().getSimpleName();
                if (!key.equals(this.moveBuildFailureLogged)) {
                    this.moveBuildFailureLogged = key;
                    Colossus.LOGGER.error("boss {} 的招式表登记失败（{}）——沿用上一张表，"
                                    + "这条日志只报一次，去 registerMoves 里修数据",
                            this.getBossId(), broken.toString(), broken);
                }
                if (this.moveSet == null) {
                    this.moveSet = com.klze.colossus.move.MoveSet.merge(this, List.of(), List.of());
                }
                this.moveDataRevision = rev; // 别再每 tick 重试构建
            }
        }
        return this.moveSet;
    }

    void beginAttack(MoveDef move) {
        this.contacts.clear(); // 每次出招是全新接触集（DBE 窗口语义）
        this.moveHistory.record(move.id()); // 招式历史：notRecent 准入与 recent_band 降权的共同输入
        this.attackMove = move; // 服务端权威：中途 /reload 换表也不影响在播的这招（轮6 P3-3）
        this.entityData.set(DATA_ATTACK_ID, move.id().toString());
        this.entityData.set(DATA_ATTACK_ANIM, move.animName());
        this.entityData.set(DATA_ATTACK_DURATION, move.duration());
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
        MoveDef m = this.attackMove; // 权威对象：不受 /reload 换表影响
        return m == null ? 0 : m.postAttackInvuln();
    }

    void syncAttackTick(int tick) {
        this.entityData.set(DATA_ATTACK_TICK, tick);
    }

    void syncAttackNone() {
        this.attackMove = null;
        this.entityData.set(DATA_ATTACK_ID, "");
        this.entityData.set(DATA_ATTACK_ANIM, "");
        this.entityData.set(DATA_ATTACK_DURATION, 0);
        this.entityData.set(DATA_ATTACK_TICK, 0);
    }

    void endAttack(MoveDef move) {
        this.syncAttackNone(); // 冷却在 beginAttack 已预扣（cooldown+duration）
    }

    /**
     * 在播招式（<b>服务端权威对象</b>，{@link #beginAttack} 写入、结束时清空）。
     * 刻意不再从同步数据反查本地表：表在客户端可能没有（datapack 只在服务端 reload），
     * 而且换表发生在出招中途时，反查会读到<b>另一招</b>的 postAttackInvuln（轮6 P3-3）。
     * 客户端调用本方法得到 null——它要显示什么请读 {@link #attackAnimId()} 与 {@link #attackProgress()}。
     */
    @Nullable
    public MoveDef currentAttack() {
        return this.level().isClientSide ? null : this.attackMove;
    }

    @Nullable
    private MoveDef attackMove;

    /** 招式 id 字符串（同步）：给血条文案/动画名用；解析不到也不影响判定与进度。 */
    public String attackAnimId() {
        var m = this.currentAttack();
        return m != null ? m.id().toString() : this.entityData.get(DATA_ATTACK_ID);
    }

    /**
     * 招式动画名：直接读服务端下发的同步串（无招时为空串——{@code syncAttackNone} 写的）。
     * 客户端适配器与模型都该用这个，而不是 {@link #currentAttack()}——后者是服务端权威对象，客户端恒 null。
     * 判"有没有在施法"请用 {@link #isAttacking()}（认 id），别拿这个显示字段当门。
     */
    public String attackAnimName() {
        return this.currentAttack() != null ? this.currentAttack().animName()
                : this.entityData.get(DATA_ATTACK_ANIM);
    }

    /**
     * 是否正在施法（双端都能问，客户端不依赖招式表）。
     *
     * <p>判据取<b>招式 id</b>而不是动画名：id 由 {@code ResourceLocation} 保证非空，
     * 而动画名是<b>显示字段</b>（审查轮 8 P2）。曾用动画名非空串当门，后果是
     * 一旦有作者写 {@code .anim("")}，服务端帧表照跑、客户端所有门（模型姿态、
     * addon 移动控制器、下游免伤/仇恨判据）全部认为"没在出招"——表现与判定分家。
     * 动画名的值域已改由 {@code MoveDef} 构造器钉住（非空白），但门的语义不该靠值域。
     */
    public boolean isAttacking() {
        return !this.entityData.get(DATA_ATTACK_ID).isEmpty();
    }

    /** 当前招式时长（同步，客户端可直接算进度）。 */
    public int attackDuration() {
        var m = this.currentAttack();
        return m != null ? m.duration() : this.entityData.get(DATA_ATTACK_DURATION);
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
        // 分母走同步时长：客户端没有招式表也算得对（旧写法查 currentAttack()，客户端恒 null）
        int dur = this.attackDuration();
        return dur <= 0 ? 0f : Math.min(1f, this.attackTick() / (float) dur);
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

    /**
     * 已结算尸体的抬血闸门（轮 9 收口，框架内**唯一**一处通用防线）。
     *
     * <p>为什么必须有：{@code deathResolved=true 且 hp<=0} 之后，谁再动血量都不该把它抬起来——
     * 抬一次就是一次"1 血不死雕像 + 二次结算"。框架里除了已钉住的两个回填点，还有
     * {@code ArenaSession.fail()} 的团灭回血这类调用方（默认 healOnFail=true），
     * 而 {@code fail()} 是 public，下游自接的失败回路随时可能调到尸体上。
     * vanilla 的 {@code heal()} 自带 {@code f > 0} 门（{@code LivingEntity:1039-1042}），
     * 但 {@code setHealth} 没有，所以在 Boss 这一侧补上。
     *
     * <p>只在"结算之后"生效，绝不影响死亡演出：{@code die()} 里把 hp 钉成 1.0 那一刻
     * {@code deathResolved} 仍是 false，读档时原版先写 Health 也发生在置位之前。
     */
    @Override
    public void setHealth(float health) {
        if (this.deathResolved && this.getHealth() <= 0.0f && health > 0.0f) {
            return; // 已结算＝这具档只等收尸，任何抬血一律拒绝
        }
        super.setHealth(health);
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

    /** 演出开场副作用：血条清零并隐藏/停音乐/清延迟队列/成员收摊（渲染层读 DEATH_TICK）。 */
    void onDeathSequenceStart() {
        this.workQueue.clear(); // 死亡后不再结算旧的 telegraph/延迟动作（审查 P2）
        // 队列一清，地上的圈就变成"永远不会落下来的假警告"——同一处一起撤（第二十四批）
        this.dropAllTelegraphs();
        // squad 收摊放在<b>演出开场</b>而不是结算处：演出默认 100t 里队长血量钉在 1.0、isAlive() 仍 true，
        // 成员照旧被锚点钉在尸体肩上挨打——广播晚发就是"Boss 已死、触手还在陪葬"。
        // （不是"不提前就会到点补员"：补员在 deathPending 下被 aiStep 与 tickSessionAndSquad 双重门挡死，
        //  轮 7 我在这里写过错误的机制，轮 8 更正。）
        if (!this.squad().defs().isEmpty() && this.level() instanceof ServerLevel server) {
            this.squad().notifyLeaderDeath(server);
        }
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
            // squad 收摊不在这里——已提前到演出开场（onDeathSequenceStart），此处只剩"确认没人留在排期里"
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
        this.entityData.define(DATA_ATTACK_ID, "");
        this.entityData.define(DATA_ATTACK_DURATION, 0);
        this.entityData.define(DATA_ATTACK_ANIM, "");
        this.entityData.define(DATA_ATTACK_TICK, 0);
        this.entityData.define(DATA_ATTACK_SEQ, 0);
        this.entityData.define(DATA_SHIELD, 0.0f);
        this.entityData.define(DATA_DEATH_TICK, 0);
        this.entityData.define(DATA_ACTIVATED, false);
        this.entityData.define(DATA_PART_BITS, 0L);
        this.entityData.define(DATA_TELEGRAPHS, new CompoundTag()); // 空标签=没有在途轮廓（非默认值才会进补包快照）
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
        }
        // 护盾与延迟队列**不能**嵌在上面那个 if 里：没有分流账的 Boss（不用护壳弱点）
        // 就会永远不持久化这两样——第十六批的持久化桩正是这么抓出来的。
        tag.putFloat("colossus_shield", this.entityData.get(DATA_SHIELD));
        net.minecraft.nbt.ListTag works = new net.minecraft.nbt.ListTag();
        for (DeferredWork w : this.workQueue) {
            CompoundTag one = new CompoundTag();
            one.putLong("due", w.dueGameTime());
            one.putString("kind", w.kind());
            one.put("data", w.data());
            works.add(one);
        }
        tag.put("colossus_works", works); // 空列表也写：读侧据此区分"没待办"与"这版本没存过"
        // 轮廓投影与待办队列各写一份：两者回答的是不同问题（"画到什么时候" vs "落什么伤害"），
        // 合成一份会让 ZoneWork 的弃单门（死亡/卸载）顺带把视觉也删掉——那正是"圈凭空消失"的老 bug。
        long telegraphNow = this.level().getGameTime();
        net.minecraft.nbt.ListTag views = new net.minecraft.nbt.ListTag();
        for (TelegraphView v : this.telegraphViews.values()) {
            if (v.endGameTime() > telegraphNow) views.add(v.toTag()); // 已过期的不落盘
        }
        tag.put("colossus_telegraphs", views);
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
        // 无条件写（0 也写）：与 colossus_works 同一口径，读侧才能分清"没历史"与"旧版本没存过"
        tag.putLong("colossus_recent_moves", this.moveHistory.snapshot());
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
        if (tag.contains("colossus_recent_moves", net.minecraft.nbt.Tag.TAG_LONG)) {
            this.moveHistory.restore(tag.getLong("colossus_recent_moves"));
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
        // 已结算过的尸体（原版收尸那 20t 里存的档）：此时 colossus_dying 已是 false
        // （deathResolved 把演出位清掉了），但存盘血量是 0。若照原样回填 hp_ratio，
        // 下面那句 Mth.clamp(..., 1.0f, max) 会把 0 抬成 1 → 一具"会选招、打不掉、永不消失"
        // 的 1 血雕像（v0.2 P0 的原样复发），再挨一刀就走完整遍死亡流程：
        // KillBoard 多记一刀 + 战利品再 roll 一份（缓冲已置 null，防重 roll 门失效）。
        // 判据就用血量本身——演出期血量被钉在 1.0，只有结算后才归零，不再新增一个 NBT 位。
        if (this.getHealth() <= 0.0f && !this.level().isClientSide) {
            this.deathPending = true;
            this.deathResolved = true; // 只收尸，绝不二次结算
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
        if (tag.contains("colossus_works", net.minecraft.nbt.Tag.TAG_LIST)
                && !this.level().isClientSide) {
            this.workQueue.clear();
            long now = this.level().getGameTime();
            int malformed = 0;
            int overdue = 0;
            for (var t : tag.getList("colossus_works", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                if (!(t instanceof CompoundTag one)) { malformed++; continue; }
                long due = one.getLong("due");
                if (due <= 0L || one.getString("kind").isEmpty()) { malformed++; continue; } // 残缺：丢掉，别在 tick 里炸
                if (due <= now) { overdue++; continue; }
                // 卸载期间 gameTime 照走 → 这一发的"预警窗口"已经过去了。
                // 重载后直接落 = Boss 站着不动、地上没圈却挨一刀（tell 撒谎），所以宁可不落。
                // 帧时间线整体不入 NBT 是 v0.3 的账（DESIGN §3），这里先把不对称的尖角磨掉。
                this.workQueue.add(new DeferredWork(due, one.getString("kind"), one.getCompound("data")));
            }
            // 计数在循环外汇总一次：原先那条 warn 打的是"此刻已入队的条数 +1"，
            // 既不是过期条数、又随遍历递增（审查轮 8 P3，日志会说谎）
            if (overdue > 0 || malformed > 0) {
                Colossus.LOGGER.warn("boss {} dropped {} overdue and {} malformed deferred works on load ({} kept)",
                        this.getBossId(), overdue, malformed, this.workQueue.size());
            }
        }
        if (tag.contains("colossus_shield", net.minecraft.nbt.Tag.TAG_FLOAT)
                && !this.level().isClientSide) {
            this.setShield(tag.getFloat("colossus_shield"));
        }
        if (tag.contains("colossus_telegraphs", net.minecraft.nbt.Tag.TAG_LIST)
                && !this.level().isClientSide) {
            // 起止存的是绝对 gameTime，跨重载仍然自洽：还剩几 tick 由 level 自己回答，
            // 不需要像旧写法那样"把剩余量重新当年龄"（那是第十六批之前的对称缺陷）。
            this.telegraphViews.clear();
            long now = this.level().getGameTime();
            int expired = 0;
            int overflow = 0;
            final int cap = telegraphCap(); // 钩子是虚调用：提到循环外，坏存档两万条不该跑两万次
            for (var entry : tag.getList("colossus_telegraphs", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                TelegraphView v = TelegraphView.fromTag(entry);
                if (v == null || v.id() <= 0) { expired++; continue; } // 残缺/id<=0 会把续号撞进哨兵（轮 14 P3-7）
                // 与待办队列<b>同一条</b>判据：那发的到期时刻是 start + settleDelayTicks()（见 MoveTriggers#telegraph）。
                // 只按 end 剪会留下一个 FADE 宽度的窗口——work 被 "due<=now" 丢掉、圈却还亮着，
                // 于是"不补画就不会有伤害落"这句在重载路径上说过头了（轮 14 P3-8）。
                // 说准一点（轮 16 P3-8）：这条判据只对<b>配了结算工作</b>的圈有意义。telegraphVisual 那种
                // "只画不结算"的圈被它剪掉的是本可以留的淡出尾巴（示范 flamewall 量级：最多 2 tick）。
                // 想区分就得给 view 多存一个"有没有配对工作"的位——不值，所以保留现形并把代价写在这儿。
                if (v.endGameTime() <= now || v.startGameTime() + v.zone().settleDelayTicks() <= now) {
                    expired++; // 与 MoveTriggers#telegraph 排待办时共用同一个式子（轮 14 P3-6）
                    continue;
                }
                if (this.telegraphViews.size() >= cap) { overflow++; continue; } // 读侧也要封顶（轮 14 P2-2）
                this.telegraphViews.put(v.id(), v);
                if (v.id() > this.telegraphSeq) this.telegraphSeq = v.id(); // 序号续上，别把 id 发给还活着的旧轮廓
            }
            if (expired + overflow > 0) {
                Colossus.LOGGER.warn("boss {} dropped {} expired/malformed and {} over-cap telegraph views on load"
                        + " ({} kept)", this.getBossId(), expired, overflow, this.telegraphViews.size());
            }
            this.publishTelegraphs(); // 重载后必须重投：entityData 不落盘，补包快照靠它
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
