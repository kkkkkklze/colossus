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
        ColossusPackets.broadcastProgress(board.snapshot());
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        MinecraftServer server = p.getServer();
        BossKillBoard board = BossKillBoard.get(server.overworld());
        ColossusPackets.sendProgressTo(board.snapshot(), p);
    }

    @SubscribeEvent
    public static void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        lastSentRevision = Long.MIN_VALUE; // 判据③：下次开服无条件重发一次
    }

    /** 内容侧主动要求立刻重推（例如管理员改了存档、或别的系统直接写了 KillBoard）。 */
    public static void invalidateServerCache() {
        lastSentRevision = Long.MIN_VALUE;
    }

    /** 诊断：当前对账基线（回归桩用来证明"同值这一轮没发"）。 */
    public static long lastSentRevision() {
        return lastSentRevision;
    }
}
