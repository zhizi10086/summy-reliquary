package com.summy.reliquary.net;

import com.summy.reliquary.effect.CovenantActions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 客户端 → 服务端：请求释放当前誓约饰品的 R 技能（空 payload，2.3）。 */
public class OathActionMessage {
	public OathActionMessage() {
	}

	public OathActionMessage(FriendlyByteBuf buffer) {
	}

	public void encode(FriendlyByteBuf buffer) {
	}

	public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		ServerPlayer sender = context.getSender();
		if (sender != null) {
			CovenantActions.perform(sender);
		}
		context.setPacketHandled(true);
	}
}
