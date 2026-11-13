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
 * 长矛第三人称手持物品矩阵（1.8.8）：蓄力前移、戳刺枢轴旋转与局部推进。
 *
 * <p>NeoForge 没有这一层的现成扩展点，只能 Mixin；仅对两把长矛生效。
 */
@Mixin(ItemInHandLayer.class)
public abstract class HeldItemFeatureRendererMixin {
	/**
	 * 左键攻击时在手臂矩阵应用前抬高当前手持物。
	 *
	 * <p>此处位于 {@code renderArmWithItem} 自身的 pushPose 之后，方法结束时会自动恢复，
	 * 因此不会把位移泄漏到另一只手。实体渲染矩阵已翻转 Y，负值才表示屏幕上移。
	 */
	@Inject(method = "renderArmWithItem",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V",
					shift = At.Shift.AFTER))
	private void summyReliquary$raiseAttackingSpear(LivingEntity entity, ItemStack stack,
			ItemDisplayContext context, HumanoidArm arm, PoseStack poseStack,
			MultiBufferSource buffer, int light, CallbackInfo ci) {
		if (entity.getMainArm() != arm
				|| !(stack.getItem() instanceof SpearItem)
				|| entity.isUsingItem()) {
			return;
		}
		float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
		if (entity.getAttackAnim(partialTick) > 0.0F) {
			poseStack.translate(0.0F, SpearAnimation.THIRD_PERSON_ATTACK_Y_OFFSET, 0.0F);
		}
	}

	@Inject(method = "renderArmWithItem",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
	private void summyReliquary$spearItemTransform(LivingEntity entity, ItemStack stack, ItemDisplayContext context,
			HumanoidArm arm, PoseStack poseStack, MultiBufferSource buffer, int light, CallbackInfo ci) {
		if (entity.getMainArm() != arm || !(stack.getItem() instanceof SpearItem)) {
			return;
		}
		float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
		float attack = entity.getAttackAnim(partialTick);
		if (attack <= 0.0F) {
			return;
		}
		// 与 Backported-Spears 1.21.1 一致：围绕手腕附近枢轴旋转，再沿局部 Y 轴推进。
		float rotation = SpearAnimation.thirdPersonRotationDegrees(attack);
		float advance = SpearAnimation.thirdPersonAdvance(attack);
		poseStack.translate(0.0F, -0.125F, 0.125F);
		poseStack.mulPose(Axis.XN.rotationDegrees(rotation));
		poseStack.translate(0.0F, 0.125F, -0.125F);
		poseStack.translate(0.0F, advance, 0.0F);
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
