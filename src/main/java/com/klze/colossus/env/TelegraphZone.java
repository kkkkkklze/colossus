package com.klze.colossus.env;

import com.klze.colossus.entity.ColossusBossEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * 危险区预告（TelegraphZone）——两形态调研后的数据形态实现
 * （BR IceSpike：区域是纯数据、由帧触发器声明，服务端只在 warn 到期做一次 AABB 结算；
 * CAT LightningArea 的"真实体形态"留给 v0.2 第二批）。
 *
 * <p>同步契约（第二十四批改过）：<b>轮廓不再是"发一次就完"的广播</b>。区域几何连同
 * 起止时刻被投进 Boss 的 {@code SynchedEntityData}（见
 * {@link com.klze.colossus.entity.ColossusBossEntity#showTelegraph}），于是
 * ①中途进场的人由 vanilla 的 {@code ServerEntity#sendPairingData} 自动补到当前快照、
 * ②换维度/重进世界后随实体一起回来、③计时用<b>绝对 gameTime</b>，晚到的人看到的是
 * "已经烧掉一半"而不是"从头再亮"。这三条正是 v11 取证点名的旧病灶
 * （旧写法：一条 {@code ZoneSync} 包 + 客户端本地倒计时，{@code mc.level == null} 即整批清空）。
 * 战斗状态本身仍零自定义包。
 * 触发时机由 {@code MoveDef} 帧表声明（BR 用 GeckoLib 关键帧指令做的事，
 * 我们的状态机帧表是 1.20.1 上的等价物）。
 */
public record TelegraphZone(double cx, double cy, double cz,
                            double radiusXZ, double radiusY,
                            int warnTicks, int colorRGB, String visual) {

    /** 结算之后轮廓还要多留这么多 tick 淡出（旧 {@code broadcastZone} 里那个字面量 +10）。 */
    public static final int FADE_TICKS = 10;

    /**
     * 半径与预警窗口的硬上界。<b>钳在形状的源头（本 record 的规范构造器）</b>，
     * 而不是钳在各消费端。但"半径会放大成什么事故"只有一条现在还在（轮 19 P3-8 把这里从"三条路"改回一条）：
     * 服务端 {@code ZoneWork → getEntitiesOfClass(巨大 AABB)} 的扫场——{@code EntitySectionStorage:35-62}
     * 的 x 方向循环在 1.20.1 <b>没有体积护栏</b>（全 jar 无 {@code Area radius too large} 之类），
     * {@code r=1e9} 是 1.25 亿次外层循环＝服务端线程挂死。<b>这才是 MAX_RADIUS 存在的真理由。</b>
     * 另两条已被各自的绝对上限吃掉：几何档 {@code segs = clamp(2πr·3, 24, 768)} 在进循环<b>之前</b>封顶
     * （所以"每帧 12πr 顶点 / 42 亿顶点卡死"在钳位存在的前提下不再成立，别再拿它当理由），
     * 粒子档的槽位数与生成率也都与 r 无关（r 只改变每点间隔）。
     *
     * <p>256 格足够任何近战/弹道危险区用；{@code warn} 的 1200 tick（60 秒）是"预警窗口"这个概念的
     * 合理上限——超过它多半是笔误，而 {@code warn = Integer.MAX_VALUE} 会让
     * {@code settleDelayTicks}/{@code lifetimeTicks} <b>双双溢出</b>（实测 -2147483639）。
     *
     * <p><b>四条</b>入口的口径<b>不</b>相同，说清免得下游误判（轮 17 P2-1 收回先前那句"JSON/DSL 都响"；
     * 第四条由轮 18 设计偏差第 1 条补上——原先只列了三条，而 public 构造器本身就是第四条）：
     * <ul>
     *   <li><b>JSON</b>：{@code MoveCodec} 的 {@code circle_ahead} 有<b>字段级拒</b>，越界进不了表；</li>
     *   <li><b>DSL 速记</b>（{@link #damageCircle}）：越界<b>先响一次再钳</b>——按 (Boss, 种类) 去重
     *       （{@link OncePerKey}），因为本方法在招式 lambda 里跑，不去重就是日志洪水
     *       （轮 18 P2-3；先前这里写的是"零日志"，那是轮 17 的事实，本批已改）；
     *       为什么不升异常：形状是在 {@code registerMoves} 的 lambda 里现算的，抛出会整张表回落到上一版
     *       （轮 9 的崩溃面），代价大于收益，但静默改值不可接受；</li>
     *   <li><b>坏存档 / 恶意服务端</b>（{@link #fromTag}）：只钳<b>不打</b>——炸在 {@code readAdditionalSaveData}
     *       或渲染路径里＝区块一加载就崩（轮 9 那个教训）；</li>
     *   <li><b>第三方直接 {@code new TelegraphZone(...)}</b>：这是第四条，也是唯一<b>既不拒也不响</b>的一条——
     *       规范构造器是 public，框架没法知道调用方是谁、也没有 Boss 身份可做去重键，所以只能静默钳。
     *       要回执就走 {@link #damageCircle}（它会算前向偏移并给出 (Boss, 种类) 一次性的 warn）。
     *       {@code MoveTriggers.telegraph/telegraphVisual} 收的用户 lambda 通常就是这一条，
     *       所以框架推荐 DSL 作者用 {@code damageCircle} 而不是手搓 record。</li>
     * </ul>
     */
    public static final double MAX_RADIUS = 256.0;
    public static final int MAX_WARN_TICKS = 1200;

    /**
     * 圈心绝对坐标的上界（轮 17 P3-5 后半：半径与 warn 钳了，圆心没钳）。数值直接取
     * vanilla 声明的那个 {@code WorldBorder.MAX_CENTER_COORDINATE}（{@code WorldBorder.java:19}，
     * {@code 2.9999984E7D}）——<b>不</b>是自己挑的一个数，也不引用它名字相近的兄弟：
     * 本版本 {@code MAX_CENTER_COORDINATE} 声明后<b>没有任何地方使用</b>（全树 1 处命中就是声明本身，
     * 与 {@code ParticleEngine.MAX_PARTICLES_PER_LAYER} 同一类死常量），真正参与钳位的是
     * {@code WorldBorder.java:27} 的 {@code int absoluteMaxSize = 29999984}——
     * <b>两个数相等</b>（{@code 2.9999984E7 = 29999984}，差 0）。
     * 这里特别写一句：上一版说"两者差 16 格"，那是我把科学计数法的<b>两种写法</b>当两个数减了一下，
     * 是本仓自述"编造源码依据"之后的<b>下一次</b>（轮 18 P3-5 抓到）。
     *
     * <p>所以这一道<b>不</b>是"圈心离 Boss 不能超过这么多"的业务规则——框架不做那种判断。
     * 它只挡两类值：非有限数（NaN/±Inf 会让 {@link #box()} 退化成空盒或无限盒，
     * {@code AABB#intersects} 对 NaN 一律 false ⇒ 结算静默不命中，客户端还会拿到 NaN 坐标的粒子）
     * 和<b>坏档 / 恶意载荷</b>里的天文数字（{@code double} 到 1e18 连 1 格精度都不剩，
     * 画出来的圈在客户端会抖）。
     */
    public static final double MAX_CENTER_ABS =
            net.minecraft.world.level.border.WorldBorder.MAX_CENTER_COORDINATE;

    /**
     * {@code circle_ahead} 的圈心<b>相对 Boss</b> 偏移上限（格）。
     *
     * <p>这个数字是<b>选型规则，不是推导</b>：地面危险区的语义是"这块地将要出事"，
     * 远到一定程度的那一发该做成弹道/实体而不是贴地块，否则玩家既看不见来源也来不及读圈。
     * 2048 给得比 {@link #MAX_RADIUS} 宽八倍，够任何"远处落点"型演出用；再远就是配错了，
     * 而配错的后果是静默不命中（圈画在没人所在的地方，服务端扫场扫不到人、零回执），
     * 所以 JSON 侧给字段级拒、DSL 侧走 {@link #clampNotice} 同一族的源头钳位。
     */
    public static final double MAX_AHEAD_OFFSET = 2048.0;

    /** 粒子档的默认样式名（轮 17 P3-11：以前 "dust" 字面量散在构造器/DSL/注释三处）。 */
    public static final String DEFAULT_VISUAL = "dust";

    /** 样式名的唯一口径：null、空串、全空白都算"没写"，一律回落 {@link #DEFAULT_VISUAL}。 */
    public static String visualOrDefault(String visual) {
        return visual == null || visual.isBlank() ? DEFAULT_VISUAL : visual;
    }

    public TelegraphZone {
        radiusXZ = finiteOr(radiusXZ, 1.0);
        if (!(radiusXZ > 0.0)) radiusXZ = 1.0; // NaN 与 <=0 都回落到 1，不给下游"负半径"这种新问题
        radiusXZ = Math.min(radiusXZ, MAX_RADIUS);
        radiusY = finiteOr(radiusY, 1.0);
        if (!(radiusY > 0.0)) radiusY = 1.0;
        radiusY = Math.min(radiusY, MAX_RADIUS);
        if (warnTicks < 0) warnTicks = 0;
        warnTicks = Math.min(warnTicks, MAX_WARN_TICKS);
        cx = clampCoord(cx);
        cy = clampCoord(cy);
        cz = clampCoord(cz);
        visual = visualOrDefault(visual);
    }

    private static double finiteOr(double v, double fallback) {
        return Double.isFinite(v) ? v : fallback;
    }

    /** 圆心那一道的实现（非有限 → 0；越界 → 钳到 ±{@link #MAX_CENTER_ABS}）。 */
    private static double clampCoord(double v) {
        if (!Double.isFinite(v)) return 0.0;
        if (v > MAX_CENTER_ABS) return MAX_CENTER_ABS;
        if (v < -MAX_CENTER_ABS) return -MAX_CENTER_ABS;
        return v;
    }

    /** 越界被钳时的一句人话（DSL 侧唯一的反馈通道；JSON 侧走字段级拒，见 {@code MoveCodec}）。 */
    public static java.util.Optional<String> clampNotice(double wantRadius, double wantWarn) {
        if (wantRadius > MAX_RADIUS || wantRadius <= 0.0 || !Double.isFinite(wantRadius)
                || wantWarn > MAX_WARN_TICKS || wantWarn < 0) {
            return java.util.Optional.of("telegraph shape was clamped: radius " + wantRadius
                    + " -> (0," + MAX_RADIUS + "], warn " + wantWarn + " -> [0," + MAX_WARN_TICKS + "]");
        }
        return java.util.Optional.empty();
    }

    /**
     * 圈心偏移的同一档回执（轮 17 P3-5：{@code radius}/{@code warn} 有界而 {@code forward}/{@code side}
     * 任意大——一个 {@code forward=1e300} 的圈会落在世界边界外，扫场扫不到人、<b>零回执</b>，
     * 比"半径太大"更静默）。返回非空时调用方（{@link #damageCircle}）照它钳。
     *
     * <p><b>按范数判，不按分量</b>（轮 18 P3-1）：上一版逐分量比 {@code Math.abs(v)}，
     * 于是 {@code forward=2048, side=2048} 两条都"合法"而实际偏移 2896.3——比上限宽了 √2 倍，
     * 而规格与回执文案写的都是"范数 ≤"。现在判据与文案同一个量。
     */
    public static java.util.Optional<String> offsetNotice(double wantForward, double wantSide) {
        if (outOfOffset(wantForward, wantSide)) {
            return java.util.Optional.of("telegraph center offset was clamped: forward " + wantForward
                    + ", side " + wantSide + " -> hypot <= " + MAX_AHEAD_OFFSET
                    + " (a drop that far out should be a projectile, not a ground zone)");
        }
        return java.util.Optional.empty();
    }

    private static boolean outOfOffset(double forward, double side) {
        return !Double.isFinite(forward) || !Double.isFinite(side)
                || Math.hypot(forward, side) > MAX_AHEAD_OFFSET;
    }

    /** 钳好的一对偏移。 */
    public record Offsets(double forward, double side) {}

    /**
     * 偏移钳位：非有限 → 0（"圈画在自己脚下"）；范数越界 → <b>按比例缩</b>而不是逐分量截断
     * ——逐分量截会把作者指的方向改掉（{@code (2048,2048)} 截成 {@code (2048,2048)} 不变，
     * 而 {@code (4096,100)} 截成 {@code (2048,100)} 就换了朝向）。
     */
    public static Offsets clampOffsets(double forward, double side) {
        double f = Double.isFinite(forward) ? forward : 0.0;
        double s = Double.isFinite(side) ? side : 0.0;
        double norm = Math.hypot(f, s);
        if (norm > MAX_AHEAD_OFFSET && norm > 0.0) {
            double k = MAX_AHEAD_OFFSET / norm;
            return new Offsets(f * k, s * k);
        }
        return new Offsets(f, s);
    }

    /** 轮廓该活多久：<b>整段</b> warn 窗口 + 淡出，客户端与服务端投影响时都用这一个口径。 */
    public int lifetimeTicks() {
        return Math.max(1, this.warnTicks) + FADE_TICKS;
    }

    /**
     * 这一发的<b>结算延迟</b>（相对出招那一 tick）：排待办与重载剪枝共用这一个式子。
     *
     * <p>为什么单独成方法（轮 14 P3-6）：这两处原先各写一遍——{@code warnTicks + 1}（MoveTriggers）与
     * {@code Math.max(1, warnTicks) + 1}（实体读档剪枝），{@code warnTicks <= -1} 时两者差 1 tick，
     * "待办被判过期丢掉、轮廓按另一条式子还活着"那一档就从负数输入里爬回来了。
     * 一条规则两份实现，正是本仓反复在消灭的东西；JSON 侧另有 {@code warn >= 0} 的拒，
     * 但 DSL 那边不经过 codec，所以两条路都必须指向这里。
     */
    public int settleDelayTicks() {
        return Math.max(1, this.warnTicks + 1);
    }

    public AABB box() {
        return new AABB(cx - radiusXZ, cy - radiusY, cz - radiusXZ,
                cx + radiusXZ, cy + radiusY, cz + radiusXZ);
    }

    /** 区域内可攻击目标（存活、非旁观、非施法 BOSS 自己）。 */
    public List<LivingEntity> targets(ServerLevel level, ColossusBossEntity owner) {
        return level.getEntitiesOfClass(LivingEntity.class, box(),
                e -> e != owner && e.isAlive() && !e.isSpectator());
    }

    /** 伤害型结算（默认 visual={@link #DEFAULT_VISUAL}；damage/knockback 由 ZoneEffect 携带）。
     *  前向取 <b>yBodyRot 水平投影</b>而非 getLookAngle——抬头看天时视线水平分量趋零，
     *  圈心会塌回脚下（审查 P2#11）。
     *
     *  <p><b>回执是"每个 Boss 每种问题一次"，不是每次调用一次</b>（轮 18 P2-3）：本方法在招式 lambda 里
     *  跑，挂在 {@code repeating(from,to,1,...)} 上就是每秒两条 warn 的服务端日志洪水。
     *  去重走 {@link OncePerKey}（有界、按最近使用逐出，且键里带上被钳的值本身——只按 (Boss,种类) 去重的话，作者把 400 改成 500 仍然越界却不会再响一次，轮 19 P3-11），所以"作者写错了要响"和"响到淹没日志"两件事都有上限。 */
    public static TelegraphZone damageCircle(ColossusBossEntity boss, double forward, double side,
                                             double radiusXZ, int warnTicks, int colorRGB) {
        // 偏移先过闸再进几何式子：`fx * 1e300` 会把 NaN 乘出来，而下游三条路各自吞 NaN 的样子不同
        String who = String.valueOf(boss.getBossId());
        offsetNotice(forward, side).filter(m -> OncePerKey.firstTime(who + "|offset|" + forward + "|" + side))
                .ifPresent(msg -> com.klze.colossus.Colossus.LOGGER.warn(
                        "boss {} {}: {} (further identical notices are suppressed)",
                        boss.getBossId(), "DSL telegraph offset out of range", msg));
        var off = clampOffsets(forward, side);
        double fx = -Math.sin(Math.toRadians(boss.yBodyRot));
        double fz = Math.cos(Math.toRadians(boss.yBodyRot));
        double cx = boss.getX() + fx * off.forward() - fz * off.side();
        double cz = boss.getZ() + fz * off.forward() + fx * off.side();
        double cy = boss.getY() + 0.1;
        clampNotice(radiusXZ, warnTicks).filter(m -> OncePerKey.firstTime(who + "|shape|" + radiusXZ + "|" + warnTicks))
                .ifPresent(msg -> com.klze.colossus.Colossus.LOGGER.warn(
                        "boss {} {}: {} (further identical notices are suppressed)",
                        boss.getBossId(), "DSL telegraph shape out of range", msg));
        return new TelegraphZone(cx, cy, cz, radiusXZ, 1.0, warnTicks, colorRGB, DEFAULT_VISUAL);
    }

    /**
     * 落盘形态。延迟结算要能跨存档，区域就必须可序列化——存的是<b>解算后的世界坐标</b>：
     * 出招那一刻的位置才是要结算的位置，重载后 Boss 走了也不该把圈子拖走。
     *
     * <p>{@code warnTicks} 也在里面（第二十四批补）：旧写法只写几何，读回来 {@code warnTicks=0}，
     * 于是 {@link #lifetimeTicks()} 在重载路径上少 30 tick——轮廓先消失、伤害后落地。
     * 一份 tag 形状只此一处，客户端投影与待办队列都复用它，不留第二套序列化。
     */
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("cx", this.cx);
        tag.putDouble("cy", this.cy);
        tag.putDouble("cz", this.cz);
        tag.putDouble("rXZ", this.radiusXZ);
        tag.putDouble("rY", this.radiusY);
        tag.putInt("warn", this.warnTicks);
        tag.putInt("color", this.colorRGB);
        tag.putString("visual", this.visual);
        return tag;
    }

    /**
     * 一份 view tag 是否<b>齐件</b>（轮 18 P3-3）。
     *
     * <p>为什么必须有：{@code CompoundTag#getDouble/getInt/getString} 对<b>缺失或错类型</b>一律返回
     * 0/""（不抛，（{@code CompoundTag.java} 里 291=getInt、324=getDouble、335=getString）），所以一份被截断的 tag 会读成
     * {@code cx=cy=cz=0、rXZ=0→构造器回落 1.0、warn=0→settle=1}——世界原点一个 1 格圈，
     * 而 {@code ZoneWork} 那份载荷里还带着伤害，于是"看不见的圈照样落伤"。
     * 键名表与 {@link #toTag()} 同处一地维护，避免"写了新字段忘了验"。
     */
    public static boolean hasRequiredKeys(CompoundTag tag) {
        if (tag == null) return false;
        // 数值键用 mask=99（ANY_NUMERIC）而不是精确类型：CompoundTag#contains（:258-267）只在
        // mask==99 时才放行 Byte..Long/Float/Double，而三个 getter 内部走的正是 99 —— 用精确类型会把
        // 读侧本来读得出来的 putFloat("rXZ",4f)（或 /data modify 给的 IntTag）判成缺件、整条静默丢弃，
        // 即"闸门比读侧更严"（轮 19 P3-4）。字符串键仍按精确类型。
        return tag.contains("cx", 99) && tag.contains("cy", 99) && tag.contains("cz", 99)
                && tag.contains("rXZ", 99) && tag.contains("rY", 99)
                && tag.contains("warn", 99) && tag.contains("color", 99)
                && tag.contains("visual", net.minecraft.nbt.Tag.TAG_STRING);
    }

    /**
     * 读一份几何 tag。<b>调用方必须先过 {@link #hasRequiredKeys}</b>（框架内两处解码都这么做：
     * {@code TelegraphView.fromTag} 与 {@code ZoneWork.settleRejectReason}）——本方法自己不查，
     * 因为它是 record 的镜像形状、要保持可组合；缺键会被 {@code CompoundTag} 读成 0/""，
     * 再经构造器回落成"世界原点一个半径 1 的圈"（轮 19 设计偏差第 4 条把这条契约写明）。
     */
    public static TelegraphZone fromTag(CompoundTag tag) {
        return new TelegraphZone(tag.getDouble("cx"), tag.getDouble("cy"), tag.getDouble("cz"),
                tag.getDouble("rXZ"), tag.getDouble("rY"), tag.getInt("warn"), tag.getInt("color"),
                tag.getString("visual"));
    }

    /** 换渲染样式（"dust" 粒子默认 / "ring" 线框 / 第三方注册键）。 */
    public TelegraphZone withVisual(String visual) {
        return new TelegraphZone(cx, cy, cz, radiusXZ, radiusY, warnTicks, colorRGB, visual);
    }

    /**
     * 把这块区域投给所有能看见 Boss 的客户端（帧触发器里调用），寿命＝{@link #lifetimeTicks()}。
     *
     * <p>旧名字是 {@code broadcast(boss)}——它发一条一次性广播就撒手，所以中途进场、
     * 重进世界、换维度都看不见；现在改成登记进 Boss 的同步数据，由 vanilla 的追踪器补包。
     *
     * @return false＝Boss 的轮廓投影已满，这一条<b>没</b>登记上。上限是
     *         {@code ColossusBossEntity.maxActiveTelegraphs()}（默认 8、可覆写，硬上界
     *         {@code HARD_MAX_TELEGRAPHS = 32}）——<b>不是</b>只有那个常量。
     *         带伤害的帧必须据此放弃整发，别留一发没预警的结算。
     */
    public boolean show(ColossusBossEntity boss) {
        return boss.showTelegraph(this, this.lifetimeTicks()) >= 0;
    }
}
