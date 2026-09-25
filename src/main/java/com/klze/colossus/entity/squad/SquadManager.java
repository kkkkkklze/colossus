package com.klze.colossus.entity.squad;

import com.klze.colossus.anim.TableSampler;
import com.klze.colossus.entity.ColossusBossEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * squad 管理器（队长侧装配）：声明成员 → 开战补员 → 血条均值 → 击破排重生。
 *
 * <p><b>身份账 = key→UUID 表</b>（v7 取证修正）。旧写法拿 {@code getBoundingBox().inflate(96,48,96)}
 * 扫场来认成员，后果是成员一旦走出框（或所在区块未加载）就被判"缺失"，
 * {@link #spawnMissing} 会补出<b>同 key 的第二个</b>。现在 Kraken 的 {@code ownedTentacles} 形：
 * UUID 入 NBT、{@code level.getEntity(uuid)} 懒解析，且"查无此人"要熬过
 * {@link #LOST_GRACE} 才允许补位——<b>未加载不等于死亡</b>。
 *
 * <p>血条分母用<b>定义总数</b>而非当前存活数（Kraken 教训：防 bar 回弹，
 * 重生中的成员计 0 而不是消失）。成员伤害转发 leader 参战账本由
 * {@link #forwardContribution} 完成（成员实体在自身 hurt/死亡处调它）。
 */
public final class SquadManager {

    /** 查无此人到多久才认定"真没了"（区块加载通常远快于此；200t=10s）。 */
    public static final long LOST_GRACE = 200L;

    /** 成员定义：key + 实体类型 + 局部锚点（随队长朝向解算）。 */
    public record MemberDef(String key, net.minecraft.world.entity.EntityType<? extends Mob> type,
                            Vec3 localOffset, long respawnDelayTicks) {}

    private final ColossusBossEntity leader;
    private final List<MemberDef> defs = new ArrayList<>();
    private final RespawnSchedule respawn = new RespawnSchedule();
    /** 身份账：memberKey → 成员实体 UUID。 */
    private final Map<String, UUID> memberIds = new LinkedHashMap<>();
    /** memberKey → 首次查无此人的 gameTime（宽限期计时）。 */
    private final Map<String, Long> lostSince = new LinkedHashMap<>();

    public SquadManager(ColossusBossEntity leader) {
        this.leader = leader;
    }

    public SquadManager add(MemberDef def) {
        defs.add(def);
        return this;
    }

    public List<MemberDef> defs() { return List.copyOf(defs); }

    /** 开战/补员：缺失的成员在锚点位落地并挂 leader 引用（幂等——已存活/未加载/排期中都不重刷）。 */
    public void spawnMissing(ServerLevel level) {
        long now = level.getGameTime();
        for (MemberDef def : defs) {
            String key = def.key();
            if (findMember(level, key) != null) continue;
            if (respawn.isScheduled(key)) continue; // 排期中，未到点
            if (memberIds.containsKey(key)) {
                long first = lostSince.computeIfAbsent(key, k -> now);
                if (now - first < LOST_GRACE) continue; // 可能只是没加载，先等
                // 宽限过完仍查无 → 用新 UUID 顶替账位。旧那具若之后重新加载，会被
                // {@link #isCurrent} 判成孤儿并自行收摊（审查轮 5 P1-3：不销人只销账＝肩上叠两具）。
            }
            Vec3 pos = TableSampler.toWorld(leader, def.localOffset());
            Mob mob = def.type().create(level);
            if (mob == null) continue; // 不销账也不排期：账还在，下一 tick 仍走宽限路径（P3-1）
            mob.moveTo(pos.x, pos.y, pos.z, leader.getYRot(), 0f);
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(pos)),
                    MobSpawnType.TRIGGERED, null, null);
            if (mob instanceof ColossusSquadMember m) {
                m.setSquadLeader(leader.getUUID());
                if (mob instanceof ColossusSquadMemberEntity anchored) {
                    anchored.setMemberKey(def.key()); // 锚点不再注入：由 defFor(key) 现查（P1-1）
                }
            }
            level.addFreshEntity(mob);
            lostSince.remove(key);
            memberIds.put(key, mob.getUUID());
        }
    }

    /** 成员定义查询（成员解算锚点用——锚点的唯一来源始终是这张表）。 */
    @Nullable
    public MemberDef defFor(String key) {
        for (MemberDef d : defs) {
            if (d.key().equals(key)) return d;
        }
        return null;
    }

    /**
     * 账当前不当前：成员每 tick 拿自己问一次，false 就说明自己已被顶替（宽限期到点后补的新那具）。
     * 被顶替者继续留在场上＝同一 key 两具实体、血条只算账内那具、收摊广播漏掉孤儿（P1-3）。
     */
    public boolean isCurrent(String key, UUID memberId) {
        UUID cur = memberIds.get(key);
        return cur != null && cur.equals(memberId);
    }

    /** 在途重生排期数（测试与诊断用）。 */
    public int respawnPending() { return respawn.size(); }

    /**
     * 成员自行生成（不走 {@link #spawnMissing}）时入账。
     * 不入账的成员会被补员逻辑再刷一个——这是旧扫描形和 UUID 形共同的约束，
     * 区别是现在契约显式了。
     */
    public void adopt(String key, UUID memberId) {
        memberIds.put(key, memberId);
        lostSince.remove(key);
    }

    /** 成员查询（按 key；UUID 懒解析 Kraken 同款——成员先于 leader 加载也不丢）。 */
    @Nullable
    public ColossusSquadMember findMember(ServerLevel level, String key) {
        UUID id = memberIds.get(key);
        if (id == null) return null;
        Entity e = level.getEntity(id);
        if (e == null || !e.isAlive()) return null; // 未加载 / 已死，都交给宽限期与重生排期
        if (e instanceof ColossusSquadMember m && key.equals(m.memberKey())) {
            lostSince.remove(key);
            return m;
        }
        return null;
    }

    /** 在册成员快照（未加载/已故成员自然缺席）。 */
    public List<ColossusSquadMember> iterateMembers(ServerLevel level) {
        List<ColossusSquadMember> out = new ArrayList<>();
        for (String key : memberIds.keySet()) {
            ColossusSquadMember m = findMember(level, key);
            if (m != null) out.add(m);
        }
        return out;
    }

    /** 已入身份账的成员 key（含未加载者）——诊断与"是否真的缺人"判定用。 */
    public Set<String> trackedKeys() { return Set.copyOf(memberIds.keySet()); }

    /** 队长血条：Σ成员血比 / 定义总数（分母恒定——kraken 案，重生中计 0）。 */
    public float barProgress() {
        if (defs.isEmpty()) return leader.getMaxHealth() > 0 ? leader.getHealth() / leader.getMaxHealth() : 0f;
        ServerLevel level = leader.level() instanceof ServerLevel s ? s : null;
        if (level == null) return 0f;
        float sum = 0f;
        for (MemberDef def : defs) {
            ColossusSquadMember m = findMember(level, def.key());
            if (m != null && m.countsForBar()) sum += m.barRatio();
        }
        float own = leader.getMaxHealth() > 0 ? leader.getHealth() / leader.getMaxHealth() : 0f;
        return (sum + own) / (defs.size() + 1);
    }

    /**
     * 成员被击破：销账 + 去重排重生（绝对 gameTime）。
     *
     * <p><b>必须带 memberId</b>（审查轮 5 P1-3）：宽限期到点后我们可能已经用新 UUID 顶替过账位，
     * 这时<b>旧</b>那具才慢慢死掉——按 key 盲销就会把现役成员从账上抹掉并多排一次重生。
     * 队长已倒下时不再排期（{@code leaderDown} 门）——否则"Boss 死了、成员还在原地复活打人"。
     */
    public void onMemberDefeated(String key, java.util.UUID memberId) {
        UUID cur = memberIds.get(key);
        if (cur != null && !cur.equals(memberId)) {
            return; // 死的是被顶替的旧那具：账籍属于现役成员，不动
        }
        memberIds.remove(key);
        lostSince.remove(key);
        if (leaderDown || key.isEmpty()) return;
        for (MemberDef def : defs) {
            if (def.key().equals(key) && def.respawnDelayTicks() > 0) {
                respawn.schedule(key, leader.level().getGameTime() + def.respawnDelayTicks());
            }
        }
    }

    private boolean leaderDown;

    /**
     * 队长倒下：撤掉在途重生排期 + 广播收摊。<b>死亡演出开场就调</b>
     * （{@code ColossusBossEntity.onDeathSequenceStart}），不等 {@code resolveDeath}。
     *
     * <p>提前的理由是<b>收摊时机与胜负对齐</b>：演出默认 100t（{@code deathAnimationTicks}），
     * 这期间队长血量钉在 1.0、{@code isAlive()} 仍为 true，成员照旧被锚点钉在尸体肩上、
     * 照旧挨打——等到结算才广播＝"Boss 明明已经死了，触手还在陪葬"。
     * <b>不要</b>把它理解成"不提前就会到点补员"：补员路径在 {@code deathPending} 下被
     * {@code aiStep} 早退与 {@code tickSessionAndSquad} 的双重门挡死（审查轮 8 更正，
     * 我轮 7 就是这么写错的，细节见 {@link RespawnSchedule#cancelAll()}）。
     *
     * <p>走的是<b>身份账</b>而不是扫场，所以"未加载的成员"这一格照 v7 的口径留给时间：
     * 它没收到广播，但队长已经从世界上消失，下次它 tick 时 {@code resolveLeader} 解析不到人，
     * 就是个不再排期的普通怪（{@link ColossusSquadMemberEntity#followsLeaderAnchor()} 自动失效）。
     *
     * <p>可重入——但<b>只限"续演"那条路</b>（轮 9 说清，免得被当成所有死亡入口都会撤单）：
     * {@code colossus_dying=true} 的档读回来会重推 DeathState、再走一次演出开场，届时成员已死/已销账，
     * 广播与撤单都是空转。而<b>已结算尸体</b>（存盘血量 0 那档）不再进演出，{@code leaderDown}
     * 仍是 false、在途排期也不撤——这条路里队长 ≤20t 就消失，残留条目是死数据
     * （{@code tickSessionAndSquad} 的 {@code !deathPending} 门 + 实体即将移除，永远点不着）。
     */
    public void notifyLeaderDeath(ServerLevel level) {
        leaderDown = true;
        respawn.cancelAll(); // 撤的是"死队长的复活预约"，不是补员时序（见 RespawnSchedule#cancelAll）
        for (ColossusSquadMember m : iterateMembers(level)) {
            m.onLeaderDefeated();
        }
    }

    /** 每队长 tick：到点补员。 */
    public void tick(ServerLevel level) {
        if (respawn.size() == 0) return;
        for (String key : respawn.consumeDue(level.getGameTime())) {
            spawnMissing(level);
            leader.sendBossVisualEvent("colossus:squad_respawn_" + key);
        }
    }

    /** 成员造成的战斗被看见——把打成员的人转记入队长的参战账（共享 credit 闭环）。 */
    public void forwardContribution(ServerLevel level, LivingEntity attackerSource) {
        if (attackerSource instanceof ServerPlayer p) {
            leader.engagement().onContribution(p);
        }
    }

    /**
     * 单 CompoundTag 形（key→{id:UUID[,lost:long]}）。两列 ListTag 的类型过滤在 1.20.1 不可靠
     * （自检里踩过的坑），UUID 直接用原版 {@code putUUID/getUUID}（CompoundTag:191/195）不自造编码。
     */
    public void save(CompoundTag tag) {
        respawn.save(tag);
        memberIds.keySet().removeIf(k -> defFor(k) == null); // 定义已删的 key 不留残账（P3-9）
        CompoundTag members = new CompoundTag();
        for (Map.Entry<String, UUID> e : memberIds.entrySet()) {
            CompoundTag rec = new CompoundTag();
            rec.putUUID("id", e.getValue());
            Long lost = lostSince.get(e.getKey());
            if (lost != null) rec.putLong("lost", lost);
            members.put(e.getKey(), rec);
        }
        tag.put("members", members);
    }

    public void load(CompoundTag tag) {
        respawn.load(tag);
        memberIds.clear();
        lostSince.clear();
        if (tag.get("members") instanceof CompoundTag members) {
            for (String key : members.getAllKeys()) {
                CompoundTag rec = members.getCompound(key);
                if (!rec.hasUUID("id")) continue;
                memberIds.put(key, rec.getUUID("id"));
                if (rec.contains("lost")) lostSince.put(key, rec.getLong("lost"));
            }
        }
    }
}
