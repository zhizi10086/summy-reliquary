package com.summy.reliquary.effect;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * 「注视」判定（1.6.5 抽出，**思想与玄秘魔眼共用同一套实现**）。
 *
 * <p>规则（与 1.5.5 起"思想：看向的目标也发光"完全一致）：
 * <ol>
 *     <li>从**眼睛位置**沿**视线方向**射出一条长度 = {@code distance} 的射线；</li>
 *     <li>**不穿墙**：射线先与方块做 {@code clip}，被挡住的部分不算；</li>
 *     <li>取"**碰撞箱外扩 0.35 格后被射线击中的最近一具生物**"（除自己；对任意生物生效，玩家也算）。</li>
 * </ol>
 *
 * <p>调用方各自决定距离与频率：思想用 {@code [spirit_altar] glow_radius}、每秒一次；
 * 玄秘魔眼用 {@code [occult_eye] fear_radius}、每秒一次（增伤判定则在命中瞬间调一次）。
 */
public final class GazeLook {
	/** 判定用的碰撞箱外扩（格），沿用"思想"原有数值 */
	public static final double HITBOX_INFLATE = 0.35D;

	private GazeLook() {
	}

	/** 该玩家此刻注视到的生物；没有则返回 null */
	public static LivingEntity lookedAt(ServerPlayer player, double distance) {
		if (player == null || distance <= 0.0D) {
			return null;
		}
		ServerLevel level = player.serverLevel();
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		if (look.lengthSqr() < 1.0E-6D) {
			return null;
		}
		Vec3 end = eye.add(look.normalize().scale(distance));
		BlockHitResult blockHit = level.clip(new ClipContext(eye, end,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		double limitSqr = blockHit.getType() == HitResult.Type.MISS
				? distance * distance
				: blockHit.getLocation().distanceToSqr(eye);

		LivingEntity best = null;
		double bestSqr = limitSqr;
		for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate(distance), entity -> entity != player && entity.isAlive())) {
			Optional<Vec3> hit = candidate.getBoundingBox().inflate(HITBOX_INFLATE).clip(eye, end);
			if (hit.isEmpty()) {
				continue;
			}
			double sqr = hit.get().distanceToSqr(eye);
			if (sqr < bestSqr) {
				bestSqr = sqr;
				best = candidate;
			}
		}
		return best;
	}
}
