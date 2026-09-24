package com.klze.colossus.fx;

import com.klze.colossus.client.ScreenShakeClient;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 内置 cue：世界空间径向震屏（ES {@code ScreenShakeVfx} 形）。
 *
 * <p>震屏不是全局开关，而是带中心/功率/半径/时长的世界坐标事件——
 * 离爆点越远抖得越轻，巨型 Boss 落地砸击的正配。
 * 衰减用线性 fade-out（ES 的 easing 曲线留作可换字段）。
 */
public final class ScreenShakeCue {

    /** @param power 最大视角扰动（度）；@param durationTicks 持续；@param radius 世界衰减半径（≤0 全局） */
    public record Data(float power, int durationTicks, float radius) {}

    public static final Codec<Data> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.FLOAT.fieldOf("power").forGetter(Data::power),
            Codec.INT.fieldOf("duration_ticks").forGetter(Data::durationTicks),
            Codec.FLOAT.fieldOf("radius").forGetter(Data::radius)
    ).apply(i, Data::new));

    public static final CueType.Type<Data> TYPE = new CueType.Type<>() {
        @Override public net.minecraft.resources.ResourceLocation id() { return CueType.id("screen_shake"); }
        @Override public Codec<Data> jsonCodec() { return CODEC; }
        @Override public void encode(Data d, FriendlyByteBuf buf) {
            buf.writeFloat(d.power());
            buf.writeVarInt(d.durationTicks());
            buf.writeFloat(d.radius());
        }
        @Override public Data decode(FriendlyByteBuf buf) {
            float power = buf.readFloat();
            int duration = buf.readVarInt();
            float radius = buf.readFloat();
            return new Data(power, duration, radius);
        }
        @Override public CueType.Player<Data> player() { return ScreenShakeClient::play; }
    };

    public static void register() {
        CueType.register(TYPE);
    }

    private ScreenShakeCue() {}
}
