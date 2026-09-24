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
        public static Spec of(List<Vec3> sealOffsets, Block sealBlock) {
            return new Spec(sealOffsets, sealBlock, 200, 100, true, 64, 32);
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
        if (!active) return;
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
        // 先问区块在不在：服务端 getBlockState 会**强制生成**缺失区块，主线程做这件事不是框架该有的形状
        // （落点距 home ≤2.4 格，实践中总是已加载——但这道门值一行——P3-6）
        if (!level.hasChunkAt(origin)) {
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
        if (!seal) {
            for (var e : new ArrayList<>(sealSnapshot.entrySet())) {
                level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_ALL);
                sealSnapshot.remove(e.getKey());
            }
            return;
        }
        for (Vec3 local : spec.sealOffsets()) {
            BlockPos pos = BlockPos.containing(
                    com.klze.colossus.env.ArenaBlockAccess.rotateFacing(local, boss.getYRot())
                            .add(boss.position()));
            BlockState current = level.getBlockState(pos);
            if (!current.canBeReplaced()) {
                // 静默跳过会在竞技场墙上留一个没人知道的缺口（P3-5）
                Colossus.LOGGER.warn("arena seal slot {} occupied by {} — seal skipped, ring has a gap (boss {})",
                        pos.toShortString(), current.getBlock().getName().getString(), boss.getBossId());
                continue;
            }
            sealSnapshot.put(pos.immutable(), current);
            level.setBlock(pos, spec.sealBlock().defaultBlockState(), Block.UPDATE_ALL);
        }
    }

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
        if (tag.contains("home_x")) {
            home = new Vec3(tag.getDouble("home_x"), tag.getDouble("home_y"), tag.getDouble("home_z"));
        }
        if (tag.contains("entry_x")) {
            entryPoint = new Vec3(tag.getDouble("entry_x"), tag.getDouble("entry_y"), tag.getDouble("entry_z"));
        }
        sealSnapshot.clear();
        ListTag snapshot = tag.getList("seal_snapshot", Tag.TAG_COMPOUND);
        for (int i = 0; i < snapshot.size(); i++) {
            CompoundTag entry = snapshot.getCompound(i);
            BlockState state = NbtUtils.readBlockState(
                    readLevel.holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                    entry.getCompound("state"));
            sealSnapshot.put(BlockPos.of(entry.getLong("pos")), state);
        }
        Colossus.LOGGER.debug("arena session restored: active={} sealSnapshots={}", active, sealSnapshot.size());
    }

    public Vec3 homeOrFallback() {
        return home == null ? boss.position() : home;
    }
}
