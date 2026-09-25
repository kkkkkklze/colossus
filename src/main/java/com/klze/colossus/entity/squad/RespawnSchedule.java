package com.klze.colossus.entity.squad;

import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 重生排期表（纯逻辑——可进 StateSelfTest）。
 *
 * <p>取 Kraken 的"绝对 gameTime"方案：条目记到点时刻而非倒计时，
 * 重启/区块卸载不漂移；同 key 去重（排期中不重复排）。
 * NBT 形：两列对齐的 keys/times 列表，损坏时按短列截断。
 */
public final class RespawnSchedule {

    private final Map<String, Long> dueAt = new HashMap<>();

    /** 排期（同 key 已在排期中则忽略——Kraken noneMatch 去重语义）。 */
    public void schedule(String key, long dueGameTime) {
        dueAt.putIfAbsent(key, dueGameTime);
    }

    public boolean isScheduled(String key) {
        return dueAt.containsKey(key);
    }

    /**
     * 全部撤单（队长倒下时用）。
     *
     * <p><b>先把机制说准</b>（审查轮 8 更正——我轮 7 这里写反过一次）：队长挂起死亡后补员路径
     * <b>本来就点不着</b>，{@code ColossusBossEntity.aiStep} 在 {@code deathPending} 时早退，
     * {@code tickSessionAndSquad} 里还有一道 {@code !deathPending} 门，所以"演出 100t 里到点补员"
     * 不成立，本方法不是修那个 bug。
     *
     * <p>它买的是<b>账的语义</b>：队长已死，它名下不该再有排期。留着条目只会让 {@code save()}
     * 把"死队长的复活预约"写进 NBT，读起来像仍有意图；哪天有人重构那两道死亡门，这里就静默补员。
     * 显式撤掉比"靠别处的门挡住"可靠。
     */
    public void cancelAll() {
        dueAt.clear();
    }

    /** 到点条目全部出队。 */
    public List<String> consumeDue(long gameTime) {
        List<String> due = new ArrayList<>();
        var it = dueAt.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getValue() <= gameTime) {
                due.add(e.getKey());
                it.remove();
            }
        }
        return due;
    }

    public int size() { return dueAt.size(); }

    /** 单 CompoundTag 键值映射（最笨但零类型陷阱——两列 ListTag 对齐在 1.20.1 读写类型滤镜下不可靠）。 */
    public void save(CompoundTag tag) {
        CompoundTag map = new CompoundTag();
        for (var e : dueAt.entrySet()) {
            map.putLong(e.getKey(), e.getValue());
        }
        tag.put("colossus_respawn", map);
    }

    public void load(CompoundTag tag) {
        dueAt.clear();
        CompoundTag map = tag.getCompound("colossus_respawn");
        for (String k : map.getAllKeys()) {
            dueAt.put(k, map.getLong(k));
        }
    }
}
