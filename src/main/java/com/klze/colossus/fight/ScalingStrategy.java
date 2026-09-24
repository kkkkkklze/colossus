package com.klze.colossus.fight;

/**
 * 人数缩放策略。研究结论：成熟工程全都没有认真做缩放
 * （Cataclysm 只有全局配置乘区，首领崛起两个作品完全没做，唯一的实现出自 MCreator）——
 * 所以框架内置一条经过验证的曲线并开放替换。
 *
 * <p>默认曲线：{@code 1 + (sqrt(n) - 1) * share}（开方抑制，n=1 时恒为 1）。
 */
public interface ScalingStrategy {

    /** 关闭缩放。 */
    ScalingStrategy NONE = new ScalingStrategy() {
        @Override public double healthMultiplier(int players) { return 1.0; }
        @Override public double damageMultiplier(int players) { return 1.0; }
    };

    /** 默认 sqrt 曲线（dumbcatmod 验证过形态，share 系数可配）。 */
    static ScalingStrategy sqrt(double share) {
        return new ScalingStrategy() {
            @Override public double healthMultiplier(int players) {
                return players <= 1 ? 1.0 : 1.0 + (Math.sqrt(players) - 1.0) * share;
            }
            @Override public double damageMultiplier(int players) {
                return players <= 1 ? 1.0 : 1.0 + (Math.sqrt(players) - 1.0) * share * 0.5;
            }
        };
    }

    double healthMultiplier(int players);

    double damageMultiplier(int players);
}
