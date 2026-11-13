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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 厄咒之印：给敌人施加带可视化图标的诅咒，增伤并把同一 tick 最高的一笔伤害转移给其它受诅咒单位。
 */
public final class HexSeal {
	private static final double[] CAST_RING_FRACTIONS = {0.35D, 0.7D, 1.0D};
	private static final int[] CAST_RING_PARTICLES = {12, 20, 32};
	private static final int CAST_RAY_COUNT = 8;
	private static final int CAST_RAY_SAMPLES = 7;
	private static final Map<UUID, Mark> MARKS = new HashMap<>();
	private static final Map<UUID, PendingTransfer> TRANSFERS = new HashMap<>();
	private static boolean applyingTransfer;
	private static Boolean forcedProc;

	private HexSeal() {
	}

	public static boolean wears(LivingEntity entity) {
		return entity != null && ReliquaryConfig.enableHexSeal()
				&& CurioHelper.wears(entity, SummyReliquary.HEX_SEAL.get())
				&& !PlayerFlags.isDeathForm(entity);
	}

	/**
	 * 受诅咒目标受伤 +50%，并登记本 tick 的转移候选；不处理转移伤害自身。
	 */
	public static void onIncomingHurt(LivingIncomingDamageEvent event) {
		if (applyingTransfer || event.isCanceled() || event.getAmount() <= 0.0F) {
			return;
		}
		LivingEntity target = event.getEntity();
		Mark mark = activeMark(target);
		if (mark == null) {
			return;
		}
		DamageSource source = event.getSource();
		// 2.3：裁决附伤不吃诅咒增伤，也不触发转移；转移伤害自身绝不递归。
		if (isSacredVerdict(source) || isHexTransfer(source)) {
			return;
		}
		double percent = ReliquaryConfig.hexSealDamageTakenPercent();
		event.setAmount((float) (event.getAmount() * (1.0D + percent / 100.0D)));
		double transfer = event.getAmount() * ReliquaryConfig.hexSealTransferPercent() / 100.0D;
		if (transfer <= 0.0D) {
			return;
		}
		long now = target.level().getGameTime();
		TRANSFERS.merge(mark.owner(), new PendingTransfer(now, transfer, target.getUUID(),
				target.level().dimension().location().toString()), (old, fresh) ->
				old.tick() == fresh.tick() && old.amount() >= fresh.amount() ? old : fresh);
	}

	/** 普通玩家伤害可独立掷骰施加诅咒。 */
	public static void onOutgoingHurt(LivingIncomingDamageEvent event) {
		if (event.isCanceled() || event.getAmount() <= 0.0F
				|| (HolyLightEffect.isDivine(event.getSource())
						&& !HolyLightEffect.isGoldenRazor(event.getSource()))) {
			return;
		}
		Entity entity = event.getSource().getEntity();
		if (!(entity instanceof ServerPlayer attacker) || attacker == event.getEntity() || !wears(attacker)) {
			return;
		}
		boolean proc = forcedProc != null ? forcedProc
				: attacker.getRandom().nextFloat() * 100.0F < ReliquaryConfig.hexSealProcChancePercent();
		if (proc) {
			applyMark(event.getEntity(), attacker.getUUID(), ReliquaryConfig.hexSealProcTicks());
		}
	}

	/** R：前方扇形内所有敌人施加 30 秒诅咒。 */
	public static boolean activate(ServerPlayer player) {
		if (!wears(player)) {
			return false;
		}
		Vec3 look = player.getLookAngle().normalize();
		double range = ReliquaryConfig.hexSealCastRange();
		double halfAngle = Math.toRadians(
				Math.max(1.0D, Math.min(360.0D, ReliquaryConfig.hexSealCastAngleDegrees())) / 2.0D);
		double cosine = Math.cos(halfAngle);
		List<LivingEntity> targets = player.serverLevel().getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate(range),
				entity -> Godhead.isAuraTarget(player, entity) && entity.distanceTo(player) <= range
						&& normalizedOffset(player, entity).dot(look) >= cosine);
		long now = player.serverLevel().getGameTime();
		for (LivingEntity target : targets) {
			applyMark(target, player.getUUID(), ReliquaryConfig.hexSealCastTicks());
		}
		drawCastCone(player, look, halfAngle, range);
		return true;
	}

	private static void drawCastCone(ServerPlayer player, Vec3 look, double halfAngle, double range) {
		Vec3 fallback = new Vec3(player.getDirection().getStepX(), 0.0D,
				player.getDirection().getStepZ());
		Vec3[] basis = castBasis(look, fallback);
		Vec3 forward = basis[0];
		Vec3 right = basis[1];
		Vec3 up = basis[2];
		Vec3 origin = player.getEyePosition();
		ServerLevel level = player.serverLevel();

		for (int ring = 0; ring < CAST_RING_FRACTIONS.length; ring++) {
			int count = CAST_RING_PARTICLES[ring];
			double distance = range * CAST_RING_FRACTIONS[ring];
			for (int index = 0; index < count; index++) {
				double phi = Math.PI * 2.0D * index / count;
				Vec3 point = coneBoundaryPointForTest(origin, forward, right, up,
						halfAngle, distance, phi);
				level.sendParticles(ring == CAST_RING_FRACTIONS.length - 1
								? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.SOUL,
						point.x, point.y, point.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			}
		}

		for (int ray = 0; ray < CAST_RAY_COUNT; ray++) {
			double phi = Math.PI * 2.0D * ray / CAST_RAY_COUNT;
			Vec3 direction = coneDirection(forward, right, up, halfAngle, phi);
			for (int sample = 1; sample <= CAST_RAY_SAMPLES; sample++) {
				double distance = 1.0D + (range - 1.0D) * sample / CAST_RAY_SAMPLES;
				Vec3 point = origin.add(direction.scale(distance));
				level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
						point.x, point.y, point.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
			}
		}
	}

	public static Vec3[] castBasisForTest(Vec3 look, Vec3 fallbackHorizontal) {
		return castBasis(look, fallbackHorizontal);
	}

	public static Vec3 coneBoundaryPointForTest(Vec3 origin, Vec3 forward, Vec3 right, Vec3 up,
			double halfAngle, double distance, double phi) {
		return origin.add(coneDirection(forward, right, up, halfAngle, phi).scale(distance));
	}

	private static Vec3[] castBasis(Vec3 look, Vec3 fallbackHorizontal) {
		Vec3 forward = look.lengthSqr() < 1.0E-8D ? new Vec3(0.0D, 0.0D, -1.0D) : look.normalize();
		Vec3 right = forward.cross(new Vec3(0.0D, 1.0D, 0.0D));
		if (right.lengthSqr() < 1.0E-8D) {
			Vec3 fallback = fallbackHorizontal.lengthSqr() < 1.0E-8D
					? new Vec3(1.0D, 0.0D, 0.0D) : fallbackHorizontal.normalize();
			right = forward.cross(fallback);
		}
		if (right.lengthSqr() < 1.0E-8D) {
			right = new Vec3(1.0D, 0.0D, 0.0D);
		}
		right = right.normalize();
		Vec3 up = right.cross(forward).normalize();
		return new Vec3[]{forward, right, up};
	}

	private static Vec3 coneDirection(Vec3 forward, Vec3 right, Vec3 up,
			double halfAngle, double phi) {
		Vec3 radial = right.scale(Math.cos(phi)).add(up.scale(Math.sin(phi))).normalize();
		double sin = Math.sin(halfAngle);
		if (sin < 1.0E-5D) {
			// 配置为 360° 时锥体边界退化；用完整正交基上的方向代替，仍能显示球形范围。
			return radial;
		}
		return forward.scale(Math.cos(halfAngle)).add(radial.scale(sin)).normalize();
	}

	/** 服务端 tick 末尾统一结算：每个施法者只取本 tick 最高的一笔。 */
	public static void tickServer(MinecraftServer server) {
		long now = server.overworld().getGameTime();
		MARKS.entrySet().removeIf(entry -> {
			if (entry.getValue().until() > now) {
				return false;
			}
			LivingEntity target = CovenantMarkerEffects.findEntity(server, entry.getKey());
			CovenantMarkerEffects.remove(target, SummyReliquary.HEX_SEAL_EFFECT.get());
			return true;
		});
		synchronizeMarkers(server);
		if (TRANSFERS.isEmpty()) {
			return;
		}
		List<Map.Entry<UUID, PendingTransfer>> batch = new ArrayList<>(TRANSFERS.entrySet());
		TRANSFERS.clear();
		for (Map.Entry<UUID, PendingTransfer> entry : batch) {
			ServerPlayer owner = server.getPlayerList().getPlayer(entry.getKey());
			PendingTransfer transfer = entry.getValue();
			if (owner == null) {
				continue;
			}
			ServerLevel ownerLevel = owner.serverLevel();
			for (Map.Entry<UUID, Mark> marked : List.copyOf(MARKS.entrySet())) {
				if (!marked.getValue().owner().equals(owner.getUUID())
						|| marked.getKey().equals(transfer.sourceTarget())) {
					continue;
				}
				Entity entity = ownerLevel.getEntity(marked.getKey());
				if (!(entity instanceof LivingEntity target) || !target.isAlive()
						|| !target.level().dimension().location().toString().equals(transfer.dimension())) {
					continue;
				}
				applyingTransfer = true;
				try {
					target.invulnerableTime = 0;
					TrueDamageLimits.hurtWithoutKnockback(
							target, transferSource(ownerLevel, owner), (float) transfer.amount());
				} finally {
					applyingTransfer = false;
				}
			}
		}
	}

	public static void onDeath(LivingEntity entity) {
		CovenantMarkerEffects.remove(entity, SummyReliquary.HEX_SEAL_EFFECT.get());
		Mark mark = activeMark(entity);
		if (mark == null) {
			MARKS.remove(entity.getUUID());
			return;
		}
		long now = entity.level().getGameTime();
		int half = (int) Math.floor((mark.until() - now) / 2.0D);
		MARKS.remove(entity.getUUID());
		if (half < 20) {
			return;
		}
		double radius = ReliquaryConfig.hexSealSpreadRadius();
		for (LivingEntity nearby : entity.level().getEntitiesOfClass(LivingEntity.class,
				entity.getBoundingBox().inflate(radius),
				candidate -> candidate != entity && !(candidate instanceof Player)
						&& candidate.distanceTo(entity) <= radius)) {
			applyMark(nearby, mark.owner(), half);
		}
	}

	public static boolean marked(LivingEntity entity) {
		return activeMark(entity) != null;
	}

	public static int remainingTicks(LivingEntity entity) {
		Mark mark = activeMark(entity);
		return mark == null ? 0 : (int) Math.max(0L, mark.until() - entity.level().getGameTime());
	}

	/** 实体加入世界时清掉重启遗留、但服务端已不存在的图标。 */
	public static void reconcileMarker(LivingEntity entity) {
		Mark mark = activeMark(entity);
		if (mark == null) {
			CovenantMarkerEffects.remove(entity, SummyReliquary.HEX_SEAL_EFFECT.get());
			return;
		}
		CovenantMarkerEffects.mirror(entity, SummyReliquary.HEX_SEAL_EFFECT.get(), mark.until());
	}

	public static void clear() {
		MARKS.clear();
		TRANSFERS.clear();
		applyingTransfer = false;
		forcedProc = null;
	}

	public static void setForcedProcForTest(Boolean value) {
		forcedProc = value;
	}

	public static void applyMarkForTest(LivingEntity target, UUID owner, int ticks) {
		applyMark(target, owner, Math.max(1, ticks));
	}

	public static int pendingTransferCountForTest() {
		return TRANSFERS.size();
	}

	public static void removeOwner(ServerPlayer owner) {
		if (owner == null) {
			return;
		}
		UUID ownerId = owner.getUUID();
		for (Map.Entry<UUID, Mark> entry : List.copyOf(MARKS.entrySet())) {
			if (!entry.getValue().owner().equals(ownerId)) {
				continue;
			}
			LivingEntity target = CovenantMarkerEffects.findEntity(owner.getServer(), entry.getKey());
			CovenantMarkerEffects.remove(target, SummyReliquary.HEX_SEAL_EFFECT.get());
			MARKS.remove(entry.getKey());
		}
		TRANSFERS.remove(ownerId);
	}

	private static void applyMark(LivingEntity target, UUID owner, int ticks) {
		if (target == null || !target.isAlive() || ticks <= 0) {
			return;
		}
		long until = target.level().getGameTime() + ticks;
		Mark merged = MARKS.merge(target.getUUID(), new Mark(owner, until), (old, fresh) ->
				new Mark(fresh.owner(), Math.max(old.until(), fresh.until())));
		CovenantMarkerEffects.mirror(target, SummyReliquary.HEX_SEAL_EFFECT.get(), merged.until());
	}

	private static void synchronizeMarkers(MinecraftServer server) {
		for (Map.Entry<UUID, Mark> entry : MARKS.entrySet()) {
			LivingEntity target = CovenantMarkerEffects.findEntity(server, entry.getKey());
			if (target != null && target.isAlive()) {
				CovenantMarkerEffects.mirror(target,
						SummyReliquary.HEX_SEAL_EFFECT.get(), entry.getValue().until());
			}
		}
	}

	private static Mark activeMark(LivingEntity entity) {
		if (entity == null) {
			return null;
		}
		Mark mark = MARKS.get(entity.getUUID());
		if (mark == null) {
			return null;
		}
		if (mark.until() <= entity.level().getGameTime()) {
			MARKS.remove(entity.getUUID());
			return null;
		}
		return mark;
	}

	private static Vec3 normalizedOffset(ServerPlayer player, LivingEntity target) {
		Vec3 offset = target.getEyePosition().subtract(player.getEyePosition());
		return offset.lengthSqr() < 1.0E-6D ? Vec3.ZERO : offset.normalize();
	}

	private static boolean isSacredVerdict(DamageSource source) {
		return HolyLightEffect.isSacredVerdictDamage(source);
	}

	private static boolean isHexTransfer(DamageSource source) {
		return source != null && "summy-reliquary.hex_transfer".equals(HolyLightEffect.typeId(source));
	}

	private static DamageSource transferSource(ServerLevel level, ServerPlayer owner) {
		var registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
		ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE,
				SummyReliquary.id("hex_transfer"));
		var holder = registry.getHolder(key).orElse(null);
		return holder == null ? level.damageSources().magic()
				: new DamageSource(holder, owner, owner);
	}

	private record Mark(UUID owner, long until) {
	}

	private record PendingTransfer(long tick, double amount, UUID sourceTarget, String dimension) {
	}
}
