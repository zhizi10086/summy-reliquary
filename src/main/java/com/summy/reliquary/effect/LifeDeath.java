package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.slot.ReliquarySlots;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 生死逆转：生形态拦截致死伤害并进入死形态，死形态累计击杀权重后满血复活。
 */
public final class LifeDeath {
	/** 物品栈上的显示缓存；只负责图标与提示，玩法状态仍以玩家 NBT 为准。 */
	public static final String DISPLAY_TAG = "summy_reliquary_death_form";
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

	/** 物品图标与提示的权威显示值；标记缺失时回退到客户端同步位。 */
	public static boolean displayDeathForm(ItemStack stack, LivingEntity entity) {
		if (stack != null && !stack.isEmpty() && stack.hasTag()
				&& stack.getTag().contains(DISPLAY_TAG)) {
			return stack.getTag().getBoolean(DISPLAY_TAG);
		}
		return entity != null && PlayerFlags.isDeathForm(entity);
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
		long startedAt = PlayerFlags.deathFormStartedAt(player);
		int duration = PlayerFlags.deathFormDuration(player);
		if (startedAt <= 0L || duration <= 0) {
			// 状态已经清掉，但物品上可能残留上一帧的显示缓存。
			updateEquippedDisplay(player, false);
			return;
		}
		long now = player.serverLevel().getGameTime();
		if (now < startedAt + duration) {
			updateEquippedDisplay(player, true);
			return;
		}
		PlayerFlags.setDeathForm(player, 0L, 0);
		updateEquippedDisplay(player, false);
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
		if (PlayerFlags.isDeathForm(player)) {
			PlayerFlags.setDeathForm(player, 0L, 0);
		}
		updateEquippedDisplay(player, false);
		GUARD_UNTIL.remove(player.getUUID());
	}

	public static void onUnequip(ServerPlayer player) {
		if (PlayerFlags.isDeathForm(player)) {
			PlayerFlags.setDeathForm(player, 0L, 0);
			updateEquippedDisplay(player, false);
			AttributeManager.apply(player);
			RevelationTracker.sync(player);
		} else {
			updateEquippedDisplay(player, false);
		}
		GUARD_UNTIL.remove(player.getUUID());
	}

	/**
	 * 登录 / 换维度 / 状态恢复时校准物品显示缓存。
	 *
	 * <p>过期或不再佩戴时清掉玩家死形态状态；仍在死形态则把标记补回物品栈。
	 */
	public static void syncDisplayState(ServerPlayer player) {
		if (!wears(player)) {
			PlayerFlags.setDeathForm(player, 0L, 0);
			updateEquippedDisplay(player, false);
			return;
		}
		boolean active = PlayerFlags.isDeathForm(player);
		if (!active && (PlayerFlags.deathFormStartedAt(player) > 0L
				|| PlayerFlags.deathFormDuration(player) > 0)) {
			PlayerFlags.setDeathForm(player, 0L, 0);
		}
		updateEquippedDisplay(player, active);
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
		updateEquippedDisplay(player, true);
		GUARD_UNTIL.put(player.getUUID(), now + 40L);
		AttributeManager.apply(player);
		RevelationTracker.sync(player);
	}

	private static void revive(ServerPlayer player) {
		PlayerFlags.setDeathForm(player, 0L, 0);
		updateEquippedDisplay(player, false);
		player.setHealth(player.getMaxHealth());
		PlayerFlags.setLifeDeathCooldownUntil(player,
				player.serverLevel().getGameTime() + ReliquaryConfig.lifeDeathCooldownTicks());
		AttributeManager.apply(player);
		RevelationTracker.sync(player);
	}

	/** 把显示缓存写回实际佩戴的 ItemStack；Curios 会随槽位内容同步给客户端。 */
	private static void updateEquippedDisplay(ServerPlayer player, boolean deathForm) {
		var handler = CuriosApi.getCuriosInventory(player).orElse(null);
		if (handler == null) {
			return;
		}
		var covenant = handler.getCurios().get(ReliquarySlots.COVENANT);
		if (covenant == null) {
			return;
		}
		for (int index = 0; index < covenant.getSlots(); index++) {
			ItemStack worn = covenant.getStacks().getStackInSlot(index);
			if (!worn.is(SummyReliquary.LIFE_DEATH.get())) {
				continue;
			}
			ItemStack updated = worn.copy();
			if (deathForm) {
				updated.getOrCreateTag().putBoolean(DISPLAY_TAG, true);
			} else if (updated.hasTag()) {
				updated.getTag().remove(DISPLAY_TAG);
				if (updated.getTag().isEmpty()) {
					updated.setTag(null);
				}
			}
			if (!ItemStack.matches(updated, worn)) {
				covenant.getStacks().setStackInSlot(index, updated);
			}
			return;
		}
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
