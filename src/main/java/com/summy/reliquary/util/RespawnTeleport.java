package com.summy.reliquary.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 「送回重生点」的共用实现（1.7.2 抽出）。
 *
 * <p>口径与 1.6.4 起神性死亡拦截 / 1.6.8 起亚巴顿环境致死完全一致：
 * <ul>
 *     <li>玩家有个人重生点（{@code getRespawnPosition} 非空且该维度能取到）→ 传送到那个点；</li>
 *     <li>没有 → 传送到**当前维度的世界出生点**；</li>
 *     <li>跨维度时用带维度的 {@code teleportTo} 重载，落地后撒一把金色粒子。</li>
 * </ul>
 *
 * <p>之所以抽出来：神性、亚巴顿、创世纪（1.7.2 新增）三处要用同一套语义，
 * 以前是两份逐行重复的私有实现，容易改一处漏一处。
 */
public final class RespawnTeleport {
	/** 落地粒子：金色（与神性死亡拦截一致） */
	private static final SimpleParticleType PARTICLE = ParticleTypes.WAX_ON;
	/** 粒子数量 */
	private static final int PARTICLES = 20;
	/** 粒子散布半径 */
	private static final double SPREAD = 0.5D;

	private RespawnTeleport() {
	}

	/**
	 * 把玩家送回重生点，并在落点撒一把金色粒子。
	 *
	 * @return true 表示真的执行了传送（服务端不可用时返回 false）
	 */
	public static boolean teleport(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return false;
		}
		ServerLevel targetLevel = player.serverLevel();
		BlockPos respawn = player.getRespawnPosition();
		ResourceKey<Level> dimension = player.getRespawnDimension();
		ServerLevel respawnLevel = dimension == null ? null : server.getLevel(dimension);
		Vec3 destination;
		if (respawn != null && respawnLevel != null) {
			targetLevel = respawnLevel;
			destination = new Vec3(respawn.getX() + 0.5D, respawn.getY() + 1.0D, respawn.getZ() + 0.5D);
		} else {
			// 1.8.2：没有个人重生点时按原版死亡重生处理 —— 回**主世界**共享出生点
			// （以前用的是"当前维度"的出生点，在下界 / 末地被拦截就会留在原维度）
			targetLevel = server.overworld();
			BlockPos spawn = targetLevel.getSharedSpawnPos();
			destination = new Vec3(spawn.getX() + 0.5D, spawn.getY() + 1.0D, spawn.getZ() + 0.5D);
		}
		if (player.serverLevel() == targetLevel) {
			player.teleportTo(destination.x, destination.y, destination.z);
		} else {
			player.teleportTo(targetLevel, destination.x, destination.y, destination.z,
					player.getYRot(), player.getXRot());
		}
		targetLevel.sendParticles(PARTICLE, player.getX(), player.getY() + 0.9D, player.getZ(),
				PARTICLES, SPREAD, SPREAD, SPREAD, 0.0D);
		return true;
	}

	/** 自检用：落地粒子类型（应为金色 WAX_ON） */
	public static SimpleParticleType particle() {
		return PARTICLE;
	}
}
