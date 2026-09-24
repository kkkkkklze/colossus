package com.klze.colossus.testboss;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

/**
 * 占位成员模型（8³ 方块浮游炮）——只为把 squad 成员这条路径跑通到可渲染，
 * 下游工程换成自己的模型/GeckoLib 部件即可。
 */
public class ExampleSentryModel extends HierarchicalModel<ExampleSentry> {

    private final ModelPart root;
    private final ModelPart orb;

    public ExampleSentryModel(ModelPart root) {
        this.root = root;
        this.orb = root.getChild("orb");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition head = mesh.getRoot();
        head.addOrReplaceChild("orb",
                CubeListBuilder.create().texOffs(0, 0)
                        .addBox(-4.0f, -8.0f, -4.0f, 8.0f, 8.0f, 8.0f, CubeDeformation.NONE),
                PartPose.offset(0.0f, 8.0f, 0.0f));
        return LayerDefinition.create(mesh, 32, 16);
    }

    @Override
    public ModelPart root() {
        return this.root;
    }

    /** 视觉＝f(同步旗标)：这里只读 age 做自旋与浮动，没有任何战斗逻辑。 */
    @Override
    public void setupAnim(ExampleSentry entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        this.orb.yRot = ageInTicks * 0.05f;
        this.orb.y = 8.0f + Mth.sin(ageInTicks * 0.1f) * 1.2f;
    }

    @Override
    public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int packedLight,
                               int packedOverlay, float red, float green, float blue, float alpha) {
        this.orb.render(pose, buffer, packedLight, packedOverlay, red, green, blue, alpha);
    }
}
