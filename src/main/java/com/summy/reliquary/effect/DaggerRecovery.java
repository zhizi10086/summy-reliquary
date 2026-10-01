package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;

/**
 * 献祭匕首的「防丢失配方」状态机（1.7.6）。
 *
 * <p>只对**已签约**的玩家生效：连续 {@code [shadow_dash] recovery_missing_seconds}（默认 300 秒 = 5 分钟）
 * 身上**既没有献祭匕首、也没有暗仪刺刀**，就开放这张配方并说那两句话；一旦重新拿到任意一把匕首，
 * 立刻清零并关闭（配方重新隐藏）。状态存 NBT（跨登录累计），并随 {@code PlayerStateMessage} 的
 * flags 同步给客户端（bit10 `FLAG_DAGGER_RECOVERY`）决定 JEI 里是否可见。
 */
public final class DaggerRecovery {
	private DaggerRecovery() {
	}

	/** 服务端每秒：累计"没匕首"的时间，到点开放防丢失配方 */
	public static void tickServer(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			tickPlayer(player);
		}
	}

	private static void tickPlayer(ServerPlayer player) {
		// 1.8.0：前置改为"发放过仪式匕首"。老存档没有该标记时，以"曾签约 / 当前是恶魔"兜底视为已发放。
		boolean granted = PlayerFlags.isDaggerGranted(player)
				|| PlayerFlags.isDemonSealed(player) || PlayerFlags.isDemon(player);
		boolean hasDagger = holdsDagger(player);
		if (!granted || hasDagger) {
			// 有匕首（或还没签约）：清零计时；已开放的配方要关掉
			if (PlayerFlags.daggerMissingSeconds(player) != 0) {
				PlayerFlags.setDaggerMissingSeconds(player, 0);
			}
			if (PlayerFlags.isDaggerRecoveryOpen(player)) {
				PlayerFlags.setDaggerRecoveryOpen(player, false);
				RevelationTracker.sync(player);
			}
			return;
		}
		int seconds = PlayerFlags.daggerMissingSeconds(player) + 1;
		PlayerFlags.setDaggerMissingSeconds(player, seconds);
		if (seconds >= ReliquaryConfig.daggerRecoverySeconds() && !PlayerFlags.isDaggerRecoveryOpen(player)) {
			PlayerFlags.setDaggerRecoveryOpen(player, true);
			// 开放那一刻只说一次这两行（重复丢失会再说一次）
			player.sendSystemMessage(Component.translatable("message.summy-reliquary.dagger.recovery.1"));
			player.sendSystemMessage(Component.translatable("message.summy-reliquary.dagger.recovery.2"));
			RevelationTracker.sync(player);
		}
	}

	/** 身上（背包 + 全部饰品栏）有没有任意一把仪式匕首 */
	public static boolean holdsDagger(LivingEntity entity) {
		return holds(entity, SummyReliquary.SACRIFICIAL_DAGGER.get())
				|| holds(entity, SummyReliquary.DARK_ARTS.get());
	}

	/** 身上（背包 + 全部饰品栏）是否持有某件物品 */
	public static boolean holds(LivingEntity entity, Item item) {
		// 1.7.10 修订：只比**物品类型**（忽略耐久 / 附魔 / 改名这些 NBT），且覆盖副手、盔甲、光标；
		// 旧写法用 Inventory#contains 是"连 NBT 一起比"，匕首掉过耐久就会被误判成"没有"。
		return com.summy.reliquary.util.HeldItems.holds(entity, item);
	}

	/** 门禁判据：防丢失配方当前是否开放 */
	public static boolean isOpen(LivingEntity entity) {
		return PlayerFlags.isDaggerRecoveryOpen(entity);
	}

	/** 自检用：当前累计的"没匕首"秒数 */
	public static int missingSeconds(LivingEntity entity) {
		return PlayerFlags.daggerMissingSeconds(entity);
	}

	/** 自检用：直接推进 N 秒（省得等真实的 5 分钟） */
	public static void tickForTest(ServerPlayer player, int seconds) {
		for (int index = 0; index < seconds; index++) {
			tickPlayer(player);
		}
	}
}
