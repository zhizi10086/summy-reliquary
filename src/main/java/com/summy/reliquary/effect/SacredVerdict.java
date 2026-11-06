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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 神圣裁决：普通命中概率施加标记；已标记目标在命中冷却完整时追加最大生命百分比真伤。
 */
public final class SacredVerdict {
	public static final String PROJECTILE_ELIGIBLE_TAG = "summy_reliquary_sacred_verdict_eligible";
	public static final String CAST_DAMAGE_TYPE = "sacred_verdict";
	public static final String EMPOWERED_CAST_DAMAGE_TYPE = "sacred_verdict_empowered";
	public static final String BONUS_DAMAGE_TYPE = "sacred_verdict_bonus";

	/** R 在实际冷却之外再加 1 秒请求锁，覆盖重复包、Connector 重入与相邻 tick 重放。 */
	private static final long CAST_LOCK_TICKS = 20L;
	/** 合法攻击间隔最低为 5 tick；同一语义命中的重复确认在 5 tick 内只结算一次。 */
	private static final long BONUS_LOCK_TICKS = 5L;
	/** Hurt 与 Damage 属于同一次受击，允许 Connector 跨 tick 派发。 */
	private static final long CANDIDATE_MATCH_TICKS = 3L;
	private static final long DUPLICATE_LOG_INTERVAL_TICKS = 100L;
	private static final UUID NO_DIRECT_ENTITY = new UUID(0L, 0L);

	private static final Map<UUID, Mark> MARKS = new HashMap<>();
	private static final List<Candidate> CANDIDATES = new ArrayList<>();
	private static final List<Confirmed> CONFIRMED = new ArrayList<>();
	private static final Map<UUID, Long> LAST_CAST_TICK = new HashMap<>();
	private static final Map<UUID, Long> LAST_CAST_TRACE = new HashMap<>();
	private static final Map<HitKey, Long> LAST_BONUS_TICK = new HashMap<>();
	private static final Map<String, Long> LAST_DUPLICATE_LOG = new HashMap<>();
	private static final ThreadLocal<Integer> APPLYING_DEPTH = ThreadLocal.withInitial(() -> 0);
	private static final AtomicLong NEXT_CAST_TRACE_ID = new AtomicLong();
	private static final AtomicLong NEXT_BONUS_TRACE_ID = new AtomicLong();
	private static Boolean forcedProc;
	private static Boolean forcedReady;
	private static int suppressedCastCount;
	private static int suppressedBonusCount;

	private SacredVerdict() {
	}

	public static boolean wears(LivingEntity entity) {
		return entity != null && ReliquaryConfig.enableSacredVerdict()
				&& CurioHelper.wears(entity, SummyReliquary.SACRED_VERDICT.get())
				&& !PlayerFlags.isDeathForm(entity);
	}

	/** 普通命中：只记录候选，真正落地后由 {@link #confirmLanding} 确认。 */
	public static void recordOutgoing(LivingHurtEvent event) {
		if (isApplyingDamage() || event.isCanceled() || event.getAmount() <= 0.0F
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

		HitKey key = hitKey(attacker, target, event.getSource());
		String damageType = HolyLightEffect.typeId(event.getSource());
		boolean ready = attackReady(attacker, event.getSource());
		boolean hadMark = mark != null;
		for (int index = 0; index < CANDIDATES.size(); index++) {
			Candidate existing = CANDIDATES.get(index);
			if (!existing.key().equals(key)
					|| !withinWindow(now, existing.tick(), CANDIDATE_MATCH_TICKS)) {
				continue;
			}
			// 同一受击即使由不同 DamageSource 实例重复派发，也合并为同一个候选。
			CANDIDATES.set(index, new Candidate(existing.tick(), key,
					mergeDamageTypes(existing.damageTypes(), damageType),
					existing.ready() || ready, existing.hadMark() || hadMark));
			return;
		}
		CANDIDATES.add(new Candidate(now, key, List.of(damageType), ready, hadMark));
	}

	/**
	 * {@code LivingDamageEvent} 表示原伤害已进入落地管线（护盾后、生命前）；此处确认候选，
	 * 附伤延后到 server tick 末结算，避免递归扰动原伤害。
	 */
	public static void confirmLanding(LivingDamageEvent event) {
		long now = event.getEntity().level().getGameTime();
		HitKey key = hitKey(null, event.getEntity(), event.getSource());
		for (int index = CANDIDATES.size() - 1; index >= 0; index--) {
			Candidate candidate = CANDIDATES.get(index);
			if (!candidate.key().equals(key)
					|| !withinWindow(now, candidate.tick(), CANDIDATE_MATCH_TICKS)) {
				continue;
			}
			CANDIDATES.remove(index);
			CONFIRMED.add(new Confirmed(candidate.tick(), now, candidate.key(),
					candidate.damageTypes(), candidate.ready(), candidate.hadMark()));
			// 一个 LivingDamageEvent 只确认一次；同击的后续重复事件没有候选可确认。
			break;
		}
	}

	/** Server tick END：结算已确认的附伤与新标记，并清理过期候选。 */
	public static void tickServer(MinecraftServer server) {
		long serverNow = server.overworld().getGameTime();
		MARKS.entrySet().removeIf(entry -> entry.getValue().until() <= serverNow);
		synchronizeMarkers(server);
		LAST_CAST_TICK.entrySet().removeIf(entry ->
				!withinWindow(serverNow, entry.getValue(), CAST_LOCK_TICKS));
		LAST_CAST_TRACE.entrySet().removeIf(entry ->
				!LAST_CAST_TICK.containsKey(entry.getKey()));
		LAST_BONUS_TICK.entrySet().removeIf(entry ->
				!withinWindow(serverNow, entry.getValue(), BONUS_LOCK_TICKS));
		LAST_DUPLICATE_LOG.entrySet().removeIf(entry ->
				!withinWindow(serverNow, entry.getValue(), DUPLICATE_LOG_INTERVAL_TICKS));
		CANDIDATES.removeIf(candidate ->
				!withinWindow(serverNow, candidate.tick(), CANDIDATE_MATCH_TICKS));
		CONFIRMED.removeIf(confirmed ->
				confirmed.confirmedTick() > serverNow
						|| serverNow - confirmed.confirmedTick() >= CANDIDATE_MATCH_TICKS);
		if (CONFIRMED.isEmpty()) {
			return;
		}

		Map<BatchKey, Confirmed> merged = new LinkedHashMap<>();
		for (Confirmed confirmed : CONFIRMED) {
			BatchKey batchKey = new BatchKey(confirmed.key(), confirmed.confirmedTick());
			Confirmed existing = merged.get(batchKey);
			if (existing == null) {
				merged.put(batchKey, confirmed);
			} else {
				merged.put(batchKey, new Confirmed(
						Math.min(existing.recordTick(), confirmed.recordTick()),
						confirmed.confirmedTick(), confirmed.key(),
						mergeDamageTypes(existing.damageTypes(), confirmed.damageTypes()),
						existing.ready() || confirmed.ready(),
						existing.hadMark() || confirmed.hadMark()));
			}
		}
		List<Confirmed> batch = new ArrayList<>(merged.values());
		CONFIRMED.clear();
		for (Confirmed confirmed : batch) {
			ServerPlayer attacker = server.getPlayerList().getPlayer(confirmed.key().attacker());
			if (attacker == null || !wears(attacker)) {
				continue;
			}
			Entity found = attacker.serverLevel().getEntity(confirmed.key().target());
			if (!(found instanceof LivingEntity target) || !target.isAlive()) {
				continue;
			}
			long now = attacker.serverLevel().getGameTime();
			boolean bonusEligible = confirmed.hadMark() && confirmed.ready();
			if (bonusEligible && withinWindow(now, LAST_BONUS_TICK.get(confirmed.key()),
					BONUS_LOCK_TICKS)) {
				// 同一次命中的重复确认静默丢弃，连新标记概率也不再重掷。
				suppressedBonusCount++;
				logDuplicateBonus(attacker, target, confirmed.damageTypes(), confirmed.key(), now,
						LAST_BONUS_TICK.get(confirmed.key()));
				continue;
			}
			if (bonusEligible) {
				LAST_BONUS_TICK.put(confirmed.key(), now);
				float bonus = (float) (target.getMaxHealth()
						* ReliquaryConfig.sacredVerdictBonusDamagePercent() / 100.0D);
				if (bonus > 0.0F) {
					target.invulnerableTime = 0;
					long bonusTrace = NEXT_BONUS_TRACE_ID.incrementAndGet();
					DamageObservation observation = new DamageObservation(target);
					SacredVerdictTrace.begin("附伤", bonusTrace, attacker, target,
							BONUS_DAMAGE_TYPE, bonus);
					boolean landed;
					try {
						landed = withApplyingDamage(() -> TrueDamageLimits.hurt(target,
								source(attacker.serverLevel(), attacker, BONUS_DAMAGE_TYPE), bonus));
					} finally {
						SacredVerdictTrace.finish();
					}
					logBonusSettlement(bonusTrace, attacker, target, confirmed.damageTypes(),
							confirmed.key(), bonus, observation.lost(target), landed, now);
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
		if (isApplyingDamage()) {
			suppressedCastCount++;
			long tick = player.serverLevel().getGameTime();
			logDuplicateCast(player, tick, LAST_CAST_TICK.get(player.getUUID()),
					LAST_CAST_TRACE.get(player.getUUID()), "执行中重入");
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
		Long lastCast = LAST_CAST_TICK.get(player.getUUID());
		if (withinWindow(now, lastCast, CAST_LOCK_TICKS)) {
			// 同一 R 请求、重复包及执行中重入只允许展开一次爆炸。
			suppressedCastCount++;
			logDuplicateCast(player, now, lastCast,
					LAST_CAST_TRACE.get(player.getUUID()), "冷却锁");
			return false;
		}
		LAST_CAST_TICK.put(player.getUUID(), now);
		long castTrace = NEXT_CAST_TRACE_ID.incrementAndGet();
		LAST_CAST_TRACE.put(player.getUUID(), castTrace);
		String castType = castDamageType(ReliquaryConfig.exactDamageGetsMultiplier());
		List<LivingEntity> targets = new ArrayList<>(level.getEntitiesOfClass(LivingEntity.class,
				new AABB(center, center).inflate(radius),
				entity -> Godhead.isAuraTarget(player, entity)));
		// 实体射线目标也稳定参与爆炸，避免 Connector 返回实体命中点却遗漏 AABB 成员。
		if (hit instanceof EntityHitResult entityHit
				&& entityHit.getEntity() instanceof LivingEntity living
				&& Godhead.isAuraTarget(player, living)
				&& !targets.contains(living)) {
			targets.add(living);
		}
		logCastStart(castTrace, player, castType, targets.size(), damage, now);
		withApplyingDamage(() -> {
			for (LivingEntity target : targets) {
				target.invulnerableTime = 0;
				DamageObservation observation = new DamageObservation(target);
				SacredVerdictTrace.begin("施法", castTrace, player, target, castType, damage);
				boolean landed;
				try {
					landed = TrueDamageLimits.hurt(target,
							source(level, player, castType), damage);
				} finally {
					SacredVerdictTrace.finish();
				}
				logCastSettlement(castTrace, player, target, castType,
						damage, observation.lost(target), landed, now);
				if (target.isAlive()) {
					applyMark(target, now + ReliquaryConfig.sacredVerdictCastMarkTicks());
				}
			}
			return null;
		});
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
		CovenantMarkerEffects.remove(entity, SummyReliquary.SACRED_VERDICT_EFFECT.get());
		LAST_BONUS_TICK.keySet().removeIf(key -> key.target().equals(entity.getUUID()));
	}

	public static boolean marked(LivingEntity entity) {
		Mark mark = entity == null ? null : MARKS.get(entity.getUUID());
		return mark != null && mark.until() > entity.level().getGameTime();
	}

	/** 实体加入世界时清掉重启遗留、但服务端已不存在的图标。 */
	public static void reconcileMarker(LivingEntity entity) {
		if (entity == null) {
			return;
		}
		Mark mark = MARKS.get(entity.getUUID());
		if (mark == null || mark.until() <= entity.level().getGameTime()) {
			MARKS.remove(entity.getUUID());
			CovenantMarkerEffects.remove(entity, SummyReliquary.SACRED_VERDICT_EFFECT.get());
			return;
		}
		CovenantMarkerEffects.mirror(entity, SummyReliquary.SACRED_VERDICT_EFFECT.get(), mark.until());
	}

	public static void clear() {
		MARKS.clear();
		CANDIDATES.clear();
		CONFIRMED.clear();
		LAST_CAST_TICK.clear();
		LAST_CAST_TRACE.clear();
		LAST_BONUS_TICK.clear();
		LAST_DUPLICATE_LOG.clear();
		APPLYING_DEPTH.remove();
		forcedProc = null;
		forcedReady = null;
		suppressedCastCount = 0;
		suppressedBonusCount = 0;
		NEXT_CAST_TRACE_ID.set(0L);
		NEXT_BONUS_TRACE_ID.set(0L);
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

	/** 自检用：清空施法 / 附伤幂等记录，不影响已有裁决标记。 */
	public static void clearDebounceForTest() {
		LAST_CAST_TICK.clear();
		LAST_CAST_TRACE.clear();
		LAST_BONUS_TICK.clear();
		LAST_DUPLICATE_LOG.clear();
		suppressedCastCount = 0;
		suppressedBonusCount = 0;
	}

	/** 同一语义附伤在 5 tick 内重复时返回 true。 */
	public static boolean shouldDebounce(long now, Long lastTick) {
		return withinWindow(now, lastTick, BONUS_LOCK_TICKS);
	}

	/** 同一玩家 R 请求在 20 tick 内重复时返回 true。 */
	public static boolean shouldDebounceCast(long now, Long lastTick) {
		return withinWindow(now, lastTick, CAST_LOCK_TICKS);
	}

	/** R 爆炸按现有定值真伤开关选择是否进入全伤害乘区。 */
	public static String castDamageType(boolean empowered) {
		return empowered ? EMPOWERED_CAST_DAMAGE_TYPE : CAST_DAMAGE_TYPE;
	}

	public static int suppressedCastCountForTest() {
		return suppressedCastCount;
	}

	public static int suppressedBonusCountForTest() {
		return suppressedBonusCount;
	}

	public static void applyMarkForTest(LivingEntity target, int ticks) {
		applyMark(target, target.level().getGameTime() + Math.max(1, ticks));
	}

	public static void confirmForTest(ServerPlayer attacker, LivingEntity target, DamageSource source,
			boolean ready) {
		boolean hadMark = marked(target);
		HitKey key = hitKey(attacker, target, source);
		long now = target.level().getGameTime();
		CONFIRMED.add(new Confirmed(now, now, key,
				List.of(HolyLightEffect.typeId(source)), ready, hadMark));
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
		Mark merged = MARKS.merge(target.getUUID(), new Mark(until), (old, fresh) ->
				new Mark(Math.max(old.until(), fresh.until())));
		CovenantMarkerEffects.mirror(target, SummyReliquary.SACRED_VERDICT_EFFECT.get(), merged.until());
	}

	private static void synchronizeMarkers(MinecraftServer server) {
		for (Map.Entry<UUID, Mark> entry : MARKS.entrySet()) {
			LivingEntity target = CovenantMarkerEffects.findEntity(server, entry.getKey());
			if (target != null && target.isAlive()) {
				CovenantMarkerEffects.mirror(target,
						SummyReliquary.SACRED_VERDICT_EFFECT.get(), entry.getValue().until());
			}
		}
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

	private static DamageSource source(ServerLevel level, ServerPlayer player, String typePath) {
		var registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
		ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE,
				SummyReliquary.id(typePath));
		var holder = registry.getHolder(key).orElse(null);
		// 数据包缺失时回退到旧裁决类型；补包不完整时仍保持“不增伤”的保守口径。
		if (holder == null && !CAST_DAMAGE_TYPE.equals(typePath)) {
			key = ResourceKey.create(Registries.DAMAGE_TYPE, SummyReliquary.id(CAST_DAMAGE_TYPE));
			holder = registry.getHolder(key).orElse(null);
		}
		return holder == null ? level.damageSources().magic()
				: new DamageSource(holder, player, player);
	}

	private static HitKey hitKey(ServerPlayer attackerFallback, LivingEntity target, DamageSource source) {
		Entity attacker = attackerFallback != null ? attackerFallback : source.getEntity();
		Entity direct = source.getDirectEntity();
		return new HitKey(attacker == null ? NO_DIRECT_ENTITY : attacker.getUUID(),
				target.getUUID(), direct == null ? NO_DIRECT_ENTITY : direct.getUUID());
	}

	/** 伤害类型只用于日志；语义去重键不包含它。 */
	private static List<String> mergeDamageTypes(List<String> existing, String incoming) {
		if (existing.contains(incoming)) {
			return existing;
		}
		List<String> merged = new ArrayList<>(existing);
		merged.add(incoming);
		return List.copyOf(merged);
	}

	private static List<String> mergeDamageTypes(List<String> first, List<String> second) {
		if (first.containsAll(second)) {
			return first;
		}
		List<String> merged = new ArrayList<>(first);
		for (String damageType : second) {
			if (!merged.contains(damageType)) {
				merged.add(damageType);
			}
		}
		return List.copyOf(merged);
	}

	private static boolean withinWindow(long now, Long previous, long ticks) {
		return previous != null && now >= previous && now - previous < ticks;
	}

	private static boolean isApplyingDamage() {
		return APPLYING_DEPTH.get() > 0;
	}

	private static <T> T withApplyingDamage(Supplier<T> action) {
		APPLYING_DEPTH.set(APPLYING_DEPTH.get() + 1);
		try {
			return action.get();
		} finally {
			int depth = APPLYING_DEPTH.get() - 1;
			if (depth <= 0) {
				APPLYING_DEPTH.remove();
			} else {
				APPLYING_DEPTH.set(depth);
			}
		}
	}

	private static void logCastStart(long traceId, ServerPlayer player, String damageType,
			int targetCount, float baseDamage, long tick) {
		SummyReliquary.LOGGER.info(
				"神圣裁决施法开始：序号={}、玩家={}、类型={}、目标数={}、基础={}、tick={}",
				traceId, player.getName().getString(), damageType, targetCount, baseDamage, tick);
	}

	private static void logCastSettlement(long traceId, ServerPlayer player, LivingEntity target,
			String damageType, float baseDamage, float actualLoss, boolean landed, long tick) {
		SummyReliquary.LOGGER.info(
				"神圣裁决施法结算：序号={}、玩家={}、目标={}、类型={}、基础={}、实际={}、落地={}、tick={}",
				traceId, player.getName().getString(), target.getName().getString(),
				damageType, baseDamage, actualLoss, landed, tick);
	}

	private static void logBonusSettlement(long traceId, ServerPlayer attacker, LivingEntity target,
			List<String> damageTypes, HitKey key, float baseDamage, float actualLoss, boolean landed,
			long tick) {
		SummyReliquary.LOGGER.info(
				"神圣裁决附伤结算：序号={}、攻击者={}、目标={}、触发类型={}、输出类型={}、基础={}、"
						+ "实际={}、落地={}、命中键={}、tick={}",
				traceId, attacker.getName().getString(), target.getName().getString(),
				String.join(" + ", damageTypes), BONUS_DAMAGE_TYPE,
				baseDamage, actualLoss, landed, key, tick);
	}

	private static void logDuplicateCast(ServerPlayer player, long now, Long lastTick,
			Long lastTraceId, String reason) {
		String key = "cast:" + player.getUUID();
		if (!shouldLogDuplicate(key, now)) {
			return;
		}
		SummyReliquary.LOGGER.info(
				"神圣裁决重复施法已拦截：玩家={}、原因={}、tick={}、上次 tick={}、上次序号={}",
				player.getName().getString(), reason, now, lastTick, lastTraceId);
	}

	private static void logDuplicateBonus(ServerPlayer attacker, LivingEntity target,
			List<String> damageTypes, HitKey key, long now, Long lastTick) {
		String logKey = "bonus:" + key;
		if (!shouldLogDuplicate(logKey, now)) {
			return;
		}
		SummyReliquary.LOGGER.info(
				"神圣裁决重复附伤已拦截：攻击者={}、目标={}、触发类型={}、直接实体={}、tick={}、上次={}",
				attacker.getName().getString(), target.getName().getString(),
				String.join(" + ", damageTypes),
				key.directEntity(), now, lastTick);
	}

	private static boolean shouldLogDuplicate(String key, long now) {
		Long last = LAST_DUPLICATE_LOG.get(key);
		if (withinWindow(now, last, DUPLICATE_LOG_INTERVAL_TICKS)) {
			return false;
		}
		LAST_DUPLICATE_LOG.put(key, now);
		return true;
	}

	private record Mark(long until) {
	}

	private record Candidate(long tick, HitKey key, List<String> damageTypes, boolean ready,
			boolean hadMark) {
	}

	private record Confirmed(long recordTick, long confirmedTick, HitKey key,
			List<String> damageTypes, boolean ready, boolean hadMark) {
	}

	private record BatchKey(HitKey key, long confirmedTick) {
	}

	private record HitKey(UUID attacker, UUID target, UUID directEntity) {
	}

	private record DamageObservation(float healthBefore, float absorptionBefore) {
		private DamageObservation(LivingEntity target) {
			this(target.getHealth(), target.getAbsorptionAmount());
		}

		private float lost(LivingEntity target) {
			return Math.max(0.0F, healthBefore - target.getHealth())
					+ Math.max(0.0F, absorptionBefore - target.getAbsorptionAmount());
		}
	}
}
