package com.klze.colossus.fight;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 参战名单（engagement）：解决"最后一击不是我，但我参与了"的共享 credit 问题。
 * 六个样本的共同做法——在 hurt() 里收集，tick 里剔除死亡/超时玩家。
 *
 * <p>注意与原版 BossEvent.players 的区别：那个集合是"可见"，这里是"真参战"；
 * 框架取两者的并集做奖励结算，取本表做击杀 credit。
 */
public final class EngagementTracker {

    /** 超过该 tick 数无贡献即除名（默认 10 分钟，对齐设计书 §2.3）。 */
    public static final int DEFAULT_TTL = 12000;

    private final Map<UUID, Integer> lastContribution = new LinkedHashMap<>();
    private final int ttl;

    public EngagementTracker() { this(DEFAULT_TTL); }

    public EngagementTracker(int ttlTicks) { this.ttl = ttlTicks; }

    /** 一次有效参战贡献（造成伤害/被 BOSS 锁定都算）。 */
    public void onContribution(Player player) {
        lastContribution.put(player.getUUID(), 0);
    }

    /** 每 tick 老化：只按"离线/超时"除名——死亡玩家<b>保留在账</b>（复活回场仍算参战、
     *  演出中的击杀结算也拿得到 credit——审查 P1#5）。 */
    public void tick(ServerLevel level) {
        Iterator<Map.Entry<UUID, Integer>> it = lastContribution.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> e = it.next();
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            int age = e.getValue() + 1;
            if (age > ttl) {
                it.remove();
            } else {
                e.setValue(age);
            }
        }
    }

    /** 当前参战玩家快照（服务端，**只含活着的人**——结算/传送/提醒用这一份）。 */
    public List<ServerPlayer> participants(MinecraftServer server) {
        List<ServerPlayer> out = new ArrayList<>();
        for (UUID id : lastContribution.keySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null && p.isAlive()) out.add(p);
        }
        return out;
    }

    /**
     * 在册玩家快照（**含阵亡未除名者**）。
     *
     * <p>存在的理由：竞技场团灭判定必须区分"名单空了（没人打过）"与"名单有人但全倒了"。
     * 若拿 {@link #participants} 判全灭，它已经把死者过滤掉了，"有人但全死"永远为假
     * ——第八批取证抓到的死分支。credit 结算用 participants，失败回路用这一份。
     */
    public List<ServerPlayer> trackedIncludingFallen(MinecraftServer server) {
        List<ServerPlayer> out = new ArrayList<>();
        for (UUID id : lastContribution.keySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) out.add(p);
        }
        return out;
    }

    /**
     * 名单当下是否有活人（不含阵亡未除名者）。
     * 注意别拿它判团灭——团灭要用 {@link #trackedIncludingFallen} 或持久化的
     * {@code ArenaSession} 判据（第八批 P1 那条恒假分支就是这么来的）。
     */
    public boolean isEmpty() { return lastContribution.isEmpty(); }

    public int size() { return lastContribution.size(); }

    public void clear() { lastContribution.clear(); }
}
