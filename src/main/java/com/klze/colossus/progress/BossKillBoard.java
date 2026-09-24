package com.klze.colossus.progress;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 全局击杀板（Confluence KillBoard 模式）：Boss 击杀状态的唯一权威存储。
 * 任何门槛系统（召唤物 gate、NPC 交易、结构解锁、成就）只查询它，不各自记账。
 *
 * <p>玩家维度的"挑战次数"另存于 {@code player.getPersistentData()}
 * （供 v0.2 的 loot condition 读取，做第 N 次击杀保底）。
 */
public class BossKillBoard extends SavedData {

    public static final String DATA_NAME = "colossus_kill_board";

    /** key = boss 注册 id 字符串。 */
    private final Map<String, Integer> kills = new HashMap<>();
    private final Set<String> defeated = new HashSet<>();

    public BossKillBoard() {}

    public static BossKillBoard get(ServerLevel level) {
        // 1.20.1 无 SavedData.Factory——DataStorage 三参形态是 (deserializer, constructor, name)
        return level.getServer().overworld().getDataStorage()
                .computeIfAbsent(BossKillBoard::load, BossKillBoard::new, DATA_NAME);
    }

    public static BossKillBoard load(CompoundTag tag) {
        BossKillBoard board = new BossKillBoard();
        CompoundTag killsTag = tag.getCompound("kills");
        for (String k : killsTag.getAllKeys()) {
            board.kills.put(k, killsTag.getInt(k));
        }
        CompoundTag defTag = tag.getCompound("defeated");
        for (String k : defTag.getAllKeys()) {
            if (defTag.getBoolean(k)) board.defeated.add(k);
        }
        return board;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag killsTag = new CompoundTag();
        kills.forEach(killsTag::putInt);
        tag.put("kills", killsTag);
        CompoundTag defTag = new CompoundTag();
        for (String d : defeated) defTag.putBoolean(d, true);
        tag.put("defeated", defTag);
        return tag;
    }

    /** 记一次击杀。返回是否首杀。 */
    public boolean recordKill(ResourceLocation bossId) {
        kills.merge(bossId.toString(), 1, Integer::sum);
        boolean first = defeated.add(bossId.toString());
        setDirty();
        return first;
    }

    public boolean isDefeated(ResourceLocation bossId) { return defeated.contains(bossId.toString()); }

    public int killCount(ResourceLocation bossId) { return kills.getOrDefault(bossId.toString(), 0); }

    // ---------------- 玩家挑战次数（PersistentData 通道） ----------------

    private static final String PLAYER_NBT_KEY = "colossus_boss_challenges";

    /** 该玩家对该 Boss 的累计挑战次数（含失败：每次进入战斗计一次）。 */
    public static int challengesOf(Player player, ResourceLocation bossId) {
        return player.getPersistentData().getCompound(PLAYER_NBT_KEY).getInt(bossId.toString());
    }

    public static void increaseChallenge(Player player, ResourceLocation bossId) {
        CompoundTag root = player.getPersistentData();
        CompoundTag counts = root.getCompound(PLAYER_NBT_KEY);
        counts.putInt(bossId.toString(), counts.getInt(bossId.toString()) + 1);
        root.put(PLAYER_NBT_KEY, counts);
        // 1.20.1 Forge 在玩家存档时序列化 getPersistentData()——无需手动标脏
    }

    /** 击杀时给参战玩家记次数（与 challenges 区分：kill 也计入挑战）。 */
    public static void recordKillFor(Player player, ResourceLocation bossId) {
        increaseChallenge(player, bossId);
    }
}
