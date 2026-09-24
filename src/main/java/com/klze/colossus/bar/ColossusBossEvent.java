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
 * 1.20.1 的 BossEvent.players 是 private，故自持一份"可见玩家"集合
 * （同时也是音乐/演出广播的目标集）。
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
        seen.add(player);
        ColossusPackets.sendToPlayer(ColossusPackets.barStyle(getId(), renderType), player);
        super.addPlayer(player);
        // 进视角补发全包快照（DE 取证第 2 条）：中途入场的人第二条条子必须直接是当前真值而不是 0
        this.shieldMeter.invalidate();
        this.broadcastShield();
    }

    @Override
    public void removePlayer(ServerPlayer player) {
        seen.remove(player);
        ColossusPackets.sendToPlayer(ColossusPackets.barStyle(getId(), BossBarStyles.VANILLA), player);
        ColossusPackets.sendToPlayer(ColossusPackets.barShield(getId(), 0.0f), player); // 清镜像防残留
        super.removePlayer(player);
    }

    /** 当前在看这条 bar 的服务端玩家（只读快照）。 */
    public Set<ServerPlayer> seenPlayers() { return Collections.unmodifiableSet(seen); }

    /** 变更渲染样式并向可见玩家广播（阶段换血条外观用；-1 回原版）。 */
    public void setRenderType(int renderType) {
        if (this.renderType == renderType) return;
        this.renderType = renderType;
        for (ServerPlayer p : seen) {
            ColossusPackets.sendToPlayer(ColossusPackets.barStyle(getId(), renderType), p);
        }
    }

    public int getRenderType() { return renderType; }

    @Override
    public void setVisible(boolean visible) {
        boolean prev = this.isVisible();
        super.setVisible(visible);
        if (visible && !prev) {
            // 隐藏期只攒值不发；转可见的<b>那一 tick</b> 补一次快照（不变量：隐藏期 0 包）
            this.shieldMeter.invalidate();
            this.broadcastShield();
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

    /** 编码一次、多播 N 次（DE 是每玩家 new 一个包，取证点名为反面）。 */
    private void broadcastShield() {
        ColossusPackets.BarShieldS2C pkt =
                ColossusPackets.barShield(getId(), this.shieldMeter.lastSent());
        for (ServerPlayer p : seen) ColossusPackets.sendToPlayer(pkt, p);
    }
}
