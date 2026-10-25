package com.summy.reliquary.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.summy.reliquary.client.SpearAnimation;
import com.summy.reliquary.item.SpearItem;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 长矛第一人称动画（1.8.8）：只接管左键戳刺，蓄力交还原版三叉戟动画。
 *
 * <p>抑制普通挥击位移、抑制挥击换手、整体替换 {@code applyItemArmAttackTransform}；
 * 蓄力不再插入自定义变换，直接复用 {@code UseAnim.SPEAR} 的原版三叉戟动画。
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
	/** 普通挥击分支的 translate（ordinal 12）：长矛交给自定义曲线 */
	@WrapOperation(method = "renderArmWithItem",
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V", ordinal = 12))
	private void summyReliquary$suppressNormalSwing(PoseStack poseStack, float x, float y, float z,
			Operation<Void> original, @Local(argsOnly = true) ItemStack itemStack) {
		if (itemStack.getItem() instanceof SpearItem) {
			return;
		}
		original.call(poseStack, x, y, z);
	}

	/** 普通挥击分支的换手位移（ordinal 8）：挥击时归零，避免叠加到戳刺上 */
	@WrapOperation(method = "renderArmWithItem",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;applyItemArmTransform(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/entity/HumanoidArm;F)V",
					ordinal = 8))
	private void summyReliquary$suppressSwapAnimation(ItemInHandRenderer instance, PoseStack poseStack,
			HumanoidArm arm, float equipProgress, Operation<Void> original,
			@Local(argsOnly = true) AbstractClientPlayer player,
			@Local(argsOnly = true) ItemStack itemStack) {
		if (itemStack.getItem() instanceof SpearItem && player.attackAnim > 0.0F) {
			original.call(instance, poseStack, arm, 0.0F);
		} else {
			original.call(instance, poseStack, arm, equipProgress);
		}
	}

	/** 整体替换挥击偏移：长矛走三段戳刺曲线 */
	@WrapOperation(method = "renderArmWithItem",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;applyItemArmAttackTransform(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/entity/HumanoidArm;F)V"))
	private void summyReliquary$spearStab(ItemInHandRenderer instance, PoseStack poseStack, HumanoidArm arm,
			float swingProgress, Operation<Void> original, @Local(argsOnly = true) ItemStack itemStack) {
		if (!(itemStack.getItem() instanceof SpearItem)) {
			original.call(instance, poseStack, arm, swingProgress);
			return;
		}
		SpearAnimation.Stab stab = SpearAnimation.stab(swingProgress);
		boolean trident = itemStack.is(Items.TRIDENT);
		poseStack.translate(stab.j() * 0.1F * (stab.g() - stab.h()), -0.075F * (stab.g() - stab.j()),
				(trident ? -0.4F * (stab.g() - stab.j()) : 0.0F) + 0.65F * (stab.g() - stab.h()));
		poseStack.mulPose(Axis.XP.rotationDegrees(-(trident ? 50.0F : 70.0F) * (stab.g() - stab.j())));
		poseStack.translate(0.0, 0.0, -0.25 * (stab.j() - stab.h()));
	}

}
