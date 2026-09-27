package com.summy.reliquary.effect;

import com.summy.reliquary.config.ReliquaryConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「逐行 + 延迟」的聊天框输出（1.5.9，恶魔交易台词专用）。
 *
 * <p>恶魔的台词按行发到聊天框，每行之间隔 {@code [demon_deal] line_delay_ticks}（默认 20 tick = 1 秒）。
 * 第一行**立刻**发，之后由服务端每 tick 推进一次；同一个玩家只保留一条队列（后进先排队），
 * 登出 / 服务器停止时清空，避免把台词发给已经离开的人。
 */
public final class DelayedChat {
	/** 玩家 → 还没发出的行 */
	private static final Map<UUID, Deque<Component>> QUEUES = new HashMap<>();
	/** 玩家 → 距下一行还有多少 tick */
	private static final Map<UUID, Integer> TIMERS = new HashMap<>();

	private DelayedChat() {
	}

	/** 入队并立刻发出第一行 */
	public static void send(ServerPlayer player, List<Component> lines) {
		if (player == null || lines == null || lines.isEmpty()) {
			return;
		}
		Deque<Component> queue = QUEUES.computeIfAbsent(player.getUUID(), key -> new ArrayDeque<>());
		queue.addAll(lines);
		// 第一行立刻发；剩下的按延迟逐行发
		player.sendSystemMessage(queue.poll());
		if (queue.isEmpty()) {
			QUEUES.remove(player.getUUID());
			TIMERS.remove(player.getUUID());
		} else {
			TIMERS.put(player.getUUID(), ReliquaryConfig.demonLineDelayTicks());
		}
	}

	/** 服务端每 tick 推进一次 */
	public static void tick(MinecraftServer server) {
		if (server == null || QUEUES.isEmpty()) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			UUID id = player.getUUID();
			Deque<Component> queue = QUEUES.get(id);
			if (queue == null) {
				continue;
			}
			int remaining = TIMERS.getOrDefault(id, ReliquaryConfig.demonLineDelayTicks()) - 1;
			if (remaining > 0) {
				TIMERS.put(id, remaining);
				continue;
			}
			Component line = queue.poll();
			if (line != null) {
				player.sendSystemMessage(line);
			}
			if (queue.isEmpty()) {
				QUEUES.remove(id);
				TIMERS.remove(id);
			} else {
				TIMERS.put(id, ReliquaryConfig.demonLineDelayTicks());
			}
		}
	}

	/** 该玩家还有多少行没发（自检用） */
	public static int pending(ServerPlayer player) {
		Deque<Component> queue = QUEUES.get(player.getUUID());
		return queue == null ? 0 : queue.size();
	}

	/** 登出时丢掉该玩家的队列 */
	public static void forget(ServerPlayer player) {
		if (player == null) {
			return;
		}
		QUEUES.remove(player.getUUID());
		TIMERS.remove(player.getUUID());
	}

	/** 服务器停止时清空 */
	public static void clear() {
		QUEUES.clear();
		TIMERS.clear();
	}
}
