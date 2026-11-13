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
 * 服务端 → 全体客户端：广播「当前**在线且拥有天使标记**的玩家名单」。
 *
 * <p>为什么要单独广播：Forge 的 {@code NameFormat} 事件在客户端渲染别人头顶名字时也会触发，
 * 而客户端唯一的玩家状态包里只有"自己"的标记位 —— 于是 1.5.2 之前只要自己有天使标记，
 * 所有玩家头顶名字都会被染金。现在改成按 UUID 查这份名单，谁有标记谁才金。
 */
public class AngelRosterMessage implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<AngelRosterMessage> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("summy-reliquary", "angel_roster"));
	public static final StreamCodec<RegistryFriendlyByteBuf, AngelRosterMessage> CODEC =
			StreamCodec.of((buffer, value) -> value.encode(buffer), AngelRosterMessage::new);

	/** 有天使标记的玩家 UUID */
	private final List<UUID> angels;

	public AngelRosterMessage(List<UUID> angels) {
		this.angels = List.copyOf(angels);
	}

	public AngelRosterMessage(FriendlyByteBuf buffer) {
		int size = buffer.readVarInt();
		List<UUID> list = new ArrayList<>(size);
		for (int index = 0; index < size; index++) {
			list.add(buffer.readUUID());
		}
		this.angels = list;
	}

	public void encode(FriendlyByteBuf buffer) {
		buffer.writeVarInt(angels.size());
		for (UUID id : angels) {
			buffer.writeUUID(id);
		}
	}

	public void handle(IPayloadContext context) {
		context.enqueueWork(() -> {
			ReliquaryClientState.setAngelRoster(angels);
		});
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
