package com.klze.colossus.progress;

import com.klze.colossus.Colossus;
import com.klze.colossus.network.ColossusPackets;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * 全局进度的<b>推送侧</b>（第十三批）：服务端变化 → 客户端镜像。
 *
 * <p>1.20.1 的 {@code SavedData} 没有变更钩子（{@code SavedData} 全文只有 dirty 布尔，
 * {@code DimensionDataStorage} 也无通知；Forge event 包里 grep SavedData 0 命中——v9 实测负结果），
 * 所以只能<b>在改写点显式推</b>。本类用"代际号 + 低频对账"实现，三个可自检判据：
 * ①<b>同值不发包</b>（{@link ProgressLedger#shouldSend}）；
 * ②<b>join 必补包</b>（{@link PlayerEvent.PlayerLoggedInEvent} 单发当前快照——
 *   Confluence 只在 login 补、respawn 不补，是缺陷不是范式）；
 * ③<b>停服重置对账基线</b>（换世界/重启后第一 tick 必然重发一次，防"镜像停在旧世界"）。
 *
 * <p>对账周期 20t：击杀频率远低于此，多等 1s 无感，省下的是每 tick 查 SavedData。
 */
@Mod.EventBusSubscriber(modid = Colossus.MODID)
public final class ProgressSync {

    private static final int CHECK_INTERVAL_TICKS = 20;

    /** 本次服务端实例的身份（跨世界/重启都会换了它，客户端据此判"这是另一张表"）。 */
    private static final long SERVER_IDENTITY = java.util.UUID.randomUUID().getMostSignificantBits();

    private static long lastSentRevision = Long.MIN_VALUE;
    private static int tickAccumulator = 0;

    private ProgressSync() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++tickAccumulator < CHECK_INTERVAL_TICKS) return;
        tickAccumulator = 0;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        BossKillBoard board = BossKillBoard.get(server.overworld());
        if (!ProgressLedger.shouldSend(lastSentRevision, board.revision())) return;
        lastSentRevision = board.revision();
        broadcastCount++;
        ColossusPackets.broadcastProgress(stamped(board));
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        MinecraftServer server = p.getServer();
        BossKillBoard board = BossKillBoard.get(server.overworld());
        ColossusPackets.sendProgressTo(stamped(board), p);
    }

    @SubscribeEvent
    public static void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        lastSentRevision = Long.MIN_VALUE; // 判据③：下次开服无条件重发一次
    }

    /** 盖来源身份章（快照本体不带，因为它也是 SavedData 的落盘形状）。 */
    private static net.minecraft.nbt.CompoundTag stamped(BossKillBoard board) {
        net.minecraft.nbt.CompoundTag tag = board.snapshot();
        tag.putLong("src", SERVER_IDENTITY);
        return tag;
    }

    /** 对账基线（诊断用）。 */
    public static long lastSentRevision() {
        return lastSentRevision;
    }

    /**
     * 实际广播次数。<b>光看基线挡不住"删掉脏检查"</b>——少了那扇门时基线仍会被设成同一个值，
     * 断言照样绿（轮 6 建议的那条判据本身有洞）。所以这里数真发出去的包：
     * 一次变化 → +1；之后静止若干轮 → 不再涨。
     */
    private static long broadcastCount;

    public static long broadcastCount() {
        return broadcastCount;
    }
}
