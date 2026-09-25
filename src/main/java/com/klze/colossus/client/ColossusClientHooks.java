package com.klze.colossus.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.BossEvent;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.klze.colossus.Colossus;

/** 客户端帧钩子：震屏倒计时/危险区粒子推进、相机叠加、断线清缓存。 */
@Mod.EventBusSubscriber(modid = Colossus.MODID, value = Dist.CLIENT)
public final class ColossusClientHooks {

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        ScreenShakeClient.tick();
        TelegraphClient.tick();
    }

    /** 相机叠震屏（对主渲染相机一次性叠加 pitch/yaw/roll 偏移）。 */
    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || event.getCamera().getEntity() != mc.player) return;
        float pt = (float) event.getPartialTick();
        float[] shake = ScreenShakeClient.sample(mc.player.getEyePosition(pt), pt);
        if (shake[0] != 0f || shake[1] != 0f || shake[2] != 0f) {
            event.setPitch(event.getPitch() + shake[0]);
            event.setYaw(event.getYaw() + shake[1]);
            event.setRoll(event.getRoll() + shake[2]);
        }
    }

    /**
     * 危险区几何档分发（v6：AFTER_TRANSLUCENT_BLOCKS + endBatch 收尾；逐区剔除在分发内做）。
     * 进度用<b>绝对 gameTime</b>算（第二十四批）：这里传进去的时刻与轮廓快照同一时基，
     * 中途进场的人第一眼看到的进度就与服务端一致。
     */
    @SubscribeEvent
    public static void onRenderLevelStage(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        if (event.getStage() != net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        net.minecraft.world.phys.Vec3 camPos = event.getCamera().getPosition();
        // endBatch 收尾在 TelegraphClient.renderZones 的 finally 里（按样式声明的 RenderType）
        TelegraphClient.renderZones(event.getPoseStack(), mc.renderBuffers().bufferSource(),
                camPos, event.getFrustum(), mc.level.getGameTime());
    }

    /**
     * 轮廓的<b>数据源</b>是 Boss 的同步数据，不是包：所以要一份"当前客户端世界里有哪些 Boss"的名单
     * 供 {@link TelegraphClient#tick()} 每 tick 去读。用 vanilla 的进/出场事件维持，
     * 且只在本类（{@code Dist.CLIENT}）里引用客户端渲染层——common 代码不碰 client 包。
     */
    @SubscribeEvent
    public static void onEntityJoin(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()
                && event.getEntity() instanceof com.klze.colossus.entity.ColossusBossEntity boss) {
            TelegraphClient.watch(boss);
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(net.minecraftforge.event.entity.EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()
                && event.getEntity() instanceof com.klze.colossus.entity.ColossusBossEntity boss) {
            TelegraphClient.unwatch(boss);
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        BossBarStyles.clear();
        ShieldBars.clear();
        com.klze.colossus.progress.ClientProgress.clear(); // 镜像不跨世界存活（换单人世界读到上一张表是点名反面）
        BossMusicClient.reset();
        TelegraphClient.clear();
        ScreenShakeClient.clear();
    }

    /**
     * 护盾条自绘（第十一批）。1.20.1 的接管通道是实测存在的
     * {@code CustomizeGuiOverlayEvent.BossEventProgress}（{@code @Cancelable}，
     * 构造带 {@code GuiGraphics} 与<b>裸 float</b> partialTick，并给 {@code getIncrement/setIncrement}）——
     * 注意 DE 那份是 NeoForge 1.21.1，它的 {@code getPartialTick().getGameTimeDeltaPartialTick(false)}
     * 在这里不存在，别照抄。
     *
     * <p>只接管"我们手上确有护盾镜像"的那条 bar，其余仍走原版。自绘完把 increment 抬高 6，
     * 后续 bar 自然下移——这比 DE 的"整条取消 + 自己排 Y"更省（原生就给了让位口）。
     * 几何抄原版 {@code BossHealthOverlay}：宽 182、高 5、填充 {@code (int)(progress*183)}。
     */
    @SubscribeEvent
    public static void onBossBarProgress(CustomizeGuiOverlayEvent.BossEventProgress event) {
        BossEvent bar = event.getBossEvent();
        float shield = ShieldBars.get(bar.getId());
        if (shield <= 0.0f) return; // 不是我们的 bar：原版照画
        GuiGraphics g = event.getGuiGraphics();
        int x = event.getX();
        int y = event.getY();
        int hp = Math.max(0, (int) (bar.getProgress() * 183.0f));
        g.fill(x - 1, y - 1, x + 183, y + 10, 0xFF000000);
        g.fill(x, y, x + 182, y + 5, 0xFF2B2B2B);
        g.fill(x, y, x + hp, y + 5, 0xFFC94A4A);            // 配色先固定，接自绘贴图时读 BossBarStyles（v0.3）
        g.fill(x, y + 6, x + 182, y + 9, 0xFF2B2B2B);
        g.fill(x, y + 6, x + Math.max(0, (int) (shield * 182.0f)), y + 9, 0xFF5BC8FF);
        event.setCanceled(true);
        event.setIncrement(event.getIncrement() + 6);
    }

    private ColossusClientHooks() {}
}
