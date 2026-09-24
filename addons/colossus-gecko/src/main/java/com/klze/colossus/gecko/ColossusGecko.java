package com.klze.colossus.gecko;

import com.klze.colossus.Colossus;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import software.bernie.geckolib.GeckoLib;

/**
 * addon 入口。它依赖 colossus 与 geckolib，而 <b>colossus 本体不依赖任何东西</b>——
 * 这正是"框架零依赖 + 动画可选"的落地形：想接 GL 的人多装一个 jar，
 * 不装的人 classpath 里连 GL 的影子都没有。
 */
@Mod(ColossusGecko.MODID)
public final class ColossusGecko {

    public static final String MODID = "colossus_gecko";

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MODID);

    public static final RegistryObject<EntityType<ExampleGeoColossus>> EXAMPLE_GEO_COLOSSUS =
            ENTITY_TYPES.register("example_geo_colossus", () -> EntityType.Builder
                    .<ExampleGeoColossus>of(ExampleGeoColossus::new, MobCategory.MONSTER)
                    .sized(1.4f, 2.8f)
                    .fireImmune()
                    .build("example_geo_colossus"));

    public static ResourceLocation res(String path) {
        return new ResourceLocation(MODID, path);
    }

    public ColossusGecko() {
        GeckoLib.initialize();
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ENTITY_TYPES.register(bus);
        bus.register(this);
    }

    @SubscribeEvent
    void onAttributes(EntityAttributeCreationEvent event) {
        event.put(EXAMPLE_GEO_COLOSSUS.get(), ExampleGeoColossus.attributes().build());
    }

    /** 客户端装配（GL 渲染器只在这条线上碰得到）。 */
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Client {
        @SubscribeEvent
        public static void onRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(EXAMPLE_GEO_COLOSSUS.get(), ExampleGeoRenderer::new);
        }
    }
}
