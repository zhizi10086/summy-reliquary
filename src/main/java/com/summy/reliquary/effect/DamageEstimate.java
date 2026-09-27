package com.summy.reliquary.effect;

import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * 「这一击会打到生命值多少点」的估算（1.6.4）。
 *
 * <p>为什么需要它：神性死亡拦截与灵魂免死要在**命中之前**决定要不要整击拦下，而
 * {@code LivingDamageEvent} 在 Kilt 上的金额既不可靠（发两次、其中一次写回无效）。
 * 这里按原版的减免顺序自己算一遍——**护甲 → 抗性效果 / 保护附魔 → 减去原版吸收**，
 * 全程只用公开 API（{@link CombatRules} / {@link EnchantmentHelper#getDamageProtection}）。
 *
 * <p>误差只可能来自"其它模组在自己的事件里追加的额外减伤"——那种情况我们估算得偏大，
 * 结果是"本来不致命的一击也可能被拦下"，属于偏保守的一方。
 */
public final class DamageEstimate {
	private DamageEstimate() {
	}

	/**
	 * 估算这一击最终会打到**生命值**的部分。
	 *
	 * @param rawAmount        本次事件的原始金额（通常是"还没算护甲"的值）
	 * @param absorptionBefore 命中前的原版吸收值（黄血 / 护盾份额）
	 */
	public static float healthPart(LivingEntity entity, DamageSource source, float rawAmount,
			float absorptionBefore) {
		if (entity == null || source == null || rawAmount <= 0.0F) {
			return 0.0F;
		}
		float amount = rawAmount;
		// ① 护甲（原版 getDamageAfterArmorAbsorb 只多看一个 bypasses_armor 标签）
		if (!source.is(DamageTypeTags.BYPASSES_ARMOR)) {
			amount = CombatRules.getDamageAfterAbsorb(amount,
					(float) entity.getAttributeValue(Attributes.ARMOR),
					(float) entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
		}
		// ② 抗性效果 + 保护附魔（与原版 getDamageAfterMagicAbsorb 同序）
		if (!source.is(DamageTypeTags.BYPASSES_EFFECTS)) {
			if (!source.is(DamageTypeTags.BYPASSES_RESISTANCE)) {
				MobEffectInstance resistance = entity.getEffect(MobEffects.DAMAGE_RESISTANCE);
				if (resistance != null) {
					int reduction = (resistance.getAmplifier() + 1) * 5;
					int remaining = 25 - reduction;
					amount = Math.max(amount * remaining / 25.0F, 0.0F);
				}
			}
			if (amount > 0.0F && !source.is(DamageTypeTags.BYPASSES_ENCHANTMENTS)) {
				int protection = EnchantmentHelper.getDamageProtection(entity.getArmorSlots(), source);
				if (protection > 0) {
					amount = CombatRules.getDamageAfterMagicAbsorb(amount, protection);
				}
			}
		}
		// ③ 吸收值（黄血 / 其它模组护盾）先扛
		return Math.max(amount - Math.max(0.0F, absorptionBefore), 0.0F);
	}
}
