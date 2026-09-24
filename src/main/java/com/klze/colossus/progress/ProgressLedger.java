package com.klze.colossus.progress;

import com.klze.colossus.Colossus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 全局进度的<b>线上形态</b>（纯逻辑，零 MC 实体依赖 → 可进 StateSelfTest）。
 *
 * <p>为什么自己写而不照抄被推荐的 Confluence KillBoard：v9 取证实测
 * {@code forge-1.20.1-47.4.23-sources.jar} 里 {@code StreamCodec}/{@code ByteBufCodecs}/
 * {@code CustomPacketPayload} <b>0 命中</b>（那些是 1.20.2+ 的东西），
 * 所以"Codec/StreamCodec 成对 + 包体即状态"这条路在 1.20.1 不存在。
 * 改道后的正解（三个 1.20.1 Forge 样本同形）：<b>一包 {@link CompoundTag}</b>，
 * 与 {@code SavedData#save} 共用同一份 tag 形状，零第二套序列化。
 *
 * <p>三条硬规矩：
 * ①<b>解码端有上限</b>（{@link #MAX_ENTRIES}）——客户端不能因为服务端（或中间人）给一张
 * 十万键的表就把自己撑爆；超限截断并告警，<b>不抛</b>（抛在 packet handler 里等于炸连接）。
 * ②<b>畸形条目跳过</b>，不整包作废（同 Tetra/vanilla reload 的"单条失败只 log"取向）。
 * ③{@link #shouldSend} 只认<b>代际号</b>：值没变就不发，与"每 tick 重播全表"划清界限
 * （Confluence 的 defeat() 是无条件重发，点名为反面）。
 */
public final class ProgressLedger {

    /** 解码端硬上限（条目数）。服务端正常远达不到，这道门防的是坏数据/超大包。 */
    public static final int MAX_ENTRIES = 4096;

    /**
     * 快照 = 来源身份 + 代际号 + 击杀数 + 已击败集合。
     *
     * <p>{@code sourceIdentity} 每次服务端启动随机、由 {@code ProgressSync} 在发送前盖章。
     * 没有它，"代际号倒退即丢"这条规则是错的：{@code BossKillBoard} 的 revision 是
     * **每世界一份**，切到一个 revision 更低的存档时，正常快照会被当成"迟到包"整条丢掉，
     * 而服务端只在变化时发 ⇒ 永不恢复（旧写法把跨世界安全全押在 LoggingOut 一个钩子上）。
     */
    public record Snapshot(long sourceIdentity, long revision, Map<String, Integer> kills, Set<String> defeated) {
        public Snapshot {
            kills = Map.copyOf(kills);
            defeated = Set.copyOf(defeated);
        }

        public int killCount(String id) {
            return kills.getOrDefault(id, 0);
        }

        public boolean isDefeated(String id) {
            return defeated.contains(id);
        }
    }

    private ProgressLedger() {}

    public static CompoundTag encode(long sourceIdentity, long revision,
                                     Map<String, Integer> kills, Set<String> defeated) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("src", sourceIdentity);
        tag.putLong("rev", revision);
        ListTag killList = new ListTag();
        for (Map.Entry<String, Integer> e : kills.entrySet()) {
            CompoundTag row = new CompoundTag();
            row.putString("id", e.getKey());
            row.putInt("count", e.getValue());
            killList.add(row);
        }
        tag.put("kills", killList);
        ListTag defList = new ListTag();
        for (String d : defeated) {
            CompoundTag row = new CompoundTag();
            row.putString("id", d);
            defList.add(row);
        }
        tag.put("defeated", defList);
        return tag;
    }

    /**
     * 宽松解码：缺字段当空、类型不符跳条目并告警、条目数封顶 {@link #MAX_ENTRIES}。
     * 永不抛异常——它跑在 netty 线程 enqueueWork 之后的主线程里，抛出=踢玩家。
     */
    public static Snapshot decode(CompoundTag tag) {
        Map<String, Integer> kills = new HashMap<>();
        Set<String> defeated = new HashSet<>();
        if (tag == null) return new Snapshot(0L, -1L, kills, defeated);
        long src = tag.getLong("src");
        long rev = tag.getLong("rev");

        if (tag.get("kills") instanceof ListTag killList) {
            int n = 0;
            for (int i = 0; i < killList.size() && n < MAX_ENTRIES; i++, n++) {
                if (!(killList.get(i) instanceof CompoundTag row) || !row.contains("id")) {
                    Colossus.LOGGER.warn("progress snapshot: skipping malformed kill entry #{}", i);
                    continue;
                }
                kills.put(row.getString("id"), row.getInt("count"));
            }
            if (killList.size() > MAX_ENTRIES) {
                Colossus.LOGGER.warn("progress snapshot kill entries {} exceeds cap {}, truncated",
                        killList.size(), MAX_ENTRIES);
            }
        }
        if (tag.get("defeated") instanceof ListTag defList) {
            int n = 0;
            for (int i = 0; i < defList.size() && n < MAX_ENTRIES; i++, n++) {
                if (!(defList.get(i) instanceof CompoundTag row) || !row.contains("id")) {
                    Colossus.LOGGER.warn("progress snapshot: skipping malformed defeated entry #{}", i);
                    continue;
                }
                defeated.add(row.getString("id"));
            }
            if (defList.size() > MAX_ENTRIES) {
                Colossus.LOGGER.warn("progress snapshot defeated entries {} exceeds cap {}, truncated",
                        defList.size(), MAX_ENTRIES);
            }
        }
        return new Snapshot(src, rev, kills, defeated);
    }

    /** 脏检查：代际号没变就一个包都不发（含首帧 lastSent=-1 必发）。 */
    public static boolean shouldSend(long lastSentRevision, long currentRevision) {
        return lastSentRevision != currentRevision;
    }
}
