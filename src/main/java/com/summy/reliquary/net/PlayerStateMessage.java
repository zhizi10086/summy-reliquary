package com.summy.reliquary.net;

import com.summy.reliquary.client.ReliquaryClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;
import java.util.UUID;

/**
 * 服务端 → 客户端：同步该玩家的七罪激活位掩码与启示坐标状态（提示文本要用）。
 */
public class PlayerStateMessage {
	/** 这条消息的目标玩家（客户端用来区分"我"和"别人"） */
	private final UUID owner;
	private final int sins;
	private final int redeemed;
	private final boolean revealed;
	private final int revealX;
	private final int revealZ;
	/** 玩家标记位：bit0 = 天使、bit1 = 已放弃一切 */
	private final int flags;
	/** 未揭示时距离揭示还剩多少秒（客户端用来显示倒计时） */
	private final int revealRemainingSeconds;
	/** 邪恶度（1.6.0 起同步；1.6.4 起改成 double，提示里显示一位小数） */
	private final double evil;
	/** 黑心池剩余点数（1.6.0；HUD 画黑心） */
	private final int blackHeartPoints;
	/** 已解锁的邪恶里程碑位图（1.6.0；JEI 隐藏配方） */
	private final int evilUnlocks;
	/** 魂心池剩余点数（1.6.2；HUD 画蓝心） */
	private final int soulHeartPoints;

	public PlayerStateMessage(UUID owner, int sins, int redeemed, boolean revealed, int revealX, int revealZ,
			int flags, int revealRemainingSeconds, double evil, int blackHeartPoints, int evilUnlocks,
			int soulHeartPoints) {
		this.owner = owner;
		this.sins = sins;
		this.redeemed = redeemed;
		this.revealed = revealed;
		this.revealX = revealX;
		this.revealZ = revealZ;
		this.flags = flags;
		this.revealRemainingSeconds = revealRemainingSeconds;
		this.evil = evil;
		this.blackHeartPoints = blackHeartPoints;
		this.evilUnlocks = evilUnlocks;
		this.soulHeartPoints = soulHeartPoints;
	}

	public PlayerStateMessage(FriendlyByteBuf buffer) {
		this.owner = buffer.readUUID();
		this.sins = buffer.readVarInt();
		this.redeemed = buffer.readVarInt();
		this.revealed = buffer.readBoolean();
		this.revealX = buffer.readInt();
		this.revealZ = buffer.readInt();
		this.flags = buffer.readVarInt();
		this.revealRemainingSeconds = buffer.readVarInt();
		this.evil = buffer.readDouble();
		this.blackHeartPoints = buffer.readVarInt();
		this.evilUnlocks = buffer.readVarInt();
		this.soulHeartPoints = buffer.readVarInt();
	}

	public void encode(FriendlyByteBuf buffer) {
		buffer.writeUUID(owner);
		buffer.writeVarInt(sins);
		buffer.writeVarInt(redeemed);
		buffer.writeBoolean(revealed);
		buffer.writeInt(revealX);
		buffer.writeInt(revealZ);
		buffer.writeVarInt(flags);
		buffer.writeVarInt(revealRemainingSeconds);
		buffer.writeDouble(evil);
		buffer.writeVarInt(blackHeartPoints);
		buffer.writeVarInt(evilUnlocks);
		buffer.writeVarInt(soulHeartPoints);
	}

	public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		ReliquaryClientState.update(owner, sins, redeemed, revealed, revealX, revealZ, flags,
				revealRemainingSeconds);
		ReliquaryClientState.setDemonStats(evil, blackHeartPoints, evilUnlocks);
		ReliquaryClientState.setSoulHeartPoints(soulHeartPoints);
		context.setPacketHandled(true);
	}
}
