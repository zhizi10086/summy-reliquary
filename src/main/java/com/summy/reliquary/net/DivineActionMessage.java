package com.summy.reliquary.net;

import com.summy.reliquary.effect.DivineActions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：请求执行一次「神性回溯 / 恶魔形态」（X 键，空 payload，1.8.2）。
 */
public class DivineActionMessage {

	public DivineActionMessage() {
	}

	/** 解码：没有字段，构造即完成 */
	public DivineActionMessage(FriendlyByteBuf buffer) {
	}

	/** 编码：没有字段 */
	public void encode(FriendlyByteBuf buffer) {
	}

	/** 在服务端主线程处理 */
	public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		ServerPlayer sender = context.getSender();
		if (sender != null) {
			DivineActions.perform(sender);
		}
		context.setPacketHandled(true);
	}
}
