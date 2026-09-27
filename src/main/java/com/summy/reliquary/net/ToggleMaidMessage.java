package com.summy.reliquary.net;

import com.summy.reliquary.maid.MaidModeManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：请求切换邦邦女仆状态（空 payload）。
 */
public class ToggleMaidMessage {

	public ToggleMaidMessage() {
	}

	/** 解码：没有字段，构造即完成 */
	public ToggleMaidMessage(FriendlyByteBuf buffer) {
	}

	/** 编码：没有字段 */
	public void encode(FriendlyByteBuf buffer) {
	}

	/** 在服务端主线程处理 */
	public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		ServerPlayer sender = context.getSender();
		if (sender != null) {
			MaidModeManager.toggle(sender);
		}
		context.setPacketHandled(true);
	}
}
