package com.summy.reliquary.net;

import com.summy.reliquary.client.ClientBloodDebtState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：同步独立血债盾，并可选携带一次静默生命同步的预期值。
 *
 * <p>血债还款逐 tick 扣除真实生命时，原版生命同步包会触发受击镜头。这个轻量包只在
 * 血债盾变化或生命层实际发生静默扣血时发送，不进入通用玩家状态包。
 */
public class BloodDebtStateMessage {
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

	public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		ClientBloodDebtState.update(shield, hasQuietTarget, quietHealthTarget);
		context.setPacketHandled(true);
	}
}
