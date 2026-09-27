package com.summy.reliquary.net;

import com.summy.reliquary.effect.RevelationBeam;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 → 服务端：通知"启示之光蓄力状态"变化。
 *
 * <p>蓄力本身是客户端行为（长按 V），这个包只用来让服务端在蓄力期间生成
 * 附近的白色粒子（别人也能看到），以及做"是否有资格蓄力"的权威判定。
 */
public class ChargeBeamMessage {
	private final boolean charging;

	public ChargeBeamMessage(boolean charging) {
		this.charging = charging;
	}

	public ChargeBeamMessage(FriendlyByteBuf buffer) {
		this.charging = buffer.readBoolean();
	}

	public void encode(FriendlyByteBuf buffer) {
		buffer.writeBoolean(charging);
	}

	public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		ServerPlayer player = context.getSender();
		if (player != null) {
			// 遁入暗影（1.7.5）：技能期间禁用 V 键蓄力
			RevelationBeam.setCharging(player,
					charging && !com.summy.reliquary.effect.ShadowDash.isInvulnerable(player));
		}
		context.setPacketHandled(true);
	}
}
