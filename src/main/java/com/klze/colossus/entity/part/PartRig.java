package com.klze.colossus.entity.part;

import java.util.HashMap;
import java.util.Map;

/**
 * 部件姿态表（Hydra 状态机表 + clampedLerp 的泛化；纯数据可无头单测）。
 *
 * <p>用法：作者为部件声明"状态 id → 姿态"，运行时给 from/to 状态与进度 t
 * 即得插值姿态；部件的世界位置由消费方（部件实体 tick）用
 * {@code length/xRot/yRot} 沿本体锚点解算（TF 直连公式），或用
 * {@code RigPose + TableSampler} 混合。GL4 无服务端动画时钟（v4 取证）——
 * 本表就是服务端世界侧的动画替身。
 */
public final class PartRig {

    private final Map<Integer, RigPose> poses = new HashMap<>();

    /** 声明一个状态的姿态（重复 id 直接抛——注册期报错优于运行期错位）。 */
    public PartRig pose(int stateId, RigPose pose) {
        if (poses.putIfAbsent(stateId, pose) != null) {
            throw new IllegalStateException("Duplicate rig state: " + stateId);
        }
        return this;
    }

    public RigPose get(int stateId) {
        return poses.getOrDefault(stateId, RigPose.ZERO);
    }

    /** from→to 的插值姿态（t∈[0,1]）。 */
    public RigPose sample(int fromState, int toState, float t) {
        return get(fromState).lerp(get(toState), t);
    }

    /**
     * 限速转向（TF updateRotation 语义）：cur 每步最多移动 maxStep 度，走短路。
     */
    public static float approach(float cur, float target, float maxStep) {
        float delta = wrapDelta(target - cur);
        if (Math.abs(delta) <= maxStep) return unwrap(target);
        return cur + Math.signum(delta) * maxStep;
    }

    /** 折到 (-180,180]。 */
    public static float wrapDelta(float deg) {
        float d = deg % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    private static float unwrap(float target) {
        return ((target % 360f) + 360f) % 360f;
    }
}
