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
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.level.ExplosionEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 神圣干预：减伤后、吸收前的反伤，以及固定在空间中的不可破坏屏障。
 */
public final class DivineIntervention {
	public static final String PROJECTILE_BOOST_TAG = "summy_reliquary_divine_intervention_boosts";
	private static final List<Barrier> BARRIERS = new ArrayList<>();
	private static final Map<UUID, Vec3> LAST_PROJECTILE_POS = new HashMap<>();

	private DivineIntervention() {
	}

	public static boolean wears(LivingEntity entity) {
		return entity != null && ReliquaryConfig.enableDivineIntervention()
				&& CurioHelper.wears(entity, SummyReliquary.DIVINE_INTERVENTION.get())
				&& !PlayerFlags.isDeathForm(entity);
	}

	/** R：在脚底高度、视线前方展开固定屏障。 */
	public static boolean activate(ServerPlayer player) {
		if (!wears(player)) {
			return false;
		}
		Vec3 look = player.getLookAngle();
		Vec3 horizontal = new Vec3(look.x, 0.0D, look.z);
		if (horizontal.lengthSqr() < 1.0E-6D) {
			horizontal = new Vec3(1.0D, 0.0D, 0.0D);
		}
		horizontal = horizontal.normalize();
		Vec3 normal = new Vec3(look.x, 0.0D, look.z).normalize();
		Vec3 right = new Vec3(-normal.z, 0.0D, normal.x);
		Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
		double halfWidth = ReliquaryConfig.divineInterventionBarrierWidth() / 2.0D;
		double height = ReliquaryConfig.divineInterventionBarrierHeight();
		Vec3 center = new Vec3(player.getX(), player.getY() + height / 2.0D, player.getZ())
				.add(normal.scale(ReliquaryConfig.divineInterventionBarrierDistance()));
		BARRIERS.removeIf(barrier -> barrier.owner().equals(player.getUUID()));
		BARRIERS.add(new Barrier(player.serverLevel(), player.getUUID(), center, normal, right, up,
				halfWidth, height / 2.0D, player.serverLevel().getGameTime()
						+ ReliquaryConfig.divineInterventionBarrierTicks()));
		return true;
	}

	/** 受击反伤：基数取护甲 / 抗性 / 保护之后、吸收与魂心黑心之前。 */
	public static void onIncomingHurt(LivingHurtEvent event) {
		if (event.isCanceled() || event.getAmount() <= 0.0F
				|| !(event.getEntity() instanceof ServerPlayer player) || !wears(player)) {
			return;
		}
		if (HolyLightEffect.isCovenantEffect(event.getSource())) {
			return;
		}
		Entity attackerEntity = event.getSource().getEntity();
		if (!(attackerEntity instanceof LivingEntity attacker) || attacker == player) {
			return;
		}
		float base = DamageEstimate.beforeAbsorption(player, event.getSource(), event.getAmount());
		float reflected = (float) (base * ReliquaryConfig.divineInterventionReflectPercent() / 100.0D);
		if (reflected <= 0.0F) {
			return;
		}
		TrueDamageLimits.hurt(attacker, reflectSource(player.serverLevel(), player), reflected);
	}

	/** 玩家投射物穿过屏障后只提升本次命中的原版伤害，不进入任何增伤名单。 */
	public static void onProjectileHurt(LivingHurtEvent event) {
		if (event.isCanceled() || event.getAmount() <= 0.0F || event.getSource().getDirectEntity() == null) {
			return;
		}
		var data = event.getSource().getDirectEntity().getPersistentData();
		int boosts = data.getInt(PROJECTILE_BOOST_TAG);
		if (boosts <= 0) {
			return;
		}
		double percent = ReliquaryConfig.divineInterventionProjectileDamagePercent();
		event.setAmount((float) (event.getAmount()
				* Math.pow(1.0D + percent / 100.0D, boosts)));
	}

	public static void tickServer(MinecraftServer server) {
		long now = server.overworld().getGameTime();
		BARRIERS.removeIf(barrier -> barrier.endTick() < now);
		for (Barrier barrier : BARRIERS) {
			drawEdges(barrier);
			AABB area = barrier.bounds().inflate(8.0D);
			for (Projectile projectile : barrier.level().getEntitiesOfClass(Projectile.class, area)) {
				Vec3 previous = LAST_PROJECTILE_POS.put(projectile.getUUID(),
						new Vec3(projectile.xo, projectile.yo, projectile.zo));
				if (previous == null) {
					continue;
				}
				Vec3 current = projectile.position();
				if (!crosses(barrier, previous, current)) {
					continue;
				}
				Entity owner = projectile.getOwner();
				if (owner instanceof net.minecraft.world.entity.player.Player) {
					var data = projectile.getPersistentData();
					data.putInt(PROJECTILE_BOOST_TAG, data.getInt(PROJECTILE_BOOST_TAG) + 1);
					barrier.level().sendParticles(ParticleTypes.END_ROD, current.x, current.y, current.z,
							12, 0.18D, 0.18D, 0.18D, 0.02D);
				} else {
					projectile.discard();
					barrier.level().sendParticles(ParticleTypes.END_ROD, current.x, current.y, current.z,
							20, 0.25D, 0.25D, 0.25D, 0.04D);
				}
			}
		}
		LAST_PROJECTILE_POS.keySet().removeIf(id -> BARRIERS.stream()
				.noneMatch(barrier -> barrier.level().getEntity(id) != null));
	}

	/** 爆炸中心到方块 / 实体中心的射线被任一屏障截断时，该目标不再受影响。 */
	public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
		Explosion explosion = event.getExplosion();
		ServerLevel level = (ServerLevel) event.getLevel();
		Vec3 origin = explosion.getPosition();
		event.getAffectedBlocks().removeIf(block -> {
			Vec3 target = Vec3.atCenterOf(block);
			return blocked(level, origin, target);
		});
		event.getAffectedEntities().removeIf(entity -> blocked(level, origin, entity.position()));
	}

	public static void forget(ServerPlayer player) {
		if (player != null) {
			BARRIERS.removeIf(barrier -> barrier.owner().equals(player.getUUID()));
			LAST_PROJECTILE_POS.remove(player.getUUID());
		}
	}

	public static void clear() {
		BARRIERS.clear();
		LAST_PROJECTILE_POS.clear();
	}

	public static int barrierCount() {
		return BARRIERS.size();
	}

	private static boolean blocked(ServerLevel level, Vec3 from, Vec3 to) {
		for (Barrier barrier : BARRIERS) {
			if (barrier.level() == level && segmentHits(barrier, from, to)) {
				return true;
			}
		}
		return false;
	}

	private static boolean crosses(Barrier barrier, Vec3 previous, Vec3 current) {
		return segmentHits(barrier, previous, current);
	}

	private static boolean segmentHits(Barrier barrier, Vec3 from, Vec3 to) {
		Vec3 delta = to.subtract(from);
		double denominator = delta.dot(barrier.normal());
		if (Math.abs(denominator) < 1.0E-7D) {
			return false;
		}
		Vec3 offset = barrier.center().subtract(from);
		double t = offset.dot(barrier.normal()) / denominator;
		if (t < 0.0D || t > 1.0D) {
			return false;
		}
		Vec3 point = from.add(delta.scale(t)).subtract(barrier.center());
		double right = Math.abs(point.dot(barrier.right()));
		double up = Math.abs(point.dot(barrier.up()));
		return right <= barrier.halfWidth() && up <= barrier.halfHeight();
	}

	private static void drawEdges(Barrier barrier) {
		int count = ReliquaryConfig.divineInterventionEdgeParticles();
		if (count <= 0) {
			return;
		}
		for (int index = 0; index < count; index++) {
			double progress = (index + 0.5D) / count;
			int edge = index % 4;
			double horizontal = edge < 2 ? -1.0D + progress * 2.0D : (edge == 2 ? -1.0D : 1.0D);
			double vertical = edge < 2 ? (edge == 0 ? -1.0D : 1.0D) : -1.0D + progress * 2.0D;
			Vec3 point = barrier.center()
					.add(barrier.right().scale(horizontal * barrier.halfWidth()))
					.add(barrier.up().scale(vertical * barrier.halfHeight()));
			barrier.level().sendParticles(ParticleTypes.END_ROD, point.x, point.y, point.z,
					1, 0.01D, 0.01D, 0.01D, 0.0D);
		}
	}

	private static DamageSource reflectSource(ServerLevel level, ServerPlayer player) {
		var registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
		ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE,
				SummyReliquary.id("divine_intervention_reflect"));
		var holder = registry.getHolder(key).orElse(null);
		return holder == null ? level.damageSources().magic()
				: new DamageSource(holder, player, player);
	}

	private record Barrier(ServerLevel level, UUID owner, Vec3 center, Vec3 normal, Vec3 right, Vec3 up,
			double halfWidth, double halfHeight, long endTick) {
		private AABB bounds() {
			double width = halfWidth;
			double height = halfHeight;
			return new AABB(
					center.x - Math.abs(right.x) * width - Math.abs(up.x) * height,
					center.y - Math.abs(right.y) * width - Math.abs(up.y) * height,
					center.z - Math.abs(right.z) * width - Math.abs(up.z) * height,
					center.x + Math.abs(right.x) * width + Math.abs(up.x) * height,
					center.y + Math.abs(right.y) * width + Math.abs(up.y) * height,
					center.z + Math.abs(right.z) * width + Math.abs(up.z) * height);
		}
	}
}
