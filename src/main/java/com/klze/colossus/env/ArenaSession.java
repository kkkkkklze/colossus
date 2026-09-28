package com.klze.colossus.env;

import com.klze.colossus.Colossus;
import com.klze.colossus.entity.ColossusBossEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 竞技场会话（第七批落地·第八批按 v7 取证修正）。取材与判语：
 * 进场封路 = BR {@code closeOffExit()}（setBlock 覆盖 + 按位快照恢复，
 * 记账形态抄 BR {@code KrakenShipStructure.captureRegion}：只存变化位、反查按中心点）；
 * 越界纠偏 = **框架自拟**（v7 负结果：ObeliskDepths 包里没有越界拉回的一手样本，
 * grace 200t / warn 100t 是我定的默认值，不是抄来的数值）；
 * 团灭复位 = **框架自拟**（库内 Boss 侧无失败回路，BR 的"解封"是 POI 判击败后
 * 由玩家自己挖开，不是方块还原——所以方块还原这条路必须自带快照）。
 *
 * <p>会话状态随 Boss 的 NBT 持久化（封路快照崩溃可恢复）；不注册方块实体、不碰结构。
 * 封/解只走 {@code level.setBlock}：1.20.1 的 {@code EntityPlaceEvent} 与 {@code BreakEvent}
 * 分别只在放物品/EnderMan/冰足 与玩家挖掘时派发，自家长方方块不经这两条，无需豁免通道。
 */
public final class ArenaSession {

    /**
     * 声明式会话参数。sealOffsets 是局部坐标（随 Boss 朝向四向量化，同 ArenaBlockAccess 口径）。
     *
     * <p>bound* 是<b>以开战点 home 为心</b>的判定半轴——v7 取证：界锚 Boss 当前 bbox 会让
     * 竞技场跟着 Boss 跑，玩家永远不出界（等于没有纠偏）。
     * <p>没有 unsealOffsets 这个字段（审查轮 5 P3-4 删掉）：解封一律按**快照位**落回原方块，
     * 不再拿解封时刻的朝向与坐标重算偏移——Boss 打过架就不在原点了，重算必然对不上账。
     */
    public record Spec(List<Vec3> sealOffsets, Block sealBlock,
                       int graceTicks, int warnEveryTicks,
                       boolean healOnFail,
                       double boundRadiusXZ, double boundRadiusY) {
        /**
         * <b>合法域只有一个地方定义</b>：紧凑构造器把四个可配参数交给 {@link ArenaBounds}。
         * 不这么做的话，下游写 {@code new Spec(..., -5, -5)} 会让 {@code AABB} 的 min&gt;max，
         * {@code bounds.contains(...)} 从此恒假——玩家在竞技场里<b>每 tick 被判越界、被反复传送</b>。
         * 这类"配一个坏数字＝运行期折磨人"的输入必须在登记期回默认值（同 {@code TelegraphZone} 的口径）。
         */
        public Spec {
            // 方块与偏移列表也在登记期查（轮 24 P3-D3）：null 的 sealBlock 会炸在 begin() 里的
            // defaultBlockState()，正是本仓刚在 MoveDef 上躲开的那种"运行期才炸"；
            // 空列表则是"会话开了但一格没封"——静默的没用配置。
            if (sealBlock == null) {
                throw new IllegalArgumentException("arena spec needs a non-null sealBlock");
            }
            if (sealOffsets == null || sealOffsets.isEmpty()) {
                throw new IllegalArgumentException("arena spec needs at least one seal offset");
            }
            graceTicks = ArenaBounds.graceTicksOr(graceTicks, ArenaBounds.DEFAULT_GRACE_TICKS);
            warnEveryTicks = ArenaBounds.warnEveryTicksOr(warnEveryTicks, ArenaBounds.DEFAULT_WARN_EVERY_TICKS);
            boundRadiusXZ = ArenaBounds.radiusOr(boundRadiusXZ, ArenaBounds.DEFAULT_RADIUS_XZ);
            boundRadiusY = ArenaBounds.radiusOr(boundRadiusY, ArenaBounds.DEFAULT_RADIUS_Y);
        }

        public static Spec of(List<Vec3> sealOffsets, Block sealBlock) {
            return new Spec(sealOffsets, sealBlock, ArenaBounds.DEFAULT_GRACE_TICKS,
                    ArenaBounds.DEFAULT_WARN_EVERY_TICKS, true,
                    ArenaBounds.DEFAULT_RADIUS_XZ, ArenaBounds.DEFAULT_RADIUS_Y);
        }
    }

    private final ColossusBossEntity boss;
    private final Spec spec;

    private boolean active = false;
    /** 本局是否真的有过参战者（持久化）——团灭判据的"有人打过"前提不能依赖易失名单（P2-4）。 */
    private boolean everEngaged = false;
    /** 封路前原方块快照（pos → state），恢复用。 */
    private final Map<BlockPos, BlockState> sealSnapshot = new HashMap<>();
    private final Map<UUID, Integer> outOfBoundsGrace = new HashMap<>();
    private final Map<UUID, Integer> warnCooldown = new HashMap<>();
    @Nullable
    private Vec3 entryPoint; // 团灭弹回点：开战瞬间的首位参战者位置
    @Nullable
    private Vec3 home;       // Boss 复位点
    /**
     * 这一局的封路发生在哪个维度（轮 24 P2-D2）。<b>{@code long} 坐标跨维度同号</b>——
     * 没有这个键，Boss 换维度之后 {@code hasChunkAt(pos)} 问的是另一个世界的同一坐标，
     * 那一块大概率是加载的，于是追偿会把上一维度的原方块写进当前维度：静默改错世界，
     * 而原来那格还继续扣着封印方块。
     */
    @Nullable
    private String sealDimension = null;

    public ArenaSession(ColossusBossEntity boss, Spec spec) {
        this.boss = boss;
        this.spec = spec;
    }

    public boolean isActive() { return active; }

    /**
     * 当前封路位（快照键集，解封后为空）。
     * 诊断/GameTest 用——测试要断的是"这些位置真的被换了方块、事后真的还原了"，
     * 而不是自己按朝向重算一遍（重算会把 Boss 的位移也重演一遍，制造 flaky）。
     */
    public java.util.Set<BlockPos> sealedPositions() { return java.util.Set.copyOf(sealSnapshot.keySet()); }

    /** 开战（ACTIVATED 的上升沿调用）。 */
    public void begin(ServerLevel level) {
        if (active) return;
        // 上一局欠着的账先结清（轮 24 P3-D3）：sealOffsets 与上一局同源、Boss 又回到 home，
        // 直接开新局会在同一格上撞键——而"先放后记"会把刚放下去的封印方块当成"原方块"记进快照。
        if (!sealSnapshot.isEmpty()) {
            Colossus.LOGGER.warn("boss {} starts a new arena session with {} seal entr(y) still unrestored"
                            + " - settling the old debt first",
                    boss.getBossId(), sealSnapshot.size());
            applySeal(level, false);
        }
        this.sealDimension = level.dimension().location().toString();
        active = true;
        home = boss.position();
        var ps = boss.engagement().participants(level.getServer());
        entryPoint = ps.isEmpty() ? home : ps.get(0).position();
        applySeal(level, true);
        boss.sendBossVisualEvent("colossus:arena_begin");
    }

    /**
     * 每服务端 tick（active 时）：越界提醒→拉回，在册全倒→团灭复位。
     *
     * <p>三处第八批修正：①判定界锚 home（不锚 Boss 移动中的 bbox）；
     * ②团灭用「曾有过真人参战 ∧ 活着的人为空」——旧写法遍历 participants（已滤死者）再问
     * {@code p.isAlive()}，条件恒真，失败回路是死代码；③拉回走 7 参
     * {@code teleportTo(ServerLevel,x,y,z,Set,yaw,pitch)}（{@code ServerPlayer:1199}），
     * 它会挂 POST_TELEPORT ticket；3 参版（{@code :1191}）只发一个 ROTATION 相对包，
     * 落进未加载区块会把人留在空中。
     */
    public void tick(ServerLevel level) {
        if (!active) {
            pumpRestoreOnly(level); // 会话结束了但账没结清 ⇒ 继续追（理由见该方法）
            return;
        }
        var server = level.getServer();
        Vec3 anchor = home == null ? boss.position() : home;
        double rx = spec.boundRadiusXZ(), ry = spec.boundRadiusY();
        AABB bounds = new AABB(anchor.x - rx, anchor.y - ry, anchor.z - rx,
                anchor.x + rx, anchor.y + ry, anchor.z + rx);
        List<ServerPlayer> alive = boss.engagement().participants(server);
        for (int i = 0; i < alive.size(); i++) {
            ServerPlayer p = alive.get(i);
            // 只管本维度的参战者（审查轮 5 P1-2）：participants 收的是全服名单，
            // 拿别的维度的坐标比本维 AABB 必判越界，teleportTo(level,...) 会走 ServerPlayer:1435
            // 的换维通道——把正在下界/末地的人硬拽进封闭竞技场，还是在别人的实体 tick 里发起换维。
            if (p.level() != level) continue;
            if (bounds.contains(p.position())) {
                outOfBoundsGrace.remove(p.getUUID());
                continue;
            }
            int g = outOfBoundsGrace.merge(p.getUUID(), 1, Integer::sum);
            if (g < spec.graceTicks()) continue;
            if (warnCooldown.getOrDefault(p.getUUID(), 0) > 0) continue;
            warnCooldown.put(p.getUUID(), Math.max(1, spec.warnEveryTicks()));
            p.displayClientMessage(Component.translatable("colossus.arena.return"), true);
            Vec3 landing = safeLanding(level, anchor, i);
            p.teleportTo(level, landing.x, landing.y, landing.z, Set.of(), p.getYRot(), p.getXRot());
            outOfBoundsGrace.remove(p.getUUID());
        }
        // 注意 warnEveryTicks 只在 > graceTicks 时才有可观察效果：每次拉回都清 grace，
        // 下一轮要再攒满 graceTicks 才会再传，冷却通常已经走完了（P3-2，如实标注不假装生效）。
        warnCooldown.entrySet().removeIf(e -> e.setValue(e.getValue() - 1) <= 0);
        java.util.Set<UUID> tracked = new java.util.HashSet<>();
        for (ServerPlayer p : boss.engagement().trackedIncludingFallen(server)) {
            tracked.add(p.getUUID());
        }
        // "曾有过真人"要落进 NBT（P2-4）：名单本身是易失的——团灭后玩家在死亡界面退服，
        // 重进时名单为空，只看当下就等于"没人打过"，封路方块永久留在世界里。
        if (!tracked.isEmpty()) everEngaged = true;
        outOfBoundsGrace.keySet().retainAll(tracked); // 越界中直接下线的人不留残项（P3-3）
        warnCooldown.keySet().retainAll(tracked);
        // 团灭：这场确实有过真人、而现在活着的人为空——封路必须解除，
        // 否则玩家复活点在圈外进不来，Boss 满血堵门。
        if (alive.isEmpty() && everEngaged) {
            fail(level);
        }
    }

    /** home 上方找可站立面，按序号绕圈散开（整队叠同一点会互相挤出判定界）。 */
    private static Vec3 safeLanding(ServerLevel level, Vec3 anchor, int index) {
        double angle = index * (Math.PI / 3.0);
        double radius = index == 0 ? 0.0 : 1.2 + 0.4 * ((index - 1) % 4);
        BlockPos origin = BlockPos.containing(
                anchor.x + Math.cos(angle) * radius, anchor.y, anchor.z + Math.sin(angle) * radius);
        // 先问区块在不在（与封/解<b>同一条判据</b>，走 ArenaBounds.worldReady——同一个规则不许有两份写法）：
        // 服务端 getBlockState 会**强制生成**缺失区块，主线程做这件事不是框架该有的形状
        // （落点距 home ≤2.4 格，实践中总是已加载——但这道门值一行——P3-6）
        if (!ArenaBounds.worldReady(level.hasChunkAt(origin))) {
            return new Vec3(origin.getX() + 0.5, origin.getY() + 1.0, origin.getZ() + 0.5);
        }
        for (int dy = -2; dy <= 3; dy++) {
            BlockPos stand = origin.above(dy);
            BlockPos floor = stand.below();
            if (level.getBlockState(stand).isAir()
                    && level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
                return new Vec3(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
            }
        }
        return new Vec3(origin.getX() + 0.5, origin.getY() + 1.0, origin.getZ() + 0.5);
    }

    /** 团灭复位：解封、回血（可选）、清目标回待机。 */
    public void fail(ServerLevel level) {
        if (!active) return;
        active = false;
        applySeal(level, false);
        if (spec.healOnFail()) boss.setHealth(boss.getMaxHealth());
        boss.setTarget(null);
        boss.setActivated(false);
        outOfBoundsGrace.clear();
        warnCooldown.clear();
        boss.sendBossVisualEvent("colossus:arena_fail");
    }

    /**
     * <b>只做一件事</b>：把还没还回去的封路块试着还掉，别的都不做。
     *
     * <p>为什么不复用 {@link #tick(ServerLevel)} 里那条分支（轮 24 P2-D1）：胜利那条路是
     * {@code resolveDeath → victory()}，而 {@code deathPending} 一直保持到原版 remove；
     * 实体 {@code aiStep} 在 {@code deathPending} 那道 return 之前就 return 了，根本走不到
     * {@code tickSessionAndSquad} ⇒ 上一批加的"会话结束后继续追偿"在胜利这条路上一次都跑不到，
     * 欠着的快照随实体一起消失、黑曜石永久留在玩家世界里。第三十六批的注释说"那道门拆了"，
     * 拆的是第二道（{@code isActive}），第一道在这里——这个入口就是补那第一道。
     */
    /**
     * 测试专用：让<b>下一次</b>解封故意欠一格（区块没加载这件事在 GameTest 里造不出来——
     * 结构范围内总是加载的，而 D-1 的现场恰恰是"欠着一格 + Boss 正在死亡"）。
     * 有了这个缝，那条路径第一次能被写成会红的判据；不设它就只能"我相信修好了"。
     */
    private boolean debugDeferNextRestore = false;
    /** 这个缝真的让它欠下过几格（判据要能证明"故意欠账"这一步发生过，否则整条桩会退化成旧测试）。 */
    private int debugDeferredCount = 0;

    public void debugDeferNextRestore() {
        this.debugDeferNextRestore = true;
    }

    /** 被测试缝故意欠下的次数。 */
    public int debugDeferredCount() {
        return this.debugDeferredCount;
    }

    public void pumpRestoreOnly(ServerLevel level) {
        if (this.active || sealSnapshot.isEmpty()) return;
        applySeal(level, false);
    }

    /** 封路是在哪个维度封的（追偿前的维度对账用；{@code null}＝本会话还没封过）。 */
    @Nullable
    public String sealDimension() { return this.sealDimension; }

    /** 胜利（resolveDeath 时调用）：只解封，不复活。 */
    public void victory(ServerLevel level) {
        if (!active) return;
        active = false;
        applySeal(level, false);
        outOfBoundsGrace.clear();
        warnCooldown.clear();
    }

    // ---------------- 封路方块：快照-覆盖-恢复（BR closeOffExit 形） ----------------

    /**
     * 封路/解封。两个方向<b>不对称是有意的</b>（审查轮 5 P3-4）：
     * 封路按 {@code sealOffsets} 现算（此刻 Boss 还在开战点），解封<b>只认快照位</b>——
     * 打了一场架之后 Boss 的坐标与朝向都变了，拿解封时刻重算偏移必然对不上账，
     * 原先"靠兜底全量还原救回来"的形状等于一条永远走不通的主路径。
     */
    private void applySeal(ServerLevel level, boolean seal) {
        String who = String.valueOf(boss.getBossId());
        if (!seal) {
            String dimNow = level.dimension().location().toString();
            for (var e : new ArrayList<>(sealSnapshot.entrySet())) {
                // <b>先问区块，再动手</b>（v14 取证 B 节：TF 1.20.1 七个 Boss 在写封印方块前
                // 固定跑 {@code isRestrictionPointValid(dim) && level().isLoaded(pos)} 双短路；
                // 而本仓此前<b>一次 isLoaded 都没有</b>）。服务端 {@code setBlock/getBlockState}
                // 对缺失区块会<b>强制生成</b>，这件事发生在 Boss 的 tick 里。
                if (this.debugDeferNextRestore) {
                    this.debugDeferNextRestore = false;
                    this.debugDeferredCount++;
                    continue; // 故意留着这条账：与"区块没加载"走的是同一条不删账路径
                }
                boolean sameDim = sealDimension == null || sealDimension.equals(dimNow);
                if (!ArenaBounds.restoreAllowed(sameDim, level.hasChunkAt(e.getKey()))) {
                    // 没加载就<b>不删账</b>：交给 tick() 开头那条"会话已结束但快照非空"的追偿路径。
                    // 旧写法是"调过 setBlock 就 remove"，于是一次没加载＝方块永久留在世界里，
                    // 连重试的机会都被删掉了。
                    if (OncePerKey.firstTime(who + "|arena-restore")) {
                        Colossus.LOGGER.warn("arena restore deferred at {} in {} ({}): keeping {}"
                                        + " snapshot entr(y) until it can be settled (boss {})",
                                e.getKey().toShortString(), dimNow,
                                sameDim ? "chunk not loaded" : "wrong dimension - boss moved?",
                                sealSnapshot.size(), boss.getBossId());
                    }
                    continue;
                }
                level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_ALL);
                sealSnapshot.remove(e.getKey());
            }
            return;
        }
        for (Vec3 local : spec.sealOffsets()) {
            BlockPos pos = BlockPos.containing(
                    com.klze.colossus.env.ArenaBlockAccess.rotateFacing(local, boss.getYRot())
                            .add(boss.position()));
            // 两个条件都先算成 boolean 再交给纯判据；{@code loaded &&} 这一段<b>必须短路</b>——
            // 少了它，缺块时查 canBeReplaced 就已经把区块强制生成了，那道门等于没设。
            boolean loaded = level.hasChunkAt(pos);
            boolean replaceable = loaded && level.getBlockState(pos).canBeReplaced();
            switch (ArenaBounds.classifySeal(loaded, replaceable)) {
                case NO_CHUNK -> {
                    // 缺块不硬放：这一格留空，环有洞，但洞是<b>记在账上</b>的（不生成区块、不谎报成功）
                    if (OncePerKey.firstTime(who + "|arena-nochunk")) {
                        Colossus.LOGGER.warn("arena seal slot {} skipped — chunk not loaded, "
                                + "ring has a gap (boss {})", pos.toShortString(), boss.getBossId());
                    }
                    continue;
                }
                case OCCUPIED -> {
                    // 静默跳过会在竞技场墙上留一个没人知道的缺口（P3-5）
                    if (OncePerKey.firstTime(who + "|arena-occupied")) {
                        Colossus.LOGGER.warn("arena seal slot {} occupied — seal skipped, ring has a gap (boss {})",
                                pos.toShortString(), boss.getBossId());
                    }
                    continue;
                }
                default -> {
                    // 同一格第二次封跳过（轮 24 P3-D3）：两条不同 Vec3 取整后可以落进同一格，
                    // 而"先放后记"会把上一格刚放下去的封印方块当成原方块记账 ⇒
                    // 解封时把封印还原成封印，原方块永久丢失。
                    if (ArenaBounds.slotAlreadySealed(sealSnapshot.containsKey(pos.immutable()))) {
                        if (OncePerKey.firstTime(who + "|arena-dup")) {
                            Colossus.LOGGER.warn("arena seal offsets collide on {} - second one skipped"
                                    + " (boss {})", pos.toShortString(), boss.getBossId());
                        }
                        continue;
                    }
                    sealSnapshot.put(pos.immutable(), level.getBlockState(pos));
                    level.setBlock(pos, spec.sealBlock().defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    /** 还欠着多少格没还回世界（诊断与 GameTest 判据：解封不是"调用过就算完"）。 */
    public int pendingRestoreCount() { return sealSnapshot.size(); }

    // ---------------- NBT 持久化（崩档/重载不吞封路） ----------------

    public void save(CompoundTag tag) {
        tag.putBoolean("active", active);
        tag.putBoolean("ever_engaged", everEngaged); // 团灭判据的持久前提（P2-4）——不存就会在退服重载后永久封死
        if (home != null) {
            tag.putDouble("home_x", home.x); tag.putDouble("home_y", home.y); tag.putDouble("home_z", home.z);
        }
        if (entryPoint != null) {
            tag.putDouble("entry_x", entryPoint.x); tag.putDouble("entry_y", entryPoint.y); tag.putDouble("entry_z", entryPoint.z);
        }
        if (sealDimension != null) tag.putString("seal_dim", sealDimension);
        ListTag snapshot = new ListTag();
        for (var e : sealSnapshot.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("pos", e.getKey().asLong());
            entry.put("state", NbtUtils.writeBlockState(e.getValue()));
            snapshot.add(entry);
        }
        tag.put("seal_snapshot", snapshot);
    }

    public void load(CompoundTag tag, net.minecraft.world.level.Level readLevel) {
        active = tag.getBoolean("active");
        everEngaged = tag.getBoolean("ever_engaged");
        // <b>三键齐 + 三轴可用</b>才认（第三十六批，v14 取证 B 节）。旧写法只看 {@code home_x}
        // 在不在，于是少写一个 {@code home_y} 的存档会静默读成 0——竞技场圆心直接沉到世界底；
        // 而 {@code getDouble} 对类型不对的键也是回 0（它内部走 mask=99 宽读数值、不认字符串）。
        // 存在性判据必须用 {@code TAG_ANY_NUMERIC=99}（{@code Tag.java:24}）：用具体类型号会比
        // 读取端更严，把"存成 Int 的合法存档"判成缺键＝整段边界静默失效（本仓已踩过三次）。
        sealDimension = tag.contains("seal_dim", Tag.TAG_STRING) ? tag.getString("seal_dim") : null;
        home = readTriple(tag, "home_x", "home_y", "home_z", "home");
        entryPoint = readTriple(tag, "entry_x", "entry_y", "entry_z", "entry");
        ListTag snapshot = tag.getList("seal_snapshot", Tag.TAG_COMPOUND);
        sealSnapshot.clear();
        for (int i = 0; i < snapshot.size(); i++) {
            CompoundTag entry = snapshot.getCompound(i);
            BlockState state = NbtUtils.readBlockState(
                    readLevel.holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                    entry.getCompound("state"));
            sealSnapshot.put(BlockPos.of(entry.getLong("pos")), state);
        }
        Colossus.LOGGER.debug("arena session restored: active={} sealSnapshots={} home={}",
                active, sealSnapshot.size(), home == null ? "<fallback: boss position>" : home.toString());
    }

    /**
     * 读一个坐标三元组：缺任一键、或读出来不是有限/范围内的值，一律交 {@code null}——
     * 让调用方既有的 {@code home == null ? boss.position() : home} 兜底去接，
     * 而不是拿一个 (0,0,0) 或 NaN 去建 {@code AABB}（NaN 圆心会让 {@code contains} 恒假，
     * 玩家在竞技场里<b>每 tick 都被判越界、被反复传送</b>）。
     */
    @Nullable
    private Vec3 readTriple(CompoundTag tag, String kx, String ky, String kz, String what) {
        if (!(tag.contains(kx, Tag.TAG_ANY_NUMERIC) && tag.contains(ky, Tag.TAG_ANY_NUMERIC)
                && tag.contains(kz, Tag.TAG_ANY_NUMERIC))) {
            return null; // 干净的"没写过"（会话还没 begin 过）——不报警
        }
        double x = tag.getDouble(kx), y = tag.getDouble(ky), z = tag.getDouble(kz);
        if (ArenaBounds.usableCoordinate(x, y, z)) return new Vec3(x, y, z);
        if (OncePerKey.firstTime(boss.getBossId() + "|arena-" + what)) {
            Colossus.LOGGER.warn("arena {} point read from save is unusable ({}, {}, {}) — "
                            + "falling back to the boss position (boss {})",
                    what, x, y, z, boss.getBossId());
        }
        return null;
    }

    public Vec3 homeOrFallback() {
        return home == null ? boss.position() : home;
    }
}
