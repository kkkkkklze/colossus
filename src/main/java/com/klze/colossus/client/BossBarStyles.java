package com.klze.colossus.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端血条样式注册表：barId -> renderType 的映射由 {@code BarStyleS2C} 维护。
 *
 * <p>v0.1 只提供数据通道（apply/lookup）；拦截
 * {@code CustomizeGuiOverlayEvent.BossEventProgress} 换自绘贴图是 v0.2 的工作
 * （DE/FDLib/Cataclysm 三种方案已在研究报告中比选，采用 wall-clock 插值 + 注册式样式的 FDLib 形状）。
 */
public final class BossBarStyles {

    /** renderType = -1：回退原版样式。 */
    public static final int VANILLA = -1;

    private static final Map<UUID, Integer> ACTIVE = new HashMap<>();

    private BossBarStyles() {}

    public static void apply(UUID barId, int renderType) {
        if (renderType == VANILLA) {
            ACTIVE.remove(barId);
        } else {
            ACTIVE.put(barId, renderType);
        }
    }

    /** 该血条是否被框架样式接管。 */
    public static boolean isCustom(UUID barId) {
        return ACTIVE.containsKey(barId);
    }

    public static int renderType(UUID barId) {
        return ACTIVE.getOrDefault(barId, VANILLA);
    }

    /** 断线清缓存。 */
    public static void clear() { ACTIVE.clear(); }
}
