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
 * 占位方块人模型（导出自 Blockbench 形状的极简版）——只证明管线通，
 * 下游工程换成自己的 GeckoLib/vanilla codegen 模型即可。
 *
 * <p>动画示范框架的同步契约用法：动画状态 = f(entityData)，
 * 读 {@code attackProgress()/attackTick()} 与 {@code isDeathPending()/deathTick()}，
 * 渲染层零战斗逻辑（Iron's Spells Layer=f(synched flag) 原则）。
 */
public class ExampleColossusModel extends HierarchicalModel<ExampleColossus> {

    private final ModelPart root;
    private final ModelPart body;
    private final ModelPart head;
    private final ModelPart rightArm;
    private final ModelPart leftArm;
    private final ModelPart rightLeg;
    private final ModelPart leftLeg;

    public ExampleColossusModel(ModelPart root) {
        this.root = root;
        this.body = root.getChild("body");
        this.head = this.body.getChild("head");
        this.rightArm = this.body.getChild("right_arm");
        this.leftArm = this.body.getChild("left_arm");
        this.rightLeg = root.getChild("right_leg");
        this.leftLeg = root.getChild("left_leg");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition part = mesh.getRoot();

        PartDefinition body = part.addOrReplaceChild("body",
                CubeListBuilder.create().texOffs(0, 16)
                        .addBox(-6.0F, -14.0F, -4.0F, 12.0F, 16.0F, 8.0F, new CubeDeformation(0.0F)),
                PartPose.offset(0.0F, 22.0F, 0.0F));
        body.addOrReplaceChild("head",
                CubeListBuilder.create().texOffs(0, 0)
                        .addBox(-5.0F, -10.0F, -5.0F, 10.0F, 10.0F, 10.0F, new CubeDeformation(0.0F)),
                PartPose.offset(0.0F, -14.0F, 0.0F));
        body.addOrReplaceChild("right_arm",
                CubeListBuilder.create().texOffs(30, 0)
                        .addBox(-3.0F, -2.0F, -3.0F, 6.0F, 16.0F, 6.0F, new CubeDeformation(0.0F)),
                PartPose.offset(-8.0F, -12.0F, 0.0F));
        body.addOrReplaceChild("left_arm",
                CubeListBuilder.create().texOffs(30, 22)
                        .addBox(-3.0F, -2.0F, -3.0F, 6.0F, 16.0F, 6.0F, new CubeDeformation(0.0F)),
                PartPose.offset(8.0F, -12.0F, 0.0F));
        part.addOrReplaceChild("right_leg",
                CubeListBuilder.create().texOffs(48, 0)
                        .addBox(-4.0F, 0.0F, -4.0F, 8.0F, 18.0F, 8.0F, new CubeDeformation(0.0F)),
                PartPose.offset(-4.0F, 6.0F, 0.0F));
        part.addOrReplaceChild("left_leg",
                CubeListBuilder.create().texOffs(48, 26)
                        .addBox(-4.0F, 0.0F, -4.0F, 8.0F, 18.0F, 8.0F, new CubeDeformation(0.0F)),
                PartPose.offset(4.0F, 6.0F, 0.0F));
        return LayerDefinition.create(mesh, 96, 64);
    }

    @Override
    public ModelPart root() {
        return this.root;
    }

    @Override
    public void setupAnim(ExampleColossus boss, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        this.root().getAllParts().forEach(ModelPart::resetPose);

        // 死亡演出：前 20t 逐渐歪倒 + 沉地。
        // 客户端信号用同步的 deathTick（isDeathPending 是服务端 transient——审查 P1#2）
        int dying = Math.max(boss.deathTick(), boss.isDeathPending() ? 1 : 0);
        if (dying > 0) {
            float f = Mth.clamp(dying / 20.0f, 0.0f, 1.0f);
            this.body.xRot = f * 1.4f;
            this.body.y += f * 10.0f;
            return;
        }

        // 招式期：抬手砸击姿态（举到 50% 进度后快速挥下）
        // 只读同步数据（currentAttack 是服务端权威对象，客户端拿不到也不该拿：
        // datapack 招式表在多人客户端可能根本没有）
        if (boss.isAttacking() && boss.attackTick() > 0) {
            float p = boss.attackProgress();
            float swing = p < 0.5f ? (p / 0.5f) * -2.4f : (1.0f - (p - 0.5f) / 0.5f) * -2.4f;
            this.rightArm.xRot = swing;
            this.head.xRot = swing * 0.2f;
            return;
        }

        // 常规：四肢摆动 + 头部追踪
        this.rightArm.xRot = Mth.cos(limbSwing * 0.6662f) * 1.2f * limbSwingAmount;
        this.leftArm.xRot = Mth.cos(limbSwing * 0.6662f + Mth.PI) * 1.2f * limbSwingAmount;
        this.rightLeg.xRot = Mth.cos(limbSwing * 0.6662f + Mth.PI) * 1.0f * limbSwingAmount;
        this.leftLeg.xRot = Mth.cos(limbSwing * 0.6662f) * 1.0f * limbSwingAmount;
        this.head.yRot = netHeadYaw * Mth.DEG_TO_RAD;
        this.head.xRot = headPitch * Mth.DEG_TO_RAD;
    }

    @Override
    public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int packedLight,
                               int packedOverlay, float red, float green, float blue, float alpha) {
        this.body.render(pose, buffer, packedLight, packedOverlay, red, green, blue, alpha);
        this.rightLeg.render(pose, buffer, packedLight, packedOverlay, red, green, blue, alpha);
        this.leftLeg.render(pose, buffer, packedLight, packedOverlay, red, green, blue, alpha);
    }
}
