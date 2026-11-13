package com.summy.reliquary.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.summy.reliquary.effect.CovenantActions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;


/** 客户端 → 服务端：请求释放当前誓约饰品的 R 技能（空 payload，2.3）。 */
public class OathActionMessage implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<OathActionMessage> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("summy-reliquary", "oath_action"));
	public static final StreamCodec<RegistryFriendlyByteBuf, OathActionMessage> CODEC =
			StreamCodec.of((buffer, value) -> value.encode(buffer), OathActionMessage::new);

	public OathActionMessage() {
	}

	public OathActionMessage(FriendlyByteBuf buffer) {
	}

	public void encode(FriendlyByteBuf buffer) {
	}

	public void handle(IPayloadContext context) {
		context.enqueueWork(() -> {
			if (context.player() instanceof ServerPlayer sender) {
				CovenantActions.perform(sender);
			}
		});
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
