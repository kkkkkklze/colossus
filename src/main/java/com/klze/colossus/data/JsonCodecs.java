package com.klze.colossus.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

/**
 * 框架共用的严格 Codec（第二十四批·审查轮 13 P3-7 提出来的落点）。
 *
 * <p>为什么要单独立一个家：{@code STRICT_INT} 原先住在 {@code move.data.MoveCodec} 里，
 * 而"JSON 里的整数不许被静默截断/饱和"这条纪律<b>不属于招式表</b>——任何从数据包读数字的
 * 路径都要用它。留在 MoveCodec 里的下场就是第二条入口出现时各写一份半套判据
 * （轮 12→13 正是这么漏掉 {@code ScreenShakeCue} 那个 {@code Codec.INT} 的）。
 */
public final class JsonCodecs {

    private JsonCodecs() {}

    /**
     * 只接受"落在 int 域内的有限整数"的整数 Codec。
     *
     * <p>它替掉的是 {@code Codec.INT}：DFU 6.0.8 的实现是
     * {@code PrimitiveCodec.read → ops.getNumberValue(input).map(Number::intValue)}，
     * 于是 {@code 10.5} 被静默截成 10。而直接写 {@code (int) getAsDouble()} 又会把
     * {@code 1e10}/{@code 1e999} <b>饱和</b>成 ±2^31-1（JLS 5.1.3 的窄化转换）且不报错——
     * 一个权重字段饱和成 2^31-1，结果就是那一招每次选招必中、其余招永久饿死。
     *
     * <p><b>它管不到的一格</b>：JSON 布尔。{@code JsonOps#getNumberValue} 明确写着
     * {@code isBoolean() → DataResult.success(getAsBoolean() ? 1 : 0)}，所以 {@code true}
     * 走到这里已经是 1.0 了，Codec 这一层无论怎么写都看不出"作者写了个布尔"。
     * 那道闸只能开在 gson 侧（{@code MoveCodec.decodeRecord} 就是干这个的），
     * 别以为加判据就能补上。
     */
    public static final Codec<Integer> STRICT_INT = Codec.DOUBLE.flatXmap(
            d -> {
                if (!Double.isFinite(d)) {
                    return DataResult.error(() -> "expected a finite integer, got " + d);
                }
                if (d != Math.rint(d)) {
                    return DataResult.error(() -> "expected an integer, got " + d);
                }
                if (d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
                    return DataResult.error(() -> "out of int range, got " + d);
                }
                return DataResult.success((int) (double) d);
            },
            i -> DataResult.success(i.doubleValue()));
}
