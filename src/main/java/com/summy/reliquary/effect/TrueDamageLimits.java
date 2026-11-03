package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 真伤限伤破除的统一落地出口。
 *
 * <p>第三方 Boss 可能在自身 {@code hurt()} 里把金额钳制或折算，而事件层无法拦截。
 * 这里只在本模组伤害已经成功落地（{@code hurt()} 返回 true）后，根据生命与吸收的
 * 实际损失补足差额；若目标处于阶段无敌或免疫并直接返回 false，则绝不补差。
 */
public final class TrueDamageLimits {
	private static final float MIN_SHORTFALL = 0.01F;
	private static final long LOG_INTERVAL_TICKS = 100L;
	private static final Map<UUID, Long> LAST_LOG = new HashMap<>();

	private TrueDamageLimits() {
	}

	/**
	 * 对外 API：落地一次真伤并按配置补齐被第三方限伤削掉的差额。
	 *
	 * @return 原始 {@code target.hurt()} 的返回值；补差不会把 false 改成 true
	 */
	public static boolean hurt(LivingEntity target, DamageSource source, float damage) {
		if (target == null || source == null || damage <= 0.0F) {
			return false;
		}
		float healthBefore = target.getHealth();
		float absorptionBefore = target.getAbsorptionAmount();
		boolean landed = target.hurt(source, damage);
		if (!landed) {
			return false;
		}

		float lostTotal = actualLost(healthBefore, target.getHealth(),
				absorptionBefore, target.getAbsorptionAmount());
		boolean isPlayer = target instanceof Player;
		if (shouldTopUp(landed, damage, lostTotal, isPlayer,
				ReliquaryConfig.trueDamageIgnoresDamageLimits(),
				ReliquaryConfig.trueDamageLimitAffectsPlayers())) {
			float supplemented = applyTopUp(target, source, damage - lostTotal);
			if (supplemented > 0.0F) {
				logTopUp(target, source, damage, lostTotal, supplemented);
			}
		}
		return true;
	}

	/** 自检用纯函数：只判断是否应该补差，不修改实体。 */
	public static boolean shouldTopUp(boolean landed, float intended, float lostTotal,
			boolean isPlayer, boolean enabled, boolean affectsPlayers) {
		if (!landed || !enabled || intended <= 0.0F || lostTotal < 0.0F) {
			return false;
		}
		if (isPlayer && !affectsPlayers) {
			return false;
		}
		return intended - lostTotal > MIN_SHORTFALL;
	}

	private static float actualLost(float healthBefore, float healthAfter,
			float absorptionBefore, float absorptionAfter) {
		return Math.max(0.0F, healthBefore - healthAfter)
				+ Math.max(0.0F, absorptionBefore - absorptionAfter);
	}

	/**
	 * 按正常护盾顺序补差：先扣剩余吸收，再扣生命；降到 0 时用原始伤害源收尾死亡。
	 *
	 * @return 实际补出的伤害量
	 */
	private static float applyTopUp(LivingEntity target, DamageSource source, float shortfall) {
		if (shortfall <= MIN_SHORTFALL || !target.isAlive()) {
			return 0.0F;
		}
		float remaining = shortfall;
		float absorptionBefore = target.getAbsorptionAmount();
		float absorptionUsed = Math.min(absorptionBefore, remaining);
		if (absorptionUsed > 0.0F) {
			target.setAbsorptionAmount(Math.max(0.0F, absorptionBefore - absorptionUsed));
			remaining -= absorptionUsed;
		}

		float healthBefore = target.getHealth();
		float healthUsed = Math.min(healthBefore, remaining);
		if (healthUsed > 0.0F) {
			target.setHealth(Math.max(0.0F, healthBefore - healthUsed));
			if (target.getHealth() <= 0.0F) {
				target.setHealth(0.0F);
				target.die(source);
			}
		}
		return Math.max(0.0F, shortfall - remaining);
	}

	private static void logTopUp(LivingEntity target, DamageSource source, float intended,
			float actual, float supplemented) {
		long now = target.level().getGameTime();
		Long last = LAST_LOG.get(target.getUUID());
		if (last != null && now - last < LOG_INTERVAL_TICKS) {
			return;
		}
		LAST_LOG.put(target.getUUID(), now);
		SummyReliquary.LOGGER.info("真伤补差：目标 {}、应得 {}、实际 {}、补 {}（{}）",
				target.getName().getString(), intended, actual, supplemented, HolyLightEffect.typeId(source));
	}
}
