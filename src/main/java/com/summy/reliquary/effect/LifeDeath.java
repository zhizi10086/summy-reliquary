package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.item.CovenantItem;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 生死逆转：生形态拦截致死伤害并进入死形态，死形态累计击杀权重后满血复活。
 */
public final class LifeDeath {
	private static final TagKey<EntityType<?>> FORGE_BOSSES =
			TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation("forge", "bosses"));
	private static final TagKey<EntityType<?>> COMMON_BOSSES =
			TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation("c", "bosses"));
	private static final TagKey<EntityType<?>> SUMMY_BOSSES =
			TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(SummyReliquary.NAMESPACE, "bosses"));
	/** 转形态后的统一 2 秒完全无敌窗口 */
	private static final Map<UUID, Long> GUARD_UNTIL = new HashMap<>();

	private LifeDeath() {
	}

	public static boolean wears(LivingEntity entity) {
		return entity != null && ReliquaryConfig.enableLifeDeath()
				&& CurioHelper.wears(entity, SummyReliquary.LIFE_DEATH.get());
	}

	public static boolean shieldSuppressed(LivingEntity entity) {
		return wears(entity) && PlayerFlags.isDeathForm(entity);
	}

	public static boolean isGuarded(LivingEntity entity) {
		if (entity == null) {
			return false;
		}
		long until = GUARD_UNTIL.getOrDefault(entity.getUUID(), 0L);
		return until > entity.level().getGameTime();
	}

	public static void onHurt(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
		if (event.getEntity() instanceof ServerPlayer player && isGuarded(player)) {
			event.setAmount(0.0F);
			event.setCanceled(true);
		}
	}

	/**
	 * 致死拦截入口。只在生形态、佩戴饰品且复活冷却已结束时转形态并取消这一击。
	 */
	public static boolean tryNullify(ServerPlayer player) {
		if (!wears(player) || PlayerFlags.isDeathForm(player)
				|| PlayerFlags.lifeDeathCooldownUntil(player) > player.serverLevel().getGameTime()) {
			return false;
		}
		enterDeathForm(player);
		return true;
	}

	/** R 键：生形态主动进入死形态；死形态禁用。 */
	public static boolean activate(ServerPlayer player) {
		if (!wears(player)) {
			return false;
		}
		if (PlayerFlags.isDeathForm(player)) {
			player.displayClientMessage(
					net.minecraft.network.chat.Component.translatable(
							"item.summy-reliquary.life_death.fail.death_form"),
					true);
			return false;
		}
		if (PlayerFlags.lifeDeathCooldownUntil(player) > player.serverLevel().getGameTime()) {
			long remaining = PlayerFlags.lifeDeathCooldownUntil(player)
					- player.serverLevel().getGameTime();
			player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
					"item.summy-reliquary.covenant.fail.cooldown",
					Math.max(1L, (remaining + 19L) / 20L)), true);
			return false;
		}
		enterDeathForm(player);
		return true;
	}

	public static void tickPlayer(ServerPlayer player) {
		if (!PlayerFlags.isDeathForm(player)) {
			return;
		}
		long now = player.serverLevel().getGameTime();
		long startedAt = PlayerFlags.deathFormStartedAt(player);
		int duration = PlayerFlags.deathFormDuration(player);
		if (startedAt <= 0L || duration <= 0) {
			PlayerFlags.setDeathForm(player, 0L, 0);
			return;
		}
		if (now < startedAt + duration) {
			return;
		}
		PlayerFlags.setDeathForm(player, 0L, 0);
		com.summy.reliquary.effect.AttributeManager.apply(player);
		RevelationTracker.sync(player);
		net.minecraft.world.damagesource.DamageSource timeout = player.damageSources().genericKill();
		player.invulnerableTime = 0;
		player.hurt(timeout, Float.MAX_VALUE);
	}

	/** 死形态期间的独立出伤倍率；普通伤害和投射物在最终乘区之后单独相乘。 */
	public static float outgoingMultiplier(LivingEntity attacker) {
		if (!wears(attacker) || !PlayerFlags.isDeathForm(attacker)) {
			return 1.0F;
		}
		long now = attacker.level().getGameTime();
		long elapsed = Math.max(0L, now - PlayerFlags.deathFormStartedAt(attacker));
		double base = ReliquaryConfig.lifeDeathDamageBase();
		double value = base + elapsed / 20.0D * ReliquaryConfig.lifeDeathDamageStepPercent() / 100.0D;
		double cap = ReliquaryConfig.lifeDeathDamageCap();
		if (cap > 0.0D) {
			value = Math.min(value, cap);
		}
		return (float) Math.max(0.0D, value);
	}

	public static void onKill(ServerPlayer killer, LivingEntity killed) {
		if (!wears(killer) || !PlayerFlags.isDeathForm(killer) || killer == killed) {
			return;
		}
		int weight = killWeight(killed, killer);
		int total = PlayerFlags.deathFormKillWeight(killer) + weight;
		if (total >= ReliquaryConfig.lifeDeathReviveKillRequired()) {
			revive(killer);
		} else {
			PlayerFlags.setDeathFormKillWeight(killer, total);
		}
	}

	public static void onPlayerDeath(ServerPlayer player) {
		if (!PlayerFlags.isDeathForm(player)) {
			return;
		}
		PlayerFlags.setDeathForm(player, 0L, 0);
		GUARD_UNTIL.remove(player.getUUID());
	}

	public static void onUnequip(ServerPlayer player) {
		if (PlayerFlags.isDeathForm(player)) {
			PlayerFlags.setDeathForm(player, 0L, 0);
			AttributeManager.apply(player);
			RevelationTracker.sync(player);
		}
		GUARD_UNTIL.remove(player.getUUID());
	}

	public static void forget(ServerPlayer player) {
		if (player != null) {
			GUARD_UNTIL.remove(player.getUUID());
		}
	}

	public static void clear() {
		GUARD_UNTIL.clear();
	}

	private static void enterDeathForm(ServerPlayer player) {
		// 先复制一份效果列表再移除，避免遍历时修改能力映射。
		for (MobEffectInstance effect : java.util.List.copyOf(player.getActiveEffects())) {
			player.removeEffect(effect.getEffect());
		}
		player.setHealth(player.getMaxHealth());
		long now = player.serverLevel().getGameTime();
		PlayerFlags.setDeathForm(player, now, ReliquaryConfig.lifeDeathDeathFormTicks());
		GUARD_UNTIL.put(player.getUUID(), now + 40L);
		AttributeManager.apply(player);
		RevelationTracker.sync(player);
	}

	private static void revive(ServerPlayer player) {
		PlayerFlags.setDeathForm(player, 0L, 0);
		player.setHealth(player.getMaxHealth());
		PlayerFlags.setLifeDeathCooldownUntil(player,
				player.serverLevel().getGameTime() + ReliquaryConfig.lifeDeathCooldownTicks());
		AttributeManager.apply(player);
		RevelationTracker.sync(player);
	}

	private static int killWeight(LivingEntity killed, ServerPlayer player) {
		ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(killed.getType());
		if (id != null) {
			for (String line : ReliquaryConfig.lifeDeathKillWeights()) {
				String[] parts = line.split("=", 2);
				if (parts.length != 2 || !id.toString().equals(parts[0].trim())) {
					continue;
				}
				try {
					return Math.max(1, Integer.parseInt(parts[1].trim()));
				} catch (NumberFormatException ignored) {
					// 非法配置行跳过，继续走 boss 标签与默认值。
				}
			}
		}
		EntityType<?> type = killed.getType();
		if (type.is(FORGE_BOSSES) || type.is(COMMON_BOSSES) || type.is(SUMMY_BOSSES)) {
			return ReliquaryConfig.lifeDeathBossWeight();
		}
		return 1;
	}
}
