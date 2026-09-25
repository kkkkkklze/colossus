package com.klze.colossus.client;

import com.klze.colossus.entity.ColossusBossEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
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
 *   <li><b>重进世界</b>：实体重新进客户端时带着同一份同步数据，轮廓自己会回来；</li>
 *   <li><b>存档重载</b>：待办队列与轮廓投影各自落盘，绝对 {@code gameTime} 让"还剩几 tick"
 *       由世界本身回答，而不是"从 0 再亮一遍"。</li>
 * </ul>
 *
 * <p><b>换维度不在这三条里，而且是另一回事</b>（轮 14 P2-1、轮 15 P1-2、轮 16 P3-3 三次修正的同一条）：
 * 1.20.1 换维度时 {@code ClientPacketListener:1029-1041} 直接 new 一个新的 {@code ClientLevel}，
 * <b>不逐个发实体离场事件</b>（全树唯一的客户端 {@code EntityLeaveLevelEvent} 发射点是
 * {@code ClientLevel:972} 的 {@code removeEntity}）。收尾办法是：轮廓按"等级实例变了就作废"清，
 * 名单<b>保留</b>、由 {@code boss.level() != mc.level} 逐条剔——因为 {@code EntityJoinLevelEvent}
 * 一个实体一生只发一次，清名单会把这一 tick 刚登记的 Boss 永久抹掉（轮 15 P1-2 就是这个）。
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

    /** 一个正在被盯的 Boss：弱引用只是兜底（断线/换维度时 vanilla 不保证逐个发离场事件），主清理见 {@link #tick()}。 */
    private static final class Watched {
        final WeakReference<ColossusBossEntity> boss;

        Watched(ColossusBossEntity boss) {
            this.boss = new WeakReference<>(boss);
        }
    }

    private static final Map<Integer, Watched> WATCHED = new LinkedHashMap<>();
    private static final Map<Long, Live> ZONES = new LinkedHashMap<>();
    private static final Map<String, ZoneRenderer> STYLES = new HashMap<>();

    /** 渲染侧的复用快照：只在集合真的变过时重建，避免每帧分配，也避开样式回调改表导致的 CME（轮 14 P3-4）。 */
    private static final List<Live> RENDER_SNAPSHOT = new ArrayList<>();
    private static int renderSnapshotVersion = -1;
    private static int version = 0;

    /** 上一次看见的客户端世界；换维度/重进世界都会换新实例 ⇒ 身份比较即可判定"整表作废"。 */
    private static ClientLevel lastLevel;

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
        // 每次登记都强制"下一次读取一定全量读"：memo 住在实体上、比这条名单长寿，
        // 若外部（HUD addon / 第三方渲染层）调过 public 的 clear()，不重置就会在重新 watch 之后
        // 仍判"没变"⇒ 在途轮廓永久回不来（轮 15 P2-3）
        boss.forgetTelegraphMemo();
        WATCHED.put(boss.getId(), new Watched(boss));
    }

    /** 实体离开客户端世界：顺手清掉它名下的轮廓，别留"没人认领的圈"。 */
    public static void unwatch(ColossusBossEntity boss) {
        WATCHED.remove(boss.getId());
        dropOwner(boss.getId());
    }

    /**
     * 每客户端 tick：①世界换了就把<b>轮廓</b>整批作废（名单保留，见下）；②接同步数据的<b>实例变化</b>；
     * ③按绝对时刻推进寿命与撒粒子。
     */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != lastLevel) {
            // <b>只作废轮廓，不能连 WATCHED 一起清</b>（轮 15 P1-2）：换等级与"Boss 进客户端世界"
            // 会落在<b>同一个客户端 tick</b>里——服务端在传送那一 tick 就把配对包与投影一起排上同一条
            // 连接（ServerPlayer:758 addDuringPortalTeleport 早于 :765 sendLevelInfo；
            // ChunkMap:1392 → ServerEntity:234-239），客户端则在这一帧的 runAllTasks() 里一次性排空
            // （Minecraft:1106），而本方法是更晚的 ClientTick END（:1875）⇒ 先 watch 后 clear
            // 就把刚登记好的 Boss 永久抹掉（实体一生只发一次 join 事件，之后再没机会）。
            // 旧维度的条目交给下面那条 boss.level() != mc.level 判据自己剔，不需要这里清名单。
            lastLevel = mc.level;
            if (!ZONES.isEmpty()) version++;
            ZONES.clear();
        }
        if (mc.level == null) return;
        long now = mc.level.getGameTime();

        Iterator<Map.Entry<Integer, Watched>> watching = WATCHED.entrySet().iterator();
        while (watching.hasNext()) {
            Map.Entry<Integer, Watched> e = watching.next();
            ColossusBossEntity boss = e.getValue().boss.get();
            // isAddedToWorld()：Entity.java:3455，置位点在 ClientLevel:339（entityStorage.addEntity 之后）。
            // 事件被别的处理器（在我们之后）取消时实体根本没进图，那时它是 false（轮 16 P3-4）。
            if (boss == null || boss.isRemoved() || boss.level() != mc.level || !boss.isAddedToWorld()) {
                dropOwner(e.getKey());
                watching.remove();
                continue;
            }
            List<ColossusBossEntity.TelegraphView> views = boss.takeTelegraphViewsIfChanged();
            if (views == null) continue; // 自上次读取没换过投影实例：零成本
            dropOwner(boss.getId());
            for (ColossusBossEntity.TelegraphView v : views) {
                if (v.endGameTime() <= now) continue; // 补包路上晚了几 tick：过期的一律不补画
                // 新增也必须 bump version（轮 15 P1-1）：判据有三态（增/删/清），上一批只记了删与清，
                // 于是"只增不删"的那一批发出去后 RENDER_SNAPSHOT 永不重建 ⇒ 注册样式（含内置 ring）
                // 一个像素都不画，而 tick() 又因为 hasStyle 为真而<b>不撒粒子</b> ⇒ 整发危险区完全隐形。
                if (ZONES.put(key(boss.getId(), v.id()), new Live(boss.getId(), v)) == null) version++;
            }
        }

        Iterator<Map.Entry<Long, Live>> zones = ZONES.entrySet().iterator();
        while (zones.hasNext()) {
            Live z = zones.next().getValue();
            if (z.endGameTime <= now) { zones.remove(); version++; continue; }
            // 本地钟<b>落后超过一整个寿命</b>才不画（轮 16 P3-7 把措辞改成与实现一致）：
            // 新 ClientLevel 的 gameTime 起点是 0（ClientLevelData 构造器不设该字段，靠 tickTime 自增），
            // 只有 respawn 与 SetTime 被 netty 拆到不同批时才会看到那种量级的错位。
            // 代价与收益都写清：正常补包晚 1~3 tick 不会误伤（span 最小 11）；反过来单程延迟若真超过
            // 一整个寿命（约 550ms 起，对最短的那批圈），这里会压掉开头几 tick——宁压不假。
            if (now < z.startGameTime - (z.endGameTime - z.startGameTime)) continue;
            if (!hasStyle(z.visual)) spawnOutlineParticles(mc, z); // 几何档接管时不双份表现
        }
    }

    private static void dropOwner(int bossId) {
        if (ZONES.values().removeIf(z -> z.ownerBossId == bossId)) version++;
    }

    /** 镜像键：视图序号在不同 Boss 之间会重复，必须带上宿主实体 id。 */
    private static long key(int bossId, int viewId) {
        return ((long) bossId << 32) | (viewId & 0xFFFFFFFFL);
    }

    /**
     * 丢掉<b>当前画着的轮廓</b>并复位世界身份。<b>不动名单</b>（轮 16 P3-3）：
     * 名单唯一的重填点是 {@code EntityJoinLevelEvent}，一个实体一生只发一次——
     * 第三方调这个 public 方法若把名单清空，<b>仍在追踪范围内的 Boss 就永远不被盯了</b>
     * （只有它离开追踪范围再回来、或换维度才救得回来）。
     * 旧维度的条目交给 {@code tick()} 里 {@code boss.level() != mc.level} 那条判据自己剔，
     * 所以登出路径也不需要一个"顺手清名单"。
     */
    public static void clear() {
        if (!ZONES.isEmpty()) version++;
        ZONES.clear();
        lastLevel = null; // static 强引用：不复位就把整张旧 ClientLevel（entityStorage/chunkSource 一长串）扣住
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
        if (renderSnapshotVersion != version) { // 复用同一个列表：既不每帧分配，也不在遍历中被样式回调改表
            RENDER_SNAPSHOT.clear();
            RENDER_SNAPSHOT.addAll(ZONES.values());
            renderSnapshotVersion = version;
        }
        try {
            for (int i = 0; i < RENDER_SNAPSHOT.size(); i++) {
                Live z = RENDER_SNAPSHOT.get(i);
                ZoneRenderer r = STYLES.get(z.visual);
                if (r == null) continue;
                if (nowGameTime < z.startGameTime - (z.endGameTime - z.startGameTime)) continue; // 同 tick()：钟没对上就不画
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

    /**
     * 粒子档的成本上限：<b>到圆环</b>的距离（48 格＝vanilla 那道 32 格闸加 16 格补包余量，
     * 见 {@link #nearOutlineDistSq}）与每圈点数（96）。
     *
     * <p>为什么必须自己节流（轮 14 P2-2）：{@code force=true} 买到"不被距离裁剪"的同时，
     * 也把 vanilla 那两个<b>事实上的总量闸</b>（{@code LevelRenderer:2511} 的 32 格、
     * {@code :2514} 的 MINIMAL 整批丢）一起短路掉了；而 vanilla 后面没有兜底——
     * {@code ParticleEngine#add(:326-337)} 只对 {@code getParticleGroup()} 非空的粒子查容量
     * （{@code Particle.java:226} 默认返回 {@code Optional.empty()}，END_ROD/DUST 都不在任何 group 里），
     * 而本版本 {@code ParticleEngine:74 MAX_PARTICLES_PER_LAYER = 16384} 声明后<b>没有任何地方用它</b>。
     * 一圈 = {@code 2πr·1.5} 个点：r=30 就是 282 个/圈/ tick，八个圈同放足以把帧率打穿。
     * "修对了可见性"不等于"没引入新的代价"，所以这两道闸放在框架侧。
     */
    private static final double PARTICLE_CULL_DIST_SQ = 48.0D * 48.0D;
    private static final int MAX_POINTS_PER_OUTLINE = 96;

    /** 粒子档：沿轮廓撒一圈。 */
    private static void spawnOutlineParticles(Minecraft mc, Live z) {
        // 按<b>到圆环最近点</b>算，不是到圈心（轮 16 P2-2）：到圈心的话，玩家站在大圈的边缘
        // ——最需要看见它的人——反而整圈一个粒子都不撒，而这道闸恰恰是为了替代
        // vanilla 那条"逐粒子对相机算"的闸（LevelRenderer:2511）而加的，不能比它更严。
        if (mc.player != null && nearOutlineDistSq(mc.player, z) > PARTICLE_CULL_DIST_SQ) return;
        RandomSource r = mc.level.getRandom();
        ParticleOptions p = z.cachedParticle != null ? z.cachedParticle : (z.cachedParticle = particleFor(z));
        double circumference = 2 * Math.PI * z.radiusXZ;
        int points = Math.min(MAX_POINTS_PER_OUTLINE, Math.max(8, (int) (circumference * 1.5)));
        for (int k = 0; k < points; k++) {
            double angle = (k + r.nextDouble() * 0.5) / points * Math.PI * 2;
            // 必须是**带 boolean 的那个重载**（轮 14 P1-1，我上一批修的其实是半条）：
            // 7 参形态在 1.20.1 是 {@code ClientLevel.java:597-598 → levelRenderer.addParticle(p, false, true, …)}，
            // 第一个实参 force 被写死成 <b>false</b> ⇒ {@code LevelRenderer.java:2509} 的短路走不到，
            // {@code :2511} 那道 "平方距离 > 1024（＝32 格）→ return null" 的闸<b>照旧生效</b>；
            // 第二个 boolean 只喂 {@code calculateParticleLevel(:2518-2528)}，连"粒子=最少"也只救回 1/10 概率。
            // 下面这个 8 参形态（{@code ClientLevel.java:601-602}）把 {@code getOverrideLimiter() || force}
            // 传成 true——vanilla 自己给营火烟用的就是这一档（{@code CampfireBlock.java:191}）。
            mc.level.addAlwaysVisibleParticle(p, true,
                    z.center.x + Math.cos(angle) * z.radiusXZ,
                    z.center.y + 0.05 + r.nextDouble() * 0.15,
                    z.center.z + Math.sin(angle) * z.radiusXZ,
                    0, 0.01, 0);
        }
    }

    /**
     * 玩家到<b>轮廓圆环</b>（不是到圆盘填充区）的最近距离平方。
     *
     * <p>为什么不是"到 AABB 的最近点"（轮 16 P2-2 追加）：那等于把圈当实心盘算，人一旦落在盘内
     * 距离就是 0 ⇒ 半径 200 的圈会从 200 格外一路撒到 32 格外，整整 <b>168 格</b>都在撒粒子，
     * "总量闸"形同没有。正确量是"离那条线有多远"：水平方向取 {@code |‖p-c‖ - r|}，
     * 竖直方向才用"超出盘厚度的部分"。
     */
    private static double nearOutlineDistSq(net.minecraft.world.entity.player.Player player, Live z) {
        double dx = player.getX() - z.center.x;
        double dz = player.getZ() - z.center.z;
        double radial = Math.abs(Math.sqrt(dx * dx + dz * dz) - z.radiusXZ);
        double dy = Math.max(0.0, Math.abs(player.getY() - z.center.y) - (z.radiusY + 1.0));
        return radial * radial + dy * dy;
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
