package com.summy.reliquary.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.summy.reliquary.maid.MaidModeManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;


/**
 * 客户端 → 服务端：请求切换邦邦女仆状态（空 payload）。
 */
public class ToggleMaidMessage implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ToggleMaidMessage> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("summy-reliquary", "toggle_maid"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ToggleMaidMessage> CODEC =
			StreamCodec.of((buffer, value) -> value.encode(buffer), ToggleMaidMessage::new);


	public ToggleMaidMessage() {
	}

	/** 解码：没有字段，构造即完成 */
	public ToggleMaidMessage(FriendlyByteBuf buffer) {
	}

	/** 编码：没有字段 */
	public void encode(FriendlyByteBuf buffer) {
	}

	/** 在服务端主线程处理 */
	public void handle(IPayloadContext context) {
		context.enqueueWork(() -> {
			if (context.player() instanceof ServerPlayer sender) {
				MaidModeManager.toggle(sender);
			}
		});
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
