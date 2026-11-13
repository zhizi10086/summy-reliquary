package com.summy.reliquary.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.summy.reliquary.effect.RevelationBeam;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;


/**
 * 客户端 → 服务端：通知"启示之光蓄力状态"变化。
 *
 * <p>蓄力本身是客户端行为（长按 V），这个包只用来让服务端在蓄力期间生成
 * 附近的白色粒子（别人也能看到），以及做"是否有资格蓄力"的权威判定。
 */
public class ChargeBeamMessage implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ChargeBeamMessage> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("summy-reliquary", "charge_beam"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ChargeBeamMessage> CODEC =
			StreamCodec.of((buffer, value) -> value.encode(buffer), ChargeBeamMessage::new);

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

	public void handle(IPayloadContext context) {
		context.enqueueWork(() -> {
			if (context.player() instanceof ServerPlayer player) {
				RevelationBeam.setCharging(player,
						charging && !com.summy.reliquary.effect.ShadowDash.isInvulnerable(player));
			}
		});
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
