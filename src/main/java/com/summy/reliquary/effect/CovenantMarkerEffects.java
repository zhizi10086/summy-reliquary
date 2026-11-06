package com.summy.reliquary.effect;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * 誓约状态的纯视觉镜像工具。
 *
 * <p>玩法状态仍由各技能自己的服务端标记维护；这里只确保目标身上的图标、状态粒子与实际剩余时间一致。
 */
public final class CovenantMarkerEffects {
	private static final int DURATION_TOLERANCE_TICKS = 1;

	private CovenantMarkerEffects() {
	}

	public static void mirror(LivingEntity target, MobEffect effect, long untilTick) {
		if (target == null || effect == null || !target.isAlive()) {
			return;
		}
		long now = target.level().getGameTime();
		int remaining = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, untilTick - now));
		MobEffectInstance current = target.getEffect(effect);
		if (current != null
				&& current.getAmplifier() == 0
				&& !current.isAmbient()
				&& current.isVisible()
				&& current.showIcon()
				&& Math.abs(current.getDuration() - remaining) <= DURATION_TOLERANCE_TICKS) {
			return;
		}
		// 旧图标可能残留更长/更短的时长；先移除，确保 UI 严格镜像服务端剩余时间。
		if (current != null) {
			target.removeEffect(effect);
		}
		target.addEffect(new MobEffectInstance(effect, remaining, 0, false, true, true));
	}

	public static void remove(LivingEntity target, MobEffect effect) {
		if (target != null && effect != null && target.hasEffect(effect)) {
			target.removeEffect(effect);
		}
	}

	public static LivingEntity findEntity(MinecraftServer server, UUID id) {
		if (server == null || id == null) {
			return null;
		}
		for (var level : server.getAllLevels()) {
			var entity = level.getEntity(id);
			if (entity instanceof LivingEntity living) {
				return living;
			}
		}
		return null;
	}
}
