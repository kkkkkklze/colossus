package com.klze.colossus;

import com.klze.colossus.network.ColossusPackets;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Colossus —— Forge 1.20.1 BOSS 战框架主入口。
 *
 * <p>定位：不面向玩家的内容 Mod，而是面向 Boss 作者的引擎底座。
 * 写一个 Boss = 1 个实体类 + 1 份招式表 + 1 个渲染器（见 testboss 包活文档）。
 */
@Mod(Colossus.MODID)
public class Colossus {

    public static final String MODID = "colossus";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static ResourceLocation res(String path) {
        return new ResourceLocation(MODID, path);
    }

    public Colossus() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ColossusRegistries.ENTITY_TYPES.register(bus);
        ColossusRegistries.ITEMS.register(bus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ColossusConfig.SPEC, "colossus-common.toml");
        bus.addListener(this::onCommonSetup);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ColossusPackets.init();
            com.klze.colossus.fx.ScreenShakeCue.register();
        });
    }
}
