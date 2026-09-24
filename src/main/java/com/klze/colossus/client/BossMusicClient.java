package com.klze.colossus.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.registries.BuiltInRegistries;

import javax.annotation.Nullable;

/**
 * Boss 音乐客户端单例播放器（Cataclysm BossMusicPlayer 模式）：
 * 服务端只发幂等布尔（musicId,on/off），客户端持单一实例——
 * 同曲目不叠加、换 Boss 复用、off 即停。
 */
public final class BossMusicClient {

    @Nullable
    private static ResourceLocation playing = null;

    private BossMusicClient() {}

    public static void apply(String musicId, boolean on) {
        ResourceLocation id = ResourceLocation.tryParse(musicId);
        if (id == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        if (!on) {
            if (id.equals(playing)) {
                stopCurrent(mc);
                playing = null;
            }
            return;
        }
        if (id.equals(playing)) return; // 幂等：同曲不重起
        stopCurrent(mc);
        SoundEvent event = BuiltInRegistries.SOUND_EVENT.get(id);
        if (event == null) return; // 未注册的音乐静默跳过（示范期常态）
        mc.getSoundManager().play(SimpleSoundInstance.forMusic(event));
        playing = id;
    }

    private static void stopCurrent(Minecraft mc) {
        if (playing != null) {
            mc.getSoundManager().stop(playing, SoundSource.MUSIC);
        }
    }

    /** 进主界面/断线时清。 */
    public static void reset() {
        Minecraft mc = Minecraft.getInstance();
        stopCurrent(mc);
        playing = null;
    }
}
