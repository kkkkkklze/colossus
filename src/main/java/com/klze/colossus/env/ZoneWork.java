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

    /**
     * 这份载荷<b>能不能</b>落伤——返回 null 表示可以，否则给出一条弃单原因。
     *
     * <p>判据单独成<b>纯函数</b>（同 {@code clampTelegraphCap} 的理由）：不留下来就没人在无图环境下
     * 让它变红。而它必须被钉住，因为"坏档只钳不打"这条口径在这里有个洞：
     * {@code CompoundTag} 对缺失键返回 {@code 0}/{@code ""}、<b>不抛</b>，
     * {@code TelegraphZone.fromTag} 又会把 0 半径回落成 1.0 ⇒ 一份被截断的 zone tag
     * 会读成"世界原点一个 1 格圈"，而伤害照样落（轮 18 P3-3 的后一半，也是要紧的那一半——
     * 上一批我只在投影解码处加了 {@code hasRequiredKeys}，可结算并不走那条路）。
     */
    public static String settleRejectReason(CompoundTag data) {
        if (!(data.get("zone") instanceof CompoundTag zone)) return "no zone payload";
        if (!TelegraphZone.hasRequiredKeys(zone)) return "incomplete zone tag";
        if (!(data.get("burst") instanceof CompoundTag)) return "no burst payload";
        return null;
    }

    /** 到期执行。区域用<b>出招时解算好的世界坐标</b>，不随 Boss 位移重算。 */
    public static void execute(ColossusBossEntity boss, CompoundTag data) {
        if (!(boss.level() instanceof ServerLevel level)) return;
        // isAlive() 在死亡演出期仍为 true（血量被钉在 1.0f），所以要额外判 deathPending：
        // 演出中不该再往玩家身上落一发没预警的圈
        if (!boss.isAlive() || boss.isDeathPending()) {
            Colossus.LOGGER.debug("zone work skipped: boss={} alive={} dying={}",
                    boss.getBossId(), boss.isAlive(), boss.isDeathPending());
            return; // Boss 已倒/正在倒下：静默丢弃（丢弃的原因在调用侧已留痕）
        }
        String reason = settleRejectReason(data);
        if (reason != null) {
            // warn 而不是 debug：坏档在 drain 路径上静默消失＝"这一发排了却没落"，
            // 与"没预警的伤害"正好相反但同样不可诊断；只在真正被钳掉的那一次响（本条不重复）
            Colossus.LOGGER.warn("dropped {} work ({}): boss {}", KIND, reason, boss.getBossId());
            return;
        }
        CompoundTag zoneTag = data.getCompound("zone");
        CompoundTag burstTag = data.getCompound("burst");
        TelegraphZone zone = TelegraphZone.fromTag(zoneTag);
        var burst = ZoneBurst.fromTag(burstTag);
        int n = zone.targets((ServerLevel) boss.level(), boss).size();
        Colossus.LOGGER.debug("zone work fired: boss={} center=({}, {}, {}) r={} dmg={} freeze={} targets={}",
                boss.getBossId(), String.format("%.1f", zone.cx()), String.format("%.1f", zone.cz()),
                String.format("%.1f", zone.cy()), zone.radiusXZ(), burst.damage(), burst.freezeTicks(), n);
        burst.toEffect().apply(boss, level, zone);
    }
}
