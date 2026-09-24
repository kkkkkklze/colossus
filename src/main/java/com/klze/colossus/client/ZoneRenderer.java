package com.klze.colossus.client;

import net.minecraft.world.phys.Vec3;

/**
 * 危险区渲染样式（v6 取证的注册表形）：一种 visual id = 一个渲染器，
 * 第三方可 {@code TelegraphClient.registerStyle} 换皮/加皮。
 *
 * <p>原则不破：这里只做"视觉 = f(同步数据)"——progress 由 ticksLeft 推导，
 * 判定永远在服务端到期 AABB 结算，渲染器无权改战斗状态。
 */
public interface ZoneRenderer {

    /** 一帧的只读快照。progress：0=圈刚亮起 → 1=即将结算。 */
    record ZoneView(Vec3 center, double radiusXZ, int colorRGB, float progress) {}

    String id();

    /** 框架在每帧分发后对这个 RenderType 统一 endBatch（样式自己别管收尾）。 */
    default net.minecraft.client.renderer.RenderType batchType() {
        return net.minecraft.client.renderer.RenderType.LINES;
    }

    /**
     * 绘制一帧。poseStack 已平移到相机相对系（-camPos），实现只管给世界坐标。
     * 结束后由框架统一 endBatch；实现取 buffer 用 {@code buffers.getBuffer(RenderType.LINES)} 等即可。
     */
    void render(ZoneView view, com.mojang.blaze3d.vertex.PoseStack pose,
                net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers);
}
