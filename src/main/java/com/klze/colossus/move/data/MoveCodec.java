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

    /** 一条记录一个错误串；由 loader 汇总打印（静默跳过会被误认为生效）。 */
    public static final class MoveDataException extends Exception {
        public MoveDataException(String field, String message) {
            super(field + ": " + message);
        }
    }

    private MoveCodec() {}

    // ==================== 触发器词汇表 ====================

    private interface TriggerDecoder {
        MoveTrigger decode(JsonObject el) throws MoveDataException;
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
        java.util.function.Function<ColossusBossEntity, ZoneEffect> decode(JsonObject el) throws MoveDataException;
    }

    private interface ConditionDecoder {
        Predicate<AttackContext> decode(JsonElement value) throws MoveDataException;
    }

    private interface WeightDecoder {
        ToIntFunction<AttackContext> decode(JsonObject el) throws MoveDataException;
    }

    static {
        registerTrigger("sound", (JsonObject el) -> {
            Sound d = orThrow(decode(el, Sound.CODEC), "sound");
            return MoveTriggers.sound(d.sound(), d.volume(), d.pitch());
        });
        registerTrigger("event", (JsonObject el) -> MoveTriggers.event(requireString(el, "id")));
        registerTrigger("arc_hit", (JsonObject el) -> {
            ArcHit d = orThrow(decode(el, ArcHit.CODEC), "arc_hit");
            if (d.contactTag() == null || d.contactTag().isEmpty()) {
                return MoveTriggers.arcHit(d.radius(), d.arc(), d.damage(), d.knockback());
            }
            return MoveTriggers.arcHitContacted(d.contactTag(), d.radius(), d.arc(), d.damage(), d.knockback());
        });
        registerTrigger("circle_hit", (JsonObject el) -> {
            CircleHit d = orThrow(decode(el, CircleHit.CODEC), "circle_hit");
            return MoveTriggers.circleHit(d.radius(), d.damage(), d.knockback());
        });
        registerTrigger("sweep_hit", (JsonObject el) -> {
            SweepHit d = orThrow(decode(el, SweepHit.CODEC), "sweep_hit");
            return MoveTriggers.sweepHit(d.length(), d.damage(), d.knockback());
        });
        registerTrigger("break_ahead", (JsonObject el) -> {
            BreakAhead d = orThrow(decode(el, BreakAhead.CODEC), "break_ahead");
            return MoveTriggers.breakAhead(d.forward(), d.width(), d.height(), d.drop());
        });
        registerTrigger("once", (JsonObject el) -> {
            String tag = requireString(el, "tag");
            MoveTrigger inner = decodeTriggerField(el.get("then"), "then");
            return MoveTriggers.once(tag, inner);
        });
        registerTrigger("telegraph", (JsonObject el) -> {
            var zone = decodeZoneField(el.get("zone"), "zone");
            var effect = decodeEffectField(el.get("effect"), "effect");
            return MoveTriggers.telegraph(zone, effect);
        });
        registerTrigger("telegraph_visual", el -> MoveTriggers.telegraphVisual(decodeZoneField(el.get("zone"), "zone")));

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
            return boss -> ZoneEffect.damageOnly(d.damage(), d.knockback());
        });
        EFFECT_KINDS.put("freeze", el -> {
            FreezeEffect d = orThrow(decode(el, FreezeEffect.CODEC), "effect.freeze");
            return boss -> ZoneEffect.freeze(d.ticks());
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

        WEIGHT_KEYS.put("base", el -> {
            int base = (int) requireFloat(el.get("base"), "weight.base"); // 取成员值，不是整行对象
            return ctx -> base;
        });
        WEIGHT_KEYS.put("distance_band", el -> {
            Band d = orThrow(decode(el, Band.CODEC), "weight.distance_band");
            return ctx -> {
                double dist = Math.sqrt(ctx.distSq());
                return dist >= d.min() && dist <= d.max() ? d.add() : 0;
            };
        });
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

        var frames = decodeFrames(el);
        if (frames.isEmpty()) throw new MoveDataException("frames", "move has no frames at all");
        for (var f : frames) {
            if (f.from() > duration) {
                // 与 Java 侧 MoveSetBuilder.done() 同一条规则：from>duration 的帧永不触发，登记期就该响
                throw new MoveDataException("frames", "frame " + f.from() + " exceeds duration " + duration);
            }
        }

        var weightFn = decodeWeights(el);
        var check = decodeConditions(el);
        ResourceLocation id = new ResourceLocation(ns.getNamespace(), key);
        return MoveDef.of(id, duration, cooldown, minPhase, maxPhase, range, anim,
                weightFn, check, postInvuln, frames);
    }

    private static List<com.klze.colossus.state.FrameRunner.Frame<ColossusBossEntity>> decodeFrames(JsonObject el)
            throws MoveDataException {
        List<com.klze.colossus.state.FrameRunner.Frame<ColossusBossEntity>> out = new ArrayList<>();
        if (!(el.get("frames") instanceof JsonArray arr)) {
            throw new MoveDataException("frames", "missing or not an array");
        }
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.get(i) instanceof JsonObject row)) {
                throw new MoveDataException("frames[" + i + "]", "not an object");
            }
            MoveTrigger trigger = decodeTriggerField(row.get("trigger"), "frames[" + i + "].trigger");
            if (row.has("at")) {
                int at = intAt(row, "at", -1);
                if (at < 1) throw new MoveDataException("frames[" + i + "].at", "frames start at 1");
                out.add(new com.klze.colossus.state.FrameRunner.Frame<>(at, at,
                        (boss, tick) -> trigger.execute(boss, tick)));
            } else if (row.has("between")) {
                int[] w = intPair(row.get("between"), "frames[" + i + "].between");
                out.add(new com.klze.colossus.state.FrameRunner.Frame<>(w[0], w[1],
                        (boss, tick) -> trigger.execute(boss, tick)));
            } else if (row.has("repeating")) {
                if (!(row.get("repeating") instanceof JsonArray r || row.get("repeating") instanceof JsonObject)) {
                    throw new MoveDataException("frames[" + i + "].repeating", "expected [from,to,period]");
                }
                if (row.get("repeating") instanceof JsonArray three && three.size() == 3) {
                    out.add(com.klze.colossus.state.FrameRunner.Frame.repeating(
                            three.get(0).getAsInt(), three.get(1).getAsInt(), three.get(2).getAsInt(),
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
        List<ToIntFunction<AttackContext>> parts = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.get(i) instanceof JsonObject row)) {
                throw new MoveDataException("weight[" + i + "]", "not an object");
            }
            String kind = requireString(row, "kind");
            WeightDecoder d = WEIGHT_KEYS.get(kind);
            if (d == null) throw new MoveDataException("weight[" + i + "].kind", "unknown weight '" + kind + "'");
            parts.add(d.decode(row));
        }
        return ctx -> {
            int sum = 0;
            for (var p : parts) sum += p.applyAsInt(ctx);
            return sum;
        };
    }

    private static Predicate<AttackContext> decodeConditions(JsonObject el) throws MoveDataException {
        Predicate<AttackContext> check = ctx -> true;
        if (!el.has("requires")) return check;
        if (!(el.get("requires") instanceof JsonArray arr)) {
            throw new MoveDataException("requires", "expected an array");
        }
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.get(i) instanceof JsonObject row) || row.size() != 1) {
                throw new MoveDataException("requires[" + i + "]", "expected a single-key object");
            }
            String key = row.entrySet().iterator().next().getKey();
            JsonElement value = row.get(key);
            ConditionDecoder d = CONDITION_KEYS.get(key);
            if (d == null) {
                throw new MoveDataException("requires[" + i + "]", "unknown condition '" + key
                        + "' (known: " + String.join(", ", CONDITION_KEYS.keySet().stream().sorted().toList()) + ")");
            }
            check = check.and(d.decode(value));
        }
        return check;
    }

    private static MoveTrigger decodeTriggerField(JsonElement el, String field) throws MoveDataException {
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
        return d.decode(obj);
    }

    private static java.util.function.Function<ColossusBossEntity, TelegraphZone> decodeZoneField(
            JsonElement el, String field) throws MoveDataException {
        if (!(el instanceof JsonObject obj)) throw new MoveDataException(field, "expected an object");
        String kind = requireString(obj, "kind");
        ZoneDecoder d = ZONE_KINDS.get(kind);
        if (d == null) throw new MoveDataException(field + ".kind", "unknown zone kind '" + kind + "'");
        return d.decode(obj);
    }

    private static java.util.function.Function<ColossusBossEntity, ZoneEffect> decodeEffectField(
            JsonElement el, String field) throws MoveDataException {
        // effect 支持单个对象或数组（数组按 and() 组合）——"伤害+冻结"是内容最常配的组合
        if (el instanceof JsonArray arr) {
            List<java.util.function.Function<ColossusBossEntity, ZoneEffect>> parts = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) parts.add(decodeEffectField(arr.get(i), field + "[" + i + "]"));
            return boss -> {
                ZoneEffect acc = parts.get(0).apply(boss);
                for (int i = 1; i < parts.size(); i++) acc = acc.and(parts.get(i).apply(boss));
                return acc;
            };
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

    private static int intAt(JsonObject el, String field, int fallback) {
        return el.has(field) ? el.get(field).getAsInt() : fallback;
    }

    private static double requireFloat(JsonElement el, String field) throws MoveDataException {
        if (el == null || !el.isJsonPrimitive()) throw new MoveDataException(field, "missing or not a number");
        return el.getAsDouble();
    }

    private static int[] intPair(JsonElement el, String field) throws MoveDataException {
        if (!(el instanceof JsonArray arr) || arr.size() != 2) {
            throw new MoveDataException(field, "expected a 2-element array [min,max]");
        }
        int a = arr.get(0).getAsInt();
        int b = arr.get(1).getAsInt();
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
                Codec.INT.optionalFieldOf("warn", 30).forGetter(CircleAhead::warn),
                Codec.INT.optionalFieldOf("color", 0xFF4040).forGetter(CircleAhead::color),
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
                Codec.INT.fieldOf("ticks").forGetter(FreezeEffect::ticks)
        ).apply(i, FreezeEffect::new));
    }

    private record Band(double min, double max, int add) {
        static final Codec<Band> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.fieldOf("min").forGetter(Band::min),
                Codec.DOUBLE.fieldOf("max").forGetter(Band::max),
                Codec.INT.fieldOf("add").forGetter(Band::add)
        ).apply(i, Band::new));
    }

    private record RepeatingWindow(int from, int to, int period) {
        static final Codec<RepeatingWindow> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("from").forGetter(RepeatingWindow::from),
                Codec.INT.fieldOf("to").forGetter(RepeatingWindow::to),
                Codec.INT.fieldOf("period").forGetter(RepeatingWindow::period)
        ).apply(i, RepeatingWindow::new));
    }
}
