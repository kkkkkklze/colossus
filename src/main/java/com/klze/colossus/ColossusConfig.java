package com.klze.colossus;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 框架全局配置（COMMON，服务端同步）。数值默认 = "研究员模式"：
 * 曲线来自研究报告（sqrt 抑制 + 0.5 分摊），全局乘区留给整合包作者调平衡。
 */
public final class ColossusConfig {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLE_SCALING;
    public static final ForgeConfigSpec.DoubleValue SCALING_SHARE;
    public static final ForgeConfigSpec.DoubleValue GLOBAL_HEALTH_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue GLOBAL_DAMAGE_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue MAX_DAMAGE_PER_HIT;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("boss_engine");
        ENABLE_SCALING = b.comment("是否启用按在场玩家数缩放 Boss 属性")
                .define("enable_player_scaling", true);
        SCALING_SHARE = b.comment("sqrt 缩放分摊系数：mult = 1 + (sqrt(n)-1) * share")
                .defineInRange("scaling_share", 0.5, 0.0, 2.0);
        GLOBAL_HEALTH_MULTIPLIER = b.comment("全局 Boss 血量乘区（整合包平衡用）")
                .defineInRange("global_health_multiplier", 1.0, 0.1, 100.0);
        GLOBAL_DAMAGE_MULTIPLIER = b.comment("全局 Boss 伤害乘区")
                .defineInRange("global_damage_multiplier", 1.0, 0.1, 100.0);
        MAX_DAMAGE_PER_HIT = b.comment("单次伤害上限（0 = 不限制；对齐 Cataclysm 的 DamageCap）")
                .defineInRange("max_damage_per_hit", 0.0, 0.0, 1000.0);
        b.pop();
        SPEC = b.build();
    }

    private ColossusConfig() {}
}
