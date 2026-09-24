package com.klze.colossus.move;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;

/**
 * datapack 招式表的<b>表/实例分离层</b>（第十四批·v8 路线第 5 步）。
 *
 * <p>为什么必须有这一层：{@link MoveSet} 直接持有 boss 实体（实例态），
 * 而数据包换表是<b>全局</b>动作——若让 loader 去改实体里的 MoveSet，
 * 就是在遍历所有已加载实体（拿不到、也不该拿）。所以这里只存"纯数据表 + 代际号"，
 * 实体侧在下次用到时按代际号自建实例。
 *
 * <p>热替换语义（DBE GraphRegistry 的成熟形，简化到本框架需要的程度）：
 * ①整表<b>原子换引用</b>（volatile + 不可变 Map，读侧永不见半套状态）；
 * ②正在播的招继续用旧 {@link MoveDef}（AttackState 自己持着，帧表不会中途换脸）；
 * ③下一招才取新表；活跃 id 在新表消失 → {@code byId} 返回 null，实体自然回 idle。
 */
public final class MoveDataRegistry {

    /** 一次加载的回执（诊断/日志/回归桩三用）。 */
    public record Report(int files, int records, int applied, List<String> errors) {
        public int skipped() { return records - applied; }
    }

    private static volatile Map<ResourceLocation, List<MoveDef>> tables = Map.of();
    private static volatile long revision = 0L;
    private static volatile Report lastReport = new Report(0, 0, 0, List.of());

    private MoveDataRegistry() {}

    /** loader 的唯一出口：整批构建完成后一次性换表。 */
    public static void publish(Map<ResourceLocation, List<MoveDef>> next, Report report) {
        Map<ResourceLocation, List<MoveDef>> immutable = new java.util.LinkedHashMap<>();
        next.forEach((k, v) -> immutable.put(k, List.copyOf(v)));
        tables = Map.copyOf(immutable);
        lastReport = report;
        revision++;   // 先换表再走号：读侧看到新号时表必然已经是新的
    }

    /** 某个 Boss 种类的 datapack 招式（无则空表）。 */
    public static List<MoveDef> defsFor(ResourceLocation bossId) {
        return tables.getOrDefault(bossId, List.of());
    }

    public static long revision() { return revision; }

    public static Report lastReport() { return lastReport; }
    // 没有 clear()：publish() 本身就是整表替换，"下一个世界读到上一张表"由替换语义天然免疫
    // （先写过一条 ServerStarting 清表，后来删了——两条路径做同一件事必然出现先后顺序 bug）
}
