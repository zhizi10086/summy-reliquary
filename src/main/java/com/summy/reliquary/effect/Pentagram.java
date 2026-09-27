package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.advancement.ItemObtained;
import com.summy.reliquary.advancement.SinChallenges;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.item.PentagramItem;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * 五芒星（1.5.8）的服务端逻辑：**只发一次**的发放与「累计持有 300 秒」的那句话。
 *
 * <p>发放口径：完成「罪无可赦」（七罪同时处于已激活）时立刻发一个；
 * 老存档 / 漏发的情况靠每秒兜底补发一次——只要进度已完成、且标记还是 false，
 * 就补一个并把标记置位；标记置位后**不再自动补发**（丢了就靠 OP 指令 {@code pentagram grant}）。
 *
 * <p>计时口径：只有"背包或饰品栏里真的有五芒星"时才累计（写在 NBT {@code pentagram_ticks}，跨存档保留）；
 * 满 {@code [pentagram] message_seconds} 秒后发一句聊天消息，并把 {@code pentagram_message_sent}
 * 置位——那句消息**终身只发一次**，之后只在提示里留着"它在对你说话"那一行。
 */
public final class Pentagram {
	/** 「罪无可赦」的进度 id */
	public static final String ADVANCEMENT = "unforgivable";

	private Pentagram() {
	}

	/** 服务端每秒调用一次 */
	public static void tick(ServerPlayer player) {
		grantIfEarned(player);
		countHeldTime(player);
	}

	/**
	 * 每秒兜底：**七罪全触发**（与「罪无可赦」同一条件）且还没发过 → 补发一个。
	 *
	 * <p>1.6.10 改条件驱动：以前判据是"「罪无可赦」进度已完成"，于是重置后用成就还在
	 * 就会**提前**补发；现在与成就用同一条判定 —— 七罪重新全触发才会发（不吞、也不提前给）。
	 *
	 * @return true 表示这次真的补发了
	 */
	public static boolean grantIfEarned(ServerPlayer player) {
		if (PlayerFlags.isPentagramGranted(player)) {
			return false;
		}
		if (!com.summy.reliquary.sin.SinManager.allSinsTriggered(player)) {
			return false;
		}
		return grant(player);
	}

	/** 立刻发一个五芒星并置位标记（指令补发走的也是这里） */
	public static boolean grant(ServerPlayer player) {
		PlayerFlags.setPentagramGranted(player, true);
		ItemStack stack = new ItemStack(SummyReliquary.PENTAGRAM.get());
		if (!player.getInventory().add(stack)) {
			player.drop(stack, false);
		}
		player.displayClientMessage(
				Component.translatable("message.summy-reliquary.pentagram.received"), true);
		return true;
	}

	/** 清掉「已发放」标记（之后每秒兜底会在进度已完成时自动补发；指令 {@code reset} 走这里） */
	public static void resetGranted(ServerPlayer player) {
		PlayerFlags.setPentagramGranted(player, false);
	}

	/** 只统计"身上有几个五芒星"（背包 + 全部 Curios 栏位） */
	public static int heldCount(ServerPlayer player) {
		int count = player.getInventory().countItem(SummyReliquary.PENTAGRAM.get());
		var handler = CuriosApi.getCuriosInventory(player).orElse(null);
		if (handler != null) {
			for (var result : handler.findCurios(SummyReliquary.PENTAGRAM.get())) {
				count += result.stack().getCount();
			}
		}
		return count;
	}

	/** 身上有它时才累计持有时间 */
	public static void countHeldTime(ServerPlayer player) {
		// 那句话已经说过了：不用再往上累计（避免每秒都写一次 NBT）
		if (PlayerFlags.isPentagramSpoken(player)) {
			return;
		}
		if (!ItemObtained.has(player, SummyReliquary.PENTAGRAM.get())) {
			return;
		}
		speakIfDue(player, PlayerFlags.pentagramTicks(player) + 20);
	}

	/**
	 * 把累计时间推进到指定值，并在到点时发出那句聊天消息。
	 *
	 * <p>把「累计 tick」作为参数传入，自检就能伪造时间、不必真的等 5 分钟。
	 *
	 * @return true 表示这次真的发了消息
	 */
	public static boolean speakIfDue(ServerPlayer player, int ticks) {
		PlayerFlags.setPentagramTicks(player, ticks);
		if (PlayerFlags.isPentagramSpoken(player)) {
			return false;
		}
		if (ticks < ReliquaryConfig.pentagramMessageSeconds() * 20) {
			return false;
		}
		PlayerFlags.setPentagramSpoken(player, true);
		player.sendSystemMessage(PentagramItem.chatMessage());
		// 标记位变了：重新同步一次，客户端提示才会追加那一行
		RevelationTracker.sync(player);
		return true;
	}

	/** 自检用：那条聊天消息是否在没有任何限制时被重复发过（把标记清掉不算） */
	public static boolean hasSpoken(ServerPlayer player) {
		return PlayerFlags.isPentagramSpoken(player);
	}
}
