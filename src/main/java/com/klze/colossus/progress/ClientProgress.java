package com.klze.colossus.progress;

import net.minecraft.resources.ResourceLocation;

/**
 * 客户端进度镜像（服务端权威 → 本地 O(1) 查询）。
 *
 * <p>存在的理由：loot 条件、NPC 交易锁、HUD 提示这些消费者每帧都可能问一次，
 * 让它们去查服务端 SavedData 既不现实也不可能的（客户端没有）。
 * 镜像只由 {@link com.klze.colossus.network.ColossusPackets.ProgressSnapshotS2C} 写入，
 * 业务代码<b>只读</b>。
 *
 * <p>生命周期三条（取证点名的反面：Confluence 的 enum static 双端共用表在换单人世界时读到上一张表）：
 * ①断线/退世界必须 {@link #clear()}（挂在 {@code ColossusClientHooks} 的 LoggingOut）；
 * ②{@link #revision()} 为 -1 表示"还没收到快照"，查询一律按"没人打过"回答，<b>不</b>回退到旧表；
 * ③服务端只在代际号变化时广播，所以这里不需要合并逻辑——整张换。
 */
public final class ClientProgress {

    private static volatile ProgressLedger.Snapshot mirror =
            new ProgressLedger.Snapshot(-1L, java.util.Map.of(), java.util.Set.of());

    private ClientProgress() {}

    /** 只由网络层调用（业务代码写这里=绕过服务端权威）。 */
    public static void apply(ProgressLedger.Snapshot next) {
        // 代际号倒退＝上一张表的迟到包（换世界/重连竞态），直接丢：镜像只能变新
        if (next.revision() < mirror.revision() && mirror.revision() >= 0L) return;
        mirror = next;
    }

    public static void clear() {
        mirror = new ProgressLedger.Snapshot(-1L, java.util.Map.of(), java.util.Set.of());
    }

    public static boolean isDefeated(ResourceLocation bossId) {
        return mirror.isDefeated(bossId.toString());
    }

    public static int killCount(ResourceLocation bossId) {
        return mirror.killCount(bossId.toString());
    }

    /** 已收到快照的条数（诊断与回归桩用）。 */
    public static int trackedBosses() {
        return mirror.kills().size();
    }

    public static long revision() {
        return mirror.revision();
    }
}
