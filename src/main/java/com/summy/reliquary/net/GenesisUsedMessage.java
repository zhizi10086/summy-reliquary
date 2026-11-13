package com.summy.reliquary.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.summy.reliquary.client.ClientRenderHooks;
import net.minecraft.network.FriendlyByteBuf;


/**
 * 服务端 → 客户端：让该客户端播放"不死图腾动画（贴图换成创世纪）"（1.7.2）。
 *
 * <p>与 1.5.9 的五芒星签约动画同一套机制（{@code GameRenderer#displayItemActivation} 是 public，
 * 不需要 mixin），只是换成创世纪的贴图；创世纪真正生效（{@code GenesisItem.performReset}）之后发这个包。
 *
 * <p>真正的客户端调用放在 {@link ClientRenderHooks} 的回调里，通用类不直接引用客户端类型
 * （否则专用服务端加载本类会被 dist 校验拦下）。
 */
public class GenesisUsedMessage implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<GenesisUsedMessage> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("summy-reliquary", "genesis_used"));
	public static final StreamCodec<RegistryFriendlyByteBuf, GenesisUsedMessage> CODEC =
			StreamCodec.of((buffer, value) -> value.encode(buffer), GenesisUsedMessage::new);

	public GenesisUsedMessage() {
	}

	public GenesisUsedMessage(FriendlyByteBuf buffer) {
		// 空包：没有负载
	}

	public void encode(FriendlyByteBuf buffer) {
		// 空包：没有负载
	}

	public void handle(IPayloadContext context) {
		context.enqueueWork(() -> {
			ClientRenderHooks.genesisActivationSink.run();
		});
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
