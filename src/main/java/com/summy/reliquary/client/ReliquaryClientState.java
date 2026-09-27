package com.summy.reliquary.client;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 客户端缓存的玩家状态（由服务端 {@code PlayerStateMessage} 同步）。
 *
 * <p>只存基本类型、没有任何客户端专用类引用，因此在专用服务端上被加载也是安全的。
 */
public final class ReliquaryClientState {
	/** 七罪位掩码 */
	private static int sinMask = 0;
	/** 已赎罪位掩码 */
	private static int redeemedMask = 0;
	/** 是否已经揭示启示坐标 */
	private static boolean revealed = false;
	private static int revealX = 0;
	private static int revealZ = 0;
	/** bit0 = 拥有天使标记；bit1 = 已完成「无罪之人」（放弃一切）；bit2 = 已完成「启示」（星已化为天启） */
	private static int flags = 0;
	/** 未揭示时距离揭示还剩多少秒 */
	private static int revealRemainingSeconds = 0;
	/** 本客户端自己的玩家 UUID（服务端在状态包里带上，用来区分"我"和"别人"） */
	private static UUID ownUuid = null;
	/** 服务端广播的「有天使标记的在线玩家」名单（给别的玩家头顶 / Tab 名字染色用） */
	private static final Set<UUID> angelRoster = new HashSet<>();
	/** 服务端广播的「有恶魔标记的在线玩家」名单（名字染深红用，1.5.9） */
	private static final Set<UUID> demonRoster = new HashSet<>();
	/** 邪恶度（1.6.0 起同步；1.6.4 起保留一位小数，契约 tooltip 显示） */
	private static double evil = 0.0D;
	/** 黑心池剩余点数（1.6.0；HUD 画黑心用） */
	private static int blackHeartPoints = 0;
	/** 已解锁的邪恶里程碑位图（1.6.0；决定 JEI 是否隐藏配方） */
	private static int evilUnlocks = 0;
	/** 魂心池剩余点数（1.6.2；HUD 画蓝心用） */
	private static int soulHeartPoints = 0;

	/** 天使标记位 */
	public static final int FLAG_ANGEL = 1;
	/** 已放弃一切位 */
	public static final int FLAG_SIN_RENOUNCED = 2;
	/** 启示已降临位 */
	public static final int FLAG_REVELATION_ASCENDED = 4;
	/** 主世界有个人重生点位（痛悔短祷的风味文本要用；个人重生点只有服务端知道，所以走同步） */
	public static final int FLAG_OVERWORLD_RESPAWN = 8;
	/** 五芒星的那句「交易」消息是否已经发过（决定提示里是否追加那一行） */
	public static final int FLAG_PENTAGRAM_SPOKEN = 16;
	/** 恶魔交易：当前持有「恶魔」标记（1.5.9） */
	public static final int FLAG_DEMON = 32;
	/** 恶魔交易：曾签下契约（决定五芒星提示用哪一套，1.5.9） */
	public static final int FLAG_DEMON_SEALED = 64;
	/** 恶魔交易：已经听过邀请（决定客户端要不要进入"长按签约"的蓄力，1.5.9） */
	public static final int FLAG_DEMON_INVITED = 128;
	/** 创世纪：是否已经"知道"过它（1.6.10；决定提示显示"你还不知此为何物。"还是完整功能） */
	public static final int FLAG_GENESIS_KNOWN = 256;
	/** 是否已被永久锁定天使线（邪恶 666；1.7.1：恶魔王冠的提示要用） */
	public static final int FLAG_HELL_LOCKED = 512;
	/** 1.7.6：献祭匕首的「防丢失配方」是否已开放（bit10；决定 JEI 可见性） */
	public static final int FLAG_DAGGER_RECOVERY = 1024;
	/** 1.7.9：圣光短矛的「防丢失配方」是否已开放（bit11；决定 JEI 可见性） */
	public static final int FLAG_SPEAR_RECOVERY = 2048;

	private ReliquaryClientState() {
	}

	public static void update(UUID owner, int sins, int redeemed, boolean isRevealed, int x, int z,
			int playerFlags, int remainingSeconds) {
		ownUuid = owner;
		sinMask = sins;
		redeemedMask = redeemed;
		revealed = isRevealed;
		revealX = x;
		revealZ = z;
		flags = playerFlags;
		revealRemainingSeconds = remainingSeconds;
	}

	/** 是否有天使标记（灵台套装 / 伯列恒之星 / 终末天启的查阅与佩戴门槛） */
	public static boolean isAngel() {
		return (flags & FLAG_ANGEL) != 0;
	}

	/** 服务端广播「有天使标记的在线玩家」名单 */
	public static void setAngelRoster(Collection<UUID> uuids) {
		angelRoster.clear();
		if (uuids != null) {
			angelRoster.addAll(uuids);
		}
	}

	/**
	 * 指定玩家是否有天使标记。
	 *
	 * <p>1.5.3 起：**自己**读本地标记位（不依赖名单到达时机、避免提示闪烁），
	 * **别人**读服务端广播的名单 —— 以前这里一律读本地标记位，导致"我有标记 → 所有人头顶名字都变金"。
	 */
	public static boolean isAngel(UUID uuid) {
		if (uuid == null) {
			return false;
		}
		return uuid.equals(ownUuid) ? isAngel() : angelRoster.contains(uuid);
	}

	/** 距离揭示还剩多少秒（未揭示时才有意义） */
	public static int revealRemainingSeconds() {
		return revealRemainingSeconds;
	}

	/** 是否已完成「启示」（伯列恒之星已化为终末天启） */
	public static boolean isRevelationAscended() {
		return (flags & FLAG_REVELATION_ASCENDED) != 0;
	}

	/** 是否已经"知道"过创世纪（1.6.10；服务端置位后同步过来） */
	public static boolean isGenesisKnown() {
		return (flags & FLAG_GENESIS_KNOWN) != 0;
	}

	/** 是否已被永久锁定天使线（邪恶 666；1.7.1） */
	public static boolean isHellLocked() {
		return (flags & FLAG_HELL_LOCKED) != 0;
	}

	/** 献祭匕首的「防丢失配方」是否已开放（1.7.6；决定 JEI 可见性） */
	public static boolean isDaggerRecoveryOpen() {
		return (flags & FLAG_DAGGER_RECOVERY) != 0;
	}

	/** 圣光短矛的「防丢失配方」是否已开放（1.7.9；决定 JEI 可见性） */
	public static boolean isSpearRecoveryOpen() {
		return (flags & FLAG_SPEAR_RECOVERY) != 0;
	}

	/** 是否已经「放弃一切」（无法再佩戴七罪之源） */
	public static boolean isSinRenounced() {
		return (flags & FLAG_SIN_RENOUNCED) != 0;
	}

	/** 是否有位于主世界的个人重生点 */
	public static boolean hasOverworldRespawn() {
		return (flags & FLAG_OVERWORLD_RESPAWN) != 0;
	}

	/** 五芒星是否已经说过那句「交易」消息（提示里追加那一行） */
	public static boolean isPentagramSpoken() {
		return (flags & FLAG_PENTAGRAM_SPOKEN) != 0;
	}

	/** 本地玩家自己是否持有恶魔标记 */
	public static boolean isDemon() {
		return (flags & FLAG_DEMON) != 0;
	}

	/** 本地玩家自己是否曾签下契约（决定五芒星提示用哪一套） */
	public static boolean isDemonSealed() {
		return (flags & FLAG_DEMON_SEALED) != 0;
	}

	/** 本地玩家自己是否已经听过恶魔的邀请（客户端用它决定要不要进入蓄力） */
	public static boolean isDemonInvited() {
		return (flags & FLAG_DEMON_INVITED) != 0;
	}

	/** 服务端广播「有恶魔标记的在线玩家」名单 */
	public static void setDemonRoster(Collection<UUID> uuids) {
		demonRoster.clear();
		if (uuids != null) {
			demonRoster.addAll(uuids);
		}
	}

	/**
	 * 指定玩家是否有恶魔标记（口径与天使一致：自己读本地位、别人读服务端名单）。
	 */
	public static boolean isDemon(UUID uuid) {
		if (uuid == null) {
			return false;
		}
		return uuid.equals(ownUuid) ? isDemon() : demonRoster.contains(uuid);
	}

	/** 是否已经收到过服务端的状态包（1.5.9：读档阶段还没收到时，门槛一律放行） */
	public static boolean hasSynced() {
		return ownUuid != null;
	}

	// ==================== 恶魔契约 / 邪恶度（1.6.0） ====================

	/** 服务端同步过来的邪恶度（保留一位小数） */
	public static double evil() {
		return evil;
	}

	/** 黑心池剩余点数 */
	public static int blackHeartPoints() {
		return blackHeartPoints;
	}

	/** 已解锁的邪恶里程碑位图 */
	public static int evilUnlocks() {
		return evilUnlocks;
	}

	/** 魂心池剩余点数（1.6.2） */
	public static int soulHeartPoints() {
		return soulHeartPoints;
	}

	public static void setSoulHeartPoints(int value) {
		soulHeartPoints = Math.max(0, value);
	}

	/** 一次性写入契约相关的三项（由 PlayerStateMessage 调用；1.6.4 起邪恶度是 double，保留一位小数） */
	public static void setDemonStats(double evilValue, int blackHeartPointsValue, int evilUnlocksValue) {
		evil = Math.max(0.0D, evilValue);
		blackHeartPoints = Math.max(0, blackHeartPointsValue);
		evilUnlocks = evilUnlocksValue;
	}

	public static int sinMask() {
		return sinMask;
	}

	/** 已赎罪位掩码（自检要按状态构造提示文本） */
	public static int redeemedMask() {
		return redeemedMask;
	}

	/** 玩家标记位（自检要按状态构造提示文本） */
	public static int flags() {
		return flags;
	}

	public static boolean hasAnySin() {
		return sinMask != 0;
	}

	/** 是否还有「已激活但未赎罪」的罪（光环减半的判定条件） */
	public static boolean hasAnyUnredeemed() {
		return (sinMask & ~redeemedMask) != 0;
	}

	public static boolean isRedeemed(int ordinal) {
		return (redeemedMask & (1 << ordinal)) != 0;
	}

	/** 七罪是否全部已赎罪 */
	public static boolean allRedeemed(int sinCount) {
		int all = (1 << sinCount) - 1;
		return (redeemedMask & all) == all;
	}

	public static boolean isSinActive(int ordinal) {
		return (sinMask & (1 << ordinal)) != 0;
	}

	public static boolean isRevealed() {
		return revealed;
	}

	public static int revealX() {
		return revealX;
	}

	public static int revealZ() {
		return revealZ;
	}
}
