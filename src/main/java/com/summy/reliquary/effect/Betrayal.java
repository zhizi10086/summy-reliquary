package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 背叛（Betrayal）：每隔一段时间点名一个敌人作为靶子，让周围敌人转而攻击它。 */
public final class Betrayal {
	private static final String TARGET_TAG = "summy_reliquary:betrayal_target";
	private static final String EXIMUS_SHIELD_TAG = "summy_threat:eximus_shield";
	private static final Map<UUID, Taunt> ACTIVE = new HashMap<>();
	private static Double chanceOverrideForTest;
	private static int triggerCount;

	private record Taunt(Mob target, long until) {
	}

	private Betrayal() {
	}

	/** 是否佩戴并启用背叛。 */
	public static boolean wears(LivingEntity entity) {
		return entity != null && ReliquaryConfig.enableBetrayal()
				&& PlayerFlags.isDemon(entity)
				&& CurioHelper.wears(entity, SummyReliquary.BETRAYAL.get());
	}

	/** 服务端每 tick。 */
	public static void tickPlayer(ServerPlayer player) {
		UUID id = player.getUUID();
		ServerLevel level = player.serverLevel();
		long now = level.getGameTime();
		Taunt current = ACTIVE.get(id);
		if (current != null) {
			if (!wears(player) || !validActiveTarget(player, current, now)) {
				finish(player, current);
				return;
			}
			forceTargets(player, current.target());
			if (now % 5L == 0L) {
				level.sendParticles(ParticleTypes.ANGRY_VILLAGER,
						current.target().getX(),
						current.target().getY() + current.target().getBbHeight() + 0.35D,
						current.target().getZ(), 1, 0.08D, 0.05D, 0.08D, 0.0D);
			}
			return;
		}
		if (!wears(player) || now < PlayerFlags.betrayalCooldownUntil(player)) {
			return;
		}
		double radius = ReliquaryConfig.betrayalRadius();
		var candidates = level.getEntitiesOfClass(Mob.class,
				player.getBoundingBox().inflate(radius),
				mob -> mob instanceof Enemy
						&& Godhead.isAuraTarget(player, mob)
						&& mob.distanceToSqr(player) <= radius * radius
						&& !eximusShielded(mob));
		if (candidates.size() < ReliquaryConfig.betrayalMinEnemies()) {
			return;
		}
		double chance = chanceOverrideForTest != null
				? chanceOverrideForTest : ReliquaryConfig.betrayalChancePerTick();
		if (chance <= 0.0D) {
			return;
		}
		Mob chosen = null;
		double bestDistance = Double.MAX_VALUE;
		for (Mob candidate : candidates) {
			if (level.random.nextDouble() * 100.0D >= Math.min(100.0D, chance)) {
				continue;
			}
			double distance = candidate.distanceToSqr(player);
			if (distance < bestDistance) {
				bestDistance = distance;
				chosen = candidate;
			}
		}
		if (chosen == null) {
			return;
		}
		long until = now + ReliquaryConfig.betrayalTauntSeconds() * 20L;
		Taunt taunt = new Taunt(chosen, until);
		ACTIVE.put(id, taunt);
		chosen.getPersistentData().putLong(TARGET_TAG, until);
		triggerCount++;
		forceTargets(player, chosen);
	}

	private static boolean validActiveTarget(ServerPlayer player, Taunt current, long now) {
		Mob target = current.target();
		return target != null
				&& now < current.until()
				&& target.isAlive()
				&& !target.isRemoved()
				&& target.level() == player.level()
				&& target instanceof Enemy
				&& Godhead.isAuraTarget(player, target)
				&& !eximusShielded(target);
	}

	private static void forceTargets(ServerPlayer player, Mob target) {
		double radius = ReliquaryConfig.betrayalRadius();
		ServerLevel level = player.serverLevel();
		for (Mob attacker : level.getEntitiesOfClass(Mob.class,
				player.getBoundingBox().inflate(radius),
				mob -> mob instanceof Enemy
						&& Godhead.isAuraTarget(player, mob)
						&& mob.distanceToSqr(player) <= radius * radius
						&& !eximusShielded(mob))) {
			if (attacker != target) {
				attacker.setTarget(target);
			}
		}
	}

	private static boolean eximusShielded(Mob mob) {
		return mob.getPersistentData().getDouble(EXIMUS_SHIELD_TAG) > 0.0D;
	}

	/** 主动结束当前嘲讽并开始冷却。 */
	public static void endFor(ServerPlayer player) {
		if (player == null) {
			return;
		}
		Taunt current = ACTIVE.get(player.getUUID());
		if (current != null) {
			finish(player, current);
		}
	}

	private static void finish(ServerPlayer player, Taunt taunt) {
		ACTIVE.remove(player.getUUID());
		if (taunt.target() != null) {
			taunt.target().getPersistentData().remove(TARGET_TAG);
		}
		long now = player.serverLevel().getGameTime();
		PlayerFlags.setBetrayalCooldownUntil(player,
				now + ReliquaryConfig.betrayalCooldownSeconds() * 20L);
	}

	public static void forget(ServerPlayer player) {
		endFor(player);
	}

	public static void clear() {
		for (Taunt taunt : ACTIVE.values()) {
			if (taunt.target() != null) {
				taunt.target().getPersistentData().remove(TARGET_TAG);
			}
		}
		ACTIVE.clear();
		chanceOverrideForTest = null;
		triggerCount = 0;
	}

	public static void setChanceForTest(Double chancePercent) {
		chanceOverrideForTest = chancePercent;
	}

	public static Mob targetForTest(ServerPlayer player) {
		Taunt taunt = ACTIVE.get(player.getUUID());
		return taunt == null ? null : taunt.target();
	}

	public static long tauntUntilForTest(ServerPlayer player) {
		Taunt taunt = ACTIVE.get(player.getUUID());
		return taunt == null ? 0L : taunt.until();
	}

	public static int triggerCount() {
		return triggerCount;
	}
}
