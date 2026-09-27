package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.net.ReliquaryNetworking;
import com.summy.reliquary.sin.SinManager;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

/**
 * 「伯列恒之星」的启示计时与坐标揭示。
 *
 * <p>累计佩戴时间（每 20 tick 记一次、落盘到玩家持久化数据，卸下保留），满 600 秒（可配置）后揭示坐标：
 * 坐标由「世界种子 + 玩家 UUID」派生，以世界出生点为中心、半径 1000 格（可配置）的圆内取 X/Z，忽略 Y；
 * 同一存档同一玩家永远得到同一个坐标，并通过网络包同步给客户端用于提示文本。
 */
public final class RevelationTracker {
	private static final String TICKS = "revelation_ticks";
	private static final String REVEALED = "revelation_revealed";
	private static final String X = "revelation_x";
	private static final String Z = "revelation_z";

	private RevelationTracker() {
	}

	/** 服务端每秒处理一次 */
	public static void tickServer(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!CurioHelper.wears(player, SummyReliquary.STAR_OF_BETHLEHEM.get())) {
				continue;
			}
			int ticks = ticks(player) + 20;
			setTicks(player, ticks);
			if (!isRevealed(player) && ticks >= Math.max(1, ReliquaryConfig.revealSeconds()) * 20) {
				reveal(player);
			}
		}
	}

	/** 累计佩戴 tick */
	public static int ticks(ServerPlayer player) {
		return root(player).getInt(TICKS);
	}

	/**
	 * 把"伯列恒之星的进度"整个清空（1.6.10：创世纪重置用）。
	 *
	 * <p>清掉**累计佩戴时间 + 是否已揭示 + 已揭示的坐标**，让重置后的玩家和一周目一样
	 * 需要重新累计 600 秒才会揭示坐标。
	 */
	public static void reset(ServerPlayer player) {
		CompoundTag root = mutableRoot(player);
		root.remove(TICKS);
		root.remove(REVEALED);
		root.remove(X);
		root.remove(Z);
		sync(player);
	}

	public static void setTicks(ServerPlayer player, int value) {
		mutableRoot(player).putInt(TICKS, value);
	}

	public static boolean isRevealed(ServerPlayer player) {
		return root(player).getBoolean(REVEALED);
	}

	public static int revealX(ServerPlayer player) {
		return root(player).getInt(X);
	}

	public static int revealZ(ServerPlayer player) {
		return root(player).getInt(Z);
	}

	/** 揭示坐标：派生 → 落盘 → 同步 → 提示 */
	public static void reveal(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		long seed = level.getSeed()
				^ player.getUUID().getMostSignificantBits()
				^ Long.rotateLeft(player.getUUID().getLeastSignificantBits(), 17);
		RandomSource random = RandomSource.create(seed);

		BlockPos spawn = level.getSharedSpawnPos();
		double angle = random.nextDouble() * Math.PI * 2.0D;
		double distance = Math.sqrt(random.nextDouble()) * Math.max(1, ReliquaryConfig.revealRadius());
		int x = spawn.getX() + (int) Math.round(Math.cos(angle) * distance);
		int z = spawn.getZ() + (int) Math.round(Math.sin(angle) * distance);

		CompoundTag root = mutableRoot(player);
		root.putBoolean(REVEALED, true);
		root.putInt(X, x);
		root.putInt(Z, z);

		sync(player);
		player.displayClientMessage(
				Component.translatable("message.summy-reliquary.revelation", coordinateText(x, z)), true);
		SummyReliquary.LOGGER.info("[Summy Reliquary] 为 {} 揭示启示坐标 ({}, {})",
				player.getName().getString(), x, z);
	}

	/**
	 * 更新「是否获取过启示」（1.6.10）。
	 *
	 * <p>置位时机：① 星→天启转化成功（{@code RevelationAscension} 里直接置位）；
	 * ② 持有**终末天启或神性**（这里每秒兜底）；③ 老存档**一次性迁移**：第一次跑这里时，
	 * 若「启示」进度已完成或身上带着天启/神性，就补记一次。迁移标记只置一次、创世纪不清它，
	 * 所以重置之后不会再从成就里补记回来。
	 *
	 * @return true 表示这次真的改了标记（调用方据此决定要不要 sync）
	 */
	public static boolean updateObtained(ServerPlayer player) {
		if (PlayerFlags.isRevelationObtained(player)) {
			return false;
		}
		if (!PlayerFlags.isRevelationMigrated(player)) {
			PlayerFlags.setRevelationMigrated(player, true);
			if (com.summy.reliquary.advancement.SinChallenges.advancementDone(player, "revelation")) {
				PlayerFlags.setRevelationObtained(player, true);
				return true;
			}
		}
		if (holdsRevelationItem(player)) {
			PlayerFlags.setRevelationObtained(player, true);
			return true;
		}
		return false;
	}

	/** 身上（背包 + 饰品栏）是否带着终末天启或神性 */
	private static boolean holdsRevelationItem(ServerPlayer player) {
		return com.summy.reliquary.advancement.ItemObtained.has(player, SummyReliquary.FINAL_REVELATION.get())
				|| com.summy.reliquary.advancement.ItemObtained.has(player, SummyReliquary.GODHEAD.get());
	}

	/** 把当前状态同步给该玩家的客户端（登录时与状态变化时调用） */
	public static void sync(ServerPlayer player) {
		// 同步前先把"是否获取过启示"补齐（老存档迁移 / 持有天启 · 神性的兜底）
		updateObtained(player);
		// 启示已降临（星→天启）会加一个标记位，供提示文本切换成"你的启示已经降临"
		int flags = com.summy.reliquary.effect.PlayerFlags.clientFlags(player);
		// 1.6.10：这一位改成读"是否获取过启示"这个属性（不再看成就）——
		// 于是创世纪重置后它会归零，恶魔交易也随之重新开放。
		if (com.summy.reliquary.effect.PlayerFlags.isRevelationObtained(player)) {
			flags |= com.summy.reliquary.client.ReliquaryClientState.FLAG_REVELATION_ASCENDED;
		}
		ReliquaryNetworking.sendPlayerState(player, SinManager.mask(player),
				SinManager.redeemedMask(player), isRevealed(player), revealX(player), revealZ(player),
				flags, remainingRevealSeconds(player),
				com.summy.reliquary.effect.PlayerFlags.evilExact(player),
				(int) Math.round(com.summy.reliquary.effect.PlayerFlags.blackHeartPoints(player)),
				com.summy.reliquary.effect.PlayerFlags.evilUnlocks(player),
				(int) Math.round(com.summy.reliquary.effect.PlayerFlags.soulHeartPoints(player)));
	}

	/** 距离揭示还剩多少秒（已揭示或未佩戴时返回 0） */
	public static int remainingRevealSeconds(ServerPlayer player) {
		if (isRevealed(player)) {
			return 0;
		}
		int needed = com.summy.reliquary.config.ReliquaryConfig.revealSeconds() * 20;
		long remainingTicks = needed - ticks(player);
		return (int) Math.max(0L, (remainingTicks + 19L) / 20L);
	}
	/** 提示文本里显示的坐标串（只有 X 与 Z，忽略 Y） */
	public static String coordinateText(int x, int z) {
		return x + ", " + z;
	}

	private static CompoundTag root(ServerPlayer player) {
		CompoundTag data = player.getPersistentData();
		return data.contains(SinManager.ROOT, CompoundTag.TAG_COMPOUND)
				? data.getCompound(SinManager.ROOT)
				: new CompoundTag();
	}

	private static CompoundTag mutableRoot(ServerPlayer player) {
		CompoundTag data = player.getPersistentData();
		CompoundTag root = data.contains(SinManager.ROOT, CompoundTag.TAG_COMPOUND)
				? data.getCompound(SinManager.ROOT)
				: new CompoundTag();
		data.put(SinManager.ROOT, root);
		return root;
	}
}
