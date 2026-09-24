package com.klze.colossus.gecko;

import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * 三个资源指向 addon 自己的 geo/animation/texture。
 * <b>盘上没有真资产</b>——本批只保证"适配器对 GL4 API 的用法能编译"，
 * 资产与运行期表现要靠真客户端验收（本工程验证口径是 headless，见 README）。
 */
public class ExampleGeoModel extends GeoModel<ExampleGeoColossus> {

    @Override
    public ResourceLocation getModelResource(ExampleGeoColossus animatable) {
        return ColossusGecko.res("geo/example_geo_colossus.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(ExampleGeoColossus animatable) {
        return ColossusGecko.res("textures/entity/example_geo_colossus.png");
    }

    @Override
    public ResourceLocation getAnimationResource(ExampleGeoColossus animatable) {
        return ColossusGecko.res("animations/example_geo_colossus.animation.json");
    }
}
