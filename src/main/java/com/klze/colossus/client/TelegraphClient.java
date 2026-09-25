package com.klze.colossus.client;

import com.klze.colossus.entity.ColossusBossEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 危险区客户端视觉（v0.2 = 轮廓粒子圈；第六批升级为<b>注册式样式</b>：粒子档保留为 fallback，
 * 新增 "ring" 线框圈档）。
 *
 * <p>第二十四批改了<b>数据来源</b>：不再消费一次性 {@code ZoneSync} 包，而是<b>投影</b> Boss 的
 * {@code DATA_TELEGRAPHS}（服务端见 {@link ColossusBossEntity#showTelegraph}）。
 * 原则没变——视觉 = f(同步数据)，客户端不做任何判定；变的是"这份数据什么时候能送到"：
 * <ul>
 *   <li><b>中途进场</b>：vanilla 的 {@code ServerEntity#sendPairingData}（{@code ServerEntity.java:237-239}）
 *       给新追踪者补一份全量快照；</li>
 *   <li><b>换维度/重进世界</b>：实体重新进客户端时带着同一份同步数据，轮廓自己会回来
 *       （旧写法在这里是 {@code mc.level == null → clear()}，之后永远画不出来）；</li>
 *   <li><b>存档重载</b>：待办队列与轮廓投影各自落盘，绝对 {@code gameTime} 让"还剩几 tick"
 *       由世界本身回答，而不是"从 0 再亮一遍"。</li>
 * </ul>
 *
 * <p>驱动点：粒子与投影刷新在 ColossusClientHooks 的 ClientTick，几何档在 RenderLevelStageEvent。
 */
public final class TelegraphClient {

    /** 可视危险区运行态：几何 + <b>绝对</b>起止时刻（与服务端同一时基：{@code level.getGameTime()}）。 */
    static final class Live {
        final int ownerBossId;
        final int viewId;
        final Vec3 center;
        final double radiusXZ;
        final double radiusY;
        final int colorRGB;
        final String visual;
        final long startGameTime;
        final long endGameTime;
        ParticleOptions cachedParticle; // 粒子档：首帧算好后复用（撒点的热路径不 new）

        Live(int ownerBossId, ColossusBossEntity.TelegraphView view) {
            this.ownerBossId = ownerBossId;
            this.viewId = view.id();
            this.center = new Vec3(view.zone().cx(), view.zone().cy(), view.zone().cz());
            this.radiusXZ = view.zone().radiusXZ();
            this.radiusY = view.zone().radiusY();
            this.colorRGB = view.zone().colorRGB();
            this.visual = view.zone().visual();
            this.startGameTime = view.startGameTime();
            this.endGameTime = view.endGameTime();
        }

        /** 进度＝已烧掉的寿命占比。晚入场的人第一眼看到的就是"这块地已经亮了一半"。 */
        float progress(long nowGameTime) {
            long span = this.endGameTime - this.startGameTime;
            if (span <= 0L) return 1.0f;
            return Mth.clamp((float) ((double) (nowGameTime - this.startGameTime) / (double) span), 0f, 1f);
        }
    }

    /** 一个正在被盯的 Boss：弱引用（断线时 vanilla 不逐个发离场事件，整个 ClientLevel 直接丢）＋上次快照实例。 */
    private static final class Watched {
        final WeakReference<ColossusBossEntity> boss;
        CompoundTag lastSnapshot;

        Watched(ColossusBossEntity boss) {
            this.boss = new WeakReference<>(boss);
        }
    }

    private static final Map<Integer, Watched> WATCHED = new LinkedHashMap<>();
    private static final Map<Long, Live> ZONES = new LinkedHashMap<>();
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

    // ---------------- 投影来源：Boss 的同步数据 ----------------

    /** 实体进客户端世界时登记（{@code EntityJoinLevelEvent}，只在 {@code Dist.CLIENT} 侧调用）。 */
    public static void watch(ColossusBossEntity boss) {
        WATCHED.put(boss.getId(), new Watched(boss));
    }

    /** 实体离开客户端世界：顺手清掉它名下的轮廓，别留"没人认领的圈"。 */
    public static void unwatch(ColossusBossEntity boss) {
        WATCHED.remove(boss.getId());
        dropOwner(boss.getId());
    }

    /** 每客户端 tick：先接同步数据的<b>实例变化</b>（vanilla 每次补包都换新实例），再推进寿命与粒子。 */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { clear(); return; }
        long now = mc.level.getGameTime();

        Iterator<Map.Entry<Integer, Watched>> watching = WATCHED.entrySet().iterator();
        while (watching.hasNext()) {
            Map.Entry<Integer, Watched> e = watching.next();
            ColossusBossEntity boss = e.getValue().boss.get();
            if (boss == null || boss.isRemoved()) {
                dropOwner(e.getKey());
                watching.remove();
                continue;
            }
            CompoundTag snapshot = boss.telegraphSnapshot();
            if (snapshot == e.getValue().lastSnapshot) continue; // 没换实例＝服务端没重投过：零成本
            e.getValue().lastSnapshot = snapshot;
            dropOwner(boss.getId());
            for (ColossusBossEntity.TelegraphView v : boss.telegraphViews()) {
                if (v.endGameTime() <= now) continue; // 补包路上晚了几 tick：过期的一律不补画
                ZONES.put(key(boss.getId(), v.id()), new Live(boss.getId(), v));
            }
        }

        Iterator<Map.Entry<Long, Live>> zones = ZONES.entrySet().iterator();
        while (zones.hasNext()) {
            Live z = zones.next().getValue();
            if (z.endGameTime <= now) { zones.remove(); continue; }
            if (!hasStyle(z.visual)) spawnOutlineParticles(mc, z); // 几何档接管时不双份表现
        }
    }

    private static void dropOwner(int bossId) {
        ZONES.values().removeIf(z -> z.ownerBossId == bossId);
    }

    /** 镜像键：视图序号在不同 Boss 之间会重复，必须带上宿主实体 id。 */
    private static long key(int bossId, int viewId) {
        return ((long) bossId << 32) | (viewId & 0xFFFFFFFFL);
    }

    /** 清场只在世界切换与登出时做——投影现在随时能从同步数据重建，所以清空不再是"丢了就没了"。 */
    public static void clear() {
        ZONES.clear();
        WATCHED.clear();
    }

    /** 在途轮廓条数（诊断用）。 */
    public static int activeZoneCount() {
        return ZONES.size();
    }

    // ---------------- 渲染 ----------------

    /** 渲染分发（ColossusClientHooks 在 RenderLevelStageEvent 里调；逐区 frustum 剔除）。 */
    public static void renderZones(com.mojang.blaze3d.vertex.PoseStack pose,
                                   net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers,
                                   Vec3 camPos, net.minecraft.client.renderer.culling.Frustum frustum,
                                   long nowGameTime) {
        try {
            for (Live z : ZONES.values()) {
                ZoneRenderer r = STYLES.get(z.visual);
                if (r == null) continue;
                double x = z.center.x - camPos.x, y = z.center.y - camPos.y, zz = z.center.z - camPos.z;
                double pad = z.radiusXZ + 0.5;
                double vpad = Math.max(1.0, z.radiusY + 0.5);
                if (!frustum.isVisible(new net.minecraft.world.phys.AABB(
                        x - pad, y - vpad, zz - pad, x + pad, y + vpad, zz + pad))) continue;
                pose.pushPose();
                pose.translate(-camPos.x, -camPos.y, -camPos.z);
                r.render(new ZoneRenderer.ZoneView(z.center, z.radiusXZ, z.colorRGB, z.progress(nowGameTime)),
                        pose, buffers);
                pose.popPose();
            }
        } finally {
            // 批收尾在框架侧统一做且带样式自声明类型——第三方渲染器异常不吞批次（第四轮 P3#3）
            for (ZoneRenderer r : STYLES.values()) {
                buffers.endBatch(r.batchType());
            }
        }
    }

    /** 粒子档：沿轮廓撒一圈。 */
    private static void spawnOutlineParticles(Minecraft mc, Live z) {
        RandomSource r = mc.level.getRandom();
        ParticleOptions p = z.cachedParticle != null ? z.cachedParticle : (z.cachedParticle = particleFor(z));
        double circumference = 2 * Math.PI * z.radiusXZ;
        int points = Math.max(8, (int) (circumference * 1.5));
        for (int k = 0; k < points; k++) {
            double angle = (k + r.nextDouble() * 0.5) / points * Math.PI * 2;
            // addAlwaysVisibleParticle 而不是 addParticle（v11 A4）：后者在 1.20.1
            // LevelRenderer#addParticleInternal（LevelRenderer.java:2511）有一道
            // "离 camera 的平方距离 > 1024（＝32 格）直接 return null"的闸，玩家把粒子调到"最少"
            // 时更是整批丢（:2514）。大半径危险区偏偏正是在 32 格外才需要被看见。
            mc.level.addAlwaysVisibleParticle(p,
                    z.center.x + Math.cos(angle) * z.radiusXZ,
                    z.center.y + 0.05 + r.nextDouble() * 0.15,
                    z.center.z + Math.sin(angle) * z.radiusXZ,
                    0, 0.01, 0);
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
