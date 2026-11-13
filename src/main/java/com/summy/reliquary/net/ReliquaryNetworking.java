package com.summy.reliquary.net;

import com.summy.reliquary.SummyReliquary;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * SR 1.21.1 强类型 Payload 网络通道。
 */
public final class ReliquaryNetworking {
	/** 协议版本；客户端与服务端必须一致。 */
	public static final String VERSION = "19";

	private ReliquaryNetworking() {
	}

	public static String protocolVersion() {
		return VERSION;
	}

	/** 在模组事件总线上注册全部 C2S / S2C Payload。 */
	public static void registerPayloadHandlers(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar(VERSION);
		registrar.playToServer(ToggleMaidMessage.TYPE, ToggleMaidMessage.CODEC,
				(payload, context) -> payload.handle(context));
		registrar.playToClient(PlayerStateMessage.TYPE, PlayerStateMessage.CODEC,
				(payload, context) -> payload.handle(context));
		registrar.playToServer(FireRevelationMessage.TYPE, FireRevelationMessage.CODEC,
				(payload, context) -> payload.handle(context));
		registrar.playToClient(RevelationBeamMessage.TYPE, RevelationBeamMessage.CODEC,
				(payload, context) -> payload.handle(context));
		registrar.playToServer(ChargeBeamMessage.TYPE, ChargeBeamMessage.CODEC,
				(payload, context) -> payload.handle(context));
		registrar.playToClient(AngelRosterMessage.TYPE, AngelRosterMessage.CODEC,
				(payload, context) -> payload.handle(context));
		registrar.playToClient(DemonRosterMessage.TYPE, DemonRosterMessage.CODEC,
				(payload, context) -> payload.handle(context));
		registrar.playToClient(DemonDealMessage.TYPE, DemonDealMessage.CODEC,
				(payload, context) -> payload.handle(context));
		registrar.playToClient(GenesisUsedMessage.TYPE, GenesisUsedMessage.CODEC,
				(payload, context) -> payload.handle(context));
		registrar.playToServer(DivineActionMessage.TYPE, DivineActionMessage.CODEC,
				(payload, context) -> payload.handle(context));
		registrar.playToServer(ToggleAuraMessage.TYPE, ToggleAuraMessage.CODEC,
				(payload, context) -> payload.handle(context));
		registrar.playToServer(OathActionMessage.TYPE, OathActionMessage.CODEC,
				(payload, context) -> payload.handle(context));
		registrar.playToClient(BloodDebtStateMessage.TYPE, BloodDebtStateMessage.CODEC,
				(payload, context) -> payload.handle(context));
	}

	public static void sendToggleMaid() {
		PacketDistributor.sendToServer(new ToggleMaidMessage());
	}

	public static void sendDivineAction() {
		PacketDistributor.sendToServer(new DivineActionMessage());
	}

	public static void sendToggleAura() {
		PacketDistributor.sendToServer(new ToggleAuraMessage());
	}

	public static void sendOathAction() {
		PacketDistributor.sendToServer(new OathActionMessage());
	}

	public static void sendFireRevelation() {
		PacketDistributor.sendToServer(new FireRevelationMessage());
	}

	public static void sendCharge(boolean charging) {
		PacketDistributor.sendToServer(new ChargeBeamMessage(charging));
	}

	public static void sendPlayerState(ServerPlayer player, int sins,
			int redeemed, boolean revealed, int revealX, int revealZ, int flags, int revealRemainingSeconds,
			double evil, int blackHeartPoints, int evilUnlocks, int soulHeartPoints,
			int lifeDeathRemainingTicks, int lifeDeathKillWeight, int lifeDeathKillRequired,
			int covenantOwned) {
		if (player.connection == null) {
			return;
		}
		PacketDistributor.sendToPlayer(player, new PlayerStateMessage(
				player.getUUID(), sins, redeemed, revealed, revealX, revealZ,
				flags, revealRemainingSeconds, evil, blackHeartPoints, evilUnlocks, soulHeartPoints,
				lifeDeathRemainingTicks, lifeDeathKillWeight, lifeDeathKillRequired, covenantOwned));
	}

	public static void sendBloodDebtShield(ServerPlayer player, double shield) {
		sendBloodDebtState(player, shield, false, 0.0F);
	}

	public static void sendBloodDebtQuietHealth(ServerPlayer player,
			double shield, float expectedHealth) {
		sendBloodDebtState(player, shield, true, expectedHealth);
	}

	private static void sendBloodDebtState(ServerPlayer player, double shield,
			boolean hasQuietTarget, float quietHealthTarget) {
		if (player.connection == null) {
			return;
		}
		PacketDistributor.sendToPlayer(player,
				new BloodDebtStateMessage(shield, hasQuietTarget, quietHealthTarget));
	}

	public static void broadcastAngelRoster(MinecraftServer server, List<UUID> angels) {
		broadcast(server, new AngelRosterMessage(angels));
	}

	public static void broadcastDemonRoster(MinecraftServer server, List<UUID> demons) {
		broadcast(server, new DemonRosterMessage(demons));
	}

	public static void broadcast(MinecraftServer server, CustomPacketPayload message) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.connection != null) {
				PacketDistributor.sendToPlayer(player, message);
			}
		}
	}

	public static void sendDemonDealAnimation(ServerPlayer player) {
		if (player.connection != null) {
			PacketDistributor.sendToPlayer(player, new DemonDealMessage());
		}
	}

	public static void sendGenesisUsedAnimation(ServerPlayer player) {
		if (player.connection != null) {
			PacketDistributor.sendToPlayer(player, new GenesisUsedMessage());
		}
	}

	public static void sendBeam(ServerPlayer player, double x, double y, double z,
			float dirX, float dirY, float dirZ, float length, float radius, int durationTicks, int kind) {
		if (player.connection != null) {
			PacketDistributor.sendToPlayer(player, new RevelationBeamMessage(
					x, y, z, dirX, dirY, dirZ, length, radius, durationTicks, kind));
		}
	}
}
