package com.summy.reliquary.effect;

import com.summy.reliquary.client.ReliquaryClientState;
import com.summy.reliquary.sin.SinManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 玩家身上的本模组标记（都存在既有的 {@code summy_reliquary} 复合标签下，
 * 因此死亡 / 换维度时会随该标签一起复制到新实体）。
 *
 * <ul>
 *     <li>{@code angel}：「纯洁之人」完成时获得，是灵台三件套 / 伯列恒之星 / 终末天启的查阅、佩戴、合成门槛；</li>
 *     <li>{@code sin_renounced}：「无罪之人」完成时获得，之后无法再佩戴七罪之源；</li>
 *     <li>{@code star_granted}：「三位一体」自动发放伯列恒之星时的一次性去重标记。</li>
 * </ul>
 *
 * <p>前两个标记需要客户端读取（提示文本要用），所以服务端会通过 {@code PlayerStateMessage}
 * 同步到 {@link ReliquaryClientState}；读取时统一走这里的 {@code hasXxx} 方法，
 * 它们会自动区分客户端与服务端。
 */
public final class PlayerFlags {
	/** 天使标记 */
	private static final String ANGEL = "angel";
	/** 已放弃一切（无罪之人）标记 */
	private static final String SIN_RENOUNCED = "sin_renounced";
	/** 伯列恒之星已发放标记 */
	private static final String STAR_GRANTED = "star_granted";
	/** 末影龙一次性裁决：0 = 未裁决、1 = 无罪之人、2 = 纯洁无瑕 */
	private static final String DRAGON_VERDICT = "dragon_verdict";
	/** 五芒星：是否已经发放过（只发一次；老存档靠每秒兜底补发一次） */
	private static final String PENTAGRAM_GRANTED = "pentagram_granted";
	/** 五芒星：累计持有时间（tick），跨存档保留 */
	private static final String PENTAGRAM_TICKS = "pentagram_ticks";
	/** 五芒星：那句「交易」聊天消息是否已经发过（终身一次） */
	private static final String PENTAGRAM_SPOKEN = "pentagram_message_sent";
	/** 恶魔交易：当前持有「恶魔」标记（与 angel 互斥） */
	private static final String DEMON_MARK = "demon_mark";
	/**
	 * 恶魔交易：曾签下契约（永久）。
	 *
	 * <p>只用于两件事：① 五芒星提示的三态（换回天使标记后改说"我们的约定依然作数"）；
	 * ② 抑制 {@code SinChallenges.selfHeal} 把天使标记塞回来。
	 * **1.5.10 起它不再阻止二次签约** —— 能不能再签只看"当前是否还持着恶魔标记"。
	 */
	private static final String DEMON_SEALED = "demon_sealed";
	/** 恶魔交易：已展示过首次邀请（或因持有终末天启而静默置位） */
	private static final String DEMON_INVITED = "demon_invited";
	/** 恶魔交易：已展示过"离开峡谷"的三行 */
	private static final String DEMON_LEFT = "demon_left";
	/** 恶魔交易：已展示过"回到峡谷"的两行 */
	private static final String DEMON_RETURNED = "demon_returned";
	/** 七罪之源：累计佩戴秒数（1.7.10 收尾：「纯洁无瑕」的佩戴率判据） */
	private static final String SIN_WORN_SECONDS = "sin_worn_seconds";
	/** 七罪之源：累计未佩戴秒数（同上；开局宽限期内不计） */
	private static final String SIN_UNWORN_SECONDS = "sin_unworn_seconds";
	/** 七罪之源：追踪起点（玩家个人游戏秒数 + 1；0 = 还没建立记录） */
	private static final String SIN_TRACKING_START = "sin_tracking_start_seconds";
	/** 恶魔契约：黑心池剩余点数（单位＝吸收点） */
	private static final String BLACK_HEART_POINTS = "black_hearts_points";
	/** 恶魔契约：本份契约是否已完成献祭（每次签约重置） */
	private static final String SACRIFICE_DONE = "sacrifice_done";
	/** 恶魔契约：已签约次数（决定献祭台词用首轮还是二轮版本） */
	private static final String PACT_SIGNS = "pact_signs";
	/** 恶魔契约：签约瞬间的「魂印物品 + 七罪逐项状态」快照（忏悔时原样恢复，1.6.1） */
	private static final String DEMON_SIN_SNAPSHOT = "demon_sin_snapshot";
	/**
	 * 1.8.0：签约时是否**发放过**仪式匕首（献祭匕首）。
	 *
	 * <p>防丢失状态机的前置：只要发放发生过（哪怕当时背包满、匕首掉在地上或掉进虚空、玩家从没真正拿到），
	 * 之后丢失满 5 分钟就会给出提示并开放配方 —— 否则玩家既不知道丢了、也永远拿不回来。
	 * 属恶魔线数据，**随创世纪清除**。
	 */
	private static final String DAGGER_GRANTED = "dagger_granted";
	/**
	 * 1.8.2：神性「回溯」（X 键）用的**上一次死亡地点**（维度 id + 坐标）。
	 *
	 * <p>写在 {@code summy_reliquary} 根标签下，因此死亡 / 换维度会随该标签一起复制到新实体；
	 * 与恶魔线数据无关，创世纪不清除。
	 */
	private static final String LAST_DEATH_DIM = "last_death_dim";
	private static final String LAST_DEATH_X = "last_death_x";
	private static final String LAST_DEATH_Y = "last_death_y";
	private static final String LAST_DEATH_Z = "last_death_z";
	/** 1.8.5：神性「神圣光环」是否被玩家关掉（默认 false = 开启；写盘持久，创世纪重置时回默认） */
	private static final String GODHEAD_AURA_OFF = "godhead_aura_off";
	/** 1.7.6：已签约玩家"连续多少秒身上没有任何一把仪式匕首" */
	private static final String DAGGER_MISSING_SECONDS = "dagger_missing_seconds";
	/** 1.7.6：献祭匕首的「防丢失配方」是否已开放 */
	private static final String DAGGER_RECOVERY_OPEN = "dagger_recovery_open";
	/** 1.7.9：玩家"连续多少秒身上没有任何一把天使线长矛"（只对曾获得过长矛的玩家累计） */
	private static final String SPEAR_MISSING_SECONDS = "spear_missing_seconds";
	/** 1.7.9：圣光短矛的「防丢失配方」是否已开放 */
	private static final String SPEAR_RECOVERY_OPEN = "spear_recovery_open";
	/**
	 * 1.7.9：是否曾通过"七罪之源 → 美德"获得过圣光短矛。
	 *
	 * <p>只用于防丢失状态机的"曾经有过"判据（新玩家不该一上来就看到那张配方）。
	 * 按需求**不随创世纪清除** —— 创世纪会收走两把长矛，之后玩家仍能靠防丢失配方重新铸一柄。
	 */
	private static final String SPEAR_OBTAINED = "spear_obtained";
	/** 「6」的掉落记录（1.6.2）：bit0 末影龙 / bit1 凋灵 / bit2 监守者，各只掉一次，忏悔时清零 */
	private static final String SIX_DROPS = "six_drops";
	/** 魂心池剩余点数（1.6.2：独立的池子，与黑心并列；不再写进原版吸收值） */
	private static final String SOUL_HEART_POINTS = "soul_heart_points";
	/** 邪恶度（可能带小数） */
	private static final String EVIL = "evil";
	/** 记录过的游戏日（用于每日衰减与每日上限重置）；未记录过时视为"今天首次" */
	private static final String EVIL_DAY = "evil_day";
	/** 亚巴顿：复活冷却到期时间（game tick；0 = 可用） */
	private static final String ABADDON_REVIVE_READY_AT = "abaddon_revive_ready_at";
	/** 当日已获得的邪恶度总量（受 daily_cap 限制） */
	private static final String EVIL_TODAY = "evil_today";
	/** 当日友善档已获得的邪恶度（受 friendly_daily_cap 限制） */
	private static final String EVIL_FRIENDLY_TODAY = "evil_friendly_today";
	/** 已解锁的邪恶度里程碑位图（永久；邪恶度衰减也不收回） */
	private static final String EVIL_UNLOCKS = "evil_unlocks";
	/** 达到 666 后永久锁定天使线 */
	private static final String HELL_LOCKED = "hell_locked";
	/** 是否获取过启示（1.6.10；为真时恶魔交易关闭，创世纪会清除） */
	private static final String REVELATION_OBTAINED = "revelation_obtained";
	/** 启示属性的老存档迁移是否做过（1.6.10；只做一次，创世纪不清） */
	private static final String REVELATION_MIGRATED = "revelation_migrated";
	/** 创世纪：是否已经"知道"过它（1.6.10；获取前禁止查看功能） */
	private static final String GENESIS_KNOWN = "genesis_known";
	/** 创世纪：是否已因"获得神性"发放过（1.6.10） */
	private static final String GENESIS_GRANTED_GODHEAD = "genesis_granted_godhead";
	/** 创世纪：是否已因"获得亚巴顿"发放过（1.6.10） */
	private static final String GENESIS_GRANTED_ABADDON = "genesis_granted_abaddon";
	/**
	 * 天使标记的老存档一次性迁移是否做过（1.6.10）。
	 *
	 * <p>1.6.10 起天使标记改成**由"七罪之源 → 美德"这个动作驱动**；这个标记只用来给
	 * 1.6.10 之前的老存档补一次，之后永不重发动 —— 于是创世纪重置清掉天使标记后，
	 * **不会再被自愈从成就里补回来**（与一周目一致）。
	 */
	private static final String ANGEL_MIGRATED = "angel_migrated";
	/** 恶魔王冠（1.7.1）：是否已经因为"七罪之源 → 撒旦圣经"发放过（创世纪会清） */
	private static final String DEVIL_CROWN_GRANTED = "devil_crown_granted";

	/**
	 * 「该实体实例的本模组数据已就绪」登记（1.5.9）。
	 *
	 * <p>为什么需要它：Curios 在读档时会（`CurioInventoryCapability.readTag` → `DynamicStackHandler.isItemValid`）
	 * 逐个调用我们的 `canEquip` 做合法性校验，而那一刻 Forge 还没把 `summy_reliquary` 根标签写进实体 ——
	 * 天使门槛因此判 false，Curios 就把"本来就戴着的饰品"判成不合法、退回背包。
	 *
	 * <p>判据用**实体实例**而不是 UUID：实体只要 tick 过一次就算就绪（登录读档阶段还没有），
	 * 所以死亡重生 / 换维度拿到的新实体同样会经历一次"未就绪 → 放行"的窗口。用 {@link IdentityHashMap}
	 * 背书（不依赖 equals），并且只在服务端线程访问。
	 */
	private static final Set<LivingEntity> DATA_READY =
			Collections.newSetFromMap(new IdentityHashMap<>());

	private PlayerFlags() {
	}

	private static CompoundTag root(LivingEntity entity) {
		CompoundTag data = entity.getPersistentData();
		return data.contains(SinManager.ROOT, CompoundTag.TAG_COMPOUND)
				? data.getCompound(SinManager.ROOT)
				: new CompoundTag();
	}

	private static CompoundTag mutableRoot(ServerPlayer player) {
		CompoundTag data = player.getPersistentData();
		CompoundTag root = data.contains(SinManager.ROOT, CompoundTag.TAG_COMPOUND)
				? data.getCompound(SinManager.ROOT)
				: new CompoundTag();
		data.put(SinManager.ROOT, root);
		return root;
	}

	private static void set(ServerPlayer player, String key, boolean value) {
		mutableRoot(player).putBoolean(key, value);
	}

	private static void setInt(ServerPlayer player, String key, int value) {
		mutableRoot(player).putInt(key, value);
	}

	// ==================== 天使标记 ====================

	/**
	 * 是否拥有天使标记。
	 *
	 * <p>1.5.3：客户端里"自己"读本地标记位、"别人"读服务端广播的天使名单 ——
	 * 以前一律读本地标记位，导致只要自己拿到标记，所有玩家头顶名字都会被染金。
	 */
	public static boolean hasAngel(LivingEntity entity) {
		if (entity == null) {
			return false;
		}
		if (entity.level().isClientSide()) {
			return ReliquaryClientState.isAngel(entity.getUUID());
		}
		return root(entity).getBoolean(ANGEL);
	}

	public static void setAngel(ServerPlayer player, boolean value) {
		set(player, ANGEL, value);
		// 1.5.9：天使与恶魔互斥（后设置者生效）
		if (value) {
			set(player, DEMON_MARK, false);
		}
		// 名字染金靠服务端广播的名单：标记一变就重算一次
		AngelRoster.refresh(player.getServer());
		DemonRoster.refresh(player.getServer());
	}

	// ==================== 无罪之人标记 ====================

	/** 是否已经「放弃一切」（完成无罪之人后无法再佩戴七罪之源） */
	public static boolean isSinRenounced(LivingEntity entity) {
		if (entity == null) {
			return false;
		}
		if (entity.level().isClientSide()) {
			return ReliquaryClientState.isSinRenounced();
		}
		return root(entity).getBoolean(SIN_RENOUNCED);
	}

	public static void setSinRenounced(ServerPlayer player, boolean value) {
		set(player, SIN_RENOUNCED, value);
	}

	// ==================== 伯列恒之星发放去重 ====================

	public static boolean isStarGranted(LivingEntity entity) {
		return root(entity).getBoolean(STAR_GRANTED);
	}

	public static void setStarGranted(ServerPlayer player, boolean value) {
		set(player, STAR_GRANTED, value);
	}

	// ==================== 五芒星（1.5.8） ====================

	// ==================== 恶魔交易（1.5.9） ====================

	/**
	 * 是否持有「恶魔」标记。
	 *
	 * <p>与天使标记互斥：自己读同步位、别人读服务端广播的恶魔名单，口径与天使一致。
	 */
	public static boolean isDemon(LivingEntity entity) {
		if (entity == null) {
			return false;
		}
		if (entity.level().isClientSide()) {
			return ReliquaryClientState.isDemon(entity.getUUID());
		}
		return root(entity).getBoolean(DEMON_MARK);
	}

	/** 设置恶魔标记；置 true 时会清掉天使标记（互斥） */
	public static void setDemon(ServerPlayer player, boolean value) {
		set(player, DEMON_MARK, value);
		if (value) {
			set(player, ANGEL, false);
		}
		AngelRoster.refresh(player.getServer());
		DemonRoster.refresh(player.getServer());
	}

	/** 是否**曾经**签下契约（永久：用于提示三态 + 抑制自愈补天使；**不阻止二次签约**） */
	public static boolean isDemonSealed(LivingEntity entity) {
		if (entity == null) {
			return false;
		}
		if (entity.level().isClientSide()) {
			return ReliquaryClientState.isDemonSealed();
		}
		return root(entity).getBoolean(DEMON_SEALED);
	}

	public static void setDemonSealed(ServerPlayer player, boolean value) {
		set(player, DEMON_SEALED, value);
	}

	public static boolean isDemonInvited(LivingEntity entity) {
		return entity != null && root(entity).getBoolean(DEMON_INVITED);
	}

	public static void setDemonInvited(ServerPlayer player, boolean value) {
		set(player, DEMON_INVITED, value);
	}

	public static boolean isDemonLeft(LivingEntity entity) {
		return entity != null && root(entity).getBoolean(DEMON_LEFT);
	}

	public static void setDemonLeft(ServerPlayer player, boolean value) {
		set(player, DEMON_LEFT, value);
	}

	public static boolean isDemonReturned(LivingEntity entity) {
		return entity != null && root(entity).getBoolean(DEMON_RETURNED);
	}

	public static void setDemonReturned(ServerPlayer player, boolean value) {
		set(player, DEMON_RETURNED, value);
	}

	/** 一次清掉恶魔交易的五个键（创世纪重置 / 自检用） */
	public static void resetDemonDeal(ServerPlayer player) {
		CompoundTag root = mutableRoot(player);
		root.remove(DEMON_MARK);
		root.remove(DEMON_SEALED);
		root.remove(DEMON_INVITED);
		root.remove(DEMON_LEFT);
		root.remove(DEMON_RETURNED);
		AngelRoster.refresh(player.getServer());
		DemonRoster.refresh(player.getServer());
	}

	// ==================== 本模组数据就绪（1.5.9） ====================

	// ==================== 恶魔契约 / 邪恶度（1.6.0） ====================

	/** 黑心池剩余点数（单位＝吸收点；上限 = 配置的黑心心数 × 每心吸收） */
	public static double blackHeartPoints(LivingEntity entity) {
		return entity == null ? 0.0D : root(entity).getDouble(BLACK_HEART_POINTS);
	}

	public static void setBlackHeartPoints(ServerPlayer player, double value) {
		mutableRoot(player).putDouble(BLACK_HEART_POINTS, Math.max(0.0D, value));
	}

	/** 本份契约是否已完成献祭（每次签约会重置） */
	public static boolean isSacrificeDone(LivingEntity entity) {
		return entity != null && root(entity).getBoolean(SACRIFICE_DONE);
	}

	public static void setSacrificeDone(ServerPlayer player, boolean value) {
		set(player, SACRIFICE_DONE, value);
	}

	/** 已签约次数（1 = 首份契约；>=2 用二轮献祭台词） */
	public static int pactSigns(LivingEntity entity) {
		return entity == null ? 0 : root(entity).getInt(PACT_SIGNS);
	}

	public static void setPactSigns(ServerPlayer player, int value) {
		setInt(player, PACT_SIGNS, Math.max(0, value));
	}

	// ==================== 献祭匕首：防丢失（1.7.6） ====================

	/** 是否发放过仪式匕首（防丢失状态机的前置；老存档以"曾签约 / 当前恶魔"兜底） */
	public static boolean isDaggerGranted(LivingEntity entity) {
		return entity != null && root(entity).getBoolean(DAGGER_GRANTED);
	}

	public static void setDaggerGranted(ServerPlayer player, boolean value) {
		set(player, DAGGER_GRANTED, value);
	}

	/** 连续多少秒身上没有匕首（拿到任意一把就会清零） */
	public static int daggerMissingSeconds(LivingEntity entity) {
		return entity == null ? 0 : root(entity).getInt(DAGGER_MISSING_SECONDS);
	}

	public static void setDaggerMissingSeconds(ServerPlayer player, int value) {
		setInt(player, DAGGER_MISSING_SECONDS, Math.max(0, value));
	}

	/** 「防丢失配方」是否已开放（决定 JEI 可见性与服务端放行） */
	public static boolean isDaggerRecoveryOpen(LivingEntity entity) {
		return entity != null && root(entity).getBoolean(DAGGER_RECOVERY_OPEN);
	}

	public static void setDaggerRecoveryOpen(ServerPlayer player, boolean value) {
		set(player, DAGGER_RECOVERY_OPEN, value);
	}

	// ==================== 神性：回溯（1.8.2） ====================

	/** 记录上一次死亡地点（死亡事件里调用） */
	public static void setLastDeath(ServerPlayer player, String dimension, double x, double y, double z) {
		CompoundTag root = mutableRoot(player);
		root.putString(LAST_DEATH_DIM, dimension == null ? "" : dimension);
		root.putDouble(LAST_DEATH_X, x);
		root.putDouble(LAST_DEATH_Y, y);
		root.putDouble(LAST_DEATH_Z, z);
	}

	/** 是否记录过死亡地点 */
	public static boolean hasLastDeath(LivingEntity entity) {
		return entity != null && !root(entity).getString(LAST_DEATH_DIM).isEmpty();
	}

	/** 上一次死亡地点的维度 id（没记录时为空串） */
	public static String lastDeathDimension(LivingEntity entity) {
		return entity == null ? "" : root(entity).getString(LAST_DEATH_DIM);
	}

	public static double lastDeathX(LivingEntity entity) {
		return entity == null ? 0.0D : root(entity).getDouble(LAST_DEATH_X);
	}

	public static double lastDeathY(LivingEntity entity) {
		return entity == null ? 0.0D : root(entity).getDouble(LAST_DEATH_Y);
	}

	public static double lastDeathZ(LivingEntity entity) {
		return entity == null ? 0.0D : root(entity).getDouble(LAST_DEATH_Z);
	}

	// ==================== 神性「神圣光环」的玩家开关（1.8.5） ====================

	/**
	 * 神性的「神圣光环」是否被玩家关掉（默认 {@code false} = 开启）。
	 *
	 * <p>客户端读的是同步位（提示里的状态后缀要用），服务端读 NBT。
	 */
	public static boolean isGodheadAuraOff(LivingEntity entity) {
		if (entity == null) {
			return false;
		}
		if (entity.level().isClientSide()) {
			return ReliquaryClientState.isGodheadAuraOff();
		}
		return root(entity).getBoolean(GODHEAD_AURA_OFF);
	}

	/** 写「神圣光环」开关（服务端；写盘持久） */
	public static void setGodheadAuraOff(ServerPlayer player, boolean value) {
		set(player, GODHEAD_AURA_OFF, value);
	}

	// ==================== 圣光短矛：防丢失（1.7.9） ====================

	/** 连续多少秒身上没有长矛（拿回任意一把就清零） */
	public static int spearMissingSeconds(LivingEntity entity) {
		return entity == null ? 0 : root(entity).getInt(SPEAR_MISSING_SECONDS);
	}

	public static void setSpearMissingSeconds(ServerPlayer player, int value) {
		setInt(player, SPEAR_MISSING_SECONDS, Math.max(0, value));
	}

	/** 圣光短矛的「防丢失配方」是否已开放（决定 JEI 可见性与服务端放行） */
	public static boolean isSpearRecoveryOpen(LivingEntity entity) {
		return entity != null && root(entity).getBoolean(SPEAR_RECOVERY_OPEN);
	}

	public static void setSpearRecoveryOpen(ServerPlayer player, boolean value) {
		set(player, SPEAR_RECOVERY_OPEN, value);
	}

	/** 是否曾通过"赎罪 → 美德"获得过圣光短矛（防丢失状态机的"曾经有过"判据） */
	public static boolean isSpearObtained(LivingEntity entity) {
		return entity != null && root(entity).getBoolean(SPEAR_OBTAINED);
	}

	public static void setSpearObtained(ServerPlayer player, boolean value) {
		set(player, SPEAR_OBTAINED, value);
	}

	// ==================== 签约快照（1.6.1） ====================

	/**
	 * 记录「签约瞬间」的魂印栏物品与七罪逐项状态。
	 *
	 * <p>忏悔（痛悔短祷换回天使标记）时原样恢复，于是"美德进的签约 → 忏悔后拿回美德"、
	 * "3 罪已赎 + 4 罪激活 → 忏悔后仍是 3+4"都能成立。每次签约都会覆盖上一次的快照。
	 *
	 * @param sealItem 快照时的魂印物品 id（{@code source_of_sins} / {@code virtues}）
	 */
	public static void setSinSnapshot(ServerPlayer player, String sealItem, int sins, int redeemed) {
		CompoundTag snapshot = new CompoundTag();
		snapshot.putString("item", sealItem);
		snapshot.putInt("sins", sins);
		snapshot.putInt("redeemed", redeemed);
		mutableRoot(player).put(DEMON_SIN_SNAPSHOT, snapshot);
	}

	/** 该玩家是否有签约快照 */
	public static boolean hasSinSnapshot(LivingEntity entity) {
		return entity != null && root(entity).contains(DEMON_SIN_SNAPSHOT, CompoundTag.TAG_COMPOUND);
	}

	/** 取签约快照（没有则返回 null） */
	public static CompoundTag sinSnapshot(LivingEntity entity) {
		if (!hasSinSnapshot(entity)) {
			return null;
		}
		return root(entity).getCompound(DEMON_SIN_SNAPSHOT);
	}

	/** 清掉签约快照（忏悔恢复之后调用） */
	public static void clearSinSnapshot(ServerPlayer player) {
		mutableRoot(player).remove(DEMON_SIN_SNAPSHOT);
	}

	// ==================== 魂心池 / 「6」掉落（1.6.2） ====================

	/** 魂心池剩余点数（独立的池子，不占原版吸收值） */
	public static double soulHeartPoints(LivingEntity entity) {
		return entity == null ? 0.0D : root(entity).getDouble(SOUL_HEART_POINTS);
	}

	public static void setSoulHeartPoints(ServerPlayer player, double value) {
		mutableRoot(player).putDouble(SOUL_HEART_POINTS, Math.max(0.0D, value));
	}

	/** 「6」的三类掉落记录位图（bit0 末影龙 / bit1 凋灵 / bit2 监守者） */
	public static int sixDrops(LivingEntity entity) {
		return entity == null ? 0 : root(entity).getInt(SIX_DROPS);
	}

	public static void setSixDrops(ServerPlayer player, int value) {
		setInt(player, SIX_DROPS, value);
	}

	/** 忏悔时清掉「6」的掉落记录（于是三类 Boss 可以再各掉一次） */
	public static void clearSixDrops(ServerPlayer player) {
		mutableRoot(player).remove(SIX_DROPS);
	}

	/** 邪恶度（可能带小数；显示时向下取整） */
	public static double evil(LivingEntity entity) {
		return entity == null ? 0.0D : root(entity).getDouble(EVIL);
	}

	/** 邪恶度的整数显示值 */
	public static int evilDisplay(LivingEntity entity) {
		return (int) Math.floor(evil(entity));
	}

	/** 1.6.4：邪恶度同步给客户端用的**精确值**（保留一位小数；阈值与加成仍走整数口径） */
	public static double evilExact(LivingEntity entity) {
		return Math.round(evil(entity) * 10.0D) / 10.0D;
	}

	public static void setEvil(ServerPlayer player, double value) {
		double max = com.summy.reliquary.config.ReliquaryConfig.evilMax();
		mutableRoot(player).putDouble(EVIL, Math.max(0.0D, Math.min(max, value)));
	}

	/** 是否已经记录过游戏日（没记录过时视为"今天"首次，不做衰减） */
	public static boolean hasEvilDay(LivingEntity entity) {
		return entity != null && root(entity).contains(EVIL_DAY);
	}

	public static long evilDay(LivingEntity entity) {
		return entity == null ? 0L : root(entity).getLong(EVIL_DAY);
	}

	public static void setEvilDay(ServerPlayer player, long value) {
		mutableRoot(player).putLong(EVIL_DAY, value);
	}

	/** 亚巴顿：复活冷却到期时间（game tick；0 或不含该键 = 可用） */
	public static long abaddonReviveReadyAt(LivingEntity entity) {
		return entity == null ? 0L : root(entity).getLong(ABADDON_REVIVE_READY_AT);
	}

	public static void setAbaddonReviveReadyAt(ServerPlayer player, long value) {
		mutableRoot(player).putLong(ABADDON_REVIVE_READY_AT, value);
	}

	public static double evilToday(LivingEntity entity) {
		return entity == null ? 0.0D : root(entity).getDouble(EVIL_TODAY);
	}

	public static void setEvilToday(ServerPlayer player, double value) {
		mutableRoot(player).putDouble(EVIL_TODAY, Math.max(0.0D, value));
	}

	public static double evilFriendlyToday(LivingEntity entity) {
		return entity == null ? 0.0D : root(entity).getDouble(EVIL_FRIENDLY_TODAY);
	}

	public static void setEvilFriendlyToday(ServerPlayer player, double value) {
		mutableRoot(player).putDouble(EVIL_FRIENDLY_TODAY, Math.max(0.0D, value));
	}

	/** 已解锁的邪恶度里程碑位图 */
	public static int evilUnlocks(LivingEntity entity) {
		return entity == null ? 0 : root(entity).getInt(EVIL_UNLOCKS);
	}

	public static void setEvilUnlocks(ServerPlayer player, int value) {
		setInt(player, EVIL_UNLOCKS, value);
	}

	/** 是否已被永久锁定天使线（达到 666） */
	public static boolean isHellLocked(LivingEntity entity) {
		if (entity == null) {
			return false;
		}
		// 1.7.1：恶魔王冠的提示要在客户端判定，所以这一位也走 PlayerStateMessage 同步
		if (entity.level().isClientSide()) {
			return ReliquaryClientState.isHellLocked();
		}
		return root(entity).getBoolean(HELL_LOCKED);
	}

	// ==================== 启示 / 创世纪（1.6.10） ====================

	/**
	 * 是否**获取过启示**（1.6.10）。
	 *
	 * <p>置位时机：星→天启转化成功、或身上持有终末天启 / 神性（每秒兜底）、或老存档一次性迁移。
	 * 该属性为真时**恶魔交易彻底关闭**；创世纪会把它清掉（但保留迁移标记，避免又从成就里补记）。
	 */
	public static boolean isRevelationObtained(LivingEntity entity) {
		if (entity == null) {
			return false;
		}
		if (entity.level().isClientSide()) {
			return ReliquaryClientState.isRevelationAscended();
		}
		return root(entity).getBoolean(REVELATION_OBTAINED);
	}

	public static void setRevelationObtained(ServerPlayer player, boolean value) {
		set(player, REVELATION_OBTAINED, value);
	}

	/** 兼容迁移是否已经做过（只做一次；创世纪重置**不清**它） */
	public static boolean isRevelationMigrated(LivingEntity entity) {
		return entity != null && root(entity).getBoolean(REVELATION_MIGRATED);
	}

	public static void setRevelationMigrated(ServerPlayer player, boolean value) {
		set(player, REVELATION_MIGRATED, value);
	}

	/** 创世纪：是否已经"知道"过它（获取前禁止查看功能；创世纪重置**不清**） */
	public static boolean isGenesisKnown(LivingEntity entity) {
		if (entity == null) {
			return false;
		}
		// 提示要在客户端判定，所以这一位也走 PlayerStateMessage 同步
		if (entity.level().isClientSide()) {
			return ReliquaryClientState.isGenesisKnown();
		}
		return root(entity).getBoolean(GENESIS_KNOWN);
	}

	public static void setGenesisKnown(ServerPlayer player, boolean value) {
		set(player, GENESIS_KNOWN, value);
	}

	/** 创世纪：是否已经因为"获得神性"发过（每条线各发一次；创世纪重置**不清**） */
	public static boolean isGenesisGrantedGodhead(LivingEntity entity) {
		return entity != null && root(entity).getBoolean(GENESIS_GRANTED_GODHEAD);
	}

	public static void setGenesisGrantedGodhead(ServerPlayer player, boolean value) {
		set(player, GENESIS_GRANTED_GODHEAD, value);
	}

	/** 创世纪：是否已经因为"获得亚巴顿"发过（每条线各发一次；创世纪重置**不清**） */
	public static boolean isGenesisGrantedAbaddon(LivingEntity entity) {
		return entity != null && root(entity).getBoolean(GENESIS_GRANTED_ABADDON);
	}

	public static void setGenesisGrantedAbaddon(ServerPlayer player, boolean value) {
		set(player, GENESIS_GRANTED_ABADDON, value);
	}

	/** 天使标记的"老存档一次性迁移"是否做过（1.6.10；创世纪重置**不清**它） */
	public static boolean isAngelMigrated(LivingEntity entity) {
		return entity != null && root(entity).getBoolean(ANGEL_MIGRATED);
	}

	public static void setAngelMigrated(ServerPlayer player, boolean value) {
		set(player, ANGEL_MIGRATED, value);
	}

	/** 恶魔王冠是否已经发放过（1.7.1；创世纪重置会清，于是重做撒旦圣经可再拿） */
	public static boolean isDevilCrownGranted(LivingEntity entity) {
		return entity != null && root(entity).getBoolean(DEVIL_CROWN_GRANTED);
	}

	public static void setDevilCrownGranted(ServerPlayer player, boolean value) {
		set(player, DEVIL_CROWN_GRANTED, value);
	}

	public static void setHellLocked(ServerPlayer player, boolean value) {
		set(player, HELL_LOCKED, value);
	}

	/** 一次清掉恶魔契约 / 邪恶度的全部键（创世纪重置用） */
	public static void resetDemonPact(ServerPlayer player) {
		CompoundTag root = mutableRoot(player);
		root.remove(BLACK_HEART_POINTS);
		root.remove(SACRIFICE_DONE);
		root.remove(PACT_SIGNS);
		root.remove(EVIL);
		root.remove(EVIL_DAY);
		root.remove(EVIL_TODAY);
		root.remove(EVIL_FRIENDLY_TODAY);
		root.remove(EVIL_UNLOCKS);
		root.remove(HELL_LOCKED);
		root.remove(DEMON_SIN_SNAPSHOT);
		root.remove(SIX_DROPS);
	}

	// ==================== 七罪之源佩戴计时（1.7.10 收尾：「纯洁无瑕」判据） ====================

	/** 七罪之源：累计佩戴秒数 */
	public static int sinWornSeconds(LivingEntity entity) {
		return entity == null ? 0 : root(entity).getInt(SIN_WORN_SECONDS);
	}

	/** 七罪之源：累计未佩戴秒数（开局宽限期内不计） */
	public static int sinUnwornSeconds(LivingEntity entity) {
		return entity == null ? 0 : root(entity).getInt(SIN_UNWORN_SECONDS);
	}

	/** 七罪之源：追踪起点（玩家个人游戏秒数；0 = 还没建立记录） */
	public static int sinTrackingStartSeconds(LivingEntity entity) {
		return entity == null ? 0 : root(entity).getInt(SIN_TRACKING_START);
	}

	public static void setSinWornSeconds(ServerPlayer player, int value) {
		mutableRoot(player).putInt(SIN_WORN_SECONDS, Math.max(0, value));
	}

	public static void setSinUnwornSeconds(ServerPlayer player, int value) {
		mutableRoot(player).putInt(SIN_UNWORN_SECONDS, Math.max(0, value));
	}

	public static void setSinTrackingStartSeconds(ServerPlayer player, int value) {
		mutableRoot(player).putInt(SIN_TRACKING_START, Math.max(0, value));
	}

	/**
	 * 一次清掉七罪之源的佩戴计时（创世纪重置用）。
	 *
	 * <p>重置后佩戴率重新从 100% 起算、并重新获得开局宽限，保证"两段线之间"还能做出「纯洁无瑕」。
	 */
	public static void resetSinWearTracking(ServerPlayer player) {
		CompoundTag root = mutableRoot(player);
		root.remove(SIN_WORN_SECONDS);
		root.remove(SIN_UNWORN_SECONDS);
		root.remove(SIN_TRACKING_START);
	}

	/**
	 * 该实体的本模组数据是否已经就绪（服务端 = 这个实例 tick 过；客户端 = 收到过状态包）。
	 *
	 * <p>未就绪时一律**放行**，避免 Curios 读档校验把本来就戴着的门槛饰品判为不合法。
	 */
	public static boolean isDataReady(LivingEntity entity) {
		if (entity == null) {
			return false;
		}
		if (entity.level().isClientSide()) {
			return ReliquaryClientState.hasSynced();
		}
		return DATA_READY.contains(entity);
	}

	public static void markDataReady(LivingEntity entity) {
		if (entity != null) {
			DATA_READY.add(entity);
		}
	}

	public static void forgetDataReady(LivingEntity entity) {
		if (entity != null) {
			DATA_READY.remove(entity);
		}
	}

	/** 服务器停止时清空 */
	public static void clearDataReady() {
		DATA_READY.clear();
	}

	/** 自检缝：显式把某个实体标成"已就绪 / 未就绪" */
	public static void setDataReadyForTest(LivingEntity entity, boolean ready) {
		if (entity == null) {
			return;
		}
		if (ready) {
			DATA_READY.add(entity);
		} else {
			DATA_READY.remove(entity);
		}
	}

	/** 五芒星是否已经发放过（「罪无可赦」只发一次） */
	public static boolean isPentagramGranted(LivingEntity entity) {
		return entity != null && root(entity).getBoolean(PENTAGRAM_GRANTED);
	}

	public static void setPentagramGranted(ServerPlayer player, boolean value) {
		set(player, PENTAGRAM_GRANTED, value);
	}

	/** 五芒星的累计持有时间（tick） */
	public static int pentagramTicks(LivingEntity entity) {
		return entity == null ? 0 : root(entity).getInt(PENTAGRAM_TICKS);
	}

	public static void setPentagramTicks(ServerPlayer player, int value) {
		setInt(player, PENTAGRAM_TICKS, value);
	}

	/**
	 * 那句「交易」聊天消息是否已经发过（终身一次）。
	 *
	 * <p>客户端要按它决定提示里是否追加那一行，所以走 {@code PlayerStateMessage} 同步。
	 */
	public static boolean isPentagramSpoken(LivingEntity entity) {
		if (entity == null) {
			return false;
		}
		if (entity.level().isClientSide()) {
			return ReliquaryClientState.isPentagramSpoken();
		}
		return root(entity).getBoolean(PENTAGRAM_SPOKEN);
	}

	public static void setPentagramSpoken(ServerPlayer player, boolean value) {
		set(player, PENTAGRAM_SPOKEN, value);
	}

	/** 0 = 未裁决；1 = 已判无罪之人；2 = 已判纯洁无瑕 */
	public static int dragonVerdict(LivingEntity entity) {
		return entity == null ? 0 : root(entity).getInt(DRAGON_VERDICT);
	}

	public static void setDragonVerdict(ServerPlayer player, int value) {
		setInt(player, DRAGON_VERDICT, value);
	}

	/** 客户端同步用的位掩码：bit0 = 天使、bit1 = 已放弃一切 */
	public static int clientFlags(LivingEntity entity) {
		int flags = 0;
		if (root(entity).getBoolean(ANGEL)) {
			flags |= ReliquaryClientState.FLAG_ANGEL;
		}
		if (root(entity).getBoolean(SIN_RENOUNCED)) {
			flags |= ReliquaryClientState.FLAG_SIN_RENOUNCED;
		}
		if (hasOverworldRespawn(entity)) {
			flags |= ReliquaryClientState.FLAG_OVERWORLD_RESPAWN;
		}
		if (root(entity).getBoolean(PENTAGRAM_SPOKEN)) {
			flags |= ReliquaryClientState.FLAG_PENTAGRAM_SPOKEN;
		}
		if (root(entity).getBoolean(DEMON_MARK)) {
			flags |= ReliquaryClientState.FLAG_DEMON;
		}
		if (root(entity).getBoolean(DEMON_SEALED)) {
			flags |= ReliquaryClientState.FLAG_DEMON_SEALED;
		}
		if (root(entity).getBoolean(DEMON_INVITED)) {
			flags |= ReliquaryClientState.FLAG_DEMON_INVITED;
		}
		// 1.7.6：献祭匕首的「防丢失配方」是否已开放（bit9，决定 JEI 可见性）
		if (root(entity).getBoolean(DAGGER_RECOVERY_OPEN)) {
			flags |= ReliquaryClientState.FLAG_DAGGER_RECOVERY;
		}
		// 1.7.9：圣光短矛的「防丢失配方」是否已开放（bit11，决定 JEI 可见性）
		if (root(entity).getBoolean(SPEAR_RECOVERY_OPEN)) {
			flags |= ReliquaryClientState.FLAG_SPEAR_RECOVERY;
		}
		// 1.8.5：神性「神圣光环」是否被玩家关掉（bit12，提示里要显示状态后缀）
		if (root(entity).getBoolean(GODHEAD_AURA_OFF)) {
			flags |= ReliquaryClientState.FLAG_GODHEAD_AURA_OFF;
		}
		// 1.6.10：创世纪"是否已知"（获取前禁止查看的提示要用，所以也要同步给客户端）
		if (root(entity).getBoolean(GENESIS_KNOWN)) {
			flags |= ReliquaryClientState.FLAG_GENESIS_KNOWN;
		}
		// 1.7.1：是否已被永久锁定天使线（恶魔王冠的条件行要用）
		if (root(entity).getBoolean(HELL_LOCKED)) {
			flags |= ReliquaryClientState.FLAG_HELL_LOCKED;
		}
		return flags;
	}

	/**
	 * 该玩家是否有「位于主世界的个人重生点」（痛悔短祷的风味文本要用）。
	 *
	 * <p>个人重生点只有服务端知道（客户端没有任何同步字段），所以客户端读的是
	 * {@code PlayerStateMessage} 同步过来的标记位。
	 */
	public static boolean hasOverworldRespawn(LivingEntity entity) {
		if (entity == null) {
			return false;
		}
		if (entity.level().isClientSide()) {
			return ReliquaryClientState.hasOverworldRespawn();
		}
		return entity instanceof ServerPlayer player
				&& player.getRespawnPosition() != null
				&& player.getRespawnDimension() == net.minecraft.world.level.Level.OVERWORLD;
	}
}
