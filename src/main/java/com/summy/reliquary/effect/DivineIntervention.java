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
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.level.ExplosionEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 神圣干预：减伤后、吸收前的反伤，以及固定在空间中的不可破坏屏障。
 */
public final class DivineIntervention {
	public static final String PROJECTILE_BOOST_TAG = "summy_reliquary_divine_intervention_boosts";
	/** 旧配置可能仍是 8；只要描边未显式关闭，就按每格约 1.5 点保证屏障轮廓可见。 */
	private static final double MIN_EDGE_PARTICLES_PER_BLOCK = 1.5D;
	private static final int PROJECTILE_RECONCILE_TICKS = 20;
	private static final List<Barrier> BARRIERS = new ArrayList<>();
	/** 只为有屏障的维度维护投射物 UUID，避免每 tick 扫描普通实体。 */
	private static final Map<ServerLevel, ProjectileIndex> PROJECTILE_INDEXES = new HashMap<>();
	private static int projectileSeedScans;
	private static int projectileReconcileScans;

	private DivineIntervention() {
	}

	public static boolean wears(LivingEntity entity) {
		return entity != null && ReliquaryConfig.enableDivineIntervention()
				&& CurioHelper.wears(entity, SummyReliquary.DIVINE_INTERVENTION.get())
				&& !PlayerFlags.isDeathForm(entity);
	}

	/** R：沿完整视线方向展开固定屏障。 */
	public static boolean activate(ServerPlayer player) {
		if (!wears(player)) {
			return false;
		}
		Vec3[] basis = barrierBasis(player.getLookAngle(), player.getYRot());
		Vec3 normal = basis[0];
		Vec3 right = basis[1];
		Vec3 up = basis[2];
		double halfWidth = ReliquaryConfig.divineInterventionBarrierWidth() / 2.0D;
		double height = ReliquaryConfig.divineInterventionBarrierHeight();
		Vec3 center = new Vec3(player.getX(), player.getY() + player.getBbHeight() / 2.0D, player.getZ())
				.add(normal.scale(ReliquaryConfig.divineInterventionBarrierDistance()));
		BARRIERS.removeIf(barrier -> barrier.owner().equals(player.getUUID()));
		BARRIERS.add(new Barrier(player.serverLevel(), player.getUUID(), center, normal, right, up,
				halfWidth, height / 2.0D, player.serverLevel().getGameTime()
						+ ReliquaryConfig.divineInterventionBarrierTicks()));
		ensureProjectileIndex(player.serverLevel());
		releaseUnusedProjectileIndexes();
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
		releaseUnusedProjectileIndexes();
		if (BARRIERS.isEmpty()) {
			return;
		}
		Map<ServerLevel, List<Barrier>> barriersByLevel = new HashMap<>();
		for (Barrier barrier : BARRIERS) {
			barriersByLevel.computeIfAbsent(barrier.level(), ignored -> new ArrayList<>()).add(barrier);
			drawEdges(barrier);
		}
		for (Map.Entry<ServerLevel, List<Barrier>> entry : barriersByLevel.entrySet()) {
			ServerLevel level = entry.getKey();
			List<Barrier> barriers = entry.getValue();
			ProjectileIndex index = ensureProjectileIndex(level);
			if (now - index.lastReconcileTick >= PROJECTILE_RECONCILE_TICKS) {
				reconcileProjectiles(level, index);
			}
			for (UUID projectileId : List.copyOf(index.projectileIds)) {
				Entity entity = level.getEntity(projectileId);
				if (!(entity instanceof Projectile projectile) || !projectile.isAlive()) {
					index.projectileIds.remove(projectileId);
					continue;
				}
				Vec3 previous = new Vec3(projectile.xo, projectile.yo, projectile.zo);
				Vec3 current = projectile.position();
				for (Barrier barrier : barriers) {
					if (!crosses(barrier, previous, current)) {
						continue;
					}
					Entity owner = projectile.getOwner();
					if (owner instanceof net.minecraft.world.entity.player.Player) {
						var data = projectile.getPersistentData();
						data.putInt(PROJECTILE_BOOST_TAG, data.getInt(PROJECTILE_BOOST_TAG) + 1);
						level.sendParticles(ParticleTypes.END_ROD, current.x, current.y, current.z,
								12, 0.18D, 0.18D, 0.18D, 0.02D);
					} else {
						projectile.discard();
						index.projectileIds.remove(projectileId);
						level.sendParticles(ParticleTypes.END_ROD, current.x, current.y, current.z,
								20, 0.25D, 0.25D, 0.25D, 0.04D);
						break;
					}
				}
			}
		}
	}

	/** 投射物加入世界：只有该维度已有屏障索引时才登记。 */
	public static void onProjectileJoin(EntityJoinLevelEvent event) {
		if (event.getLevel().isClientSide() || !(event.getLevel() instanceof ServerLevel level)
				|| !(event.getEntity() instanceof Projectile projectile)) {
			return;
		}
		ProjectileIndex index = PROJECTILE_INDEXES.get(level);
		if (index != null) {
			index.projectileIds.add(projectile.getUUID());
		}
	}

	/** 投射物离开世界：立即从索引删除，避免等待低频对账。 */
	public static void onProjectileLeave(EntityLeaveLevelEvent event) {
		if (event.getLevel().isClientSide() || !(event.getLevel() instanceof ServerLevel level)
				|| !(event.getEntity() instanceof Projectile projectile)) {
			return;
		}
		ProjectileIndex index = PROJECTILE_INDEXES.get(level);
		if (index != null) {
			index.projectileIds.remove(projectile.getUUID());
		}
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
			releaseUnusedProjectileIndexes();
		}
	}

	public static void clear() {
		BARRIERS.clear();
		PROJECTILE_INDEXES.clear();
		projectileSeedScans = 0;
		projectileReconcileScans = 0;
	}

	private static ProjectileIndex ensureProjectileIndex(ServerLevel level) {
		ProjectileIndex existing = PROJECTILE_INDEXES.get(level);
		if (existing != null) {
			return existing;
		}
		ProjectileIndex index = new ProjectileIndex(level.getGameTime());
		PROJECTILE_INDEXES.put(level, index);
		projectileSeedScans++;
		for (Entity entity : level.getAllEntities()) {
			if (entity instanceof Projectile projectile && projectile.isAlive()) {
				index.projectileIds.add(projectile.getUUID());
			}
		}
		return index;
	}

	private static void reconcileProjectiles(ServerLevel level, ProjectileIndex index) {
		projectileReconcileScans++;
		index.lastReconcileTick = level.getGameTime();
		Set<UUID> found = new HashSet<>();
		for (Entity entity : level.getAllEntities()) {
			if (entity instanceof Projectile projectile && projectile.isAlive()) {
				found.add(projectile.getUUID());
			}
		}
		index.projectileIds.retainAll(found);
		index.projectileIds.addAll(found);
	}

	private static void releaseUnusedProjectileIndexes() {
		PROJECTILE_INDEXES.keySet().removeIf(level -> BARRIERS.stream()
				.noneMatch(barrier -> barrier.level() == level));
	}

	public static int indexedProjectileCountForTest(ServerLevel level) {
		ProjectileIndex index = PROJECTILE_INDEXES.get(level);
		return index == null ? 0 : index.projectileIds.size();
	}

	public static int projectileSeedScanCountForTest() {
		return projectileSeedScans;
	}

	public static int projectileReconcileScanCountForTest() {
		return projectileReconcileScans;
	}

	public static void reconcileProjectileIndexForTest(ServerLevel level) {
		ProjectileIndex index = PROJECTILE_INDEXES.get(level);
		if (index != null) {
			reconcileProjectiles(level, index);
		}
	}

	public static int barrierCount() {
		return BARRIERS.size();
	}

	/** 自检：完整视线方向下的屏障正交基。 */
	public static Vec3[] barrierBasisForTest(Vec3 look, float yRot) {
		return barrierBasis(look, yRot);
	}

	/** 自检：当前玩家屏障的法线。 */
	public static Vec3 barrierNormalForTest(ServerPlayer player) {
		for (Barrier barrier : BARRIERS) {
			if (barrier.owner().equals(player.getUUID())) {
				return barrier.normal();
			}
		}
		return Vec3.ZERO;
	}

	/** 自检：当前玩家屏障的中心。 */
	public static Vec3 barrierCenterForTest(ServerPlayer player) {
		for (Barrier barrier : BARRIERS) {
			if (barrier.owner().equals(player.getUUID())) {
				return barrier.center();
			}
		}
		return Vec3.ZERO;
	}

	/** 自检：用指定三维正交基验证线段与矩形平面的相交。 */
	public static boolean barrierSegmentHitsForTest(Vec3 center, Vec3 normal, Vec3 right, Vec3 up,
			double halfWidth, double halfHeight, Vec3 from, Vec3 to) {
		return segmentHits(center, normal, right, up, halfWidth, halfHeight, from, to);
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
		return segmentHits(barrier.center(), barrier.normal(), barrier.right(), barrier.up(),
				barrier.halfWidth(), barrier.halfHeight(), from, to);
	}

	private static boolean segmentHits(Vec3 center, Vec3 normal, Vec3 rightAxis, Vec3 upAxis,
			double halfWidth, double halfHeight, Vec3 from, Vec3 to) {
		Vec3 delta = to.subtract(from);
		double denominator = delta.dot(normal);
		if (Math.abs(denominator) < 1.0E-7D) {
			return false;
		}
		Vec3 offset = center.subtract(from);
		double t = offset.dot(normal) / denominator;
		if (t < 0.0D || t > 1.0D) {
			return false;
		}
		Vec3 point = from.add(delta.scale(t)).subtract(center);
		double right = Math.abs(point.dot(rightAxis));
		double up = Math.abs(point.dot(upAxis));
		return right <= halfWidth && up <= halfHeight;
	}

	private static Vec3[] barrierBasis(Vec3 look, float yRot) {
		Vec3 horizontal = new Vec3(look.x, 0.0D, look.z);
		if (horizontal.lengthSqr() < 1.0E-8D) {
			double yaw = Math.toRadians(yRot);
			horizontal = new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
		}
		horizontal = horizontal.normalize();
		Vec3 normal = look.lengthSqr() < 1.0E-8D ? horizontal : look.normalize();
		Vec3 right = normal.cross(new Vec3(0.0D, 1.0D, 0.0D));
		if (right.lengthSqr() < 1.0E-8D) {
			right = normal.cross(horizontal);
		}
		if (right.lengthSqr() < 1.0E-8D) {
			right = new Vec3(1.0D, 0.0D, 0.0D);
		}
		right = right.normalize();
		Vec3 up = right.cross(normal).normalize();
		return new Vec3[]{normal, right, up};
	}

	private static void drawEdges(Barrier barrier) {
		double width = barrier.halfWidth() * 2.0D;
		double height = barrier.halfHeight() * 2.0D;
		int count = edgeParticleCount(ReliquaryConfig.divineInterventionEdgeParticles(),
				width, height);
		if (count <= 0) {
			return;
		}
		double perimeter = 2.0D * (width + height);
		// 相邻 tick 错开半个采样间距，用前后两批粒子把固定亮点补成连续轮廓。
		double phase = (barrier.level().getGameTime() & 1L) == 0L ? 0.0D : 0.5D;
		for (int index = 0; index < count; index++) {
			double distance = ((index + 0.5D + phase) / count * perimeter) % perimeter;
			double horizontal;
			double vertical;
			if (distance < width) {
				horizontal = -0.5D + distance / width;
				vertical = -0.5D;
			} else if (distance < width + height) {
				horizontal = 0.5D;
				vertical = -0.5D + (distance - width) / height;
			} else if (distance < width * 2.0D + height) {
				horizontal = 0.5D - (distance - width - height) / width;
				vertical = 0.5D;
			} else {
				horizontal = -0.5D;
				vertical = 0.5D - (distance - width * 2.0D - height) / height;
			}
			Vec3 point = barrier.center()
					.add(barrier.right().scale(horizontal * width))
					.add(barrier.up().scale(vertical * height));
			barrier.level().sendParticles(ParticleTypes.END_ROD, point.x, point.y, point.z,
					1, 0.015D, 0.015D, 0.015D, 0.0D);
		}
	}

	/** 按实际周长计算描边数量；显式配置 0 时保持关闭。 */
	public static int edgeParticleCount(int configured, double width, double height) {
		if (configured <= 0) {
			return 0;
		}
		double perimeter = 2.0D * (Math.max(0.5D, width) + Math.max(0.5D, height));
		int densityFloor = (int) Math.ceil(perimeter * MIN_EDGE_PARTICLES_PER_BLOCK);
		return Math.max(configured, densityFloor);
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
	}

	private static final class ProjectileIndex {
		private final Set<UUID> projectileIds = new HashSet<>();
		private long lastReconcileTick;

		private ProjectileIndex(long lastReconcileTick) {
			this.lastReconcileTick = lastReconcileTick;
		}
	}
}
