package com.klze.colossus.client;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端护盾镜像表：bar UUID → 进度 [0,1]。血条本体继续用原版 {@code LerpingBossEvent}，
 * 这里<b>只存附加字段</b>（DE 的 {@code CustomBossInfoHandler} 同构）。
 *
 * <p>生命周期（取证点名的反面：static 表只在断线时清）：
 * ①断线/退世界 → {@link #clear()}（由 {@link ColossusClientHooks} 的 LoggingOut 挂）；
 * ②服务端在 removePlayer 时显式发 0，避免"玩家还在但条子残留"；
 * ③收到未知 bar 的值不当异常——覆盖写入即可，读侧永远按"没有＝0"处理。
 */
public final class ShieldBars {

    private static final Map<UUID, Float> VALUES = new HashMap<>();

    private ShieldBars() {}

    public static void set(UUID barId, float value) {
        if (value <= 0.0f || !Float.isFinite(value)) {
            VALUES.remove(barId); // 0 与非有限值都视为"这条 bar 没有盾"——表里不留死项
        } else {
            VALUES.put(barId, Math.min(1.0f, value));
        }
    }

    public static float get(UUID barId) {
        Float v = VALUES.get(barId);
        return v == null ? 0.0f : v;
    }

    public static boolean has(UUID barId) {
        return VALUES.containsKey(barId);
    }

    public static void clear() {
        VALUES.clear();
    }
}
