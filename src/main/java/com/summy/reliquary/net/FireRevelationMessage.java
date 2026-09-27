package com.summy.reliquary.net;

import com.summy.reliquary.effect.RevelationBeam;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：请求发射「启示之光」。
 *
 * <p>空包；是否真的能发射由服务端权威判定（是否佩戴终末天启、冷却是否就绪）。
 */
public class FireRevelationMessage {
	public FireRevelationMessage() {
	}

	public FireRevelationMessage(FriendlyByteBuf buffer) {
	}

	public void encode(FriendlyByteBuf buffer) {
	}

	public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		ServerPlayer player = context.getSender();
		// 遁入暗影（1.7.5）：技能期间禁用 V 键射线
		if (player != null && !com.summy.reliquary.effect.ShadowDash.isInvulnerable(player)) {
			RevelationBeam.fire(player);
		}
		context.setPacketHandled(true);
	}
}
