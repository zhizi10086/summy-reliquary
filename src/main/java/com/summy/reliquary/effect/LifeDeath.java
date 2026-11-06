package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.slot.ReliquarySlots;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.Holder;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 生死逆转：生形态拦截致死伤害并进入死形态，累计权重达标后等待倒计时归零再满血复活。
 */
public final class LifeDeath {
	/** 物品栈上的显示缓存；只负责图标与提示，玩法状态仍以玩家 NBT 为准。 */
	public static final String DISPLAY_TAG = "summy_reliquary_death_form";
	/** 旧版卸装补发链留下的物品标记；只做清理，不再参与任何归还逻辑。 */
	private static final String LEGACY_RETURN_TAG = "summy_reliquary_life_death_return";
	private static final TagKey<EntityType<?>> FORGE_BOSSES =
			TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation("forge", "bosses"));
	private static final TagKey<EntityType<?>> COMMON_BOSSES =
			TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation("c", "bosses"));
	private static final TagKey<EntityType<?>> SUMMY_BOSSES =
			TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(SummyReliquary.NAMESPACE, "bosses"));
	private static final ResourceKey<DamageType> REVIVAL_FAILURE_DAMAGE =
			ResourceKey.create(Registries.DAMAGE_TYPE, SummyReliquary.id("life_death_failure"));
	/** 转形态后的统一 2 秒完全无敌窗口 */
	private static final Map<UUID, Long> GUARD_UNTIL = new HashMap<>();
	/** 正在执行死形态到期处决的玩家；只跳过生死逆转自身，不跳其它免死。 */
	private static final Set<UUID> EXPIRING = new HashSet<>();
	/** 同一玩家、同一被击杀实体、同一 tick 的死亡事件只计一次权重。 */
	private static final Set<KillStamp> RECENT_KILLS = new HashSet<>();
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
		private int shownKillWeight = -1;

		private HudEntry(ServerBossEvent bar) {
			this.bar = bar;
		}
	}

	private record KillStamp(UUID player, UUID killed, long tick) {
	}

	private LifeDeath() {
	}

	public static boolean wears(LivingEntity entity) {
		return entity != null && ReliquaryConfig.enableLifeDeath()
				&& CurioHelper.wears(entity, SummyReliquary.LIFE_DEATH.get());
	}

	/** 修女服：复活后的独立 30 秒冷却是否仍在进行。 */
	public static boolean hasActiveCooldown(ServerPlayer player) {
		return player != null && wears(player)
				&& PlayerFlags.lifeDeathCooldownUntil(player) > player.serverLevel().getGameTime();
	}

	/** 修女服：按生死逆转总复活冷却推进，至少推进 {@code minTicks}。 */
	public static void advanceCooldown(ServerPlayer player, double fraction, int minTicks) {
		if (!hasActiveCooldown(player)) {
			return;
		}
		long now = player.serverLevel().getGameTime();
		long remaining = PlayerFlags.lifeDeathCooldownUntil(player) - now;
		int advance = Math.max(minTicks, (int) Math.ceil(
				ReliquaryConfig.lifeDeathCooldownTicks() * Math.max(0.0D, fraction)));
		long next = Math.max(0L, remaining - advance);
		PlayerFlags.setLifeDeathCooldownUntil(player, now + next);
		RevelationTracker.sync(player);
		updateHud(player, now);
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

	/**
	 * 死形态到期处决的专用伤害源。
	 *
	 * <p>数据包缺失时退回原版 {@code generic_kill}，确保旧包或诊断包仍能正常结束死形态。
	 */
	public static DamageSource revivalFailureSource(ServerPlayer player) {
		var registry = player.serverLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
		Holder<DamageType> holder = registry.getHolder(REVIVAL_FAILURE_DAMAGE)
				.<Holder<DamageType>>map(value -> value)
				.orElseGet(() -> registry.getHolderOrThrow(DamageTypes.GENERIC_KILL));
		return new DamageSource(holder);
	}

	/**
	 * 这类伤害必须按旧 {@code /kill} 口径穿过魂心 / 黑心整击保护。
	 */
	public static boolean bypassesWholeHitShield(DamageSource source) {
		return source != null && (source.is(DamageTypes.GENERIC_KILL)
				|| source.is(REVIVAL_FAILURE_DAMAGE));
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
		RECENT_KILLS.removeIf(stamp -> stamp.tick() < now - 20L);
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
		if (PlayerFlags.deathFormKillWeight(player)
				>= ReliquaryConfig.lifeDeathReviveKillRequired()) {
			GUARD_UNTIL.remove(player.getUUID());
			revive(player);
			return;
		}
		// 先屏蔽自身拦截，再清死形态并执行处决。其它免死仍按现有优先链正常工作。
		// 正常到期时这个守卫早已结束；主动清除也避免配置调整或调试改时间后残留旧无敌窗口。
		GUARD_UNTIL.remove(player.getUUID());
		EXPIRING.add(player.getUUID());
		PlayerFlags.setLifeDeathCooldownPending(player, true);
		try {
			PlayerFlags.setDeathForm(player, 0L, 0);
			updateEquippedDisplay(player, false);
			com.summy.reliquary.effect.AttributeManager.apply(player);
			RevelationTracker.sync(player);
			DamageSource timeout = revivalFailureSource(player);
			player.invulnerableTime = 0;
			player.hurt(timeout, Float.MAX_VALUE);
		} finally {
			EXPIRING.remove(player.getUUID());
		}
		// 被神性 / 亚巴顿 / 灵台免死等其它机制救下时，视为这次死形态已消耗。
		if (!player.isDeadOrDying() && player.isAlive()) {
			PlayerFlags.setLifeDeathCooldownPending(player, false);
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
		long now = killer.serverLevel().getGameTime();
		RECENT_KILLS.removeIf(stamp -> stamp.tick() < now - 20L);
		KillStamp stamp = new KillStamp(killer.getUUID(), killed.getUUID(), now);
		if (!RECENT_KILLS.add(stamp)) {
			return;
		}
		int weight = killWeight(killed, killer);
		int required = ReliquaryConfig.lifeDeathReviveKillRequired();
		int previous = PlayerFlags.deathFormKillWeight(killer);
		int total = Math.min(required, previous + weight);
		boolean newlyReached = previous < required && total >= required;
		ResourceLocation typeId = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES
				.getKey(killed.getType());
		SummyReliquary.LOGGER.info(
				"生死逆转击杀计数：玩家={}、目标={}、类型={}、本次权重={}、累计={}/{}、"
						+ "结果={}、tick={}",
				killer.getName().getString(), killed.getName().getString(),
				typeId == null ? killed.getType().toString() : typeId.toString(),
				weight, total, required,
				previous >= required ? "已达标待倒计时" : newlyReached ? "达标待倒计时" : "继续",
				now);
		PlayerFlags.setDeathFormKillWeight(killer, total);
		RevelationTracker.sync(killer);
		updateHud(killer, now);
	}

	public static void onPlayerDeath(ServerPlayer player) {
		boolean hadDeathForm = PlayerFlags.deathFormStartedAt(player) > 0L
				&& PlayerFlags.deathFormDuration(player) > 0;
		if (hadDeathForm || PlayerFlags.lifeDeathCooldownPending(player)) {
			PlayerFlags.setLifeDeathCooldownPending(player, true);
			PlayerFlags.setDeathForm(player, 0L, 0);
		}
		updateEquippedDisplay(player, false);
		GUARD_UNTIL.remove(player.getUUID());
		EXPIRING.remove(player.getUUID());
		removeHud(player);
	}

	/**
	 * 重生完成：从此刻开始完整复活冷却。
	 *
	 * <p>死亡瞬间只写待处理标记，避免死亡界面停留时间提前消耗冷却。
	 */
	public static void onRespawn(ServerPlayer player) {
		clearLegacyReturnState(player);
		if (!PlayerFlags.lifeDeathCooldownPending(player)) {
			updateEquippedDisplay(player, false);
			updateHud(player, player.serverLevel().getGameTime());
			return;
		}
		PlayerFlags.setLifeDeathCooldownPending(player, false);
		PlayerFlags.setDeathForm(player, 0L, 0);
		updateEquippedDisplay(player, false);
		PlayerFlags.setLifeDeathCooldownUntil(player,
				player.serverLevel().getGameTime() + ReliquaryConfig.lifeDeathCooldownTicks());
		AttributeManager.apply(player);
		RevelationTracker.sync(player);
		updateHud(player, player.serverLevel().getGameTime());
	}

	public static void onUnequip(ServerPlayer player) {
		if (PlayerFlags.isDeathForm(player)) {
			long now = player.serverLevel().getGameTime();
			PlayerFlags.setLifeDeathCooldownPending(player, true);
			PlayerFlags.setDeathForm(player, 0L, 0);
			updateEquippedDisplay(player, false);
			AttributeManager.apply(player);
			RevelationTracker.sync(player);
			GUARD_UNTIL.remove(player.getUUID());
			EXPIRING.add(player.getUUID());
			removeHud(player);
			player.invulnerableTime = 0;
			try {
				// 真实摘下生死逆转视同逆转失败；若其它免死接管则按获救后的冷却口径结算。
				player.hurt(revivalFailureSource(player), Float.MAX_VALUE);
			} finally {
				EXPIRING.remove(player.getUUID());
			}
			if (!player.isDeadOrDying() && player.isAlive()) {
				PlayerFlags.setLifeDeathCooldownPending(player, false);
				PlayerFlags.setLifeDeathCooldownUntil(player,
						now + ReliquaryConfig.lifeDeathCooldownTicks());
				RevelationTracker.sync(player);
			}
			return;
		}
		updateEquippedDisplay(player, false);
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
		clearLegacyReturnState(player);
		if (PlayerFlags.lifeDeathCooldownPending(player)) {
			onRespawn(player);
			return;
		}
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
			RECENT_KILLS.removeIf(stamp -> stamp.player().equals(player.getUUID()));
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
		RECENT_KILLS.clear();
	}

	private static void removeTag(ItemStack stack, String key) {
		if (stack == null || !stack.hasTag()) {
			return;
		}
		stack.getTag().remove(key);
		if (stack.getTag().isEmpty()) {
			stack.setTag(null);
		}
	}

	/** 清理旧版卸装补发遗留状态；已经复制出的实体物品只清标记，不主动删除。 */
	private static void clearLegacyReturnState(ServerPlayer player) {
		PlayerFlags.clearLegacyLifeDeathReturnItem(player);
		for (ItemStack stack : player.getInventory().items) {
			clearLegacyReturnMarker(stack);
		}
		for (ItemStack stack : player.getInventory().armor) {
			clearLegacyReturnMarker(stack);
		}
		for (ItemStack stack : player.getInventory().offhand) {
			clearLegacyReturnMarker(stack);
		}
		clearLegacyReturnMarker(player.containerMenu.getCarried());
	}

	private static void clearLegacyReturnMarker(ItemStack stack) {
		if (stack == null || stack.isEmpty() || !stack.is(SummyReliquary.LIFE_DEATH.get())) {
			return;
		}
		removeTag(stack, LEGACY_RETURN_TAG);
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
		int duration = ReliquaryConfig.lifeDeathDeathFormTicks();
		PlayerFlags.setLifeDeathCooldownPending(player, false);
		PlayerFlags.setDeathForm(player, now, duration);
		updateEquippedDisplay(player, true);
		GUARD_UNTIL.put(player.getUUID(), now + 40L);
		AttributeManager.apply(player);
		RevelationTracker.sync(player);
		updateHud(player, now);
		SummyReliquary.LOGGER.info(
				"生死逆转进入死形态：玩家={}、开始 tick={}、持续={} tick、初始权重=0/{}",
				player.getName().getString(), now, duration,
				ReliquaryConfig.lifeDeathReviveKillRequired());
	}

	private static void revive(ServerPlayer player) {
		float healthBefore = player.getHealth();
		long now = player.serverLevel().getGameTime();
		PlayerFlags.setLifeDeathCooldownPending(player, false);
		PlayerFlags.setDeathForm(player, 0L, 0);
		updateEquippedDisplay(player, false);
		player.setHealth(player.getMaxHealth());
		long cooldownUntil = now + ReliquaryConfig.lifeDeathCooldownTicks();
		PlayerFlags.setLifeDeathCooldownUntil(player, cooldownUntil);
		AttributeManager.apply(player);
		RevelationTracker.sync(player);
		updateHud(player, now);
		SummyReliquary.LOGGER.info(
				"生死逆转倒计时结束复活：玩家={}、生命 {}→{}、tick={}、冷却截止={}、死形态=false",
				player.getName().getString(), healthBefore, player.getHealth(), now, cooldownUntil);
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
		int killWeight = -1;
		int killRequired = -1;
		BossEvent.BossBarColor color = BossEvent.BossBarColor.RED;

		if (PlayerFlags.isDeathForm(player)) {
			int duration = PlayerFlags.deathFormDuration(player);
			remainingTicks = (int) Math.max(0L, PlayerFlags.deathFormStartedAt(player)
					+ duration - now);
			if (duration > 0 && remainingTicks > 0) {
				mode = HudMode.DEATH_FORM;
				totalTicks = duration;
				killWeight = PlayerFlags.deathFormKillWeight(player);
				killRequired = ReliquaryConfig.lifeDeathReviveKillRequired();
				color = BossEvent.BossBarColor.RED;
			}
		}
		if (mode == null) {
			long cooldown = PlayerFlags.lifeDeathCooldownUntil(player);
			if (cooldown > now) {
				mode = HudMode.COOLDOWN;
				remainingTicks = (int) Math.min(Integer.MAX_VALUE, cooldown - now);
				totalTicks = Math.max(1, ReliquaryConfig.lifeDeathCooldownTicks());
				color = BossEvent.BossBarColor.BLUE;
			}
		}
		if (mode == null) {
			removeHud(player);
			return;
		}

		int seconds = Math.max(1, (remainingTicks + 19) / 20);
		HudEntry entry = HUD_ENTRIES.get(player.getUUID());
		if (entry == null) {
			Component title = hudTitle(mode, seconds, killWeight, killRequired);
			entry = new HudEntry(new ServerBossEvent(title, color, BossEvent.BossBarOverlay.PROGRESS));
			entry.mode = mode;
			entry.shownSeconds = seconds;
			entry.shownKillWeight = killWeight;
			entry.bar.setDarkenScreen(false);
			entry.bar.setCreateWorldFog(false);
			entry.bar.setPlayBossMusic(false);
			entry.bar.setProgress(Mth.clamp(remainingTicks / (float) totalTicks, 0.0F, 1.0F));
			entry.bar.addPlayer(player);
			// 部分客户端在 ADD 包抵达时只先建立空进度条；加入玩家后再显式补发一次完整状态，
			// 确保标题无需等下一秒或打开背包界面才出现。
			entry.bar.setName(title);
			entry.bar.setColor(color);
			entry.bar.setOverlay(BossEvent.BossBarOverlay.PROGRESS);
			entry.bar.setProgress(Mth.clamp(remainingTicks / (float) totalTicks, 0.0F, 1.0F));
			entry.shownSeconds = -1;
			HUD_ENTRIES.put(player.getUUID(), entry);
			return;
		}
		if (!entry.bar.getPlayers().contains(player)) {
			entry.bar.addPlayer(player);
		}
		if (entry.mode != mode || entry.shownSeconds != seconds
				|| entry.shownKillWeight != killWeight) {
			entry.mode = mode;
			entry.shownSeconds = seconds;
			entry.shownKillWeight = killWeight;
			entry.bar.setName(hudTitle(mode, seconds, killWeight, killRequired));
			entry.bar.setColor(color);
			entry.bar.setOverlay(BossEvent.BossBarOverlay.PROGRESS);
		}
		// 每 tick 推送进度；标题仍只在模式 / 秒数 / 权重变化时更新。
		entry.bar.setProgress(Mth.clamp(remainingTicks / (float) totalTicks, 0.0F, 1.0F));
	}

	private static Component hudTitle(HudMode mode, int seconds, int killWeight, int killRequired) {
		if (mode == HudMode.DEATH_FORM) {
			return Component.translatable("message.summy-reliquary.life_death.hud.death_form",
					seconds, killWeight, killRequired);
		}
		return Component.translatable("message.summy-reliquary.life_death.hud.cooldown", seconds);
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
			}
			if (updated.hasTag()) {
				updated.getTag().remove(LEGACY_RETURN_TAG);
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
