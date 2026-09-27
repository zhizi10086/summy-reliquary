package com.summy.reliquary.sin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/**
 * 七罪的触发进度计数（存放在玩家持久化数据里，与七罪状态同一个根标签下）。
 *
 * <p>因为 {@code summy_reliquary} 这一整个复合标签在玩家死亡 / 换维度时会被整份复制到新实体上，
 * 计数天然跨死亡保留，不需要额外处理。
 *
 * <p>计数只在**佩戴七罪之源**时累加（未佩戴时暂停、不清零）。
 */
public final class SinProgress {
	/** 计数所在的复合标签键 */
	private static final String PROGRESS = "sin_progress";

	// ===== 计数键 =====
	/** 傲慢：击杀的中立 / 友善生物数量 */
	public static final String PRIDE_KILLS = "pride_kills";
	/** 嫉妒：是否已经观察到满足增伤条件的生物（0 / 1） */
	public static final String ENVY_SEEN = "envy_seen";
	/** 暴怒：累计击杀的非玩家生物数量 */
	public static final String WRATH_KILLS = "wrath_kills";
	/** 怠惰：早睡次数（入睡时刻早于配置小时） */
	public static final String SLOTH_SLEEPS = "sloth_sleeps";
	/** 贪婪：历史上背包里出现过的最大钻石数量 */
	public static final String GREED_PEAK_DIAMONDS = "greed_peak_diamonds";
	/** 暴食：食用高饱和度食物（或佩戴过白饭）的次数 */
	public static final String GLUTTONY_MEALS = "gluttony_meals";
	/** 色欲：累计繁殖动物的次数 */
	public static final String LUST_BREEDS = "lust_breeds";

	private SinProgress() {
	}

	private static CompoundTag root(ServerPlayer player) {
		CompoundTag data = player.getPersistentData();
		return data.contains(SinManager.ROOT, CompoundTag.TAG_COMPOUND)
				? data.getCompound(SinManager.ROOT)
				: new CompoundTag();
	}

	private static CompoundTag progress(ServerPlayer player) {
		CompoundTag root = root(player);
		return root.contains(PROGRESS, CompoundTag.TAG_COMPOUND) ? root.getCompound(PROGRESS) : new CompoundTag();
	}

	private static CompoundTag mutableProgress(ServerPlayer player) {
		CompoundTag data = player.getPersistentData();
		CompoundTag root = data.contains(SinManager.ROOT, CompoundTag.TAG_COMPOUND)
				? data.getCompound(SinManager.ROOT)
				: new CompoundTag();
		CompoundTag progress = root.contains(PROGRESS, CompoundTag.TAG_COMPOUND)
				? root.getCompound(PROGRESS)
				: new CompoundTag();
		root.put(PROGRESS, progress);
		data.put(SinManager.ROOT, root);
		return progress;
	}

	/** 读取一项计数（不存在时为 0） */
	public static int get(ServerPlayer player, String key) {
		return progress(player).getInt(key);
	}

	/** 加一项计数，返回加完之后的值 */
	public static int add(ServerPlayer player, String key, int delta) {
		CompoundTag progress = mutableProgress(player);
		int value = Math.max(0, progress.getInt(key) + delta);
		progress.putInt(key, value);
		return value;
	}

	/** 写入一项计数 */
	public static void set(ServerPlayer player, String key, int value) {
		mutableProgress(player).putInt(key, Math.max(0, value));
	}

	/** 清空所有进度（自检用；正式玩法里不调用） */
	public static void reset(ServerPlayer player) {
		mutableProgress(player).getAllKeys().clear();
	}
}
