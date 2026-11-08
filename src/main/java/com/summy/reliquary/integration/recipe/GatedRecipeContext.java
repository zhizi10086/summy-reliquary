package com.summy.reliquary.integration.recipe;

import net.minecraft.world.entity.player.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * 标记当前线程是否正在执行玩家可见的手动合成网格刷新。
 *
 * <p>只由原版工作台与玩家 2x2 合成共用的 {@code slotChangedCraftingGrid} 入口开启；
 * 机械合成、配方自动化和直接 {@code assemble} 调用默认都视为自动化。
 */
public final class GatedRecipeContext {
	private static final ThreadLocal<Deque<Player>> MANUAL_STACK =
			ThreadLocal.withInitial(ArrayDeque::new);

	private GatedRecipeContext() {
	}

	public static Scope enterManualCrafting(Player player) {
		Deque<Player> stack = MANUAL_STACK.get();
		stack.push(Objects.requireNonNull(player, "player"));
		return new Scope();
	}

	public static boolean isManualCrafting() {
		return !MANUAL_STACK.get().isEmpty();
	}

	/** 服务器停止或开发自检复用时清理线程残留。 */
	public static void clear() {
		MANUAL_STACK.remove();
	}

	public static final class Scope implements AutoCloseable {
		private boolean closed;

		private Scope() {
		}

		@Override
		public void close() {
			if (closed) {
				return;
			}
			closed = true;
			Deque<Player> stack = MANUAL_STACK.get();
			if (!stack.isEmpty()) {
				stack.pop();
			}
			if (stack.isEmpty()) {
				MANUAL_STACK.remove();
			}
		}
	}
}
