package com.klze.colossus.move;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 不可变招式集合 + 加权选招器。
 * 选招 = 过滤（阶段/距离/谓词/冷却）→ 上下文权重 → 加权随机。
 * 没有中央 if-else 瀑布：一切是数据。
 */
public final class MoveSet {

    private final List<MoveDef> moves;
    private final com.klze.colossus.entity.ColossusBossEntity boss;

    MoveSet(com.klze.colossus.entity.ColossusBossEntity boss, List<MoveDef> moves) {
        this.boss = boss;
        this.moves = List.copyOf(moves);
    }

    public List<MoveDef> moves() { return moves; }

    /**
     * Java DSL 为底 + datapack 同名覆盖（第十四批）。
     *
     * <p>覆盖而非并存：同名两条都进表的话，{@code byId} 取首个＝谁先注册看运气，
     * 而整合包作者改 JSON 的本意就是"我要换掉这一招"。覆盖必然打日志——不留静默改命。
     */
    public static MoveSet merge(com.klze.colossus.entity.ColossusBossEntity boss,
                                List<MoveDef> javaDefs, List<MoveDef> dataDefs) {
        java.util.Map<ResourceLocation, MoveDef> byId = new java.util.LinkedHashMap<>();
        for (MoveDef m : javaDefs) byId.put(m.id(), m);
        for (MoveDef m : dataDefs) {
            if (byId.put(m.id(), m) != null) {
                com.klze.colossus.Colossus.LOGGER.warn(
                        "datapack move {} overrides the Java-defined move with the same id", m.id());
            }
        }
        return new MoveSet(boss, new ArrayList<>(byId.values()));
    }

    public MoveDef byId(ResourceLocation id) {
        for (MoveDef m : moves) {
            if (m.id().equals(id)) return m;
        }
        return null;
    }

    /** 加权随机选招；全部不可用时返回 empty。cooldownLeft 由实体提供。 */
    public java.util.Optional<MoveDef> pick(AttackContext ctx, ToIntFunction<ResourceLocation> cooldownLeft, RandomSource random) {
        List<MoveDef> pool = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        long total = 0; // long 累加：两个大权重 int 相加会溢出成负数炸 nextInt（审查 P2#10）
        for (MoveDef m : moves) {
            if (!m.available(ctx)) continue;
            if (cooldownLeft.applyAsInt(m.id()) > 0) continue;
            int w = m.weight(ctx);
            if (w <= 0) continue;
            pool.add(m);
            weights.add(w);
            total += w;
        }
        if (pool.isEmpty()) return java.util.Optional.empty();
        // 1.20.1 RandomSource 无 nextLong(bound)——权重实际远小于 int 域，钳位后走 nextInt
        int roll = random.nextInt((int) Math.min(total, Integer.MAX_VALUE - 1L));
        for (int i = 0; i < pool.size(); i++) {
            roll -= weights.get(i);
            if (roll < 0) return java.util.Optional.of(pool.get(i));
        }
        return java.util.Optional.of(pool.get(pool.size() - 1));
    }
}
