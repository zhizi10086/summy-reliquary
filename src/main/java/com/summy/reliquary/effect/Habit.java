package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.sin.SinEffects;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 修女服的受击充能：把正在冷却的自记技能向前推进。 */
public final class Habit {
	private static final Map<UUID, Long> LAST_TRIGGER = new HashMap<>();
	private static int triggerCount;

	private Habit() {
	}

	/** 当前是否佩戴并启用修女服。 */
	public static boolean wears(LivingEntity entity) {
		return entity != null && ReliquaryConfig.enableHabit()
				&& CurioHelper.wears(entity, SummyReliquary.HABIT.get());
	}

	/**
	 * LivingHurt 末端调用。
	 *
	 * <p>事件仍有效且金额大于 0 才代表这一击会落地；没有技能在冷却时也消耗本次触发窗口，
	 * 不存格。0.5 秒节流由配置控制。
	 */
	public static void onHurt(LivingIncomingDamageEvent event) {
		if (!(event.getEntity() instanceof ServerPlayer player) || event.isCanceled()
				|| event.getAmount() <= 0.0F || !wears(player)) {
			return;
		}
		// 自伤按既定规格不计入受击充能，也不占用 0.5 秒触发窗口。
		if (Sacrifice.isSelfDamage(event.getSource()) || SinEffects.isApplyingSelfHit()) {
			return;
		}
		long now = player.serverLevel().getGameTime();
		Long last = LAST_TRIGGER.get(player.getUUID());
		int interval = ReliquaryConfig.habitChargeIntervalTicks();
		if (last != null && now - last < interval) {
			return;
		}
		// “充能溢出浪费”：即使当前没有可推进的技能，也吃掉这次受击窗口。
		LAST_TRIGGER.put(player.getUUID(), now);

		boolean beamCooling = RevelationBeam.hasActiveCooldown(player);
		boolean abaddonCooling = Abaddon.hasActiveCooldown(player);
		boolean oathCooling = CovenantActions.hasActiveCooldown(player);
		boolean lifeDeathCooling = LifeDeath.hasActiveCooldown(player);
		if (!beamCooling && !abaddonCooling && !oathCooling && !lifeDeathCooling) {
			return;
		}
		double fraction = Math.max(0.0D, ReliquaryConfig.habitChargePercent() / 100.0D);
		int minTicks = Math.max(0, (int) Math.round(ReliquaryConfig.habitChargeMinSeconds() * 20.0D));
		if (beamCooling) {
			RevelationBeam.advanceCooldown(player, fraction, minTicks);
		}
		if (abaddonCooling) {
			Abaddon.advanceCooldown(player, fraction, minTicks);
		}
		if (oathCooling) {
			CovenantActions.advanceCooldown(player, fraction, minTicks);
		}
		if (lifeDeathCooling) {
			LifeDeath.advanceCooldown(player, fraction, minTicks);
		}
		triggerCount++;
	}

	public static void forget(ServerPlayer player) {
		if (player != null) {
			LAST_TRIGGER.remove(player.getUUID());
		}
	}

	public static void clear() {
		LAST_TRIGGER.clear();
		triggerCount = 0;
	}

	public static int triggerCount() {
		return triggerCount;
	}

	public static long lastTrigger(ServerPlayer player) {
		return player == null ? 0L : LAST_TRIGGER.getOrDefault(player.getUUID(), 0L);
	}

	public static void clearThrottleForTest(ServerPlayer player) {
		if (player != null) {
			LAST_TRIGGER.remove(player.getUUID());
		}
	}
}
