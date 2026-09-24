package com.klze.colossus.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;

/**
 * "ring" 样式：贴地线框圈（v6 取证的 LINES 档——{@code RenderType.LINES} 自带
 * VIEW_OFFSET_Z_LAYERING+TRANSLUCENT，天然免 z-fighting；y 再抬 0.04 双保险）。
 * 表现随 progress 变奏：半径微收 + 亮度爬升，结算前一眼可读。
 */
public final class RingZoneRenderer implements ZoneRenderer {

    @Override
    public String id() { return "ring"; }

    @Override
    public void render(ZoneView v, PoseStack pose, MultiBufferSource.BufferSource buffers) {
        VertexConsumer buf = buffers.getBuffer(RenderType.LINES);
        PoseStack.Pose p = pose.last();
        float radius = (float) v.radiusXZ() * Mth.lerp(v.progress(), 1.06f, 0.94f); // 微微收口
        int segs = Math.max(24, (int) (2 * Math.PI * radius * 3));
        int alpha = (int) ((0.45f + 0.5f * v.progress()) * 255f);
        int r8 = (v.colorRGB() >> 16) & 0xFF;
        int g8 = (v.colorRGB() >> 8) & 0xFF;
        int b8 = v.colorRGB() & 0xFF;
        float y = (float) v.center().y + 0.04f;
        for (int i = 0; i < segs; i++) {
            double a0 = (double) i / segs * Math.PI * 2;
            double a1 = (double) (i + 1) / segs * Math.PI * 2;
            buf.vertex(p.pose(),
                    (float) (v.center().x + Math.cos(a0) * radius), y,
                    (float) (v.center().z + Math.sin(a0) * radius))
                    .color(r8, g8, b8, alpha).endVertex();
            buf.vertex(p.pose(),
                    (float) (v.center().x + Math.cos(a1) * radius), y,
                    (float) (v.center().z + Math.sin(a1) * radius))
                    .color(r8, g8, b8, alpha).endVertex();
        }
    }
}
