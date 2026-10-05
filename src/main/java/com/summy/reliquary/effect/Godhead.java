package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingDamageEvent;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 「神性」的被动（1.5.5）：继承天启的属性与光柱、救恩半径扩大、8 格光环审判、
 * 环境伤害免疫、以及死亡拦截（回到重生点）。
 *
 * <p>它占用「启示之座」，与伯列恒之星 / 终末天启互斥；只继承**属性与光柱**，不继承坐标揭示。
 */
public final class Godhead {
	/** 死亡拦截的落地金色粒子（与光柱同款的"金色粒子"） */
	private static final SimpleParticleType TELEPORT_PARTICLE = ParticleTypes.WAX_ON;
	/** 落地粒子数量与散布 */
	private static final int TELEPORT_PARTICLES = 20;
	private static final double TELEPORT_SPREAD = 0.5D;

	/** 直接按原版伤害类型判定免疫的（这些没有现成的标签） */
	private static final Set<ResourceKey<DamageType>> IMMUNE_TYPES = Set.of(
			DamageTypes.CACTUS,          // 仙人掌
			DamageTypes.CRAMMING,        // 挤压
			DamageTypes.FALLING_ANVIL,   // 铁砧坠落
			DamageTypes.FLY_INTO_WALL,   // 动能
			DamageTypes.IN_WALL,         // 窒息
			DamageTypes.FREEZE);         // 冰冻细雪
	/** 按标签判定的免疫（燃烧与岩浆、坠落、溺水、闪电、爆炸、冰冻） */
	private static final List<TagKey<DamageType>> IMMUNE_TAGS = List.of(
			DamageTypeTags.IS_FIRE,
			DamageTypeTags.IS_FALL,
			DamageTypeTags.IS_DROWNING,
			DamageTypeTags.IS_LIGHTNING,
			DamageTypeTags.IS_EXPLOSION,
			DamageTypeTags.IS_FREEZING);

	/** 自检用：死亡拦截次数 */
	private static int deathGuardCount;
	/** 1.8.2：死亡拦截后的 2 秒无敌（清掉全部状态效果的补偿窗口） */
	private static final int GUARD_TICKS = 40;
	/** 无敌截止时刻（游戏 tick） */
	private static final Map<UUID, Long> GUARD_UNTIL = new HashMap<>();

	private Godhead() {
	}

	/** 佩戴神性且总开关打开 */
	public static boolean active(LivingEntity entity) {
		return entity != null && ReliquaryConfig.enableGodhead()
				&& CurioHelper.wears(entity, SummyReliquary.GODHEAD.get());
	}

	/**
	 * 能否使用「启示之光」（V 键）：终末天启**或**神性（1.5.6 起继承）。
	 *
	 * <p>客户端蓄力门槛与服务端的发射 / 充能 / 中断判定统一走这里，保证"只戴神性也能放光柱"。
	 */
	public static boolean hasRevelation(LivingEntity entity) {
		return entity != null && (CurioHelper.wears(entity, SummyReliquary.FINAL_REVELATION.get())
				|| active(entity));
	}

	// ==================== 光柱覆盖值（客户端与服务端共用） ====================

	public static int beamChargeTicks(LivingEntity entity) {
		return active(entity) ? ReliquaryConfig.godheadBeamChargeTicks() : ReliquaryConfig.beamChargeTicks();
	}

	public static int beamCooldownTicks(LivingEntity entity) {
		return active(entity) ? ReliquaryConfig.godheadBeamCooldownTicks() : ReliquaryConfig.beamCooldownTicks();
	}

	public static int beamLength(LivingEntity entity) {
		return active(entity) ? ReliquaryConfig.godheadBeamLength() : ReliquaryConfig.beamLength();
	}

	public static int beamRadius(LivingEntity entity) {
		return active(entity) ? ReliquaryConfig.godheadBeamRadius() : ReliquaryConfig.beamRadius();
	}

	/** 救恩领域半径：佩戴神性时扩大（配置默认 5 格） */
	public static double salvationRadius(LivingEntity entity) {
		return ReliquaryConfig.godheadSalvationRadius();
	}

	// ==================== 8 格光环审判 ====================

	/** 服务端每秒：对光环内目标结算圣光伤害（真实伤害口径） */
	public static void tickAura(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0 || !ReliquaryConfig.enableGodhead()) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!active(player)) {
				continue;
			}
			judgeAuraNow(player);
		}
	}

	/** 单次光环结算（自检直接调用，避免依赖秒边界） */
	public static int judgeAuraNow(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		double radius = ReliquaryConfig.godheadAuraRadius();
		int damage = ReliquaryConfig.godheadAuraDamage();
		int hits = 0;
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate(radius), entity -> canJudge(player, entity))) {
			if (target.distanceTo(player) > radius) {
				continue;
			}
			// 1.5.7：死亡文本沿用「被圣光审判了」（新类型 godhead_aura 的 message_id 指向同一键）。
			// 1.6.4：真伤口径改由伤害标签实现（godhead_aura 写进 bypasses_armor/effects/enchantments/resistance），
			// 不再依赖 LivingDamageEvent 的金额校正 —— 原版「圣光」本身必须保持"保护与抗性仍生效"。
			// 并且**不击退**：结算前后保存 / 恢复速度（原版 hurt 会施加减速与击退）
			Vec3 velocity = target.getDeltaMovement();
			RevelationBeam.applyTrueDamage(level, player, target, damage, "godhead_aura", false);
			target.setDeltaMovement(velocity);
			target.hurtMarked = true;
			hits++;
		}
		return hits;
	}

	/** 自检用：该目标是否属于光环的审判对象（玩家目标复用救恩名单） */
	public static boolean isAuraTarget(ServerPlayer caster, LivingEntity target) {
		return canJudge(caster, target);
	}

	/** 自检用：光环使用的伤害类型路径（1.6.4 起是独立的 godhead_aura，死亡文本仍沿用圣光那条） */
	public static String auraDamageTypePath() {
		return "godhead_aura";
	}

	/** 敌对生物、对佩戴者有仇恨的生物、以及（复用救恩名单的）其他玩家 */
	private static boolean canJudge(ServerPlayer caster, LivingEntity target) {
		if (target == caster || !target.isAlive()) {
			return false;
		}
		if (target instanceof Player) {
			return SalvationDomain.isTargetable(target);
		}
		if (target instanceof Enemy) {
			return true;
		}
		// 训练人偶（dummmmmmy:target_dummy）：默认也吃光环，可用开关关掉
		if (DummySupport.isTargetDummy(target)) {
			return true;
		}
		return target instanceof Mob mob && mob.getTarget() == caster;
	}

	// ==================== 环境伤害免疫 ====================

	/** 佩戴神性时，清单里的原版环境伤害全部免疫 */
	public static boolean isEnvironmentImmune(ServerPlayer player, DamageSource source) {
		if (!active(player)) {
			return false;
		}
		for (TagKey<DamageType> tag : IMMUNE_TAGS) {
			if (source.is(tag)) {
				return true;
			}
		}
		for (ResourceKey<DamageType> key : IMMUNE_TYPES) {
			if (source.is(key)) {
				return true;
			}
		}
		return false;
	}

	// ==================== 死亡拦截 ====================

	/**
	 * 致命伤拦截：伤害清零、保留 1 点生命，并传送回个人重生点（没有则当前维度世界出生点），
	 * 落地时在脚下撒一小把金色粒子。按需求**不设冷却**。
	 */
	/**
	 * 神性死亡拦截（1.6.4：改成"命中前整击拦下"，不再依赖 {@code LivingDamageEvent} 的金额）。
	 *
	 * <p>调用方（{@link DamagePools}）会在返回 true 后把这一击整击并入原版吸收值 ——
	 * 于是血量不会被扣，事后也不需要再改事件数值。副作用与旧版一致：**保留 1 点生命 + 传送回重生点**。
	 *
	 * @param healthPart 估算"会打到生命值"的伤害（见 {@link DamageEstimate}）
	 * @return true 表示这次被神性拦下（免死不再参与这一击）
	 */
	public static boolean tryNullify(ServerPlayer player, float healthPart) {
		if (player == null || !active(player) || healthPart < player.getHealth()) {
			return false;
		}
		player.setHealth(1.0F);
		// 1.8.2：拦截时清除全部状态效果，并给 2 秒无敌作为补偿
		player.removeAllEffects();
		GUARD_UNTIL.put(player.getUUID(), player.level().getGameTime() + GUARD_TICKS);
		deathGuardCount++;
		// 1.8.2：把「被拦截的地点」记为回溯点（X 可回到这里）
		PlayerFlags.setLastDeath(player, player.level().dimension().location().toString(),
				player.getX(), player.getY(), player.getZ());
		teleportToRespawn(player);
		return true;
	}

	/** 死亡拦截后的 2 秒无敌：取消一切来源的伤害 */
	public static void onHurt(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
		if (event.getEntity() instanceof ServerPlayer player && isGuarded(player)) {
			event.setAmount(0.0F);
			event.setCanceled(true);
		}
	}

	/** 是否处于死亡拦截后的无敌窗口 */
	public static boolean isGuarded(ServerPlayer player) {
		Long until = GUARD_UNTIL.get(player.getUUID());
		return until != null && player.level().getGameTime() < until;
	}

	/** 服务端每 tick：清理过期的无敌记录 */
	public static void tickPlayer(ServerPlayer player) {
		Long until = GUARD_UNTIL.get(player.getUUID());
		if (until != null && player.level().getGameTime() >= until) {
			GUARD_UNTIL.remove(player.getUUID());
		}
	}

	/** 玩家退出 / 服务端停止：清掉无敌记录 */
	public static void forget(ServerPlayer player) {
		GUARD_UNTIL.remove(player.getUUID());
	}

	public static void clearGuards() {
		GUARD_UNTIL.clear();
	}

	/** 自检用：2 秒无敌的 tick 数 */
	public static int guardTicks() {
		return GUARD_TICKS;
	}

	private static void teleportToRespawn(ServerPlayer player) {
		// 1.7.2：实现挪到共用的 util/RespawnTeleport（创世纪也要用同一套语义），行为不变
		com.summy.reliquary.util.RespawnTeleport.teleport(player);
	}

	// ==================== 自检接口 ====================

	public static int deathGuardCount() {
		return deathGuardCount;
	}

	public static void reset() {
		deathGuardCount = 0;
	}

	/** 自检用：死亡拦截的落地粒子类型（应为金色 WAX_ON） */
	public static SimpleParticleType teleportParticle() {
		return com.summy.reliquary.util.RespawnTeleport.particle();
	}

	/** 自检用：免疫清单是否命中该伤害源 */
	public static boolean immuneTo(DamageSource source) {
		for (TagKey<DamageType> tag : IMMUNE_TAGS) {
			if (source.is(tag)) {
				return true;
			}
		}
		for (ResourceKey<DamageType> key : IMMUNE_TYPES) {
			if (source.is(key)) {
				return true;
			}
		}
		return false;
	}

}
