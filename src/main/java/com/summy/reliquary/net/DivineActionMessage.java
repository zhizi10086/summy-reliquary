package com.summy.reliquary.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.summy.reliquary.effect.DivineActions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;


/**
 * 客户端 → 服务端：请求执行一次「神性回溯 / 恶魔形态」（X 键，空 payload，1.8.2）。
 */
public class DivineActionMessage implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<DivineActionMessage> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("summy-reliquary", "divine_action"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DivineActionMessage> CODEC =
			StreamCodec.of((buffer, value) -> value.encode(buffer), DivineActionMessage::new);


	public DivineActionMessage() {
	}

	/** 解码：没有字段，构造即完成 */
	public DivineActionMessage(FriendlyByteBuf buffer) {
	}

	/** 编码：没有字段 */
	public void encode(FriendlyByteBuf buffer) {
	}

	/** 在服务端主线程处理 */
	public void handle(IPayloadContext context) {
		context.enqueueWork(() -> {
			if (context.player() instanceof ServerPlayer sender) {
				DivineActions.perform(sender);
			}
		});
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
