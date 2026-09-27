package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 圣光短矛的「防丢失配方」状态机（1.7.9）：照 {@link DaggerRecovery} 的同款口径。
 *
 * <p>只对**曾获得过长矛**（{@code spear_obtained}，由"七罪之源 → 美德"发放时置位）的玩家生效：
 * 连续 {@code [shadow_dash] recovery_missing_seconds}（默认 300 秒 = 5 分钟）身上
 * **既没有圣光短矛、也没有炽天使之枪**，就开放那张配方并说那两句话；一旦重新拿到任意一把长矛，
 * 立刻清零并关闭（配方重新隐藏）。状态存 NBT，并随 {@code PlayerStateMessage} 的 flags 同步给客户端。
 *
 * <p>物品本身还有独立的防丢失：掉落实体免疫伤害且永不自然消失、死亡时不产生掉落物而由复活补发
 * （见 {@code ReliquaryEvents}）。丢进虚空仍会丢失 —— 那时就靠这张配方重新铸一柄。
 */
public final class SpearRecovery {
	private SpearRecovery() {
	}

	/** 服务端每秒：累计"没长矛"的时间，到点开放防丢失配方 */
	public static void tickServer(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			tickPlayer(player);
		}
	}

	private static void tickPlayer(ServerPlayer player) {
		boolean had = PlayerFlags.isSpearObtained(player);
		boolean hasSpear = holdsSpear(player);
		if (!had || hasSpear) {
			// 有长矛（或从没拿到过）：清零计时；已开放的配方要关掉
			if (PlayerFlags.spearMissingSeconds(player) != 0) {
				PlayerFlags.setSpearMissingSeconds(player, 0);
			}
			if (PlayerFlags.isSpearRecoveryOpen(player)) {
				PlayerFlags.setSpearRecoveryOpen(player, false);
				RevelationTracker.sync(player);
			}
			return;
		}
		int seconds = PlayerFlags.spearMissingSeconds(player) + 1;
		PlayerFlags.setSpearMissingSeconds(player, seconds);
		if (seconds >= ReliquaryConfig.daggerRecoverySeconds() && !PlayerFlags.isSpearRecoveryOpen(player)) {
			PlayerFlags.setSpearRecoveryOpen(player, true);
			// 开放那一刻只说一次这两行（重复丢失会再说一次）
			player.sendSystemMessage(Component.translatable("message.summy-reliquary.spear.recovery.1"));
			player.sendSystemMessage(Component.translatable("message.summy-reliquary.spear.recovery.2"));
			RevelationTracker.sync(player);
		}
	}

	/** 身上（背包 + 全部饰品栏）有没有任意一把天使线长矛 */
	public static boolean holdsSpear(LivingEntity entity) {
		return holds(entity, SummyReliquary.HOLY_SPEAR.get())
				|| holds(entity, SummyReliquary.SERAPH_SPEAR.get());
	}

	/** 身上（背包 + 全部饰品栏）是否持有某件物品 */
	public static boolean holds(LivingEntity entity, Item item) {
		return DaggerRecovery.holds(entity, item);
	}

	/** 门禁判据：防丢失配方当前是否开放 */
	public static boolean isOpen(LivingEntity entity) {
		return PlayerFlags.isSpearRecoveryOpen(entity);
	}

	/** 自检用：当前累计的"没长矛"秒数 */
	public static int missingSeconds(LivingEntity entity) {
		return PlayerFlags.spearMissingSeconds(entity);
	}

	/**
	 * 1.7.9：死亡复活时的"长矛不丢失"补发（与创世纪 / 匕首同款口径）。
	 *
	 * <p>判据 = **原玩家身上有**这把矛、而新玩家身上**没有**；主动丢弃后再死不会补发。
	 */
	public static void restoreOnDeath(ServerPlayer player, boolean hadHolySpear, boolean hadSeraphSpear) {
		if (player == null) {
			return;
		}
		restoreOne(player, hadHolySpear, SummyReliquary.HOLY_SPEAR.get());
		restoreOne(player, hadSeraphSpear, SummyReliquary.SERAPH_SPEAR.get());
	}

	private static void restoreOne(ServerPlayer player, boolean had, Item item) {
		if (!had || holds(player, item)) {
			return;
		}
		ItemStack stack = new ItemStack(item);
		if (!player.getInventory().add(stack)) {
			com.summy.reliquary.item.GenesisItem.protectDrop(player.drop(stack, false));
		}
	}

	/** 自检用：直接推进 N 秒（省得等真实的 5 分钟） */
	public static void tickForTest(ServerPlayer player, int seconds) {
		for (int index = 0; index < seconds; index++) {
			tickPlayer(player);
		}
	}
}
