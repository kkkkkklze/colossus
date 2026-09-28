package com.klze.colossus.env;

/**
 * 竞技场边界与持久化读值的<b>纯判据</b>——零 MC 依赖（可进 {@code colossusSelfTest}）。
 *
 * <p>为什么要单独成类（第三十六批，v14 取证来料）：{@code ArenaSession} 是 import MC 的会话类，
 * 四条门里只有 {@code runGameTestServer} 能跑它，而跑它需要一个活世界 + 活区块。
 * "读档读出一个 NaN 圆心"、"解封那一格区块没加载"这两类故障的**判据**其实都是纯算术/纯布尔，
 * 留在会话里就只能靠真机桩观测——本仓的规矩是先搬进零 MC 的纯类，再谈断言（同
 * {@link TelegraphBudget} / {@link TailLedger} / {@link OncePerKey} 的由来）。
 *
 * <p>它管三件事：①判定半轴与计时参数的<b>合法域</b>；②从 NBT 读出的坐标三元组<b>能不能用</b>；
 * ③封/解一块方块的<b>分类</b>（能放 / 被占 / 区块没加载）。会话侧只负责问与做，规则在这里。
 */
public final class ArenaBounds {

    /**
     * 水平圆心的绝对上限，对齐 vanilla 世界边界的 {@code WorldBorder.absoluteMaxSize = 29999984}
     * （{@code WorldBorder} 里 {@code MAX_CENTER_COORDINATE} 与它<b>是同一个数</b>，不是差 16 的那对——
     * 早先一处注释把两者当成差 16，那是我算错）。
     */
    public static final double MAX_CENTER_ABS = 29_999_984.0;
    /** 竖直方向的上限：远高于任何真实建筑高度，只用来挡 NaN 之外的荒谬值。 */
    public static final double MAX_Y_ABS = 1_000_000.0;
    /**
     * 判定半轴的上界。超过它，"越界"就永远不会发生（玩家走不完 512 格），
     * 纠偏形同没有——与其让一个手滑的 5000 静默变成"没有边界"，不如回默认值并让它可查。
     */
    public static final double MAX_BOUND_RADIUS = 512.0;

    /** {@code Spec.of} 的默认半轴（x/z、y）——非法输入一律回它，而不是回 0。 */
    public static final double DEFAULT_RADIUS_XZ = 64.0, DEFAULT_RADIUS_Y = 32.0;
    /** 默认越界宽限与提醒间隔。 */
    public static final int DEFAULT_GRACE_TICKS = 200, DEFAULT_WARN_EVERY_TICKS = 100;

    /**
     * 判定半轴的合法化：{@code NaN}、{@code <=0}、超 {@link #MAX_BOUND_RADIUS} 都回默认。
     *
     * <p>为什么必须拦 {@code <=0}：{@code AABB} 的 min/max 一旦反过来（负半轴会算出 min&gt;max），
     * {@code bounds.contains(...)} 就<b>恒假</b> ⇒ 每个 tick 都判"越界" ⇒ 玩家在竞技场里被反复传送。
     * 这是"配一个坏数字 = 服务器把人弹来弹去"的形状，必须在源头拒。
     */
    public static double radiusOr(double v, double def) {
        if (!(v > 0.0) || v > MAX_BOUND_RADIUS) return def; // 取反写法：NaN 走 !(v>0) 落回默认
        return v;
    }

    /**
     * 越界宽限的合法化：允许 0（"立刻拉回"是作者的合法意图），只拦负数与离谱的大值。
     * 上界取 20 分钟（24000t）——比它长就等于没有这条纠偏。
     */
    public static int graceTicksOr(int v, int def) {
        if (v < 0 || v > 24_000) return def;
        return v;
    }

    /** 提醒间隔的合法化：至少 1 tick（0 会让冷却形同不存在，每 tick 刷一条 actionbar）。 */
    public static int warnEveryTicksOr(int v, int def) {
        if (v < 1 || v > 24_000) return def;
        return v;
    }

    /**
     * 从 NBT 读出的坐标三元组<b>能不能用</b>：三轴都必须是有限值。
     *
     * <p>这条判据存在的理由（v14 B 节 + 轮 22 同族）：1.20.1 的 {@code CompoundTag#getDouble}
     * 对<b>类型不对</b>的键返回 0（它内部走 mask=99，宽读数值类型，但字符串/复合体只会得 0），
     * 而手写/篡改过的存档完全可以把 {@code home_y} 写成字符串或干脆漏掉——于是圆心静默变成
     * {@code (x, 0, z)}（把竞技场沉到世界底）或者 {@code NaN}（判据恒假，人一直被弹）。
     * 读侧的"缺哪个键"必须由<b>三键齐</b>的判据回答，不能只看第一个键在不在。
     */
    public static boolean usableCoordinate(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Math.abs(x) <= MAX_CENTER_ABS && Math.abs(z) <= MAX_CENTER_ABS
                && Math.abs(y) <= MAX_Y_ABS;
    }

    /** 封一块方块的三种结果（会话侧据此决定告警与是否记账）。 */
    public enum SealOutcome {
        /** 这一格可以放：区块在、原方块可被替换。 */
        PLACED,
        /** 这一格被不可替换的方块占着（原有告警分支）。 */
        OCCUPIED,
        /** <b>区块没加载</b>：不能放——服务端 {@code getBlockState} 会强制生成区块，
         *  而且是在 Boss 那一 tick 的主线程里做（v14 取证里 TF 1.20.1 七个 Boss 都先跑这道门）。 */
        NO_CHUNK
    }

    /** 封路分类。判据只有一处：先问区块，再问可替换。 */
    public static SealOutcome classifySeal(boolean chunkLoaded, boolean canBeReplaced) {
        if (!worldReady(chunkLoaded)) return SealOutcome.NO_CHUNK;
        if (!canBeReplaced) return SealOutcome.OCCUPIED;
        return SealOutcome.PLACED;
    }

    /**
     * <b>这一格现在能不能碰这个世界</b>——封路、<b>解封</b>、找落点三处共用同一条判据
     * （本仓规矩：同一个判定不许有两份写法）。返回 false 时调用方必须<b>连方块状态都不去查</b>：
     * 服务端 {@code Level#getBlockState} 对缺失区块会<b>强制生成</b>，那件事发生在 Boss 的 tick 里。
     *
     * <p>解封方向还有一条附带的义务：<b>不能把快照删掉</b>。旧写法是"调用 setBlock 之后就 remove"，
     * 于是区块没加载的那一次把账也删了——一堵黑曜石永久留在玩家世界里，
     * 而且再也没人负责还原它（追偿路径见 {@code ArenaSession.tick()} 开头）。
     */
    public static boolean worldReady(boolean chunkLoaded) {
        return chunkLoaded;
    }

    /**
     * 这一格欠账<b>现在该不该还</b>——两个条件都要，且<b>维度排在区块之前</b>。
     *
     * <p>为什么必须有维度这一半（审查轮 24 P2-D2）：封路快照记的是 {@code long} 坐标，而
     * 不同维度共用同一套坐标编码——Boss 换维度（传送门、被别的 mod 搬运）之后，
     * {@code hasChunkAt(pos)} 问的是<b>另一个世界的同一坐标</b>，那一块大概率是加载的，
     * 于是原方块状态会被写进<b>当前维度</b>：静默改错世界，而原来那格还扣着封印方块。
     * 上一批把"欠账"从 1 tick 的短命改成无限期追偿之后，这条路从理论变成可达，所以必须现在补。
     *
     * <p>不匹配时既不写也不删账——账留着，Boss 回到那个维度时仍然还得掉；
     * 但必须有一次性告警，否则"永远追不完"和"已经追完了"在外部看不出区别。
     */
    public static boolean restoreAllowed(boolean sameDimension, boolean chunkLoaded) {
        return sameDimension && worldReady(chunkLoaded);
    }

    /** 这一格是不是<b>本局已经封过</b>的（同一格第二次封会把上一格刚放下去的封印方块当成"原方块"记账）。 */
    public static boolean slotAlreadySealed(boolean alreadyInSnapshot) {
        return alreadyInSnapshot;
    }

    private ArenaBounds() {}
}
