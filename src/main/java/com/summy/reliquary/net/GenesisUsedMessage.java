package com.summy.reliquary.net;

import com.summy.reliquary.client.ClientRenderHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：让该客户端播放"不死图腾动画（贴图换成创世纪）"（1.7.2）。
 *
 * <p>与 1.5.9 的五芒星签约动画同一套机制（{@code GameRenderer#displayItemActivation} 是 public，
 * 不需要 mixin），只是换成创世纪的贴图；创世纪真正生效（{@code GenesisItem.performReset}）之后发这个包。
 *
 * <p>真正的客户端调用放在 {@link ClientRenderHooks} 的回调里，通用类不直接引用客户端类型
 * （否则专用服务端加载本类会被 dist 校验拦下）。
 */
public class GenesisUsedMessage {
	public GenesisUsedMessage() {
	}

	public GenesisUsedMessage(FriendlyByteBuf buffer) {
		// 空包：没有负载
	}

	public void encode(FriendlyByteBuf buffer) {
		// 空包：没有负载
	}

	public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(ClientRenderHooks.genesisActivationSink);
		context.setPacketHandled(true);
	}
}
