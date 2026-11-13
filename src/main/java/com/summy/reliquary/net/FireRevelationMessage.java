package com.summy.reliquary.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.summy.reliquary.effect.RevelationBeam;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;


/**
 * 客户端 → 服务端：请求发射「启示之光」。
 *
 * <p>空包；是否真的能发射由服务端权威判定（是否佩戴终末天启、冷却是否就绪）。
 */
public class FireRevelationMessage implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<FireRevelationMessage> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("summy-reliquary", "fire_revelation"));
	public static final StreamCodec<RegistryFriendlyByteBuf, FireRevelationMessage> CODEC =
			StreamCodec.of((buffer, value) -> value.encode(buffer), FireRevelationMessage::new);

	public FireRevelationMessage() {
	}

	public FireRevelationMessage(FriendlyByteBuf buffer) {
	}

	public void encode(FriendlyByteBuf buffer) {
	}

	public void handle(IPayloadContext context) {
		context.enqueueWork(() -> {
			if (context.player() instanceof ServerPlayer player
					&& !com.summy.reliquary.effect.ShadowDash.isInvulnerable(player)) {
				RevelationBeam.fire(player);
			}
		});
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
