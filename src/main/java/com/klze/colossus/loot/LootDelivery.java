package com.klze.colossus.loot;

/**
 * BOSS 战利品交付策略（v0.2 第五批；调研依据：TF 缓冲入箱全链路 / ES 玩家袋 / Cataclysm 首杀播报）。
 *
 * <ul>
 *   <li>{@link #DROP_NOW} —— 原版即时掉落（v0.1 行为；小 Boss/调试用）。</li>
 *   <li>{@link #INTO_CHEST} —— 死亡演出开场 roll 一次进实体缓冲（随 NBT 持久化，
 *       防火烧/岩浆/过期/崩档四种丢失），演出结束在尸体处放箱灌入；
 *       放不下去时**兜底落地**（TF 在这里是静默丢失——Colossus 修掉）。
 *       "开箱仪式感"与参战共享结算由框架代管，Boss 作者零代码。</li>
 *   <li>{@link #INTO_BAG} —— 每参战者一只绑定战利品袋（v0.3：需要 loot condition 注册表
 *       与自定义 param set，见 DESIGN §7）。</li>
 * </ul>
 */
public enum LootDelivery {
    DROP_NOW,
    INTO_CHEST,
    INTO_BAG
}
