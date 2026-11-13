package com.summy.reliquary.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.summy.reliquary.client.ClientBloodDebtState;
import net.minecraft.network.FriendlyByteBuf;


/**
 * 服务端 → 客户端：同步独立血债盾，并可选携带一次静默生命同步的预期值。
 *
 * <p>血债还款逐 tick 扣除真实生命时，原版生命同步包会触发受击镜头。这个轻量包只在
 * 血债盾变化或生命层实际发生静默扣血时发送，不进入通用玩家状态包。
 */
public class BloodDebtStateMessage implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<BloodDebtStateMessage> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("summy-reliquary", "blood_debt_state"));
	public static final StreamCodec<RegistryFriendlyByteBuf, BloodDebtStateMessage> CODEC =
			StreamCodec.of((buffer, value) -> value.encode(buffer), BloodDebtStateMessage::new);

	private final double shield;
	private final boolean hasQuietTarget;
	private final float quietHealthTarget;

	public BloodDebtStateMessage(double shield, boolean hasQuietTarget, float quietHealthTarget) {
		this.shield = Math.max(0.0D, shield);
		this.hasQuietTarget = hasQuietTarget;
		this.quietHealthTarget = Math.max(0.0F, quietHealthTarget);
	}

	public BloodDebtStateMessage(FriendlyByteBuf buffer) {
		this(buffer.readDouble(), buffer.readBoolean(), buffer.readFloat());
	}

	public void encode(FriendlyByteBuf buffer) {
		buffer.writeDouble(shield);
		buffer.writeBoolean(hasQuietTarget);
		buffer.writeFloat(quietHealthTarget);
	}

	public void handle(IPayloadContext context) {
		context.enqueueWork(() -> {
			ClientBloodDebtState.update(shield, hasQuietTarget, quietHealthTarget);
		});
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
