package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.slot.ReliquarySlots;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
	/** 正在执行死形态到期处决的玩家；只跳过生死逆转自身，不跳其它免死。 */
	private static final Set<UUID> EXPIRING = new HashSet<>();
	/** 每名玩家独立的倒计时 Boss 条。 */
	private static final Map<UUID, HudEntry> HUD_ENTRIES = new HashMap<>();

	private enum HudMode {
		DEATH_FORM,
		COOLDOWN
	}

	private static final class HudEntry {
		private final ServerBossEvent bar;
		private HudMode mode;
		private int shownSeconds = -1;

		private HudEntry(ServerBossEvent bar) {
			this.bar = bar;
		}
	}

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
		if (EXPIRING.contains(player.getUUID()) || !wears(player) || PlayerFlags.isDeathForm(player)
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
		long now = player.serverLevel().getGameTime();
		if (startedAt <= 0L || duration <= 0) {
			// 状态已经清掉，但物品上可能残留上一帧的显示缓存。
			updateEquippedDisplay(player, false);
			updateHud(player, now);
			return;
		}
		if (now < startedAt + duration) {
			updateEquippedDisplay(player, true);
			updateHud(player, now);
			// 客户端屏幕警告只需要秒级精度；每秒补发一次可纠正网络延迟与重登后的计数。
			if (now % 20L == 0L) {
				RevelationTracker.sync(player);
			}
			return;
		}
		// 先屏蔽自身拦截，再清死形态并执行处决。其它免死仍按现有优先链正常工作。
		// 正常到期时这个守卫早已结束；主动清除也避免配置调整或调试改时间后残留旧无敌窗口。
		GUARD_UNTIL.remove(player.getUUID());
		EXPIRING.add(player.getUUID());
		try {
			PlayerFlags.setDeathForm(player, 0L, 0);
			updateEquippedDisplay(player, false);
			com.summy.reliquary.effect.AttributeManager.apply(player);
			RevelationTracker.sync(player);
			net.minecraft.world.damagesource.DamageSource timeout = player.damageSources().genericKill();
			player.invulnerableTime = 0;
			player.hurt(timeout, Float.MAX_VALUE);
		} finally {
			EXPIRING.remove(player.getUUID());
		}
		// 被神性 / 亚巴顿 / 灵台免死等其它机制救下时，视为这次死形态已消耗。
		if (!player.isDeadOrDying() && player.isAlive()) {
			PlayerFlags.setLifeDeathCooldownUntil(player,
					now + ReliquaryConfig.lifeDeathCooldownTicks());
			RevelationTracker.sync(player);
		}
		updateHud(player, now);
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
			RevelationTracker.sync(killer);
			updateHud(killer, killer.serverLevel().getGameTime());
		}
	}

	public static void onPlayerDeath(ServerPlayer player) {
		if (PlayerFlags.isDeathForm(player)) {
			PlayerFlags.setDeathForm(player, 0L, 0);
		}
		updateEquippedDisplay(player, false);
		GUARD_UNTIL.remove(player.getUUID());
		EXPIRING.remove(player.getUUID());
		removeHud(player);
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
		EXPIRING.remove(player.getUUID());
		removeHud(player);
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
			removeHud(player);
			return;
		}
		boolean active = PlayerFlags.isDeathForm(player);
		if (!active && (PlayerFlags.deathFormStartedAt(player) > 0L
				|| PlayerFlags.deathFormDuration(player) > 0)) {
			PlayerFlags.setDeathForm(player, 0L, 0);
		}
		updateEquippedDisplay(player, active);
		updateHud(player, player.serverLevel().getGameTime());
	}

	public static void forget(ServerPlayer player) {
		if (player != null) {
			GUARD_UNTIL.remove(player.getUUID());
			EXPIRING.remove(player.getUUID());
			removeHud(player);
		}
	}

	public static void clear() {
		for (HudEntry entry : HUD_ENTRIES.values()) {
			entry.bar.removeAllPlayers();
		}
		HUD_ENTRIES.clear();
		GUARD_UNTIL.clear();
		EXPIRING.clear();
	}

	/** 自检用：当前玩家是否显示倒计时 Boss 条。 */
	public static boolean hudVisible(ServerPlayer player) {
		HudEntry entry = HUD_ENTRIES.get(player.getUUID());
		return entry != null && entry.bar.getPlayers().contains(player);
	}

	/** 自检用：当前 Boss 条标题。 */
	public static String hudTitle(ServerPlayer player) {
		HudEntry entry = HUD_ENTRIES.get(player.getUUID());
		return entry == null ? "" : entry.bar.getName().getString();
	}

	/** 自检用：当前 Boss 条进度。 */
	public static float hudProgress(ServerPlayer player) {
		HudEntry entry = HUD_ENTRIES.get(player.getUUID());
		return entry == null ? 0.0F : entry.bar.getProgress();
	}

	/** 自检用：当前 Boss 条颜色。 */
	public static BossEvent.BossBarColor hudColor(ServerPlayer player) {
		HudEntry entry = HUD_ENTRIES.get(player.getUUID());
		return entry == null ? null : entry.bar.getColor();
	}

	/** 自检用：玩家是否正处在到期处决的执行窗口。 */
	public static boolean expiryInProgressForTest(ServerPlayer player) {
		return EXPIRING.contains(player.getUUID());
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
		updateHud(player, now);
	}

	private static void revive(ServerPlayer player) {
		PlayerFlags.setDeathForm(player, 0L, 0);
		updateEquippedDisplay(player, false);
		player.setHealth(player.getMaxHealth());
		PlayerFlags.setLifeDeathCooldownUntil(player,
				player.serverLevel().getGameTime() + ReliquaryConfig.lifeDeathCooldownTicks());
		AttributeManager.apply(player);
		RevelationTracker.sync(player);
		updateHud(player, player.serverLevel().getGameTime());
	}

	/**
	 * 维护死形态 / 复活冷却 Boss 条。
	 *
	 * <p>只在显示秒数或模式变化时更新，避免每 tick 重复给客户端发包。
	 */
	private static void updateHud(ServerPlayer player, long now) {
		if (!wears(player) || player.isDeadOrDying()) {
			removeHud(player);
			return;
		}
		HudMode mode = null;
		int remainingTicks = 0;
		int totalTicks = 0;
		BossEvent.BossBarColor color = BossEvent.BossBarColor.RED;
		String titleKey = "";

		if (PlayerFlags.isDeathForm(player)) {
			int duration = PlayerFlags.deathFormDuration(player);
			remainingTicks = (int) Math.max(0L, PlayerFlags.deathFormStartedAt(player)
					+ duration - now);
			if (duration > 0 && remainingTicks > 0) {
				mode = HudMode.DEATH_FORM;
				totalTicks = duration;
				color = BossEvent.BossBarColor.RED;
				titleKey = "message.summy-reliquary.life_death.hud.death_form";
			}
		}
		if (mode == null) {
			long cooldown = PlayerFlags.lifeDeathCooldownUntil(player);
			if (cooldown > now) {
				mode = HudMode.COOLDOWN;
				remainingTicks = (int) Math.min(Integer.MAX_VALUE, cooldown - now);
				totalTicks = Math.max(1, ReliquaryConfig.lifeDeathCooldownTicks());
				color = BossEvent.BossBarColor.BLUE;
				titleKey = "message.summy-reliquary.life_death.hud.cooldown";
			}
		}
		if (mode == null) {
			removeHud(player);
			return;
		}

		int seconds = Math.max(1, (remainingTicks + 19) / 20);
		HudEntry entry = HUD_ENTRIES.get(player.getUUID());
		if (entry == null) {
			Component title = Component.translatable(titleKey, seconds);
			entry = new HudEntry(new ServerBossEvent(title, color, BossEvent.BossBarOverlay.PROGRESS));
			entry.mode = mode;
			entry.shownSeconds = seconds;
			entry.bar.setDarkenScreen(false);
			entry.bar.setCreateWorldFog(false);
			entry.bar.setPlayBossMusic(false);
			entry.bar.setProgress(Mth.clamp(remainingTicks / (float) totalTicks, 0.0F, 1.0F));
			entry.bar.addPlayer(player);
			HUD_ENTRIES.put(player.getUUID(), entry);
			return;
		}
		if (!entry.bar.getPlayers().contains(player)) {
			entry.bar.addPlayer(player);
		}
		if (entry.mode != mode || entry.shownSeconds != seconds) {
			entry.mode = mode;
			entry.shownSeconds = seconds;
			entry.bar.setName(Component.translatable(titleKey, seconds));
			entry.bar.setColor(color);
			entry.bar.setOverlay(BossEvent.BossBarOverlay.PROGRESS);
			entry.bar.setProgress(Mth.clamp(remainingTicks / (float) totalTicks, 0.0F, 1.0F));
		}
	}

	private static void removeHud(ServerPlayer player) {
		HudEntry entry = HUD_ENTRIES.remove(player.getUUID());
		if (entry != null) {
			entry.bar.removePlayer(player);
			if (entry.bar.getPlayers().isEmpty()) {
				entry.bar.removeAllPlayers();
			}
		}
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
