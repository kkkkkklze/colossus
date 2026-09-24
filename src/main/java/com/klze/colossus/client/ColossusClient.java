package com.klze.colossus.client;

import com.klze.colossus.Colossus;
import com.klze.colossus.ColossusRegistries;
import com.klze.colossus.testboss.ExampleColossus;
import com.klze.colossus.testboss.ExampleColossusModel;
import com.klze.colossus.testboss.ExampleColossusRenderer;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端装配：模型层 + 渲染器注册（MOD bus），断线清缓存（GAME bus 见 ColossusClientHooks）。
 */
@Mod.EventBusSubscriber(modid = Colossus.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ColossusClient {

    public static final ModelLayerLocation EXAMPLE_COLOSSUS_LAYER =
            new ModelLayerLocation(new ResourceLocation(Colossus.MODID, "example_colossus"), "main");

    public static final ModelLayerLocation EXAMPLE_SENTRY_LAYER =
            new ModelLayerLocation(new ResourceLocation(Colossus.MODID, "example_sentry"), "main");

    @SubscribeEvent
    public static void onRegisterLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(EXAMPLE_COLOSSUS_LAYER, ExampleColossusModel::createBodyLayer);
        event.registerLayerDefinition(EXAMPLE_SENTRY_LAYER,
                com.klze.colossus.testboss.ExampleSentryModel::createBodyLayer);
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ColossusRegistries.EXAMPLE_COLOSSUS.get(), ExampleColossusRenderer::new);
        event.registerEntityRenderer(ColossusRegistries.EXAMPLE_SENTRY.get(),
                com.klze.colossus.testboss.ExampleSentryRenderer::new);
    }

    private ColossusClient() {}
}
