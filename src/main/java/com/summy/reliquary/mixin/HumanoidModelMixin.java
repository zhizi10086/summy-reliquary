package com.summy.reliquary.mixin;

import com.summy.reliquary.client.SpearAnimation;
import com.summy.reliquary.item.SpearItem;
import net.minecraft.client.model.AgeableListModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 长矛第三人称挥臂（1.8.8）。
 *
 * <p>常态与蓄力完全交还原版（{@code ArmPose.ITEM} / {@code THROW_TRIDENT}），
 * 只在左键戳刺时替换手臂基准姿势和 {@code setupAttackAnimation} 的挥臂。
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin<T extends LivingEntity> extends AgeableListModel<T> {
	@Shadow @Final public ModelPart rightArm;
	@Shadow @Final public ModelPart leftArm;
	@Shadow @Final public ModelPart body;
	@Shadow @Final public ModelPart head;
	@Shadow protected abstract ModelPart getArm(HumanoidArm arm);

	/** 攻击前设置长矛手臂的基准姿势，避免原版挥击姿势把矛身带斜。 */
	@Inject(method = "poseRightArm", at = @At("HEAD"), cancellable = true)
	private void summyReliquary$poseRightSpearArm(T entity, CallbackInfo ci) {
		if (summyReliquary$applySpearAttackPose(entity, HumanoidArm.RIGHT, rightArm)) {
			ci.cancel();
		}
	}

	@Inject(method = "poseLeftArm", at = @At("HEAD"), cancellable = true)
	private void summyReliquary$poseLeftSpearArm(T entity, CallbackInfo ci) {
		if (summyReliquary$applySpearAttackPose(entity, HumanoidArm.LEFT, leftArm)) {
			ci.cancel();
		}
	}

	private boolean summyReliquary$applySpearAttackPose(T entity, HumanoidArm arm, ModelPart armPart) {
		if (entity.getMainArm() != arm
				|| !(entity.getMainHandItem().getItem() instanceof SpearItem)
				|| entity.isUsingItem()
				|| this.attackTime <= 0.0F) {
			return false;
		}
		armPart.yRot = -0.1F * head.yRot;
		armPart.xRot = -((float) Math.PI / 2.0F) + head.xRot + 0.8F;
		if (entity.isFallFlying() || entity.getSwimAmount(1.0F) > 0.0F) {
			armPart.xRot -= 0.9599311F;
		}
		return true;
	}

	/** 左键戳刺：替换原版挥臂 */
	@Inject(method = "setupAttackAnimation(Lnet/minecraft/world/entity/LivingEntity;F)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;sin(F)F", ordinal = 3),
			cancellable = true)
	private void summyReliquary$stabArms(T entity, float ageInTicks, CallbackInfo ci) {
		if (entity.isUsingItem() || !(entity.getMainHandItem().getItem() instanceof SpearItem)) {
			return;
		}
		SpearAnimation.Stab stab = SpearAnimation.stab(this.attackTime);
		HumanoidArm arm = entity.getMainArm();
		rightArm.yRot -= body.yRot;
		leftArm.yRot -= body.yRot;
		leftArm.xRot -= body.yRot;
		getArm(arm).xRot += (90.0F * stab.g() - 120.0F * stab.h() + 30.0F * stab.j()) * ((float) Math.PI / 180.0F);
		ci.cancel();
	}

}
