package com.summy.reliquary.net;

import com.summy.reliquary.client.ReliquaryClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服务端 → 全体客户端：广播「当前**在线且拥有恶魔标记**的玩家名单」（1.5.9）。
 *
 * <p>用途与 {@code AngelRosterMessage} 相同：客户端渲染别人头顶名字时要按 UUID 判断该染什么颜色
 * （恶魔 = 深红，天使 = 金色），否则只能看到自己的标记位。
 */
public class DemonRosterMessage {
	/** 有恶魔标记的玩家 UUID */
	private final List<UUID> demons;

	public DemonRosterMessage(List<UUID> demons) {
		this.demons = List.copyOf(demons);
	}

	public DemonRosterMessage(FriendlyByteBuf buffer) {
		int size = buffer.readVarInt();
		List<UUID> list = new ArrayList<>(size);
		for (int index = 0; index < size; index++) {
			list.add(buffer.readUUID());
		}
		this.demons = list;
	}

	public void encode(FriendlyByteBuf buffer) {
		buffer.writeVarInt(demons.size());
		for (UUID id : demons) {
			buffer.writeUUID(id);
		}
	}

	public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		ReliquaryClientState.setDemonRoster(demons);
		context.setPacketHandled(true);
	}
}
