package com.summy.reliquary.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.summy.reliquary.client.SpearAnimation;
import com.summy.reliquary.item.SpearItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 长矛第三人称手持物品矩阵（1.8.8）：蓄力前移、戳刺前突。
 *
 * <p>Forge 没有这一层的扩展点，只能 Mixin；仅对两把长矛生效。
 */
@Mixin(ItemInHandLayer.class)
public abstract class HeldItemFeatureRendererMixin {
	@Inject(method = "renderArmWithItem",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
	private void summyReliquary$spearItemTransform(LivingEntity entity, ItemStack stack, ItemDisplayContext context,
			HumanoidArm arm, PoseStack poseStack, MultiBufferSource buffer, int light, CallbackInfo ci) {
		if (entity.getMainArm() != arm || !(stack.getItem() instanceof SpearItem)) {
			return;
		}
		float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
		// 戳刺：物品随三段曲线前突
		SpearAnimation.Stab stab = SpearAnimation.stab(entity.getAttackAnim(partialTick));
		poseStack.translate(0.0F, -0.125F, 0.125F);
		poseStack.mulPose(Axis.XN.rotationDegrees(70.0F * (stab.h() - stab.j())));
		poseStack.translate(0.0F, 0.125F, -0.125F);
	}

	/**
	 * 实体空间微调（1.8.8）：在整层渲染入口平移，Y 是世界上下、-Z 是玩家前方。
	 *
	 * <p>{@code renderArmWithItem} 里拿到的 poseStack 已经带手臂变换，Y/Z 会被
	 * THROW_TRIDENT 的手腕方向带偏，所以改在 {@code render} 的 HEAD 调整。
	 */
	@Inject(method = "render", at = @At("HEAD"))
	private void summyReliquary$layerOffset(PoseStack poseStack, MultiBufferSource buffer, int light,
			LivingEntity entity, float limbSwing, float limbSwingAmount, float partialTick,
			float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
		if (!(entity.getMainHandItem().getItem() instanceof SpearItem)) {
			return;
		}
		if (entity.isUsingItem() && entity.getUseItem().getItem() instanceof SpearItem) {
			// 蓄力：下移 + 前移
			poseStack.translate(0.0, -0.1, -0.3);
		} else {
			// 常态：下移
			poseStack.translate(0.0, 0.3, 0.0);
		}
	}
}
