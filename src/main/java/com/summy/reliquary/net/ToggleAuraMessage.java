package com.summy.reliquary.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.summy.reliquary.effect.Godhead;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;


/**
 * 客户端 → 服务端：请求切换神性「神圣光环」的开关（1.8.5；空 payload）。
 *
 * <p>由「背包 / Curios 面板里对着神性右键」触发（见 {@code client/ReliquaryClientInteractions}）；
 * 手持 + 潜行右键那条走 {@code GodheadItem#use}，不需要这个包。
 */
public class ToggleAuraMessage implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ToggleAuraMessage> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("summy-reliquary", "toggle_aura"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ToggleAuraMessage> CODEC =
			StreamCodec.of((buffer, value) -> value.encode(buffer), ToggleAuraMessage::new);


	public ToggleAuraMessage() {
	}

	/** 解码：没有字段，构造即完成 */
	public ToggleAuraMessage(FriendlyByteBuf buffer) {
	}

	/** 编码：没有字段 */
	public void encode(FriendlyByteBuf buffer) {
	}

	/** 在服务端主线程处理 */
	public void handle(IPayloadContext context) {
		context.enqueueWork(() -> {
			if (context.player() instanceof ServerPlayer sender) {
				Godhead.toggleAura(sender);
			}
		});
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
