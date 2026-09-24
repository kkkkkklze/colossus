package com.klze.colossus.fx;

import com.klze.colossus.Colossus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 演出 cue 类型注册表（Eternal Starlight {@code VfxType} 形状，原生 1.20.1 血统样本）。
 *
 * <p>一种效果 = 一个 {@link CueType}：JSON {@link Codec}（数据侧可配置，为 v0.3
 * datapack 化预留）+ 网络 {@code StreamCodec}（同一份数据的双编码）+ 客户端播放器。
 * 全部 cue 走<b>一个</b> {@code CueS2C} 包广播——不再每加一种粒子/震屏就新写一种包
 * （第三轮调研 3a/4b 合并项）。
 *
 * <p>字符串版 {@code sendBossVisualEvent} 保留为轻量糖；需要携带参数/坐标衰减的
 * 重演出一律走 cue。
 */
public final class CueType {

    /** 一个 cue 类型的完整定义。 */
    public interface Type<D> {
        ResourceLocation id();

        /** JSON 编码（v0.3 招式/datapack 引用用；先备好不强用）。 */
        Codec<D> jsonCodec();

        /** 网络编码（写入/读出 CueS2C 缓冲的 payload 段）。 */
        void encode(D data, FriendlyByteBuf buf);

        D decode(FriendlyByteBuf buf);

        /** 客户端播放器（主线程调用）。 */
        Player<D> player();
    }

    @FunctionalInterface
    public interface Player<D> {
        void play(net.minecraft.client.multiplayer.ClientLevel level, Vec3 pos, D data);
    }

    private static final Map<ResourceLocation, Type<?>> TYPES = new HashMap<>();

    /** 注册期即校验重名（DBE EventCatalog 的"注册期报错优于运行期静默"原则）。 */
    public static <D> void register(Type<D> type) {
        if (TYPES.putIfAbsent(type.id(), type) != null) {
            throw new IllegalStateException("Duplicate cue type: " + type.id());
        }
        Colossus.LOGGER.debug("Colossus cue registered: {}", type.id());
    }

    public static Type<?> get(ResourceLocation id) { return TYPES.get(id); }

    /** 手搭一个 StreamCodec 风格的小工具：数值三元组的定长读写。 */
    public static void writeFloats(FriendlyByteBuf buf, float... values) {
        for (float v : values) buf.writeFloat(v);
    }

    public static float[] readFloats(FriendlyByteBuf buf, int count) {
        float[] out = new float[count];
        for (int i = 0; i < count; i++) out[i] = buf.readFloat();
        return out;
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(Colossus.MODID, path.toLowerCase(Locale.ROOT));
    }

    private CueType() {}
}
