package com.summy.reliquary.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * 纯视觉状态：提供 HUD 图标、倒计时和原版状态粒子，不附带任何属性或 tick 行为。
 */
public final class VisualMarkerMobEffect extends MobEffect {
	public VisualMarkerMobEffect(MobEffectCategory category, int color) {
		super(category, color);
	}
}
