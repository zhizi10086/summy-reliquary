package com.summy.reliquary.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.summy.reliquary.entity.ThrownSpear;
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
 * 幻影矛的渲染器（1.7.8）：照原版 {@code ThrownTridentRenderer} 的朝向处理，
 * 但画的是**物品模型**（我们只有 2D 贴图，没有 3D 几何）——
 * Y 轴跟着实体朝向 −90°、Z 轴跟着俯仰 +90°，让长杆顺着飞行方向。
 *
 * <p>1.7.9 修订（两次实测才定下来）：
 * <ul>
 *     <li>补偿写成 **−45°** → 掷出的矛"竖直、矛头朝上"（矛身与飞行方向差 90°）；</li>
 *     <li>改成 **+45°** → 矛身顺着飞行方向了，但**前后反了**（矛头朝后）；</li>
 *     <li>最终 **+225° = +45°（对齐飞行方向）+ 180°（头尾翻转）** → 顺着飞行方向、矛头朝前。
 *     加 180° 的好处是**保持矛身所在的那条线不变**、只交换两端（贴图核验：矛头在画布左上）。</li>
 * </ul>
 */
public class ThrownSpearRenderer extends EntityRenderer<ThrownSpear> {
	/**
	 * 平面内补偿角（度）：把"斜着画的"矛身转到**顺着飞行方向、矛头朝前**。
	 *
	 * <p>由两次实测确定（见类注释）：**45° 对齐飞行方向、再 +180° 换头尾**，两者缺一都会出问题 ——
	 * `−45°` 让矛身垂直于飞行方向（"竖直、矛头朝上"），只写 `45°` 则"顺着飞但前后反了"。
	 * 抽成常量是为了让自检直接断言这个数（改错符号 / 漏掉翻转都会报红）。
	 */
	public static final float Z_TILT_DEGREES = 225.0F;

	/** 物品渲染不走实体贴图，这里只是接口要求（取一张肯定存在的原版贴图占位） */
	private static final ResourceLocation PLACEHOLDER =
			new ResourceLocation("minecraft", "textures/particle/flame.png");

	public ThrownSpearRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public void render(ThrownSpear entity, float entityYaw, float partialTicks, PoseStack poseStack,
			MultiBufferSource buffer, int packedLight) {
		ItemStack stack = entity.getDisplayStack();
		if (!stack.isEmpty()) {
			poseStack.pushPose();
			poseStack.mulPose(Axis.YP.rotationDegrees(
					Mth.lerp(partialTicks, entity.yRotO, entity.getYRot()) - 90.0F));
			poseStack.mulPose(Axis.ZP.rotationDegrees(
					Mth.lerp(partialTicks, entity.xRotO, entity.getXRot()) + 90.0F));
			// 贴图是斜向的：+45° 对齐飞行方向、+180° 换头尾 → 总补偿 225°
			poseStack.mulPose(Axis.ZP.rotationDegrees(Z_TILT_DEGREES));
			Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED,
					packedLight, OverlayTexture.NO_OVERLAY, poseStack, buffer, entity.level(), entity.getId());
			poseStack.popPose();
		}
		super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
	}

	@Override
	public ResourceLocation getTextureLocation(ThrownSpear entity) {
		return PLACEHOLDER;
	}
}
