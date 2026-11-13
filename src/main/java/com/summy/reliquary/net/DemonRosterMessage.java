package com.summy.reliquary.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.summy.reliquary.client.ReliquaryClientState;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务端 → 全体客户端：广播「当前**在线且拥有恶魔标记**的玩家名单」（1.5.9）。
 *
 * <p>用途与 {@code AngelRosterMessage} 相同：客户端渲染别人头顶名字时要按 UUID 判断该染什么颜色
 * （恶魔 = 深红，天使 = 金色），否则只能看到自己的标记位。
 */
public class DemonRosterMessage implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<DemonRosterMessage> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("summy-reliquary", "demon_roster"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DemonRosterMessage> CODEC =
			StreamCodec.of((buffer, value) -> value.encode(buffer), DemonRosterMessage::new);

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

	public void handle(IPayloadContext context) {
		context.enqueueWork(() -> {
			ReliquaryClientState.setDemonRoster(demons);
		});
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
