package com.klze.colossus.anim;

import com.klze.colossus.entity.ColossusBossEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析轨迹采样器（Forsaken {@code getHandPos(animationTick)} 分段公式的泛化）。
 *
 * <p>GL4 没有服务端动画时钟（v4 取证定案）——服务端要"骨骼在哪"，
 * 正解是离线烘焙：作者从动画文件里抄几个关键帧位姿进表，运行时线性插值。
 * 零依赖、确定性、可进自检——也是第六批部件跟随（{@code PartAnchor} 的表形态后端）的地基。
 *
 * <p>坐标系：局部 offset 以 BOSS 脚底原点——{@code x=右向位移, y=上, z=前向位移}
 * （右向 = forward × up，与 yBodyRot 一起旋转进世界系）。
 */
public final class TableSampler {

    /** 一条关键位姿：招式逻辑帧 tick 时的局部坐标。 */
    public record Key(int tick, double x, double y, double z) {}

    private final Map<String, List<Key>> tables = new HashMap<>();

    /** 注册一条轨迹（同一锚点按 tick 升序自动维护）。 */
    public TableSampler track(String anchor, List<Key> keys) {
        List<Key> sorted = new ArrayList<>(keys);
        sorted.sort((a, b) -> Integer.compare(a.tick, b.tick));
        tables.put(anchor, sorted);
        return this;
    }

    public boolean has(String anchor) {
        return tables.containsKey(anchor);
    }

    /**
     * 局部系采样（纯数学、无 MC 状态——自检用这个）。
     * 表外区间钳到首/末键；两键之间线性插值。
     */
    public Vec3 sampleLocal(String anchor, int tick) {
        List<Key> keys = tables.get(anchor);
        if (keys == null || keys.isEmpty()) return Vec3.ZERO;
        if (tick <= keys.get(0).tick()) {
            Key k = keys.get(0);
            return new Vec3(k.x(), k.y(), k.z());
        }
        Key prev = keys.get(0);
        for (int i = 1; i < keys.size(); i++) {
            Key k = keys.get(i);
            if (tick <= k.tick()) {
                float t = (tick - prev.tick) / (float) (k.tick - prev.tick);
                return new Vec3(
                        Mth.lerp(t, prev.x, k.x),
                        Mth.lerp(t, prev.y, k.y),
                        Mth.lerp(t, prev.z, k.z));
            }
            prev = k;
        }
        Key last = keys.get(keys.size() - 1);
        return new Vec3(last.x(), last.y(), last.z());
    }

    /** 世界系采样：局部 offset 随 yBodyRot 旋转后叠到实体脚底。 */
    public Vec3 sample(ColossusBossEntity boss, String anchor, int tick) {
        return toWorld(boss, sampleLocal(anchor, tick));
    }

    /** 局部 → 世界（含朝向旋转）。也供部件跟随复用。 */
    public static Vec3 toWorld(ColossusBossEntity boss, Vec3 local) {
        double yawRad = Math.toRadians(boss.yBodyRot);
        double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);          // forward
        double rx = -Math.cos(yawRad), rz = -Math.sin(yawRad);         // right = forward × up
        return boss.position().add(
                local.z * fx + local.x * rx,
                local.y,
                local.z * fz + local.x * rz);
    }
}
