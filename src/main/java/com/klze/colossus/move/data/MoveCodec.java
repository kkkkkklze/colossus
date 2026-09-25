package com.klze.colossus.move.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.klze.colossus.Colossus;
import com.klze.colossus.entity.ColossusBossEntity;
import com.klze.colossus.env.TelegraphZone;
import com.klze.colossus.env.ZoneEffect;
import com.klze.colossus.move.AttackContext;
import com.klze.colossus.move.MoveDef;
import com.klze.colossus.move.MoveTrigger;
import com.klze.colossus.move.MoveTriggers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * JSON → {@link MoveDef} 的解码器（datapack 招式表，第十四批）。
 *
 * <p>三条从取证里定的硬规矩：
 * ①<b>行为仍是 Java，数据只给词汇表</b>——{@code "type"} 字符串查注册表分派
 * （DBE {@code AbilityType.java:165} 的 {@code byNameCodec().dispatch}、Tetra 复用原版 {@code ItemPredicate}
 * 都是这个选择）；<b>不引脚本/反射/表达式求值</b>（v3 §3c 判过"别引 Rhino"）。
 * ②<b>telegraph 类帧必须先把 zone/effect 变成封闭词汇表</b>——Java 侧它们收的是
 * {@code Function<Boss,TelegraphZone>}（落点每次施放现算），JSON 写不出函数，
 * 所以这里把"圈/方/朝向偏移"与"伤害/冻结"做成有限的 kind 表，由本类在解码时重建那个函数。
 * ③<b>单条记录失败只拒这一条</b>，错误带字段名进回执（DBE {@code PackLoader.java:259-265} 的
 * 结构化校验取向），绝不因为一个坏 JSON 让整张表回滚或让游戏崩。
 */
public final class MoveCodec {

    /** 单条招式最多多少帧（轮6 P2-4：记录数封了 512，帧数不封就是"一条记录钉住主线程"）。 */
    private static final int MAX_FRAMES_PER_MOVE = 64;

    /** 触发器嵌套上限（once 套 once…；防深递归把栈打穿——SOE 不是 RuntimeException，loader 抓不住）。 */
    private static final int MAX_TRIGGER_DEPTH = 8;

    /**
     * 权重/准入条目上限（轮 8 P3）：这两条数组原先**没封顶**，而选招时每 tick 对表内每条招式
     * 全遍历一遍（{@code MoveSet.pick}）——轮 6 那句"一条记录钉住主线程"只是换了个入口还在。
     * 封顶取 16：真实招式表里没人叠这么多调制项，超了就是写错或恶意。
     */
    private static final int MAX_WEIGHT_ENTRIES = 16;

    /** 按"最近是否用过"调权的 kind（普通项与历史项在 {@link #decodeWeights} 里分路）。 */
    private static final java.util.Set<String> HISTORY_WEIGHT_KEYS = java.util.Set.of("recent_band");

    /** requires 里的保留键：不折进谓词，解成 {@code MoveDef.notRecent} 数据（见 decodeConditions）。 */
    private static final String NOT_RECENT_KEY = "not_recent";
    private static final int MAX_CONDITIONS_PER_MOVE = 16;

    /** 一条记录一个错误串；由 loader 汇总打印（静默跳过会被误认为生效）。 */
    public static final class MoveDataException extends Exception {
        public MoveDataException(String field, String message) {
            super(field + ": " + message);
        }
    }

    private MoveCodec() {}

    // ==================== 触发器词汇表 ====================

    private interface TriggerDecoder {
        /** depth＝当前触发器嵌套层数（只有 once 会 +1，其余忽略）。 */
        MoveTrigger decode(JsonObject el, int depth) throws MoveDataException;
    }

    private static final Map<ResourceLocation, TriggerDecoder> TRIGGER_TYPES = new HashMap<>();
    private static final Map<String, ZoneDecoder> ZONE_KINDS = new HashMap<>();
    private static final Map<String, EffectDecoder> EFFECT_KINDS = new HashMap<>();
    private static final Map<String, ConditionDecoder> CONDITION_KEYS = new HashMap<>();
    private static final Map<String, WeightDecoder> WEIGHT_KEYS = new HashMap<>();

    private interface ZoneDecoder {
        java.util.function.Function<ColossusBossEntity, TelegraphZone> decode(JsonObject el) throws MoveDataException;
    }

    private interface EffectDecoder {
        /** 解成一条 {@link com.klze.colossus.env.ZoneBurst} 增量（与 Java DSL 的 telegraph 同一数据形状 ⇒ 可入 NBT）。 */
        com.klze.colossus.env.ZoneBurst decode(JsonObject el) throws MoveDataException;
    }

    private interface ConditionDecoder {
        Predicate<AttackContext> decode(JsonElement value) throws MoveDataException;
    }

    private interface WeightDecoder {
        ToIntFunction<AttackContext> decode(JsonObject el) throws MoveDataException;
    }

    static {
        registerTrigger("sound", (JsonObject el, int depth) -> {
            Sound d = orThrow(decode(el, Sound.CODEC), "sound");
            return MoveTriggers.sound(d.sound(), d.volume(), d.pitch());
        });
        registerTrigger("event", (JsonObject el, int depth) -> MoveTriggers.event(requireString(el, "id")));
        registerTrigger("arc_hit", (JsonObject el, int depth) -> {
            ArcHit d = orThrow(decode(el, ArcHit.CODEC), "arc_hit");
            if (d.contactTag() == null || d.contactTag().isEmpty()) {
                return MoveTriggers.arcHit(d.radius(), d.arc(), d.damage(), d.knockback());
            }
            return MoveTriggers.arcHitContacted(d.contactTag(), d.radius(), d.arc(), d.damage(), d.knockback());
        });
        registerTrigger("circle_hit", (JsonObject el, int depth) -> {
            CircleHit d = orThrow(decode(el, CircleHit.CODEC), "circle_hit");
            return MoveTriggers.circleHit(d.radius(), d.damage(), d.knockback());
        });
        registerTrigger("sweep_hit", (JsonObject el, int depth) -> {
            SweepHit d = orThrow(decode(el, SweepHit.CODEC), "sweep_hit");
            return MoveTriggers.sweepHit(d.length(), d.damage(), d.knockback());
        });
        registerTrigger("break_ahead", (JsonObject el, int depth) -> {
            BreakAhead d = orThrow(decode(el, BreakAhead.CODEC), "break_ahead");
            return MoveTriggers.breakAhead(d.forward(), d.width(), d.height(), d.drop());
        });
        registerTrigger("once", (JsonObject el, int depth) -> {
            String tag = requireString(el, "tag");
            MoveTrigger inner = decodeTriggerField(el.get("then"), "then", depth + 1);
            return MoveTriggers.once(tag, inner);
        });
        registerTrigger("telegraph", (JsonObject el, int depth) -> {
            var zone = decodeZoneField(el.get("zone"), "zone");
            var burst = decodeEffectField(el.get("effect"), "effect");
            return MoveTriggers.telegraph(zone, b -> burst);

        });
        registerTrigger("telegraph_visual", (JsonObject el, int depth) -> MoveTriggers.telegraphVisual(decodeZoneField(el.get("zone"), "zone")));

        ZONE_KINDS.put("circle_ahead", el -> {
            CircleAhead d = orThrow(decode(el, CircleAhead.CODEC), "zone.circle_ahead");
            return boss -> {
                TelegraphZone z = TelegraphZone.damageCircle(boss, d.forward(), d.side(),
                        d.radius(), d.warn(), d.color());
                return d.visual() == null || d.visual().isEmpty() ? z : z.withVisual(d.visual());
            };
        });

        EFFECT_KINDS.put("damage", el -> {
            DamageEffect d = orThrow(decode(el, DamageEffect.CODEC), "effect.damage");
            return new com.klze.colossus.env.ZoneBurst(d.damage(), d.knockback(), 0);
        });
        EFFECT_KINDS.put("freeze", el -> {
            FreezeEffect d = orThrow(decode(el, FreezeEffect.CODEC), "effect.freeze");
            return new com.klze.colossus.env.ZoneBurst(0.0f, 0.0f, d.ticks());
        });

        CONDITION_KEYS.put("phase_in", value -> {
            int[] band = intPair(value, "requires.phase_in");
            return ctx -> ctx.phase() >= band[0] && ctx.phase() < band[1];
        });
        CONDITION_KEYS.put("target_within", value -> {
            double dist = requireFloat(value, "requires.target_within");
            return ctx -> ctx.distSq() <= dist * dist;
        });
        CONDITION_KEYS.put("target_beyond", value -> {
            double dist = requireFloat(value, "requires.target_beyond");
            return ctx -> ctx.distSq() > dist * dist;
        });
        // `not_recent` 在这里**不注册**：它是保留键，解成 MoveDef.notRecent 这个数据字段而不是折进
        // lambda。引擎必须认得出"这条是被历史挡的"，才能在整表被挡空时只放开这一道做保底
        // （审查轮 10 F1：环形窗口只由出招推进，等待不消解封锁）。

        WEIGHT_KEYS.put("base", el -> {
            int base = requireInt(el.get("base"), "weight.base"); // 成员值而非整行；非整数拒（轮 11 #5）
            return ctx -> base;
        });
        WEIGHT_KEYS.put("distance_band", el -> {
            Band d = orThrow(decode(el, Band.CODEC), "weight.distance_band");
            return ctx -> {
                double dist = Math.sqrt(ctx.distSq());
                return dist >= d.min() && dist <= d.max() ? d.add() : 0;
            };
        });
        WEIGHT_KEYS.put("recent_band", el -> {
            int window = recentWindow(el.get("window"), "weight.recent_band.window");
            int add = requireInt(el.get("add"), "weight.recent_band.add"); // 通常是负数＝降权
            return ctx -> ctx.usedRecently(window) ? add : 0;
        });
    }

    /**
     * 严格整数 Codec：{@code Codec.INT} 在 DFU 6.0.8 里是
     * {@code getNumberValue(...).map(Number::intValue)}——**照样把 10.5 静默截成 10**。
     * 轮 12 F3：同一个字段的数组形态已经会拒、codec 形态却仍截断，等于一个字段两套规则
     * （正是 {@code MoveDef} javadoc 自己立的"不留可绕过的第二形态"）。错因由 {@code orThrow}
     * 拼成字段级回执，所以作者看到的是 {@code <字段>: codec rejected input: ...}。
     */
    private static final Codec<Integer> STRICT_INT = Codec.DOUBLE.flatXmap(
            d -> d == Math.rint(d) ? com.mojang.serialization.DataResult.success((int) (double) d)
                    : com.mojang.serialization.DataResult.error(
                            () -> "expected an integer, got " + d),
            i -> com.mojang.serialization.DataResult.success(i.doubleValue()));

    /** 整数值读取：非整数/非数字一律字段级拒（原先 {@code (int) requireFloat} 会把 8.9 静默截成 8）。 */
    private static int requireInt(JsonElement el, String field) throws MoveDataException {
        if (el == null || !el.isJsonPrimitive()) throw new MoveDataException(field, "missing or not a number");
        double d;
        try {
            d = el.getAsDouble();
        } catch (RuntimeException notNumber) { // 布尔/字符串在 gson 里就炸在这，回执要带字段名
            throw new MoveDataException(field, "expected a number, got " + el);
        }
        if (d != Math.rint(d) || Double.isNaN(d) || Double.isInfinite(d)) {
            throw new MoveDataException(field, "expected an integer, got " + el.getAsString());
        }
        if (d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
            throw new MoveDataException(field, "超出 int 域：" + el.getAsString());
        }
        return (int) d;
    }

    /**
     * 招式历史窗口取值。越界一律字段级拒，不做静默截断——
     * 截成 8 会让作者以为"最近 20 次"生效了，实际只记住 8 次，是会让招式表行为说谎的那类错。
     */
    private static int recentWindow(JsonElement value, String field) throws MoveDataException {
        int n = requireInt(value, field);
        if (n < 1 || n > com.klze.colossus.move.MoveHistory.SLOTS) {
            throw new MoveDataException(field, "窗口必须是 1.."
                    + com.klze.colossus.move.MoveHistory.SLOTS + "，拿到 " + n);
        }
        return n;
    }

    private static void registerTrigger(String path, TriggerDecoder decoder) {
        TRIGGER_TYPES.put(new ResourceLocation(Colossus.MODID, path), decoder);
    }

    // ==================== 记录解码 ====================

    /**
     * 解一条招式记录。失败抛 {@link MoveDataException}（带字段名），由 loader 记进回执后跳过该条。
     *
     * @param ns 该记录所属命名空间（文件所在 pack 的 ns）
     */
    public static MoveDef decodeMove(String key, ResourceLocation ns, JsonObject el) throws MoveDataException {
        int duration = intAt(el, "duration", 20);
        if (duration < 1) throw new MoveDataException("duration", "must be >= 1, got " + duration);
        int cooldown = Math.max(0, intAt(el, "cooldown", 20));
        int postInvuln = Math.max(0, intAt(el, "post_invuln", 0));
        int minPhase = 0;
        int maxPhase = Integer.MAX_VALUE;
        if (el.has("phase")) {
            int[] band = intPair(el.get("phase"), "phase");
            minPhase = band[0];
            maxPhase = band[1];
            if (maxPhase <= minPhase) {
                throw new MoveDataException("phase", "max must be > min: [" + minPhase + "," + maxPhase + ")");
            }
        }
        float range = el.has("range") ? (float) requireFloat(el.get("range"), "range") : -1.0f;
        String anim = el.has("anim") ? requireString(el, "anim") : key;
        if (anim.isBlank()) {
            // 值域的最终闸门在 MoveDef 构造器（两条入口共用）；这里先报错是为了给作者
            // **字段级**回执，而不是让 IllegalArgumentException 混进"解析炸了"那一类（轮 8 P2）
            throw new MoveDataException("anim", "animation name must not be blank");
        }

        var frames = decodeFrames(el);
        if (frames.isEmpty()) throw new MoveDataException("frames", "move has no frames at all");
        for (var f : frames) {
            if (f.from() > duration) {
                // 与 Java 侧 MoveSetBuilder.done() 同一条规则：from>duration 的帧永不触发，登记期就该响
                throw new MoveDataException("frames", "frame " + f.from() + " exceeds duration " + duration);
            }
        }

        var weightFn = decodeWeights(el);
        Conditions cond = decodeConditions(el);
        var check = cond.check();
        ResourceLocation id = ResourceLocation.tryParse(ns.getNamespace() + ":" + key);
        if (id == null) throw new MoveDataException("id", "不是合法的招式 id：" + key
                + "（合法字符：小写字母/数字/._-，路径段以 / 分隔）");
        return MoveDef.of(id, duration, cooldown, minPhase, maxPhase, range, anim,
                weightFn, check, cond.notRecent(), postInvuln, frames);
    }

    private static List<com.klze.colossus.state.FrameRunner.Frame<ColossusBossEntity>> decodeFrames(JsonObject el)
            throws MoveDataException {
        List<com.klze.colossus.state.FrameRunner.Frame<ColossusBossEntity>> out = new ArrayList<>();
        if (!(el.get("frames") instanceof JsonArray arr)) {
            throw new MoveDataException("frames", "missing or not an array");
        }
        if (arr.size() > MAX_FRAMES_PER_MOVE) {
            throw new MoveDataException("frames", "条目数 " + arr.size() + " 超单招上限 " + MAX_FRAMES_PER_MOVE);
        }
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.get(i) instanceof JsonObject row)) {
                throw new MoveDataException("frames[" + i + "]", "not an object");
            }
            MoveTrigger trigger = decodeTriggerField(row.get("trigger"), "frames[" + i + "].trigger");
            if (row.has("at")) {
                int at = intAt(row, "at", -1);
                String bad = com.klze.colossus.state.FrameRunner.windowError(at, at, 0);
                if (bad != null) throw new MoveDataException("frames[" + i + "].at", bad);
                out.add(new com.klze.colossus.state.FrameRunner.Frame<>(at, at,
                        (boss, tick) -> trigger.execute(boss, tick)));
            } else if (row.has("between")) {
                int[] w = intPair(row.get("between"), "frames[" + i + "].between");
                // 与 MoveSetBuilder 同一条判据：from<1 也要拒（旧写法只查了 to>=from → 死帧静默通过）
                String bad = com.klze.colossus.state.FrameRunner.windowError(w[0], w[1], 0);
                if (bad != null) throw new MoveDataException("frames[" + i + "].between", bad);
                out.add(new com.klze.colossus.state.FrameRunner.Frame<>(w[0], w[1],
                        (boss, tick) -> trigger.execute(boss, tick)));
            } else if (row.has("repeating")) {
                if (!(row.get("repeating") instanceof JsonArray r || row.get("repeating") instanceof JsonObject)) {
                    throw new MoveDataException("frames[" + i + "].repeating", "expected [from,to,period]");
                }
                if (row.get("repeating") instanceof JsonArray three && three.size() == 3) {
                    out.add(com.klze.colossus.state.FrameRunner.Frame.repeating(
                            requireInt(three.get(0), "frames[" + i + "].repeating[0]"),
                            requireInt(three.get(1), "frames[" + i + "].repeating[1]"),
                            requireInt(three.get(2), "frames[" + i + "].repeating[2]"),
                            (boss, tick) -> trigger.execute(boss, tick)));
                } else {
                    RepeatingWindow d = orThrow(decode(row.get("repeating"), RepeatingWindow.CODEC),
                            "frames[" + i + "].repeating");
                    out.add(com.klze.colossus.state.FrameRunner.Frame.repeating(
                            d.from(), d.to(), d.period(),
                            (boss, tick) -> trigger.execute(boss, tick)));
                }
            } else {
                throw new MoveDataException("frames[" + i + "]",
                        "needs one of at / between / repeating");
            }
        }
        return out;
    }

    private static ToIntFunction<AttackContext> decodeWeights(JsonObject el) throws MoveDataException {
        if (!el.has("weight")) return ctx -> 1;
        if (!(el.get("weight") instanceof JsonArray arr) || arr.isEmpty()) {
            throw new MoveDataException("weight", "expected a non-empty array of entries");
        }
        if (arr.size() > MAX_WEIGHT_ENTRIES) {
            throw new MoveDataException("weight", "条目数 " + arr.size() + " 超上限 " + MAX_WEIGHT_ENTRIES);
        }
        List<ToIntFunction<AttackContext>> parts = new ArrayList<>();
        List<ToIntFunction<AttackContext>> historyParts = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.get(i) instanceof JsonObject row)) {
                throw new MoveDataException("weight[" + i + "]", "not an object");
            }
            String kind = requireString(row, "kind");
            WeightDecoder d = WEIGHT_KEYS.get(kind);
            if (d == null) throw new MoveDataException("weight[" + i + "].kind", "unknown weight '" + kind + "'");
            // 历史项与普通项分开攒：轮 10 F2 实测 demo 表在 >6 格时 3-6=-3 整条被 pick 丢掉，
            // 于是"降权仍可选"其实是禁选——与 notRecent 撞成同一件事，还更隐蔽。
            // 规则：普通项之和若非正，那是作者真的要禁用（保留）；历史项只能在正数基础上往下压，
            // 且地板是 1（可选但几乎不会被选中）。
            (HISTORY_WEIGHT_KEYS.contains(kind) ? historyParts : parts).add(d.decode(row));
        }
        return ctx -> {
            int sum = 0;
            for (var p : parts) sum += p.applyAsInt(ctx);
            if (sum <= 0 || historyParts.isEmpty()) return sum;
            for (var p : historyParts) sum = Math.max(1, sum + p.applyAsInt(ctx));
            return sum;
        };
    }

    /** requires 的解码结果：普通条件折成谓词，历史门单独带回（引擎要能忽略它做保底）。 */
    record Conditions(Predicate<AttackContext> check, int notRecent) {}

    private static Conditions decodeConditions(JsonObject el) throws MoveDataException {
        Predicate<AttackContext> check = ctx -> true;
        int notRecent = 0;
        if (!el.has("requires")) return new Conditions(check, 0);
        if (!(el.get("requires") instanceof JsonArray arr)) {
            throw new MoveDataException("requires", "expected an array");
        }
        if (arr.size() > MAX_CONDITIONS_PER_MOVE) {
            throw new MoveDataException("requires", "条目数 " + arr.size() + " 超上限 " + MAX_CONDITIONS_PER_MOVE);
        }
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.get(i) instanceof JsonObject row) || row.size() != 1) {
                throw new MoveDataException("requires[" + i + "]", "expected a single-key object");
            }
            String key = row.entrySet().iterator().next().getKey();
            JsonElement value = row.get(key);
            if (NOT_RECENT_KEY.equals(key)) { // 保留键：单独带回，不折进 lambda
                if (notRecent != 0) throw new MoveDataException("requires[" + i + "]", "not_recent 只能出现一次");
                notRecent = recentWindow(value, "requires[" + i + "].not_recent");
                continue;
            }
            ConditionDecoder d = CONDITION_KEYS.get(key);
            if (d == null) {
                java.util.List<String> known = new java.util.ArrayList<>(CONDITION_KEYS.keySet());
                known.add(NOT_RECENT_KEY);
                throw new MoveDataException("requires[" + i + "]", "unknown condition '" + key
                        + "' (known: " + String.join(", ", known.stream().sorted().toList()) + ")");
            }
            check = check.and(d.decode(value));
        }
        return new Conditions(check, notRecent);
    }

    private static MoveTrigger decodeTriggerField(JsonElement el, String field) throws MoveDataException {
        return decodeTriggerField(el, field, 0);
    }

    private static MoveTrigger decodeTriggerField(JsonElement el, String field, int depth)
            throws MoveDataException {
        if (depth > MAX_TRIGGER_DEPTH) {
            throw new MoveDataException(field, "触发器嵌套超过 " + MAX_TRIGGER_DEPTH + " 层");
        }
        if (!(el instanceof JsonObject obj)) throw new MoveDataException(field, "expected an object");
        String typeId = requireString(obj, "type");
        // tryParse 而不是 new ResourceLocation：非法字符串会直接抛 IllegalArgumentException，
        // 那会被当成"记录解析炸了"收进回执，作者看到的是一句路径报错而不是"这一招不认识"
        ResourceLocation parsed = ResourceLocation.tryParse(typeId);
        if (parsed == null) throw new MoveDataException(field + ".type", "不是合法的 id：" + typeId);
        TriggerDecoder d = TRIGGER_TYPES.get(parsed);
        if (d == null && "minecraft".equals(parsed.getNamespace())) {
            d = TRIGGER_TYPES.get(new ResourceLocation(Colossus.MODID, parsed.getPath())); // 允许简写
        }
        if (d == null) {
            throw new MoveDataException(field + ".type", "unknown trigger '" + typeId + "' (known: "
                    + TRIGGER_TYPES.keySet().stream().map(ResourceLocation::getPath).sorted().toList() + ")");
        }
        return d.decode(obj, depth);
    }

    private static java.util.function.Function<ColossusBossEntity, TelegraphZone> decodeZoneField(
            JsonElement el, String field) throws MoveDataException {
        if (!(el instanceof JsonObject obj)) throw new MoveDataException(field, "expected an object");
        String kind = requireString(obj, "kind");
        ZoneDecoder d = ZONE_KINDS.get(kind);
        if (d == null) throw new MoveDataException(field + ".kind", "unknown zone kind '" + kind + "'");
        return d.decode(obj);
    }

    private static com.klze.colossus.env.ZoneBurst decodeEffectField(
            JsonElement el, String field) throws MoveDataException {
        // effect 支持单个对象或数组：数组＝多条合并成一次爆发（damage+freeze 是内容最常配的组合）。
        // 词汇表刻意只有这两格——要往 telegraph 里塞自定义 ZoneEffect 就得先给它一个 NBT 形态，
        // 因为这条路径上的东西必须能进存档（延迟队列第十六批起是持久化的）。
        if (el instanceof JsonArray arr) {
            com.klze.colossus.env.ZoneBurst acc = com.klze.colossus.env.ZoneBurst.NONE;
            for (int i = 0; i < arr.size(); i++) {
                acc = acc.merge(decodeEffectField(arr.get(i), field + "[" + i + "]"));
            }
            return acc;
        }
        if (!(el instanceof JsonObject obj)) throw new MoveDataException(field, "expected object or array");
        String kind = requireString(obj, "kind");
        EffectDecoder d = EFFECT_KINDS.get(kind);
        if (d == null) throw new MoveDataException(field + ".kind", "unknown effect kind '" + kind + "'");
        return d.decode(obj);
    }

    // ==================== 小工具 ====================

    private static <D> D orThrow(com.mojang.serialization.DataResult<D> r, String field) throws MoveDataException {
        var ok = r.result();
        if (ok.isPresent()) return ok.get();
        // 只借 DFU 的 Optional 面取错因（PartialResult 的取词方法在 1.20.1 的 DFU 上形不保）
        String detail = r.error().map(Object::toString).orElse("unknown");
        throw new MoveDataException(field, "codec rejected input: " + detail);
    }

    private static <D> com.mojang.serialization.DataResult<D> decode(JsonElement el, Codec<D> codec) {
        return codec.parse(JsonOps.INSTANCE, el);
    }

    private static String requireString(JsonObject el, String field) throws MoveDataException {
        if (!el.has(field) || !el.get(field).isJsonPrimitive()) {
            throw new MoveDataException(field, "missing or not a string");
        }
        return el.get(field).getAsString();
    }

    private static String requireString(JsonElement el, String field) throws MoveDataException {
        if (el == null || !el.isJsonPrimitive()) throw new MoveDataException(field, "missing or not a string");
        return el.getAsString();
    }

    /** 可选整数字段（缺省走 fallback）；给了值就必须是整数——10.5 静默截成 10 是行为说谎。 */
    private static int intAt(JsonObject el, String field, int fallback) throws MoveDataException {
        return el.has(field) ? requireInt(el.get(field), field) : fallback;
    }

    private static double requireFloat(JsonElement el, String field) throws MoveDataException {
        if (el == null || !el.isJsonPrimitive()) throw new MoveDataException(field, "missing or not a number");
        return el.getAsDouble();
    }

    private static int[] intPair(JsonElement el, String field) throws MoveDataException {
        if (!(el instanceof JsonArray arr) || arr.size() != 2) {
            throw new MoveDataException(field, "expected a 2-element array [min,max]");
        }
        int a = requireInt(arr.get(0), field + "[0]");
        int b = requireInt(arr.get(1), field + "[1]");
        if (b < a) throw new MoveDataException(field, "max < min: [" + a + "," + b + "]");
        return new int[]{a, b};
    }

    // ==================== 记录型数据（Codec） ====================

    private record Sound(ResourceLocation sound, float volume, float pitch) {
        static final Codec<Sound> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceLocation.CODEC.fieldOf("sound").forGetter(Sound::sound),
                Codec.FLOAT.optionalFieldOf("volume", 1.0f).forGetter(Sound::volume),
                Codec.FLOAT.optionalFieldOf("pitch", 1.0f).forGetter(Sound::pitch)
        ).apply(i, Sound::new));
    }

    private record ArcHit(float radius, float arc, float damage, float knockback, String contactTag) {
        static final Codec<ArcHit> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.fieldOf("radius").forGetter(ArcHit::radius),
                Codec.FLOAT.optionalFieldOf("arc", 360.0f).forGetter(ArcHit::arc),
                Codec.FLOAT.fieldOf("damage").forGetter(ArcHit::damage),
                Codec.FLOAT.optionalFieldOf("knockback", 0.0f).forGetter(ArcHit::knockback),
                Codec.STRING.optionalFieldOf("contact_tag", "").forGetter(ArcHit::contactTag)
        ).apply(i, ArcHit::new));
    }

    private record CircleHit(float radius, float damage, float knockback) {
        static final Codec<CircleHit> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.fieldOf("radius").forGetter(CircleHit::radius),
                Codec.FLOAT.fieldOf("damage").forGetter(CircleHit::damage),
                Codec.FLOAT.optionalFieldOf("knockback", 0.0f).forGetter(CircleHit::knockback)
        ).apply(i, CircleHit::new));
    }

    private record SweepHit(float length, float damage, float knockback) {
        static final Codec<SweepHit> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.fieldOf("length").forGetter(SweepHit::length),
                Codec.FLOAT.fieldOf("damage").forGetter(SweepHit::damage),
                Codec.FLOAT.optionalFieldOf("knockback", 0.0f).forGetter(SweepHit::knockback)
        ).apply(i, SweepHit::new));
    }

    private record BreakAhead(double forward, double width, double height, boolean drop) {
        static final Codec<BreakAhead> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.optionalFieldOf("forward", 2.5).forGetter(BreakAhead::forward),
                Codec.DOUBLE.optionalFieldOf("width", 3.0).forGetter(BreakAhead::width),
                Codec.DOUBLE.optionalFieldOf("height", 1.6).forGetter(BreakAhead::height),
                Codec.BOOL.optionalFieldOf("drop", false).forGetter(BreakAhead::drop)
        ).apply(i, BreakAhead::new));
    }

    private record CircleAhead(double forward, double side, double radius, int warn, int color, String visual) {
        static final Codec<CircleAhead> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.optionalFieldOf("forward", 0.0).forGetter(CircleAhead::forward),
                Codec.DOUBLE.optionalFieldOf("side", 0.0).forGetter(CircleAhead::side),
                Codec.DOUBLE.fieldOf("radius").forGetter(CircleAhead::radius),
                STRICT_INT.optionalFieldOf("warn", 30).forGetter(CircleAhead::warn),
                STRICT_INT.optionalFieldOf("color", 0xFF4040).forGetter(CircleAhead::color),
                Codec.STRING.optionalFieldOf("visual", "").forGetter(CircleAhead::visual)
        ).apply(i, CircleAhead::new));
    }

    private record DamageEffect(float damage, float knockback) {
        static final Codec<DamageEffect> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.fieldOf("damage").forGetter(DamageEffect::damage),
                Codec.FLOAT.optionalFieldOf("knockback", 0.0f).forGetter(DamageEffect::knockback)
        ).apply(i, DamageEffect::new));
    }

    private record FreezeEffect(int ticks) {
        static final Codec<FreezeEffect> CODEC = RecordCodecBuilder.create(i -> i.group(
                STRICT_INT.fieldOf("ticks").forGetter(FreezeEffect::ticks)
        ).apply(i, FreezeEffect::new));
    }

    private record Band(double min, double max, int add) {
        static final Codec<Band> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.fieldOf("min").forGetter(Band::min),
                Codec.DOUBLE.fieldOf("max").forGetter(Band::max),
                STRICT_INT.fieldOf("add").forGetter(Band::add)
        ).apply(i, Band::new));
    }

    private record RepeatingWindow(int from, int to, int period) {
        static final Codec<RepeatingWindow> CODEC = RecordCodecBuilder.create(i -> i.group(
                STRICT_INT.fieldOf("from").forGetter(RepeatingWindow::from),
                STRICT_INT.fieldOf("to").forGetter(RepeatingWindow::to),
                STRICT_INT.fieldOf("period").forGetter(RepeatingWindow::period)
        ).apply(i, RepeatingWindow::new));
    }
}
