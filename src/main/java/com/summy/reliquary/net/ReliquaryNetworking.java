package com.summy.reliquary.net;

import com.summy.reliquary.SummyReliquary;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 网络通道定义。
 *
 * <p>客户端按键后发一个空包，服务端在主线程切换邦邦女仆状态。
 * 通道名沿用 {@code summy-reliquary:main}，与旧版保持一致。
 */
public final class ReliquaryNetworking {
	/** 协议版本；客户端与服务端必须一致 */
	// 4：1.5.2 起 flags 新增 bit3 = 主世界有个人重生点（痛悔短祷的风味文本要用），两端必须同版本
	// 5：1.5.3 起新增「天使名单」广播 + 状态包带上自己的 UUID，两端必须同版本
	// 6：1.5.9 起新增「恶魔名单」广播 + 契约签署动画包，两端必须同版本
	// 7：1.5.11 起状态包新增 邪恶度 / 黑心点数 / 邪恶解锁位图，两端必须同版本
	// 8：1.6.2 起状态包新增 魂心池点数（HUD 画蓝心）
	// 9：1.6.4 起 邪恶度改为 double（显示一位小数）、光束广播包新增 kind（启示之光 / 恶魔之焰）
	// 10 / 11：1.6.7 起新增狱火效果与短寿命火焰粒子、1.6.8 起新增恶魔光环伤害类型（都是同步注册表变化）
	// 12：1.7.2 起新增「创世纪使用 → 图腾动画」的 S2C 空包（注册序号 8），两端必须同版本
	// 13：1.8.2 起新增「神性回溯 / 恶魔形态」的 C2S 空包（注册序号 9），两端必须同版本
	private static final String VERSION = "13";

	public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
			SummyReliquary.id("main"),
			() -> VERSION,
			VERSION::equals,
			VERSION::equals);

	private ReliquaryNetworking() {
	}

	/** 当前协议版本（自检与文档用） */
	public static String protocolVersion() {
		return VERSION;
	}

	/** 注册消息（主入口调用一次） */
	public static void register() {
		CHANNEL.messageBuilder(ToggleMaidMessage.class, 0, NetworkDirection.PLAY_TO_SERVER)
				.encoder(ToggleMaidMessage::encode)
				.decoder(ToggleMaidMessage::new)
				.consumerMainThread(ToggleMaidMessage::handle)
				.add();

		// 服务端 → 客户端：七罪激活状态与启示坐标
		CHANNEL.messageBuilder(PlayerStateMessage.class, 1, NetworkDirection.PLAY_TO_CLIENT)
				.encoder(PlayerStateMessage::encode)
				.decoder(PlayerStateMessage::new)
				.consumerMainThread(PlayerStateMessage::handle)
				.add();

		// 客户端 → 服务端：请求发射「启示之光」
		CHANNEL.messageBuilder(FireRevelationMessage.class, 2, NetworkDirection.PLAY_TO_SERVER)
				.encoder(FireRevelationMessage::encode)
				.decoder(FireRevelationMessage::new)
				.consumerMainThread(FireRevelationMessage::handle)
				.add();

		// 服务端 → 客户端：广播光柱，供客户端渲染
		CHANNEL.messageBuilder(RevelationBeamMessage.class, 3, NetworkDirection.PLAY_TO_CLIENT)
				.encoder(RevelationBeamMessage::encode)
				.decoder(RevelationBeamMessage::new)
				.consumerMainThread(RevelationBeamMessage::handle)
				.add();

		// 客户端 → 服务端：启示之光的蓄力状态（用于附近可见的蓄力粒子）
		CHANNEL.messageBuilder(ChargeBeamMessage.class, 4, NetworkDirection.PLAY_TO_SERVER)
				.encoder(ChargeBeamMessage::encode)
				.decoder(ChargeBeamMessage::new)
				.consumerMainThread(ChargeBeamMessage::handle)
				.add();

		// 服务端 → 客户端：广播「有天使标记的在线玩家名单」（名字染金要用）
		CHANNEL.messageBuilder(AngelRosterMessage.class, 5, NetworkDirection.PLAY_TO_CLIENT)
				.encoder(AngelRosterMessage::encode)
				.decoder(AngelRosterMessage::new)
				.consumerMainThread(AngelRosterMessage::handle)
				.add();

		// 服务端 → 客户端：广播「有恶魔标记的在线玩家名单」（名字染深红要用，1.5.9）
		CHANNEL.messageBuilder(DemonRosterMessage.class, 6, NetworkDirection.PLAY_TO_CLIENT)
				.encoder(DemonRosterMessage::encode)
				.decoder(DemonRosterMessage::new)
				.consumerMainThread(DemonRosterMessage::handle)
				.add();

		// 服务端 → 客户端：契约签署成功 → 播放"不死图腾动画（贴图换成五芒星）"（1.5.9）
		CHANNEL.messageBuilder(DemonDealMessage.class, 7, NetworkDirection.PLAY_TO_CLIENT)
				.encoder(DemonDealMessage::encode)
				.decoder(DemonDealMessage::new)
				.consumerMainThread(DemonDealMessage::handle)
				.add();

		// 服务端 → 客户端：创世纪生效 → 播放"不死图腾动画（贴图换成创世纪）"（1.7.2）
		CHANNEL.messageBuilder(GenesisUsedMessage.class, 8, NetworkDirection.PLAY_TO_CLIENT)
				.encoder(GenesisUsedMessage::encode)
				.decoder(GenesisUsedMessage::new)
				.consumerMainThread(GenesisUsedMessage::handle)
				.add();

		// 客户端 → 服务端：请求执行一次「神性回溯 / 恶魔形态」（X 键，1.8.2）
		CHANNEL.messageBuilder(DivineActionMessage.class, 9, NetworkDirection.PLAY_TO_SERVER)
				.encoder(DivineActionMessage::encode)
				.decoder(DivineActionMessage::new)
				.consumerMainThread(DivineActionMessage::handle)
				.add();
	}

	/** 客户端调用：请求切换邦邦女仆状态 */
	public static void sendToggleMaid() {
		CHANNEL.sendToServer(new ToggleMaidMessage());
	}

	/** 客户端调用：请求执行一次「神性回溯 / 恶魔形态」 */
	public static void sendDivineAction() {
		CHANNEL.sendToServer(new DivineActionMessage());
	}

	/** 服务端调用：把该玩家的状态同步给他自己 */
	public static void sendPlayerState(net.minecraft.server.level.ServerPlayer player, int sins,
			int redeemed, boolean revealed, int revealX, int revealZ, int flags, int revealRemainingSeconds,
			double evil, int blackHeartPoints, int evilUnlocks, int soulHeartPoints) {
		// 玩家实体还在加载时 connection 可能为空（例如 Curios 在读档时就触发装备事件），此时跳过
		if (player.connection == null) {
			return;
		}
		CHANNEL.sendTo(new PlayerStateMessage(player.getUUID(), sins, redeemed, revealed, revealX, revealZ,
				flags, revealRemainingSeconds, evil, blackHeartPoints, evilUnlocks, soulHeartPoints),
				player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
	}

	/** 服务端调用：把「有天使标记的在线玩家名单」广播给所有人 */
	public static void broadcastAngelRoster(net.minecraft.server.MinecraftServer server,
			java.util.List<java.util.UUID> angels) {
		AngelRosterMessage message = new AngelRosterMessage(angels);
		broadcast(server, message);
	}

	/** 服务端调用：把「有恶魔标记的在线玩家名单」广播给所有人（1.5.9） */
	public static void broadcastDemonRoster(net.minecraft.server.MinecraftServer server,
			java.util.List<java.util.UUID> demons) {
		DemonRosterMessage message = new DemonRosterMessage(demons);
		broadcast(server, message);
	}

	/** 把一条 S2C 包发给所有在线玩家 */
	public static void broadcast(net.minecraft.server.MinecraftServer server, Object message) {
		for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.connection != null) {
				CHANNEL.sendTo(message, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
			}
		}
	}

	/** 服务端调用：让某个客户端播放"不死图腾动画（贴图换成五芒星）"（1.5.9） */
	public static void sendDemonDealAnimation(net.minecraft.server.level.ServerPlayer player) {
		if (player.connection == null) {
			return;
		}
		CHANNEL.sendTo(new DemonDealMessage(), player.connection.connection,
				NetworkDirection.PLAY_TO_CLIENT);
	}

	/** 服务端调用：让某个客户端播放"不死图腾动画（贴图换成创世纪）"（1.7.2） */
	public static void sendGenesisUsedAnimation(net.minecraft.server.level.ServerPlayer player) {
		if (player.connection == null) {
			return;
		}
		CHANNEL.sendTo(new GenesisUsedMessage(), player.connection.connection,
				NetworkDirection.PLAY_TO_CLIENT);
	}

	/** 客户端调用：请求发射启示之光 */
	public static void sendFireRevelation() {
		CHANNEL.sendToServer(new FireRevelationMessage());
	}

	/** 客户端调用：通知服务端蓄力状态变化 */
	public static void sendCharge(boolean charging) {
		CHANNEL.sendToServer(new ChargeBeamMessage(charging));
	}

	/** 服务端调用：把光柱广播给能看到它的玩家 */
	public static void sendBeam(net.minecraft.server.level.ServerPlayer player, double x, double y, double z,
			float dirX, float dirY, float dirZ, float length, float radius, int durationTicks, int kind) {
		if (player.connection == null) {
			return;
		}
		CHANNEL.sendTo(new RevelationBeamMessage(x, y, z, dirX, dirY, dirZ, length, radius, durationTicks, kind),
				player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
	}

}
