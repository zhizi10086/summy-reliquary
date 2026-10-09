package com.summy.reliquary.net;

import com.summy.reliquary.effect.Godhead;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：请求切换神性「神圣光环」的开关（1.8.5；空 payload）。
 *
 * <p>由「背包 / Curios 面板里对着神性右键」触发（见 {@code client/ReliquaryClientInteractions}）；
 * 手持 + 潜行右键那条走 {@code GodheadItem#use}，不需要这个包。
 */
public class ToggleAuraMessage {

	public ToggleAuraMessage() {
	}

	/** 解码：没有字段，构造即完成 */
	public ToggleAuraMessage(FriendlyByteBuf buffer) {
	}

	/** 编码：没有字段 */
	public void encode(FriendlyByteBuf buffer) {
	}

	/** 在服务端主线程处理 */
	public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		ServerPlayer sender = context.getSender();
		if (sender != null) {
			Godhead.toggleAura(sender);
		}
		context.setPacketHandled(true);
	}
}
