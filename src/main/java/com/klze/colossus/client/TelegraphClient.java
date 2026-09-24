package com.klze.colossus.client;

import com.klze.colossus.env.TelegraphZone;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 危险区客户端视觉（v0.2 = 轮廓粒子圈；本轮按 v6 取证升级为**注册式样式**：
 * 粒子档保留为 fallback，新增 "ring" 线框圈档；半透圆盘 quad 档推 v0.3——模板已在 v6 §二）。
 *
 * <p>原则：视觉 = f(同步数据)——只消费 ZoneSync 包，不做任何判定；
 * 服务端在 warn 到期自行结算（{@link TelegraphZone}）。
 * 驱动点：粒子在 ColossusClientHooks 的 ClientTick，几何档在 RenderLevelStageEvent。
 */
public final class TelegraphClient {

    /** 可视危险区运行态。 */
    static final class Live {
        final Vec3 center;
        final double radiusXZ;
        final double radiusY;
        final int colorRGB;
        final String visual;
        final int totalTicks;
        int ticksLeft;
        ParticleOptions cachedParticle; // 粒子档：入列时算好（热路径不 new）

        Live(double cx, double cy, double cz, double radiusXZ, double radiusY,
             int colorRGB, String visual, int durationTicks) {
            this.center = new Vec3(cx, cy, cz);
            this.radiusXZ = radiusXZ;
            this.radiusY = radiusY;
            this.colorRGB = colorRGB;
            this.visual = visual;
            this.totalTicks = Math.max(1, durationTicks);
            this.ticksLeft = durationTicks;
        }

        float progress() {
            return net.minecraft.util.Mth.clamp(1.0f - (float) ticksLeft / (float) totalTicks, 0f, 1f);
        }
    }

    private static final List<Live> ZONES = new ArrayList<>();
    private static final Map<String, ZoneRenderer> STYLES = new HashMap<>();

    static {
        registerStyle(new RingZoneRenderer()); // "ring"：RenderType.LINES 圆环（v6：1.20.1 实证可行档）
    }

    public static void registerStyle(ZoneRenderer renderer) {
        STYLES.put(renderer.id(), renderer);
    }

    /** 该 visual 是否由几何渲染器接管（接管则不再撒粒子）。 */
    public static boolean hasStyle(String visual) {
        return STYLES.containsKey(visual);
    }

    /** 渲染分发（ColossusClientHooks 在 RenderLevelStageEvent 里调；逐区 frustum 剔除）。 */
    public static void renderZones(com.mojang.blaze3d.vertex.PoseStack pose,
                                   net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers,
                                   Vec3 camPos, net.minecraft.client.renderer.culling.Frustum frustum) {
        try {
            for (int i = 0; i < ZONES.size(); i++) { // 索引遍历：样式若回调 add() 也不 CME（第四轮 P3）
                Live z = ZONES.get(i);
                ZoneRenderer r = STYLES.get(z.visual);
                if (r == null) continue;
                double x = z.center.x - camPos.x, y = z.center.y - camPos.y, zz = z.center.z - camPos.z;
                double pad = z.radiusXZ + 0.5;
                double vpad = Math.max(1.0, z.radiusY + 0.5);
                if (!frustum.isVisible(new net.minecraft.world.phys.AABB(
                        x - pad, y - vpad, zz - pad, x + pad, y + vpad, zz + pad))) continue;
                pose.pushPose();
                pose.translate(-camPos.x, -camPos.y, -camPos.z);
                r.render(new ZoneRenderer.ZoneView(z.center, z.radiusXZ, z.colorRGB, z.progress()), pose, buffers);
                pose.popPose();
            }
        } finally {
            // 批收尾在框架侧统一做且带样式自声明类型——第三方渲染器异常不吞批次（第四轮 P3#3）
            for (ZoneRenderer r : STYLES.values()) {
                buffers.endBatch(r.batchType());
            }
        }
    }

    /** 网络线程入列（enqueueWork 已在主线程）。 */
    public static void add(double cx, double cy, double cz, double radiusXZ, double radiusY,
                           int colorRGB, String visual, int durationTicks) {
        ZONES.add(new Live(cx, cy, cz, radiusXZ, radiusY, colorRGB, visual, durationTicks));
    }

    public static void clear() {
        ZONES.clear();
    }

    /** 每客户端 tick：粒子档撒轮廓，倒计时归零移除。 */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { clear(); return; }
        RandomSource r = mc.level.getRandom();
        for (int i = ZONES.size() - 1; i >= 0; i--) {
            Live z = ZONES.get(i);
            z.ticksLeft--;
            if (!hasStyle(z.visual)) { // 几何档接管时不双份表现
                ParticleOptions p = z.cachedParticle != null ? z.cachedParticle : (z.cachedParticle = particleFor(z));
                double circumference = 2 * Math.PI * z.radiusXZ;
                int points = Math.max(8, (int) (circumference * 1.5));
                for (int k = 0; k < points; k++) {
                    double angle = (k + r.nextDouble() * 0.5) / points * Math.PI * 2;
                    mc.level.addParticle(p,
                            z.center.x + Math.cos(angle) * z.radiusXZ,
                            z.center.y + 0.05 + r.nextDouble() * 0.15,
                            z.center.z + Math.sin(angle) * z.radiusXZ,
                            0, 0.01, 0);
                }
            }
            if (z.ticksLeft <= 0) ZONES.remove(i);
        }
    }

    private static ParticleOptions particleFor(Live z) {
        if ("dust".equals(z.visual)) {
            return new DustParticleOptions(new org.joml.Vector3f(
                    ((z.colorRGB >> 16) & 0xFF) / 255f,
                    ((z.colorRGB >> 8) & 0xFF) / 255f,
                    (z.colorRGB & 0xFF) / 255f), 1.2f);
        }
        return ParticleTypes.END_ROD;
    }

    private TelegraphClient() {}
}
