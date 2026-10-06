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

	private SacredHeart() {
	}

	/** 服务端每 tick：为所有佩戴者的箭矢做追踪 */
	public static void tick(MinecraftServer server) {
		if (!ReliquaryConfig.enableArrowHoming()) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!CurioHelper.wears(player, SummyReliquary.SACRED_HEART.get())) {
				continue;
			}
			ServerLevel level = player.serverLevel();
			// 1.6.10：神性 + 圣心同时佩戴时半径扩大（配置见 [sacred_heart]）
			double radius = Synergies.sacredHeartArrowRadius(player);
			for (Entity entity : level.getAllEntities()) {
				if (!(entity instanceof AbstractArrow arrow) || !arrow.isAlive()) {
					continue;
				}
				// 1.7.10：金刀片的投掷**不吃圣心**（按需求只作用于两把天使线长矛）
				if (arrow instanceof com.summy.reliquary.entity.ThrownRazor) {
					continue;
				}
				if (arrow.getOwner() != player) {
					continue;
				}
				LivingEntity target = nearestEnemy(level, arrow, radius);
				if (target != null) {
					steer(arrow, target);
				}
			}
		}
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
						&& entity.distanceToSqr(arrow) <= radiusSqr)) {
			double distance = candidate.distanceToSqr(arrow);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = candidate;
			}
		}
		return best;
	}

	/** 朝目标偏转：保持速率，只改方向 */
	private static void steer(AbstractArrow arrow, LivingEntity target) {
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
		arrow.setDeltaMovement(homingDirection(velocity, direction));
		// 让客户端也同步这次速度变化
		arrow.hurtMarked = true;
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
		double speed = velocity.length();
		Vec3 blended = velocity.normalize().scale(1.0D - HOMING_STRENGTH)
				.add(toTargetUnit.scale(HOMING_STRENGTH));
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
		steer(arrow, target);
	}

	/** 自检用：最近的敌对生物（半径内） */
	public static LivingEntity nearestEnemyForTest(ServerLevel level, AbstractArrow arrow, double radius) {
		return nearestEnemy(level, arrow, radius);
	}

}
