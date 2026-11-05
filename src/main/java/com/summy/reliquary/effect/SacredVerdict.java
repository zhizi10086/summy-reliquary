package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 神圣裁决：普通命中概率施加标记；已标记目标在命中冷却完整时追加最大生命百分比真伤。
 */
public final class SacredVerdict {
	public static final String PROJECTILE_ELIGIBLE_TAG = "summy_reliquary_sacred_verdict_eligible";
	private static final Map<UUID, Mark> MARKS = new HashMap<>();
	private static final List<Candidate> CANDIDATES = new ArrayList<>();
	private static final List<Confirmed> CONFIRMED = new ArrayList<>();
	private static boolean applyingBonus;
	private static Boolean forcedProc;
	private static Boolean forcedReady;

	private SacredVerdict() {
	}

	public static boolean wears(LivingEntity entity) {
		return entity != null && ReliquaryConfig.enableSacredVerdict()
				&& CurioHelper.wears(entity, SummyReliquary.SACRED_VERDICT.get())
				&& !PlayerFlags.isDeathForm(entity);
	}

	/** 普通命中：只记录候选，真正落地后由 {@link #confirmLanding} 确认。 */
	public static void recordOutgoing(LivingHurtEvent event) {
		if (applyingBonus || event.isCanceled() || event.getAmount() <= 0.0F
				|| (HolyLightEffect.isDivine(event.getSource())
						&& !HolyLightEffect.isGoldenRazor(event.getSource()))) {
			return;
		}
		Entity attackerEntity = event.getSource().getEntity();
		if (!(attackerEntity instanceof ServerPlayer attacker) || attacker == event.getEntity()
				|| !wears(attacker)) {
			return;
		}
		LivingEntity target = event.getEntity();
		long now = attacker.serverLevel().getGameTime();
		Mark mark = MARKS.get(target.getUUID());
		if (mark != null && mark.until() <= now) {
			MARKS.remove(target.getUUID());
			mark = null;
		}
		// Kilt / Connector 等环境可能对同一击重复派发 LivingHurtEvent。
		// 同一 tick 的同攻击者、同目标、同 DamageSource 实例只登记一次。
		for (Candidate existing : CANDIDATES) {
			if (existing.tick() == now
					&& existing.target().equals(target.getUUID())
					&& existing.attacker().equals(attacker.getUUID())
					&& existing.source() == event.getSource()) {
				return;
			}
		}
		CANDIDATES.add(new Candidate(now, target.getUUID(), attacker.getUUID(), event.getSource(),
				attackReady(attacker, event.getSource()), mark != null));
	}

	/**
	 * {@code LivingDamageEvent} 表示原伤害已进入落地管线（护盾后、生命前）；此处确认候选，
	 * 附伤延后到 server tick 末结算，避免递归扰动原伤害。
	 */
	public static void confirmLanding(LivingDamageEvent event) {
		long now = event.getEntity().level().getGameTime();
		for (int index = CANDIDATES.size() - 1; index >= 0; index--) {
			Candidate candidate = CANDIDATES.get(index);
			if (candidate.tick() != now || !candidate.target().equals(event.getEntity().getUUID())
					|| candidate.source() != event.getSource()) {
				continue;
			}
			CANDIDATES.remove(index);
			CONFIRMED.add(new Confirmed(candidate.attacker(), candidate.target(), candidate.tick(),
					candidate.ready(), candidate.hadMark()));
			// 一个 LivingDamageEvent 只确认一次，剩余同组候选在 tickServer 开头统一丢弃。
			break;
		}
	}

	/** Server tick END：结算已确认的附伤与新标记，并清掉未落地的候选。 */
	public static void tickServer(MinecraftServer server) {
		if (CANDIDATES.isEmpty() && CONFIRMED.isEmpty()) {
			return;
		}
		CANDIDATES.clear();
		Map<BonusKey, Confirmed> merged = new LinkedHashMap<>();
		for (Confirmed confirmed : CONFIRMED) {
			BonusKey key = new BonusKey(confirmed.attacker(), confirmed.target(), confirmed.tick());
			Confirmed existing = merged.get(key);
			if (existing == null || (!existing.hadMark() && confirmed.hadMark())) {
				merged.put(key, confirmed);
			}
		}
		List<Confirmed> batch = new ArrayList<>(merged.values());
		CONFIRMED.clear();
		for (Confirmed confirmed : batch) {
			ServerPlayer attacker = server.getPlayerList().getPlayer(confirmed.attacker());
			if (attacker == null || !wears(attacker)) {
				continue;
			}
			Entity found = attacker.serverLevel().getEntity(confirmed.target());
			if (!(found instanceof LivingEntity target) || !target.isAlive()) {
				continue;
			}
			long now = attacker.serverLevel().getGameTime();
			if (confirmed.hadMark() && confirmed.ready()) {
				float bonus = (float) (target.getMaxHealth()
						* ReliquaryConfig.sacredVerdictBonusDamagePercent() / 100.0D);
				if (bonus > 0.0F) {
					applyingBonus = true;
					try {
						target.invulnerableTime = 0;
						TrueDamageLimits.hurt(target, source(attacker.serverLevel(), attacker), bonus);
					} finally {
						applyingBonus = false;
					}
				}
			}
			boolean proc = forcedProc != null ? forcedProc
					: attacker.getRandom().nextFloat() * 100.0F
							< ReliquaryConfig.sacredVerdictProcChancePercent();
			if (target.isAlive() && proc) {
				applyMark(target, now + ReliquaryConfig.sacredVerdictProcTicks());
			}
		}
	}

	/** 投射物生成时固化“冷却 / 蓄力是否满足”的前置，命中时不再读取已经变化的玩家状态。 */
	public static void onProjectileJoin(EntityJoinLevelEvent event) {
		if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Projectile projectile)
				|| !(projectile.getOwner() instanceof ServerPlayer owner) || !wears(owner)) {
			return;
		}
		boolean ready;
		if (projectile instanceof com.summy.reliquary.entity.ThrownSpear
				|| projectile instanceof com.summy.reliquary.entity.ThrownRazor) {
			ready = true;
		} else if (projectile instanceof AbstractArrow) {
			ItemStack use = owner.getUseItem();
			if (use.getItem() instanceof BowItem) {
				ready = owner.getTicksUsingItem() >= 20;
			} else if (use.getItem() instanceof CrossbowItem) {
				ready = CrossbowItem.isCharged(use) || owner.getTicksUsingItem() == 0;
			} else {
				ready = true;
			}
		} else {
			ready = true;
		}
		projectile.getPersistentData().putBoolean(PROJECTILE_ELIGIBLE_TAG, ready);
	}

	/** R：命中方块或实体后释放半径爆炸，并给范围内敌人施加裁决。 */
	public static boolean activate(ServerPlayer player) {
		if (!wears(player)) {
			return false;
		}
		HitResult hit = raycast(player, ReliquaryConfig.sacredVerdictCastRange());
		if (hit == null || hit.getType() == HitResult.Type.MISS) {
			player.displayClientMessage(
					net.minecraft.network.chat.Component.translatable(
							"item.summy-reliquary.sacred_verdict.fail.no_target"),
					true);
			return false;
		}
		Vec3 center = hit.getLocation();
		ServerLevel level = player.serverLevel();
		double radius = ReliquaryConfig.sacredVerdictCastRadius();
		float damage = ReliquaryConfig.sacredVerdictCastDamage();
		long now = level.getGameTime();
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
				new AABB(center, center).inflate(radius),
				entity -> Godhead.isAuraTarget(player, entity))) {
			target.invulnerableTime = 0;
			TrueDamageLimits.hurt(target, source(level, player), damage);
			if (target.isAlive()) {
				applyMark(target, now + ReliquaryConfig.sacredVerdictCastMarkTicks());
			}
		}
		for (int index = 0; index < 96; index++) {
			double angle = Math.PI * 2.0D * index / 96.0D;
			double distance = radius * (index % 3 + 1) / 3.0D;
			level.sendParticles(ParticleTypes.END_ROD,
					center.x + Math.cos(angle) * distance,
					center.y + (index % 5) * 0.15D,
					center.z + Math.sin(angle) * distance,
					1, 0.02D, 0.02D, 0.02D, 0.01D);
		}
		level.sendParticles(ParticleTypes.FLASH, center.x, center.y, center.z,
				1, 0.0D, 0.0D, 0.0D, 0.0D);
		return true;
	}

	public static void onDeath(LivingEntity entity) {
		MARKS.remove(entity.getUUID());
	}

	public static boolean marked(LivingEntity entity) {
		Mark mark = entity == null ? null : MARKS.get(entity.getUUID());
		return mark != null && mark.until() > entity.level().getGameTime();
	}

	public static void clear() {
		MARKS.clear();
		CANDIDATES.clear();
		CONFIRMED.clear();
		applyingBonus = false;
		forcedProc = null;
		forcedReady = null;
	}

	public static void setForcedProcForTest(Boolean value) {
		forcedProc = value;
	}

	public static void setForcedReadyForTest(Boolean value) {
		forcedReady = value;
	}

	/** 自检用：当前尚未由 LivingDamageEvent 确认的候选数。 */
	public static int pendingCandidateCountForTest() {
		return CANDIDATES.size();
	}

	public static void applyMarkForTest(LivingEntity target, int ticks) {
		applyMark(target, target.level().getGameTime() + Math.max(1, ticks));
	}

	public static void confirmForTest(ServerPlayer attacker, LivingEntity target, DamageSource source,
			boolean ready) {
		boolean hadMark = marked(target);
		CONFIRMED.add(new Confirmed(attacker.getUUID(), target.getUUID(),
				target.level().getGameTime(), ready, hadMark));
	}

	private static boolean attackReady(ServerPlayer attacker, DamageSource source) {
		if (forcedReady != null) {
			return forcedReady;
		}
		Entity direct = source.getDirectEntity();
		if (direct instanceof Projectile projectile) {
			return projectile.getPersistentData().getBoolean(PROJECTILE_ELIGIBLE_TAG);
		}
		// 非投射物技能成功释放时通常已有满攻击强度；近战连点则会被这条门槛拦住。
		return attacker.getAttackStrengthScale(0.5F) >= 0.999F;
	}

	private static void applyMark(LivingEntity target, long until) {
		MARKS.merge(target.getUUID(), new Mark(until), (old, fresh) ->
				new Mark(Math.max(old.until(), fresh.until())));
	}

	private static HitResult raycast(ServerPlayer player, double range) {
		Vec3 start = player.getEyePosition();
		Vec3 end = start.add(player.getLookAngle().scale(range));
		BlockHitResult block = player.level().clip(new net.minecraft.world.level.ClipContext(
				start, end, net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.NONE, player));
		EntityHitResult entity = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
				player, start, end, player.getBoundingBox().expandTowards(end.subtract(start)).inflate(1.0D),
				candidate -> candidate instanceof LivingEntity living && Godhead.isAuraTarget(player, living),
				range * range);
		if (entity == null) {
			return block;
		}
		if (block == null || block.getType() == HitResult.Type.MISS
				|| entity.getLocation().distanceToSqr(start) < block.getLocation().distanceToSqr(start)) {
			return entity;
		}
		return block;
	}

	private static DamageSource source(ServerLevel level, ServerPlayer player) {
		var registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
		ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE,
				SummyReliquary.id("sacred_verdict"));
		var holder = registry.getHolder(key).orElse(null);
		return holder == null ? level.damageSources().magic()
				: new DamageSource(holder, player, player);
	}

	private record Mark(long until) {
	}

	private record Candidate(long tick, UUID target, UUID attacker, DamageSource source, boolean ready,
			boolean hadMark) {
	}

	private record Confirmed(UUID attacker, UUID target, long tick, boolean ready, boolean hadMark) {
	}

	private record BonusKey(UUID attacker, UUID target, long tick) {
	}
}
