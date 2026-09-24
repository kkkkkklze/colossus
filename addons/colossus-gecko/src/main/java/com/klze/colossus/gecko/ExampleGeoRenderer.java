package com.klze.colossus.gecko;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * GL4 在 1.20.1 的渲染注册面只有 {@code EntityRenderersEvent.RegisterRenderers}——
 * <b>没有</b> {@code RegisterModelLayerEvent}、也没有 {@code GeoModelRegistry.registerModel}
 * （那是 GL5 形态，v9 取证实测 1.20.1 分支里不存在）。模型就是 ctor 里 new 出来。
 */
public class ExampleGeoRenderer extends GeoEntityRenderer<ExampleGeoColossus> {

    public ExampleGeoRenderer(EntityRendererProvider.Context context) {
        super(context, new ExampleGeoModel());
    }
}
