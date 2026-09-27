package com.summy.reliquary.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * 「启示之光」（1.5.6）：**纯标记** buff。
 *
 * <p>所有启示伤害（启示之光柱 / 救恩领域）都会给受击者挂上它；带它死亡的生物（非玩家、非训练人偶）
 * 有机会掉「心之碎片」。它本身不带任何属性效果，只显示图标与倒计时（图标用伯列恒之星的贴图）。
 */
public class RevelationLightEffect extends MobEffect {
	/** 图标色调（淡金） */
	private static final int COLOR = 0xFFE4B5;

	public RevelationLightEffect() {
		super(MobEffectCategory.NEUTRAL, COLOR);
	}
}
