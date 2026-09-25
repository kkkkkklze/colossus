package com.klze.colossus.env;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "同一件事只说一次"的最小载体（轮 18 P2-3）。
 *
 * <p>为什么需要它：{@code TelegraphZone.damageCircle} 这类形状是在<b>招式 lambda 里现算</b>的，
 * 于是"每发都新建一个圈"的写法（{@code repeating(from,to,1,...)}）会把一次性 warn 变成
 * 每秒两条的服务端日志洪水。框架里同类噪声本来就有两套去重
 * （{@code ColossusBossEntity.telegraphCapWarned} 的每实例一次、{@code telegraphFullWarnedAt} 的 100t 桶），
 * 但这一个住在 record 的静态工具面上、没有实例可挂旗标，所以去重状态必须有、且必须<b>有界</b>。
 *
 * <p>上限 {@value #MAX_KEYS} 个键、LRU 逐出：坏数据（例如每 tick 换一个 {@code bossId} 的调用方）
 * 最多把它滚动使用，不会让它无限长。这是"配置期回执"用的，不是运行期计数器——不追求精确统计条数。
 */
public final class OncePerKey {

    private static final int MAX_KEYS = 64;

    private static final LinkedHashMap<String, Boolean> SEEN = new LinkedHashMap<>();

    /** 第一次见这个键返回 true（并登记）；之后一律 false。 */
    public static synchronized boolean firstTime(String key) {
        if (SEEN.containsKey(key)) return false;
        while (SEEN.size() >= MAX_KEYS) {
            java.util.Iterator<Map.Entry<String, Boolean>> it = SEEN.entrySet().iterator();
            if (it.hasNext()) { it.next(); it.remove(); } else break;
        }
        SEEN.put(key, Boolean.TRUE);
        return true;
    }

    /** 测试/热重载入场用：清空登记（生产路径不调）。 */
    public static synchronized void reset() {
        SEEN.clear();
    }

    /** 当前登记的键数（诊断与自检用）。 */
    public static synchronized int size() {
        return SEEN.size();
    }

    private OncePerKey() {}
}
