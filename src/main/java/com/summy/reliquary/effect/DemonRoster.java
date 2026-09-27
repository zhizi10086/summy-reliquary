package com.summy.reliquary.effect;

import com.summy.reliquary.net.ReliquaryNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 服务端维护的「有恶魔标记的在线玩家名单」（1.5.9）。
 *
 * <p>与 {@link AngelRoster} 完全同构：客户端渲染任何玩家头顶名字时都会触发 Forge 的 {@code NameFormat}，
 * 但客户端手里只有自己的标记位 —— 所以必须由服务端把"谁有恶魔标记"广播出去。
 *
 * <p>只在名单**发生变化**（有人签下契约 / 被撤销、登录、登出）时发包；另外每秒兜底比对一次。
 */
public final class DemonRoster {
	/** 上一次广播的名单（用于判断是否变化） */
	private static final Set<UUID> LAST = new HashSet<>();

	private DemonRoster() {
	}

	/** 重新计算并（必要时）广播 */
	public static void refresh(MinecraftServer server) {
		if (server == null) {
			return;
		}
		Set<UUID> current = new HashSet<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (PlayerFlags.isDemon(player)) {
				current.add(player.getUUID());
			}
		}
		if (current.equals(LAST)) {
			return;
		}
		LAST.clear();
		LAST.addAll(current);
		ReliquaryNetworking.broadcastDemonRoster(server, new ArrayList<>(current));
	}

	/** 服务端停止时清掉缓存，避免换存档后带着旧名单 */
	public static void clear() {
		LAST.clear();
	}
}
