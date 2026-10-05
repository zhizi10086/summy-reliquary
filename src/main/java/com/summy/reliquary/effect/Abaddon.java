package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「亚巴顿」（1.6.8）：启示之座的恶魔侧终极饰品。
 *
 * <p>佩戴时提供四件事：
 * <ol>
 *     <li>复仇之魂的作用半径扩到 5 格（见 {@link VengefulSpirit#effectiveRadius}）；</li>
 *     <li>继承并强化硫磺火 —— 恶魔之焰 9 点 / 32 格 / 半径 3（见 {@code RevelationBeam}）；</li>
 *     <li>不减速的创造飞行（见 {@code AttributeManager#applyFlight}，本模组授予飞行时还免摔落伤害）；</li>
 *     <li><b>被击杀时拦截致命一击</b>：原地满血 + 清空全部负面 + 8 秒无敌 + 8 秒「恶魔光环」，
 *     冷却 1200 秒（存 NBT）。**环境伤害**（`source.getEntity()` 为空，含虚空 / 挤压 / 窒息 / 仙人掌……）
 *     只把玩家送回重生点、保持当前血量、不给任何效果也不吃冷却。</li>
 * </ol>
 *
 * <p>恶魔光环每 tick 对半径 5 格内"与神性光环同口径"的目标打 {@code abyss_aura} 真实伤害，
 * 并在球内随机撒黑烟粒子。
 */
public final class Abaddon {
	/** 8 秒无敌（取消一切来源伤害） */
	private static final Map<UUID, Long> GUARD_UNTIL = new HashMap<>();
	/** 恶魔光环剩余时间 */
	private static final Map<UUID, Long> AURA_UNTIL = new HashMap<>();
	/** 每次光环结算在球内撒的黑烟颗数（纯表现常量） */
	private static final int SMOKE_PER_TICK = 6;

	/** 自检用计数 */
	private static int reviveCount;
	private static int auraTicks;
	private static int smokeBatches;

	private Abaddon() {
	}

	/** 是否佩戴着亚巴顿（且功能已启用） */
	public static boolean wears(LivingEntity entity) {
		return entity != null && ReliquaryConfig.enableAbaddon()
				&& CurioHelper.wears(entity, SummyReliquary.ABADDON.get());
	}

	/** 环境伤害 = 没有攻击者实体（虚空 / 挤压 / 窒息 / 仙人掌 / 坠落 / 溺水 / 岩浆 / 火焰 / 饥饿……） */
	public static boolean isEnvironmentDamage(DamageSource source) {
		return source == null || source.getEntity() == null;
	}

	/** 冷却是否已经好了（未设置 = 可用） */
	public static boolean reviveReady(ServerPlayer player, long now) {
		return now >= PlayerFlags.abaddonReviveReadyAt(player);
	}

	/**
	 * 致命一击拦截（调用方：{@link DamagePools#prepare}）。
	 *
	 * <p>优先级：神性死亡拦截 → **亚巴顿** → 灵魂免死；冷却中或没戴亚巴顿时返回 false，让后面的机制接手。
	 *
	 * @param healthPart 估算"会打到生命值"的伤害
	 * @param source     本次伤害源（用来区分"被击杀"与"环境伤害"）
	 * @return true 表示这一击被拦下
	 */
	public static boolean tryNullify(ServerPlayer player, float healthPart, DamageSource source) {
		if (player == null || !wears(player) || healthPart < player.getHealth()) {
			return false;
		}
		long now = player.level().getGameTime();
		// 环境伤害：只送回重生点（保持当前血量），不给效果、不吃冷却
		if (isEnvironmentDamage(source)) {
			// 1.8.2：把「被送走的地点」记为回溯点（与神性拦截同口径）
			PlayerFlags.setLastDeath(player, player.level().dimension().location().toString(),
					player.getX(), player.getY(), player.getZ());
			teleportToRespawn(player);
			if (player.getHealth() <= 0.0F) {
				player.setHealth(1.0F);
			}
			return true;
		}
		if (!reviveReady(player, now)) {
			return false;
		}
		// 被击杀：满血 + 清空负面 + 8 秒无敌 + 8 秒恶魔光环 + 冷却
		activateForm(player, now);
		return true;
	}

	/**
	 * 1.8.2：**主动**激活恶魔形态（X 键，与被动共用同一个 1200 秒冷却）。
	 *
	 * @return true 表示成功激活（佩戴亚巴顿且冷却已好）
	 */
	public static boolean activateManually(ServerPlayer player) {
		if (player == null || !wears(player)) {
			return false;
		}
		long now = player.level().getGameTime();
		if (!reviveReady(player, now)) {
			return false;
		}
		activateForm(player, now);
		return true;
	}

	/** 恶魔形态的落地效果（被动拦截与主动激活共用） */
	private static void activateForm(ServerPlayer player, long now) {
		reviveCount++;
		player.setHealth(player.getMaxHealth());
		clearHarmfulEffects(player);
		long until = now + ReliquaryConfig.abaddonReviveTicks();
		GUARD_UNTIL.put(player.getUUID(), until);
		AURA_UNTIL.put(player.getUUID(), until);
		PlayerFlags.setAbaddonReviveReadyAt(player, now + ReliquaryConfig.abaddonReviveCooldownTicks());
		playReviveEffects(player);
	}

	/** 恶魔形态剩余冷却秒数（向上取整；0 = 可用） */
	public static int cooldownSecondsLeft(ServerPlayer player) {
		long remaining = PlayerFlags.abaddonReviveReadyAt(player) - player.level().getGameTime();
		return (int) Math.max(0L, (remaining + 19L) / 20L);
	}

	/** 无敌期间：取消一切来源的伤害（与灵魂破碎 / 神圣斗篷同一套写法） */
	public static void onHurt(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
		if (event.getEntity() instanceof ServerPlayer player && isGuarded(player)) {
			event.setAmount(0.0F);
			event.setCanceled(true);
		}
	}

	public static boolean isGuarded(ServerPlayer player) {
		Long until = GUARD_UNTIL.get(player.getUUID());
		return until != null && player.level().getGameTime() < until;
	}

	/** 服务端每 tick：恶魔光环结算 + 黑烟；顺带清理过期的无敌记录 */
	public static void tickPlayer(ServerPlayer player) {
		long now = player.level().getGameTime();
		Long guard = GUARD_UNTIL.get(player.getUUID());
		if (guard != null && now >= guard) {
			GUARD_UNTIL.remove(player.getUUID());
		}
		Long aura = AURA_UNTIL.get(player.getUUID());
		if (aura == null) {
			return;
		}
		if (now >= aura) {
			AURA_UNTIL.remove(player.getUUID());
			return;
		}
		judgeAura(player);
		spawnSmoke(player);
	}

	/** 恶魔光环：对半径内与神性光环同口径的目标打真实伤害 */
	private static void judgeAura(ServerPlayer player) {
		int damage = ReliquaryConfig.abaddonAuraDamagePerTick();
		if (damage <= 0) {
			return;
		}
		ServerLevel level = player.serverLevel();
		double radius = ReliquaryConfig.abaddonAuraRadius();
		auraTicks++;
		for (LivingEntity target : new ArrayList<>(level.getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate(radius), entity -> Godhead.isAuraTarget(player, entity)))) {
			if (target.distanceTo(player) > radius) {
				continue;
			}
			RevelationBeam.applyTrueDamage(level, player, target, (float) damage, "abyss_aura", false);
		}
	}

	/** 球内随机撒黑烟（纯表现） */
	private static void spawnSmoke(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		double radius = ReliquaryConfig.abaddonAuraRadius();
		for (int index = 0; index < SMOKE_PER_TICK; index++) {
			// 球内均匀取点：半径按立方根分布，方向取球面均匀
			double r = radius * Math.cbrt(level.random.nextDouble());
			double cosTheta = level.random.nextDouble() * 2.0D - 1.0D;
			double sinTheta = Math.sqrt(Math.max(0.0D, 1.0D - cosTheta * cosTheta));
			double phi = level.random.nextDouble() * Math.PI * 2.0D;
			double x = player.getX() + r * sinTheta * Math.cos(phi);
			double y = player.getY() + 1.0D + r * cosTheta;
			double z = player.getZ() + r * sinTheta * Math.sin(phi);
			level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
		}
		smokeBatches++;
	}

	/** 复活特效：图腾音效 + 图腾粒子 + 黑烟爆发 */
	private static void playReviveEffects(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0F, 1.0F);
		level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, player.getX(), player.getY() + 1.0D, player.getZ(),
				40, 0.6D, 1.0D, 0.6D, 0.3D);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getY() + 1.0D, player.getZ(),
				60, 1.2D, 1.0D, 1.2D, 0.02D);
		level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, player.getX(), player.getY() + 1.0D, player.getZ(),
				30, 1.0D, 1.0D, 1.0D, 0.02D);
	}

	/** 环境致死：送回重生点（没有个人重生点就用世界出生点），离开点与到达点各放一组粒子 */
	private static void teleportToRespawn(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		ServerLevel fromLevel = player.serverLevel();
		double fromX = player.getX();
		double fromY = player.getY();
		double fromZ = player.getZ();

		// 1.7.2：目标点与传送本身走共用实现（行为与原来一致），这里只负责"离开点"的粒子
		ServerLevel targetLevel = com.summy.reliquary.util.RespawnTeleport.teleport(player)
				? player.serverLevel()
				: fromLevel;
		// 离开点：黑烟 + 灵魂火
		fromLevel.sendParticles(ParticleTypes.LARGE_SMOKE, fromX, fromY + 1.0D, fromZ,
				30, 0.6D, 0.8D, 0.6D, 0.02D);
		fromLevel.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, fromX, fromY + 1.0D, fromZ,
				20, 0.5D, 0.8D, 0.5D, 0.02D);
		// 到达点：同上
		targetLevel.sendParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getY() + 1.0D, player.getZ(),
				30, 0.6D, 0.8D, 0.6D, 0.02D);
		targetLevel.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, player.getX(), player.getY() + 1.0D, player.getZ(),
				20, 0.5D, 0.8D, 0.5D, 0.02D);
	}

	/** 清空全部**负面**效果（保留正面） */
	private static void clearHarmfulEffects(ServerPlayer player) {
		List<MobEffectInstance> harmful = new ArrayList<>();
		for (MobEffectInstance instance : player.getActiveEffects()) {
			if (instance.getEffect().getCategory() == MobEffectCategory.HARMFUL) {
				harmful.add(instance);
			}
		}
		for (MobEffectInstance instance : harmful) {
			player.removeEffect(instance.getEffect());
		}
	}

	/** 玩家退出：清掉内存里的守卫与光环 */
	public static void forget(ServerPlayer player) {
		GUARD_UNTIL.remove(player.getUUID());
		AURA_UNTIL.remove(player.getUUID());
	}

	public static void clear() {
		GUARD_UNTIL.clear();
		AURA_UNTIL.clear();
	}

	// ==================== 自检接口 ====================

	public static int reviveCount() {
		return reviveCount;
	}

	public static int auraTicks() {
		return auraTicks;
	}

	public static int smokeBatches() {
		return smokeBatches;
	}

	public static long auraUntil(ServerPlayer player) {
		return AURA_UNTIL.getOrDefault(player.getUUID(), 0L);
	}

	public static long guardUntil(ServerPlayer player) {
		return GUARD_UNTIL.getOrDefault(player.getUUID(), 0L);
	}

	public static void clearGuardForTest(ServerPlayer player) {
		GUARD_UNTIL.remove(player.getUUID());
	}

	public static void clearAuraForTest(ServerPlayer player) {
		AURA_UNTIL.remove(player.getUUID());
	}

	/** 自检用：直接制造"复活无敌 + 恶魔光环"内存态（1.7.3 用来验证创世纪会把它们清掉） */
	public static void setGuardAndAuraForTest(ServerPlayer player, int ticks) {
		long until = player.level().getGameTime() + ticks;
		GUARD_UNTIL.put(player.getUUID(), until);
		AURA_UNTIL.put(player.getUUID(), until);
	}

	public static void reset() {
		reviveCount = 0;
		auraTicks = 0;
		smokeBatches = 0;
		GUARD_UNTIL.clear();
		AURA_UNTIL.clear();
	}
}
