package com.summy.reliquary.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.summy.reliquary.entity.ThrownRazor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * 金刀片投掷物的渲染器（1.7.10）：与幻影矛同一套朝向链（Y 轴跟实体朝向 −90°、Z 轴跟俯仰 +90°），
 * 但**不加平面内补偿** —— 金刀片的贴图主轴实测是竖直的（不透明像素 bbox (8,5)-(22,26)、主轴 90°），
 * 贴图自带的"上"就是刀身的长度方向，所以原版这套旋转已经把它摆正。
 */
public class ThrownRazorRenderer extends EntityRenderer<ThrownRazor> {
	/** 物品渲染不走实体贴图，这里只是接口要求 */
	private static final ResourceLocation PLACEHOLDER =
			new ResourceLocation("minecraft", "textures/particle/flame.png");

	public ThrownRazorRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public void render(ThrownRazor entity, float entityYaw, float partialTicks, PoseStack poseStack,
			MultiBufferSource buffer, int packedLight) {
		ItemStack stack = entity.getDisplayStack();
		if (!stack.isEmpty()) {
			poseStack.pushPose();
			poseStack.mulPose(Axis.YP.rotationDegrees(
					Mth.lerp(partialTicks, entity.yRotO, entity.getYRot()) - 90.0F));
			poseStack.mulPose(Axis.ZP.rotationDegrees(
					Mth.lerp(partialTicks, entity.xRotO, entity.getXRot()) + 90.0F));
			Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED,
					packedLight, OverlayTexture.NO_OVERLAY, poseStack, buffer, entity.level(), entity.getId());
			poseStack.popPose();
		}
		super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
	}

	@Override
	public ResourceLocation getTextureLocation(ThrownRazor entity) {
		return PLACEHOLDER;
	}
}
