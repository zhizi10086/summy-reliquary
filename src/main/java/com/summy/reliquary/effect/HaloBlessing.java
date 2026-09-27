package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

/**
 * 「光环」的启示祝福（1.7.1）：**获取过启示**（`revelation_obtained`）后，
 * 佩戴光环且生命值低于阈值（默认 50%）时，每秒刷新一次**原版「生命回复」**（regeneration）。
 *
 * <p>它**不受** {@link HaloState} 的属性倍率影响 —— 这是条件触发效果，不是属性加成；
 * 条件不满足时不再刷新，2 秒后自然过期。
 */
public final class HaloBlessing {
	/** 刷新后的持续时间（tick）：2 秒 */
	private static final int DURATION_TICKS = 40;

	private HaloBlessing() {
	}

	/** 服务端每秒：满足条件就刷新生命回复 */
	public static void tickPlayer(ServerPlayer player) {
		if (!shouldRegen(player)) {
			return;
		}
		player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, DURATION_TICKS,
				ReliquaryConfig.haloRegenAmplifier(), false, true, true));
	}

	/** 条件（提示与自检共用）：佩戴光环 + 获取过启示 + 生命值低于阈值 */
	public static boolean shouldRegen(LivingEntity entity) {
		if (entity == null || !CurioHelper.wears(entity, SummyReliquary.THE_HALO.get())) {
			return false;
		}
		if (!PlayerFlags.isRevelationObtained(entity)) {
			return false;
		}
		float threshold = entity.getMaxHealth() * (ReliquaryConfig.haloRegenThresholdPercent() / 100.0F);
		return entity.getHealth() < threshold;
	}
}
