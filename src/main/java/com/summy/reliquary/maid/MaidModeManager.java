package com.summy.reliquary.maid;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 邦邦女仆状态的服务端实现。
 *
 * <p>状态只存在内存中：玩家死亡、退出游戏、卸下光环后都会自动结束，避免出现「光环没戴还一直吸东西」。
 * 吸附的实现是给范围内的掉落物设置朝向玩家的速度，靠近后交给原版拾取逻辑收取，
 * 因此不会绕过原版的物品拾取延迟（自己刚丢出去的物品仍受原版保护时间限制）。
 */
public final class MaidModeManager {
	/** 当前处于邦邦女仆状态的玩家 */
	private static final Set<UUID> ACTIVE = ConcurrentHashMap.newKeySet();

	/** 掉落物飞向玩家的速度（格 / tick） */
	private static final double PULL_SPEED = 0.8D;
	/** 距离玩家小于该值就不再干预，交给原版拾取 */
	private static final double HANDOVER_DISTANCE = 1.0D;

	private MaidModeManager() {
	}

	/** 切换状态；未佩戴光环时只给提示 */
	public static void toggle(ServerPlayer player) {
		if (ACTIVE.contains(player.getUUID())) {
			deactivate(player);
			return;
		}

		if (!hasHalo(player)) {
			player.displayClientMessage(Component.translatable("message.summy-reliquary.maid.need_halo"), true);
			return;
		}

		ACTIVE.add(player.getUUID());
		player.displayClientMessage(Component.translatable("message.summy-reliquary.maid.on"), true);
		playToggleSound(player, 1.4F);
	}

	/** 服务端每 tick：先校验状态是否仍然成立，再吸附范围内的掉落物 */
	public static void tickServer(MinecraftServer server) {
		if (ACTIVE.isEmpty()) {
			return;
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!ACTIVE.contains(player.getUUID())) {
				continue;
			}

			// 死亡或卸下光环 -> 自动结束
			if (!player.isAlive() || !hasHalo(player)) {
				deactivate(player);
				continue;
			}

			pullItems(player);
		}
	}

	/** 玩家退出时清理状态 */
	public static void forget(ServerPlayer player) {
		ACTIVE.remove(player.getUUID());
	}

	/** 该玩家当前是否处于邦邦女仆状态 */
	public static boolean isActive(ServerPlayer player) {
		return ACTIVE.contains(player.getUUID());
	}

	/** 是否佩戴着邦邦咔邦光环（只能戴在光环栏位） */
	public static boolean hasHalo(net.minecraft.world.entity.LivingEntity entity) {
		return CuriosApi.getCuriosInventory(entity)
				.map(handler -> handler.isEquipped(SummyReliquary.BANG_BANG_HALO.get()))
				.orElse(false);
	}

	private static void deactivate(ServerPlayer player) {
		if (!ACTIVE.remove(player.getUUID())) {
			return;
		}
		player.displayClientMessage(Component.translatable("message.summy-reliquary.maid.off"), true);
		playToggleSound(player, 0.8F);
	}

	private static void playToggleSound(ServerPlayer player, float pitch) {
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8F, pitch);
	}

	/** 把配置半径内的掉落物拉向玩家 */
	private static void pullItems(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		double radius = ReliquaryConfig.radius();
		Vec3 target = player.position().add(0.0D, player.getBbHeight() * 0.5D, 0.0D);
		AABB box = player.getBoundingBox().inflate(radius);

		for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box, ItemEntity::isAlive)) {
			if (item.hasPickUpDelay()) {
				continue;
			}

			Vec3 delta = target.subtract(item.position());
			double distance = delta.length();
			if (distance <= HANDOVER_DISTANCE || distance > radius) {
				continue;
			}

			item.setDeltaMovement(delta.normalize().scale(PULL_SPEED));
			// 1.20.1 需要显式标记才会把速度同步给客户端
			item.hurtMarked = true;
		}
	}
}
