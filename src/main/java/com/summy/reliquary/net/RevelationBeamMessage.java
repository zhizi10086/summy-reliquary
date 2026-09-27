package com.summy.reliquary.net;

import com.summy.reliquary.client.ClientRenderHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 → 客户端：广播一次「启示之光」的位置与朝向，供客户端渲染光柱。
 *
 * <p>真正的渲染器由客户端注册（见 {@link ClientRenderHooks}），本类不引用任何客户端类型。
 */
public class RevelationBeamMessage {
	private final double x;
	private final double y;
	private final double z;
	private final float dirX;
	private final float dirY;
	private final float dirZ;
	private final float length;
	private final float radius;
	private final int durationTicks;
	/** 光束种类（1.6.4）：见 {@code RevelationBeam.BeamKind} 的 ordinal —— 客户端据此决定配色 */
	private final int kind;

	public RevelationBeamMessage(double x, double y, double z, float dirX, float dirY, float dirZ,
			float length, float radius, int durationTicks, int kind) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.dirX = dirX;
		this.dirY = dirY;
		this.dirZ = dirZ;
		this.length = length;
		this.radius = radius;
		this.durationTicks = durationTicks;
		this.kind = kind;
	}

	public RevelationBeamMessage(FriendlyByteBuf buffer) {
		this.x = buffer.readDouble();
		this.y = buffer.readDouble();
		this.z = buffer.readDouble();
		this.dirX = buffer.readFloat();
		this.dirY = buffer.readFloat();
		this.dirZ = buffer.readFloat();
		this.length = buffer.readFloat();
		this.radius = buffer.readFloat();
		this.durationTicks = buffer.readVarInt();
		this.kind = buffer.readVarInt();
	}

	public void encode(FriendlyByteBuf buffer) {
		buffer.writeDouble(x);
		buffer.writeDouble(y);
		buffer.writeDouble(z);
		buffer.writeFloat(dirX);
		buffer.writeFloat(dirY);
		buffer.writeFloat(dirZ);
		buffer.writeFloat(length);
		buffer.writeFloat(radius);
		buffer.writeVarInt(durationTicks);
		buffer.writeVarInt(kind);
	}

	public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> ClientRenderHooks.beamSink.spawn(x, y, z, dirX, dirY, dirZ,
				length, radius, durationTicks, kind));
		context.setPacketHandled(true);
	}
}
