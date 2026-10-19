package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 「圣心」的箭矢追踪（1.5.5）。
 *
 * <p>佩戴者射出的箭矢（含投掷三叉戟）会在**以箭矢自身为中心**的配置半径（默认 8 格）内寻找最近的
 * **敌对生物**，并让箭的飞行方向朝目标缓慢偏转（每 tick 插值 {@value #HOMING_STRENGTH}，保持速率）。
 * 找不到目标时不干预，也不关闭重力。
 */
public final class SacredHeart {
	/** 每 tick 朝目标偏转的比例 */
	private static final double HOMING_STRENGTH = 0.5D;
	/** 速率低于该值就不硬掰（避免原地打转的箭乱飞） */
	private static final double MIN_SPEED = 0.05D;
	/** 1.8.3：夹角小于该值（弧度，约 1.15°）就不再修正 —— 已对准时不再写速度、不置 hurtMarked */
	private static final double MIN_TURN_ANGLE = 0.02D;
	/** 1.8.3：混合结果长度平方低于该值时视为"正后方"（零向量），直接朝目标 */
	private static final double BLEND_EPSILON = 1.0E-8D;
	/** 1.8.6：连续这么多 tick 都没有变近，就转入「追猎态」（直接朝目标） */
	private static final int STALL_TICKS = 10;
	/** 1.8.6：算不算"变近了"的阈值（格）——轨道上忽远忽近不算进展 */
	private static final double PROGRESS_EPSILON = 0.02D;

	/** 1.8.6：箭矢 UUID → 停滞状态（只存内存，不写 NBT） */
	private static final Map<UUID, StallState> STALL = new HashMap<>();
	/** 初始状态：还没有过"最近距离" */
	private static final StallState INITIAL = new StallState(Double.MAX_VALUE, 0, false);

	/**
	 * 1.8.6：投射物 → 它碰过的目标 UUID 集合（键弱引用，箭消失后自动回收，无需手工清理）。
	 *
	 * <p>登记时机是 {@code ProjectileImpactEvent}（原版 {@code AbstractArrow.onHit} 内就会触发，
	 * **早于伤害结算**）—— 因此"碰过但没打成"（目标处于无敌帧 / 被完全拦下）的目标同样会被记下来，
	 * 与原版穿透箭的"已命中记账"语义一致。
	 */
	private static final Map<AbstractArrow, Set<UUID>> HIT_TARGETS = new WeakHashMap<>();

	/**
	 * 1.8.6：一支箭的跟踪状态。
	 *
	 * <p>{@code bestDistance} = 历史最近距离；{@code stallTicks} = 连续"没有变近"的 tick 数；
	 * {@code hunting} = 是否已进入追猎态（偏转强度提到 1.0，直接朝目标）。
	 */
	public record StallState(double bestDistance, int stallTicks, boolean hunting) {
	}

	private SacredHeart() {
	}

	/** 服务端每 tick：为所有佩戴者的箭矢做追踪 */
	public static void tick(MinecraftServer server) {
		if (!ReliquaryConfig.enableArrowHoming()) {
			STALL.clear();
			return;
		}
		// 2.8：按维度聚合 —— 先把佩戴者分组（维度 → 玩家 UUID → 追踪半径），
		// 每个维度只遍历一次 getAllEntities()，再用箭的 owner 查表；
		// 同维度 N 人同戴时，扫描次数由 N 次降到 1 次。
		lastScanLevels = 0;
		Map<ServerLevel, Map<UUID, Double>> wearersByLevel = new HashMap<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!CurioHelper.wears(player, SummyReliquary.SACRED_HEART.get())) {
				continue;
			}
			// 1.6.10：神性 + 圣心同时佩戴时半径扩大（配置见 [sacred_heart]）
			wearersByLevel
					.computeIfAbsent(player.serverLevel(), level -> new HashMap<>())
					.put(player.getUUID(), Synergies.sacredHeartArrowRadius(player));
		}
		// 2.8 自检：把注入的假佩戴者并进来（生产环境该表恒空）
		for (Map.Entry<ServerLevel, Map<UUID, Double>> testEntry : TEST_WEARERS.entrySet()) {
			wearersByLevel.computeIfAbsent(testEntry.getKey(), ignored -> new HashMap<>())
					.putAll(testEntry.getValue());
		}
		Set<UUID> seen = new HashSet<>();
		for (Map.Entry<ServerLevel, Map<UUID, Double>> entry : wearersByLevel.entrySet()) {
			ServerLevel level = entry.getKey();
			Map<UUID, Double> wearers = entry.getValue();
			lastScanLevels++;
			for (Entity entity : level.getAllEntities()) {
				if (!(entity instanceof AbstractArrow arrow) || !arrow.isAlive()) {
					continue;
				}
				// 1.7.10：金刀片的投掷**不吃圣心**（按需求只作用于两把天使线长矛）
				if (arrow instanceof com.summy.reliquary.entity.ThrownRazor) {
					continue;
				}
				// 2.8：owner 直接查表；不在表里 = 不是圣心佩戴者的箭
				if (arrow.getOwner() == null) {
					continue;
				}
				Double radius = wearers.get(arrow.getOwner().getUUID());
				if (radius == null) {
					continue;
				}
				LivingEntity target = nearestEnemy(level, arrow, radius);
				if (target != null) {
					// 1.8.6：停滞检测 —— 连续 STALL_TICKS 没有变近就转入追猎态（直接朝目标）
					seen.add(arrow.getUUID());
					double distance = arrow.position().distanceTo(target.getEyePosition());
					StallState state = advanceStall(STALL.getOrDefault(arrow.getUUID(), INITIAL), distance);
					STALL.put(arrow.getUUID(), state);
					steer(arrow, target, strengthFor(state));
				} else {
					// 没有目标就不再保留状态（下次重新锁定从零开始）
					STALL.remove(arrow.getUUID());
				}
			}
		}
		// 清理已消失 / 已不再追踪的条目
		STALL.keySet().retainAll(seen);
	}

	/** 2.8 自检用：上一次 tick 实际扫描了几个维度（同一维度 N 名佩戴者应仍为 1） */
	private static int lastScanLevels = 0;

	/** 2.8 自检用：读取上一次 {@link #tick(MinecraftServer)} 的维度扫描次数 */
	public static int lastScanLevelsForTest() {
		return lastScanLevels;
	}

	/** 2.8 自检用：注入的"假佩戴者"（仅自检使用；真实玩家仍照常从服务器玩家列表收集） */
	private static final Map<ServerLevel, Map<UUID, Double>> TEST_WEARERS = new HashMap<>();

	/** 2.8 自检用：往指定维度再塞一名佩戴者，用于断言"同维度只扫一次" */
	public static void addWearerForTest(ServerLevel level, UUID id, double radius) {
		TEST_WEARERS.computeIfAbsent(level, ignored -> new HashMap<>()).put(id, radius);
	}

	/** 2.8 自检用：清空注入的假佩戴者 */
	public static void clearTestWearers() {
		TEST_WEARERS.clear();
	}

	/** 以箭矢为中心的半径内最近的敌对生物 */
	private static LivingEntity nearestEnemy(ServerLevel level, AbstractArrow arrow, double radius) {
		LivingEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		// 1.8.3：AABB 只做粗筛（走区块索引），这里补一层球体距离精筛，
		// 让"半径 N 格"严格成立（旧的方盒在斜角上最远能到 N√3 格）
		double radiusSqr = radius * radius;
		for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class,
				arrow.getBoundingBox().inflate(radius),
				entity -> entity instanceof Enemy && entity.isAlive()
						&& entity.distanceToSqr(arrow) <= radiusSqr
						// 1.8.6：这支箭已经碰过的目标不再计入可选 ——
						// 否则箭会被拽回一个"打不动"的目标身边打转。
						&& !alreadyHit(arrow, entity))) {
			double distance = candidate.distanceToSqr(arrow);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = candidate;
			}
		}
		return best;
	}

	/**
	 * 1.8.6：登记"这支投射物碰过这个目标"（调用方：{@code ReliquaryEvents} 的 {@code ProjectileImpactEvent}）。
	 *
	 * <p>只登记玩家所属的投射物 —— 供圣心追踪排除已经打过的目标用。
	 */
	public static void recordHit(AbstractArrow arrow, Entity target) {
		if (arrow == null || target == null || !(arrow.getOwner() instanceof ServerPlayer)) {
			return;
		}
		HIT_TARGETS.computeIfAbsent(arrow, key -> new HashSet<>()).add(target.getUUID());
	}

	/** 这支箭是否已经碰过该目标 */
	private static boolean alreadyHit(AbstractArrow arrow, Entity candidate) {
		Set<UUID> hits = HIT_TARGETS.get(arrow);
		return hits != null && hits.contains(candidate.getUUID());
	}

	/** 1.8.6（自检用）：这支箭已登记的目标数 */
	public static int hitTargetCountForTest(AbstractArrow arrow) {
		Set<UUID> hits = HIT_TARGETS.get(arrow);
		return hits == null ? 0 : hits.size();
	}

	/** 朝目标偏转：保持速率，只改方向（1.8.6：偏转强度由停滞状态决定） */
	private static void steer(AbstractArrow arrow, LivingEntity target, double strength) {
		Vec3 velocity = arrow.getDeltaMovement();
		double speed = velocity.length();
		if (speed < MIN_SPEED) {
			return;
		}
		Vec3 direction = target.getEyePosition().subtract(arrow.position()).normalize();
		// 1.8.3：已经基本对准就不再改写 —— 省掉每 tick 的速度写入与 hurtMarked 同步
		double cos = Math.max(-1.0D, Math.min(1.0D, velocity.normalize().dot(direction)));
		if (Math.acos(cos) < MIN_TURN_ANGLE) {
			return;
		}
		arrow.setDeltaMovement(homingDirection(velocity, direction, strength));
		// 让客户端也同步这次速度变化
		arrow.hurtMarked = true;
	}

	/**
	 * 1.8.6（自检用）：推进一次停滞状态 —— 纯函数，不碰实体。
	 *
	 * <p>比历史最近距离小了不止 {@value #PROGRESS_EPSILON} 格 → 算"有进展"，状态清回 0；
	 * 否则累计停滞 tick，达到 {@value #STALL_TICKS} 就进入追猎态（并保持到重新有进展为止）。
	 */
	public static StallState advanceStall(StallState state, double distance) {
		StallState from = state == null ? INITIAL : state;
		if (distance < from.bestDistance() - PROGRESS_EPSILON) {
			return new StallState(distance, 0, false);
		}
		int stall = from.stallTicks() + 1;
		return new StallState(from.bestDistance(), stall, from.hunting() || stall >= STALL_TICKS);
	}

	/** 1.8.6（自检用）：当前状态该用的偏转强度（追猎态 = 1.0，直接朝目标） */
	public static double strengthFor(StallState state) {
		return state != null && state.hunting() ? 1.0D : HOMING_STRENGTH;
	}

	/** 1.8.6（自检用）：当前正在跟踪的箭矢条数 */
	public static int trackedArrowCount() {
		return STALL.size();
	}

	/**
	 * 1.8.3：偏转后的速度（保持原速率，只改方向）。
	 *
	 * <p>取「当前方向」与「指向目标」的角平分线；当两者**恰好相反**（正后方）时混合结果会成为
	 * 零向量，若不处理就会把箭速写成 0、悬停在半空 —— 这里退化为直接朝目标。
	 *
	 * <p>自检用：纯计算，不碰实体。
	 */
	public static Vec3 homingDirection(Vec3 velocity, Vec3 toTargetUnit) {
		return homingDirection(velocity, toTargetUnit, HOMING_STRENGTH);
	}

	/** 1.8.6：带偏转强度的版本（追猎态传 1.0 就是直接朝目标） */
	public static Vec3 homingDirection(Vec3 velocity, Vec3 toTargetUnit, double strength) {
		double speed = velocity.length();
		Vec3 blended = velocity.normalize().scale(1.0D - strength)
				.add(toTargetUnit.scale(strength));
		if (blended.lengthSqr() < BLEND_EPSILON) {
			return toTargetUnit.scale(speed);
		}
		return blended.normalize().scale(speed);
	}

	/** 自检用：小角度死区（弧度） */
	public static double minTurnAngle() {
		return MIN_TURN_ANGLE;
	}

	/** 自检用：偏转强度 */
	public static double homingStrength() {
		return HOMING_STRENGTH;
	}

	/** 自检用：单次偏转（把逻辑暴露出来，便于直接断言方向） */
	public static void steerForTest(AbstractArrow arrow, LivingEntity target) {
		steer(arrow, target, HOMING_STRENGTH);
	}

	/** 自检用：最近的敌对生物（半径内） */
	public static LivingEntity nearestEnemyForTest(ServerLevel level, AbstractArrow arrow, double radius) {
		return nearestEnemy(level, arrow, radius);
	}

}
