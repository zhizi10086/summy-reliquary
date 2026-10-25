package com.summy.reliquary.mixin;

import com.summy.reliquary.client.SpearAnimation;
import com.summy.reliquary.item.SpearItem;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 长矛挥击时长（1.8.8）：让左键戳刺动画严格跟随当前攻速。
 *
 * <p>原版 {@code getCurrentSwingDuration} 固定 6 tick（受急迫/疲劳修正），与攻击冷却无关；
 * 长矛需要把挥击时长改成 {@code 20 / 攻速}，动画才会和冷却同步。副手与非长矛走原版。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "getCurrentSwingDuration", at = @At("HEAD"), cancellable = true)
	private void summyReliquary$spearSwingDuration(CallbackInfoReturnable<Integer> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!(self.getMainHandItem().getItem() instanceof SpearItem)) {
			return;
		}
		float attackSpeed = (float) self.getAttributeValue(Attributes.ATTACK_SPEED);
		cir.setReturnValue(SpearAnimation.swingDuration(attackSpeed));
	}
}
