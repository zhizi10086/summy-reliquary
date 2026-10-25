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
 * 只在左键戳刺时替换 {@code setupAttackAnimation} 的挥臂。
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin<T extends LivingEntity> extends AgeableListModel<T> {
	@Shadow @Final public ModelPart rightArm;
	@Shadow @Final public ModelPart leftArm;
	@Shadow @Final public ModelPart body;
	@Shadow protected abstract ModelPart getArm(HumanoidArm arm);

	/** 左键戳刺：替换原版挥臂 */
	@Inject(method = "setupAttackAnimation(Lnet/minecraft/world/entity/LivingEntity;F)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;sin(F)F", ordinal = 3),
			cancellable = true)
	private void summyReliquary$stabArms(T entity, float ageInTicks, CallbackInfo ci) {
		if (!(entity.getMainHandItem().getItem() instanceof SpearItem)) {
			return;
		}
		SpearAnimation.Stab stab = SpearAnimation.stab(entity.isUsingItem() ? 1.0F : this.attackTime);
		HumanoidArm arm = entity.getMainArm();
		rightArm.yRot -= body.yRot;
		leftArm.yRot -= body.yRot;
		leftArm.xRot -= body.yRot;
		getArm(arm).xRot += (90.0F * stab.g() - 120.0F * stab.h() + 30.0F * stab.j()) * ((float) Math.PI / 180.0F);
		ci.cancel();
	}

}
