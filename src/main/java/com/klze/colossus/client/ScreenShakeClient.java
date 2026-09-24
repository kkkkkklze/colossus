package com.klze.colossus.client;

import com.klze.colossus.fx.ScreenShakeCue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 震屏状态机（客户端唯一事实源；服务端只发 cue）：
 * 每个活跃震屏 = 世界中心 + 功率 + 半径 + 倒计时。采样公式（ES 形）：
 * {@code fade = 1 - age/duration}（线性淡出）× {@code 距离衰减}，
 * pitch/yaw/roll 用不同频率正弦错相，避免"整屏平移"的廉价感。
 */
public final class ScreenShakeClient {

    private static final class Active {
        final Vec3 center;
        final float power;
        final float radius;
        int durationTicks;
        int age;

        Active(Vec3 center, float power, int durationTicks, float radius) {
            this.center = center; this.power = power;
            this.durationTicks = Math.max(1, durationTicks); this.radius = radius;
        }
    }

    private static final List<Active> ACTIVE = new ArrayList<>();

    /** cue 播放器入口（主线程）。 */
    public static void play(ClientLevel level, Vec3 pos, ScreenShakeCue.Data data) {
        if (data.power() <= 0f || data.durationTicks() <= 0) return;
        ACTIVE.add(new Active(pos, data.power(), data.durationTicks(), data.radius()));
    }

    /** 每客户端 tick 推进（挂在 ColossusClientHooks 的 ClientTickEvent）。 */
    public static void tick() {
        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            Active a = ACTIVE.get(i);
            a.age++;
            if (a.age >= a.durationTicks) ACTIVE.remove(i);
        }
    }

    /** 采样本帧相机叠加量 [pitch, yaw, roll]（度）。 */
    public static float[] sample(Vec3 eyePos, float partialTick) {
        float pitch = 0f, yaw = 0f, roll = 0f;
        for (Active a : ACTIVE) {
            float fade = 1.0f - (a.age + partialTick) / a.durationTicks;
            fade = Mth.clamp(fade, 0f, 1f);
            if (a.radius > 0) {
                double dist = eyePos.distanceTo(a.center);
                fade *= Mth.clamp(1.0 - dist / a.radius, 0f, 1f);
            }
            if (fade <= 0f) continue;
            float t = a.age + partialTick;
            pitch += a.power * fade * Mth.sin(t * 1.9f) * 0.5f;
            yaw   += a.power * fade * Mth.sin(t * 1.3f + 1.7f) * 0.5f;
            roll  += a.power * fade * 0.35f * Mth.sin(t * 0.9f + 0.5f);
        }
        return new float[]{pitch, yaw, roll};
    }

    public static void clear() { ACTIVE.clear(); }

    private ScreenShakeClient() {}
}
