package com.summy.reliquary.net;

import com.summy.reliquary.client.ClientRenderHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：让该客户端播放"不死图腾动画"（1.5.9）。
 *
 * <p>契约签署成功时发这个空包；客户端收到后调用
 * {@code Minecraft.getInstance().gameRenderer.displayItemActivation(五芒星)} —— 这会播放与
 * 不死图腾完全相同的动画，只是把贴图换成五芒星（{@code displayItemActivation} 是 public，
 * 所以不需要 mixin）。
 *
 * <p>真正的客户端调用放在 {@link ClientRenderHooks} 的回调里，通用类不直接引用客户端类型
 * （否则专用服务端加载本类会被 dist 校验拦下）。
 */
public class DemonDealMessage {
	public DemonDealMessage() {
	}

	public DemonDealMessage(FriendlyByteBuf buffer) {
		// 空包：没有负载
	}

	public void encode(FriendlyByteBuf buffer) {
		// 空包：没有负载
	}

	public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(ClientRenderHooks.itemActivationSink);
		context.setPacketHandled(true);
	}
}
