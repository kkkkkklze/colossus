package com.klze.colossus.env;

import com.klze.colossus.Colossus;
import com.klze.colossus.entity.ColossusBossEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * 延迟工作的一种：危险区爆发（telegraph 的 warn 到期结算）。
 *
 * <p>它的存在把"排到未来的事"从闭包变成三件可写进存档的东西——
 * {@code (绝对 gameTime, 种类 id, NBT 数据)}。种类注册表在
 * {@code ColossusBossEntity.registerDeferredWork} 上开放给下游（同一机制，不另开一条路）。
 */
public final class ZoneWork {

    public static final String KIND = "colossus:zone_burst";

    private ZoneWork() {}

    public static CompoundTag encode(TelegraphZone zone, ZoneBurst burst) {
        CompoundTag tag = new CompoundTag();
        tag.put("zone", zone.toTag());
        tag.put("burst", burst.toTag());
        return tag;
    }

    /** 到期执行。区域用<b>出招时解算好的世界坐标</b>，不随 Boss 位移重算。 */
    public static void execute(ColossusBossEntity boss, CompoundTag data) {
        if (!(boss.level() instanceof ServerLevel level)) return;
        // isAlive() 在死亡演出期仍为 true（血量被钉在 1.0f），所以要额外判 deathPending：
        // 演出中不该再往玩家身上落一发没预警的圈
        if (!(data.get("zone") instanceof CompoundTag zoneTag) || !boss.isAlive() || boss.isDeathPending()) {
            Colossus.LOGGER.debug("zone work skipped: boss={} alive={} dying={} hasZoneTag={}",
                    boss.getBossId(), boss.isAlive(), boss.isDeathPending(),
                    data.get("zone") instanceof CompoundTag);
            return; // 结构不合法或 Boss 已倒/正在倒下：静默丢弃（丢弃的原因在调用侧已留痕）
        }
        if (!(data.get("burst") instanceof CompoundTag burstTag)) {
            Colossus.LOGGER.warn("dropped {} work with no burst payload (boss {})", KIND, boss.getBossId());
            return;
        }
        TelegraphZone zone = TelegraphZone.fromTag(zoneTag);
        var burst = ZoneBurst.fromTag(burstTag);
        int n = zone.targets((ServerLevel) boss.level(), boss).size();
        Colossus.LOGGER.debug("zone work fired: boss={} center=({}, {}, {}) r={} dmg={} freeze={} targets={}",
                boss.getBossId(), String.format("%.1f", zone.cx()), String.format("%.1f", zone.cz()),
                String.format("%.1f", zone.cy()), zone.radiusXZ(), burst.damage(), burst.freezeTicks(), n);
        burst.toEffect().apply(boss, level, zone);
    }
}
