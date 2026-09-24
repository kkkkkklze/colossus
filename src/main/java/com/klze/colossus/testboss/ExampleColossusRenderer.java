package com.klze.colossus.testboss;

import com.klze.colossus.client.ColossusClient;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * 占位渲染器：贴图暂借原版羊（示范管线通即可）。
 * 阶段视觉变化放在 {@link ExampleColossusModel#setupAnim}（姿态）与
 * 客户端事件订阅（粒子，v0.2 接 {@code onBossVisualEvent}），
 * 遵循"视觉 = f(同步旗标)、渲染层零战斗逻辑"原则。
 */
public class ExampleColossusRenderer extends MobRenderer<ExampleColossus, ExampleColossusModel> {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/sheep/sheep.png");

    public ExampleColossusRenderer(EntityRendererProvider.Context context) {
        super(context, new ExampleColossusModel(context.bakeLayer(ColossusClient.EXAMPLE_COLOSSUS_LAYER)), 0.8f);
    }

    @Override
    public ResourceLocation getTextureLocation(ExampleColossus entity) {
        return TEXTURE;
    }
}
