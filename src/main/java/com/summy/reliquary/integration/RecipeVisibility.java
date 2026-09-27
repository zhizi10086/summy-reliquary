package com.summy.reliquary.integration;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.function.Function;

/**
 * 客户端 ↔ JEI 之间的「配方可见性」桥（1.4.1 起支持多张配方）。
 *
 * <p>这里刻意不引用任何 JEI 类型：没装 JEI 时本类照样能加载，只是回调为空、什么都不做。
 * JEI 运行时可用时由 {@code ReliquaryJeiPlugin} 把写入回调注册进来；客户端每 tick 调用一次
 * {@link #tick(Object, Map)}，只在目标状态变化（或刚换世界）时才真正通知 JEI。
 *
 * <p>目前用它来按玩家状态切换两张/多张配方的可见性：
 * 赎罪配方（七罪是否全部已赎罪）、天使线配方（是否有天使标记：三件套 / 圣光 / 斗篷 / 神性 / 圣心）、
 * 仪式法袍（是否已签约）。
 */
public final class RecipeVisibility {
	/** JEI 写入回调：返回 true 表示已成功写入（世界未加载 / 拿不到配方时返回 false，之后会重试） */
	private static Function<Map<ResourceLocation, Boolean>, Boolean> applier;
	/** 上一次的客户端世界：换世界后 JEI 里的配方对象全都换了，需要重新写一遍 */
	private static Object lastLevel;
	/** 是否已经成功写入过 */
	private static boolean applied;
	/** 上一次成功写入的状态 */
	private static Map<ResourceLocation, Boolean> appliedState = Map.of();
	/** 客户端 tick 计数 */
	private static long tickCounter;
	/** 换世界后持续重试的截止 tick：兜住"JEI 还没注册完配方"的时间窗 */
	private static long retryUntilTick;

	private RecipeVisibility() {
	}

	/** JEI 运行时可用时注册回调 */
	public static void setApplier(Function<Map<ResourceLocation, Boolean>, Boolean> value) {
		applier = value;
		applied = false;
		lastLevel = null;
	}

	/** JEI 运行时卸载时清除回调 */
	public static void clearApplier() {
		applier = null;
		applied = false;
		lastLevel = null;
	}

	/**
	 * 客户端每 tick 调用一次。
	 *
	 * @param level  当前客户端世界（null 表示还没进世界）
	 * @param wanted 目标状态：配方 id → 是否可见
	 */
	public static void tick(Object level, Map<ResourceLocation, Boolean> wanted) {
		if (applier == null) {
			return;
		}
		tickCounter++;
		if (level != lastLevel) {
			lastLevel = level;
			applied = false;
			retryUntilTick = tickCounter + 100L;
		}
		if (level == null) {
			return;
		}
		boolean needApply = !applied || !appliedState.equals(wanted);
		boolean retry = tickCounter <= retryUntilTick && tickCounter % 20L == 0L;
		if (!needApply && !retry) {
			return;
		}
		if (Boolean.TRUE.equals(applier.apply(wanted))) {
			applied = true;
			appliedState = Map.copyOf(wanted);
		}
	}
}
