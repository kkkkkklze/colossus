package com.klze.colossus.testboss;

import com.klze.colossus.client.ColossusClient;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/** 示范成员渲染器（贴图借原版羊，与 {@link ExampleColossusRenderer} 同一占位口径）。 */
public class ExampleSentryRenderer extends MobRenderer<ExampleSentry, ExampleSentryModel> {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/sheep/sheep.png");

    public ExampleSentryRenderer(EntityRendererProvider.Context context) {
        super(context, new ExampleSentryModel(context.bakeLayer(ColossusClient.EXAMPLE_SENTRY_LAYER)), 0.35f);
    }

    @Override
    public ResourceLocation getTextureLocation(ExampleSentry entity) {
        return TEXTURE;
    }
}
