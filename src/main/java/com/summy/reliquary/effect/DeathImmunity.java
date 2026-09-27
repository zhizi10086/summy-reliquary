package com.summy.reliquary.effect;

import com.summy.reliquary.config.ReliquaryConfig;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 「灵魂」三件套的免死效果。
 *
 * <p>触发时（20% 几率，见配置 {@code spirit_altar.death_immunity_percent}）：
 * <ol>
 *     <li>把这次致命伤害清零——玩家不会死亡；</li>
 *     <li>生命值恢复到**最大生命的一半**；</li>
 *     <li>获得 **2 秒（40 tick）无敌**：期间所有伤害都由 {@link #onHurt} 直接取消；</li>
 *     <li>播放原版图腾音效与图腾粒子。</li>
 * </ol>
 *
 * <p>时机选在 {@code LivingDamageEvent}（伤害已经扣完吸收、真正扣血之前），
 * 而且必须排在「启示之光」把伤害校正为真实伤害之后，否则清零会被覆盖。
 *
 * <p>无敌守卫只存在内存里（不写进玩家存档），因此崩溃 / 重启后不会残留成永久无敌；
 * 按需求不设冷却、不清负面，也不区分伤害来源。
 */
public final class DeathImmunity {
	/** 免死后的无敌时长（tick）：40 = 2 秒 */
	public static final int INVULNERABLE_TICKS = 40;

	/** 玩家 UUID → 无敌守卫的到期游戏 tick */
	private static final Map<UUID, Long> GUARD_UNTIL = new HashMap<>();

	/** 自检用：触发次数 */
	private static int triggerCount;

	private DeathImmunity() {
	}

	/**
	 * 免死判定（1.6.4：改成"命中前整击拦下"，不再依赖 {@code LivingDamageEvent} 的金额）。
	 *
	 * <p>调用方（{@link DamagePools}）在返回 true 后会把这一击整击并入原版吸收值 —— 等价于旧版的"伤害清零"，
	 * 而且不依赖任何事件金额写回。副作用与旧版一致：**生命恢复到半血 + 2 秒无敌 + 图腾音效与粒子**。
	 *
	 * @param healthPart 估算"会打到生命值"的伤害（见 {@link DamageEstimate}）
	 * @return true 表示这次触发免死
	 */
	public static boolean tryNullify(ServerPlayer player, float healthPart) {
		if (player == null) {
			return false;
		}
		// 1.6.2：咒印同样继承"20% 免死"
		if (!SpiritAltarSet.hasSetEffects(player)) {
			return false;
		}
		// 只有"真的会打死人"的那一次才判定
		if (healthPart <= 0.0F || healthPart < player.getHealth()) {
			return false;
		}
		if (player.getRandom().nextDouble() * 100.0D >= ReliquaryConfig.deathImmunityPercent()) {
			return false;
		}

		// 生命恢复到半血 + 2 秒无敌
		player.setHealth(Math.max(1.0F, player.getMaxHealth() * 0.5F));
		GUARD_UNTIL.put(player.getUUID(), player.serverLevel().getGameTime() + INVULNERABLE_TICKS);
		triggerCount++;

		ServerLevel level = player.serverLevel();
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0F, 1.0F);
		level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
				player.getX(), player.getY() + 0.5D, player.getZ(), 60,
				0.6D, 0.9D, 0.6D, 0.35D);
		return true;
	}

	/** 2 秒无敌守卫：无敌期间的一切伤害都被取消（不区分来源，虚空与指令同样拦） */
	public static void onHurt(LivingHurtEvent event) {
		if (!(event.getEntity() instanceof ServerPlayer player) || !isGuarded(player)) {
			return;
		}
		event.setAmount(0.0F);
		event.setCanceled(true);
	}

	/** 该玩家当前是否处在免死后的无敌期（自检用） */
	public static boolean isGuarded(ServerPlayer player) {
		Long until = GUARD_UNTIL.get(player.getUUID());
		if (until == null) {
			return false;
		}
		if (player.serverLevel().getGameTime() >= until) {
			GUARD_UNTIL.remove(player.getUUID());
			return false;
		}
		return true;
	}

	/** 服务端每 tick 清理过期的无敌守卫 */
	public static void tick(MinecraftServer server) {
		if (GUARD_UNTIL.isEmpty()) {
			return;
		}
		long now = server.overworld().getGameTime();
		GUARD_UNTIL.entrySet().removeIf(entry -> entry.getValue() <= now);
	}

	/** 玩家退出时清掉守卫，避免残留 */
	public static void forget(ServerPlayer player) {
		GUARD_UNTIL.remove(player.getUUID());
	}

	/** 自检用：累计触发次数 */
	public static int triggerCount() {
		return triggerCount;
	}

	/** 自检用：清空计数与守卫 */
	public static void reset() {
		triggerCount = 0;
		GUARD_UNTIL.clear();
	}
}
