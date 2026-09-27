package com.summy.reliquary.effect;

import com.summy.reliquary.config.ReliquaryConfig;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

/**
 * 「恐惧」（1.6.5）：玄秘魔眼给"被注视的那一具生物"施加的减益载体。
 *
 * <p>它本身不带属性，只显示图标（图标＝魔眼贴图）与倒计时；每秒把随包的四个减益写给目标：
 * **黑暗、挖掘疲劳 III、缓慢 VI、虚弱 X**（等级与开关都在 `[occult_eye]` 里可配）。
 * 发光不在这里 —— 它由注视方（{@code SpiritAltarSet} 的发光簿记）单独维护，所以"圣心免疫恐惧"
 * 不会连带免疫发光。
 */
public class FearEffect extends MobEffect {
	/** 图标色调（暗红，与恶魔线一致） */
	private static final int COLOR = 0x8B0000;

	public FearEffect() {
		super(MobEffectCategory.HARMFUL, COLOR);
	}

	@Override
	public void applyEffectTick(LivingEntity entity, int amplifier) {
		if (!ReliquaryConfig.enableOccultEye()) {
			return;
		}
		int duration = Math.max(20, ReliquaryConfig.fearTicks());
		// 1.6.6：这里要的是原版「黑暗」（DARKNESS）—— 1.6.5 误写成了「失明」（BLINDNESS）
		if (ReliquaryConfig.fearDarkness()) {
			entity.addEffect(new MobEffectInstance(MobEffects.DARKNESS, duration, 0, false, false, false));
		}
		entity.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, duration,
				ReliquaryConfig.fearMiningFatigueAmplifier(), false, false, false));
		entity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, duration,
				ReliquaryConfig.fearSlownessAmplifier(), false, false, false));
		entity.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, duration,
				ReliquaryConfig.fearWeaknessAmplifier(), false, false, false));
	}

	/** 每秒结算一次（与刷新周期一致） */
	@Override
	public boolean isDurationEffectTick(int duration, int amplifier) {
		return duration % 20 == 0;
	}
}
