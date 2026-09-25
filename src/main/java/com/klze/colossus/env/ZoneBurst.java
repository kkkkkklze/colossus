package com.klze.colossus.env;

import com.klze.colossus.entity.ColossusBossEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/**
 * 延迟结算的<b>危险区爆发</b>——把"warn 到期后对圈内做什么"从 lambda 变成数据（第十六批）。
 *
 * <p>为什么必须成数据：旧写法 {@code scheduleWork(delay, () -> effect.apply(...))} 存的是 Runnable，
 * <b>不可序列化</b>，所以整条延迟队列既不落盘、到期时间又用 {@code tickCount}（每次加载从 0 重数）。
 * 后果是"打了一半的 telegraph 遇到区块卸载/重启就凭空消失"，以及重载后到期时刻全错。
 * 现在队列里躺的是 {@code (绝对 gameTime, 工作种类, CompoundTag)}，存档可带、重载可续。
 *
 * <p>词汇表刻意封闭（damage/freeze 两格）：<b>自定义 ZoneEffect 依然可以用在即时帧上</b>，
 * 但要走 telegraph 这种"延迟到未来某一 tick"的路径，就必须能写进 NBT——这是框架的取舍，
 * 也是 JSON 招式表用的同一套 effect.kind（单一事实源，见 {@code MoveCodec}）。
 */
public record ZoneBurst(float damage, float knockback, int freezeTicks) {

    public static final ZoneBurst NONE = new ZoneBurst(0.0f, 0.0f, 0);

    /** 合并（effect 数组＝多条合并成一次爆发）。 */
    public ZoneBurst merge(ZoneBurst other) {
        return new ZoneBurst(Math.max(this.damage, other.damage),
                Math.max(this.knockback, other.knockback),
                Math.max(this.freezeTicks, other.freezeTicks));
    }

    public boolean empty() {
        return this.damage <= 0.0f && this.freezeTicks <= 0;
    }

    /** 变回运行时形态（结算仍走既有 ZoneEffect，执行语义不改）。 */
    public ZoneEffect toEffect() {
        ZoneEffect eff = damage > 0.0f ? ZoneEffect.damageOnly(damage, knockback) : ZoneEffect.nothing();
        if (freezeTicks > 0) eff = eff.and(ZoneEffect.freeze(freezeTicks));
        return eff;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("damage", this.damage);
        tag.putFloat("knockback", this.knockback);
        tag.putInt("freeze", this.freezeTicks);
        return tag;
    }

    /**
     * 与 {@code TelegraphZone.hasRequiredKeys} 对称（轮 19 P3-6）：闸门只查 zone 那一半时，
     * "zone 齐件 + burst 被截断"的存档会通过闸门、落一个 0 伤害 0 冰冻的空圈且零日志——
     * 与要消灭的"排了却没落"是同一个不可诊断形态。不能用 {@code empty()} 代替本判据：
     * {@code telegraphVisual} 那种"只画不落"的圈本来就合法地全 0。
     */
    public static boolean hasRequiredKeys(CompoundTag tag) {
        if (tag == null) return false;
        return tag.contains("damage", 99) && tag.contains("knockback", 99)
                && tag.contains("freeze", 99); // mask=99：与 getter 的数值容忍度同宽
    }

    public static ZoneBurst fromTag(CompoundTag tag) {
        return new ZoneBurst(tag.getFloat("damage"), tag.getFloat("knockback"), tag.getInt("freeze"));
    }
}
