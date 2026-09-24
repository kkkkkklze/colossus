package com.klze.colossus.entity.part;

/**
 * 部件姿态值（记录型数据；仅引纯数学 Mth——无头测试安全，同 TableSampler 口径）
 * 一条"部件在一个状态下的完整姿态"。
 * 泛化自 TF {@code HydraHeadContainer} 的四张 {@code Map<State,Float>}
 * （neckLength/xRot/yRot/mouthOpen 各自查表 → 一个 record 打包）。
 *
 * @param length    伸展长度（脖子/触手/脊椎段的可见长度，格）
 * @param xRot      俯仰（度，正=下俯）
 * @param yRot      偏航（度，相对身体）
 * @param mouthOpen [0,1] 张合度（弱点姿态门的通用化：任何"部件相位标量"）
 */
public record RigPose(float length, float xRot, float yRot, float mouthOpen) {

    public static final RigPose ZERO = new RigPose(0f, 0f, 0f, 0f);

    /** 线性插值（t∈[0,1] 钳位）。 */
    public RigPose lerp(RigPose to, float t) {
        float c = net.minecraft.util.Mth.clamp(t, 0f, 1f);
        return new RigPose(
                net.minecraft.util.Mth.lerp(c, length, to.length),
                net.minecraft.util.Mth.rotLerp(c, xRot, shortestTo(to.xRot, xRot)),
                net.minecraft.util.Mth.rotLerp(c, yRot, shortestTo(to.yRot, yRot)),
                net.minecraft.util.Mth.lerp(c, mouthOpen, to.mouthOpen));
    }

    /** 把 target 折算到与 from 相邻的 ±180° 等价角（防"绕远路"）。 */
    public static float shortestTo(float target, float from) {
        return from + PartRig.wrapDelta(target - from);
    }
}
