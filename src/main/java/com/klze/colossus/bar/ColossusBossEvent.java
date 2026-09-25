package com.klze.colossus.bar;

import com.klze.colossus.client.BossBarStyles;
import com.klze.colossus.network.ColossusPackets;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 框架血条：原生 ServerBossEvent + renderType 旁路通道。
 * （ACBossEvent 与 Cataclysm CMBossInfoServer 两个 Forge 工程独立演化出的同一协议。）
 *
 * <p>血条同步本体（进度/颜色/增删玩家）交给原版广播；框架只加一条附加通道：
 * 玩家加入时补发 {@code BarStyleS2C{barId, renderType}}，离开发 -1。
 * 1.20.1 的 BossEvent.players 是 private（{@code ServerBossEvent.java:16}，v11 B4-2 复核过），
 * 故自持一份"可见玩家"集合（同时也是音乐/演出广播的目标集）。
 *
 * <p><b>客户端镜像只有一个出入口</b>（第二十四批收敛）：{@link #pushMirrorTo} 补齐、
 * {@link #clearMirrorFor} 清零，其余路径一律不自己拼包。原写法只在 {@code addPlayer}
 * 与 {@code removePlayer} 两侧动手，而 {@code setVisible(false)} 这条<b>第三条</b>路径没人管——
 * vanilla 在那儿只发自己的 REMOVE 包、<b>不会</b>调 {@code removePlayer}
 * （{@code ServerBossEvent.java:121-130}，v11 B4-3 点名 Cataclysm 的 {@code -1} 清理正是这么漏的），
 * 于是客户端的样式/护盾旁路表留在一条已经不存在的 bar 上。收成一个点之后顺带修掉两处：
 * ①隐藏后再转可见，样式会随快照一起补回（旧代码只补护盾，自定义样式永久丢失）；
 * ②补发的护盾用<b>权威值</b> {@code shieldValue}。旧写法是 {@code invalidate()} 之后立刻
 * 读 {@code lastSent()}，拿到的是"从未发过"那个 NaN，而客户端 {@code ShieldBars.set} 把
 * 非有限值当作"这条 bar 没有盾"直接删项——于是那句"中途入场的人第二条条子必须直接是当前真值"
 * 的注释与实际效果相反：晚到的人看到的永远是空盾，直到下一次数值变化。
 */
public class ColossusBossEvent extends ServerBossEvent {

    private final Set<ServerPlayer> seen = new LinkedHashSet<>();
    private int renderType = BossBarStyles.VANILLA;
    /** 附加资源条（护盾）的脏检查件——见 {@link DirtyMeter} 的三条不变量。 */
    private final DirtyMeter shieldMeter = new DirtyMeter();
    private float shieldValue = 0.0f;

    public ColossusBossEvent(Component title, BossBarColor color, BossBarOverlay overlay) {
        super(title, color, overlay);
    }

    @Override
    public void addPlayer(ServerPlayer player) {
        this.seen.add(player);
        super.addPlayer(player);
        // 隐藏期 vanilla 连自己的 Add 包都不发（ServerBossEvent.java:95），镜像就保持"已清"，
        // 转可见时由 setVisible 那条路径统一补——否则这里会留一份没人认领的样式
        if (this.isVisible()) this.pushMirrorTo(player);
    }

    @Override
    public void removePlayer(ServerPlayer player) {
        this.seen.remove(player);
        this.clearMirrorFor(player);
        super.removePlayer(player);
    }

    /** 当前在看这条 bar 的服务端玩家（只读快照）。 */
    public Set<ServerPlayer> seenPlayers() { return Collections.unmodifiableSet(this.seen); }

    /** 变更渲染样式（阶段换血条外观用；{@code BossBarStyles.VANILLA} 回原版）。 */
    public void setRenderType(int renderType) {
        if (this.renderType == renderType) return;
        this.renderType = renderType;
        if (!this.isVisible()) return; // 隐藏期只攒值；转可见时随快照补发（与护盾同一口径）
        for (ServerPlayer p : this.seen) {
            ColossusPackets.sendToPlayer(ColossusPackets.barStyle(getId(), renderType), p);
        }
    }

    public int getRenderType() { return this.renderType; }

    @Override
    public void setVisible(boolean visible) {
        boolean prev = this.isVisible();
        super.setVisible(visible);
        if (visible == prev) return;
        if (visible) {
            // 转可见的那一 tick 给每个在看的人补齐镜像（样式+护盾）：隐藏期只攒值不发（不变量：0 包）
            for (ServerPlayer p : this.seen) this.pushMirrorTo(p);
        } else {
            for (ServerPlayer p : this.seen) this.clearMirrorFor(p);
        }
    }

    /**
     * 推护盾进度 [0,1]（内容侧由 {@code ColossusBossEntity#setShield} 调用）。
     * 值未变 0 包；不可见只攒不发；进视角/转可见补发快照——三条都可自检。
     */
    public void syncShield(float value) {
        this.shieldValue = value;
        if (!this.isVisible()) return;
        if (this.shieldMeter.changedAndRemember(value)) this.broadcastShield();
    }

    /** 权威护盾值（诊断与回归桩用）。 */
    public float shieldValue() { return this.shieldValue; }

    /** 一个玩家<b>此刻</b>该持有的镜像：样式 + 护盾，一次补齐。 */
    private void pushMirrorTo(ServerPlayer player) {
        ColossusPackets.sendToPlayer(ColossusPackets.barStyle(getId(), this.renderType), player);
        this.shieldMeter.invalidate(); // 让紧跟其后的一次同值变化仍被当作"变了"，别被记忆挡掉
        if (Float.isFinite(this.shieldValue)) {
            this.shieldMeter.remember(this.shieldValue);
            ColossusPackets.sendToPlayer(ColossusPackets.barShield(getId(), this.shieldValue), player);
        }
    }

    /** 镜像清零：样式回原版、护盾撤除（离开视野与 bar 被隐藏共用这一条）。 */
    private void clearMirrorFor(ServerPlayer player) {
        ColossusPackets.sendToPlayer(ColossusPackets.barStyle(getId(), BossBarStyles.VANILLA), player);
        ColossusPackets.sendToPlayer(ColossusPackets.barShield(getId(), 0.0f), player);
    }

    /** 编码一次、多播 N 次（DE 是每玩家 new 一个包，取证点名为反面）。 */
    private void broadcastShield() {
        if (!Float.isFinite(this.shieldValue)) return; // NaN/Inf 不进镜像（DirtyMeter 同一条纪律）
        this.shieldMeter.remember(this.shieldValue);
        ColossusPackets.BarShieldS2C pkt = ColossusPackets.barShield(getId(), this.shieldValue);
        for (ServerPlayer p : this.seen) ColossusPackets.sendToPlayer(pkt, p);
    }
}
