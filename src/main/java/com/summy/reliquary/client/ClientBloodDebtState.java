package com.summy.reliquary.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * 血债祭坛的客户端显示状态。
 *
 * <p>护盾本身是服务端独立账本；这里只保存同步值和一小段“静默生命同步”目标。
 * 原版在收到生命值下降包时会把 {@code hurtTime} 设为 10，从而触发镜头受击倾斜。
 * 血债逐 tick 还款属于无来源的静默扣血，因此只在客户端生命值精确达到服务端给出的
 * 本次预期值后清除该动画；同一 tick 真正受到攻击时生命值会继续下降，不会误吞受击反馈。
 */
public final class ClientBloodDebtState {
	/** 静默目标最多保留的客户端 tick 数，防止延迟包永久影响后续真实受击。 */
	private static final int QUIET_TARGET_LIFETIME_TICKS = 3;

	private static double shield;
	private static boolean hasQuietTarget;
	private static float quietHealthTarget;
	private static int quietTargetTicks;

	private ClientBloodDebtState() {
	}

	/** 当前血债盾点数，供黄心覆盖层绘制。 */
	public static double shield() {
		return shield;
	}

	/**
	 * 更新服务端状态。
	 *
	 * <p>不带静默目标的普通护盾同步不会覆盖已经挂起的目标；血债还款会在同一个包中
	 * 携带最新的护盾值和本次预期生命值，两者一次写入。
	 */
	public static void update(double shieldValue, boolean quietTargetPresent, float quietTarget) {
		shield = Math.max(0.0D, shieldValue);
		if (quietTargetPresent) {
			hasQuietTarget = true;
			quietHealthTarget = Math.max(0.0F, quietTarget);
			quietTargetTicks = QUIET_TARGET_LIFETIME_TICKS;
		}
	}

	/** 客户端 tick：离开世界时清空，并淘汰过期的静默目标。 */
	public static void tick() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			clear();
			return;
		}
		if (!hasQuietTarget) {
			return;
		}
		quietTargetTicks--;
		if (quietTargetTicks <= 0) {
			clearQuietTarget();
		}
	}

	/**
	 * 在渲染前尝试消费静默目标。
	 *
	 * @return true 表示本次生命变化与血债静默还款完全匹配，已清除受击镜头
	 */
	public static boolean suppressMatchingDamageTilt(LocalPlayer player) {
		if (player == null || !hasQuietTarget || !matchesQuietHealth(player.getHealth())) {
			return false;
		}
		player.hurtTime = 0;
		clearQuietTarget();
		return true;
	}

	/** 纯函数形式的匹配检查，供自检复用。 */
	public static boolean matchesQuietHealthForTest(float currentHealth) {
		return hasQuietTarget && matchesQuietHealth(currentHealth);
	}

	/** 自检：手动写入一个静默目标。 */
	public static void markQuietHealthForTest(float targetHealth) {
		update(shield, true, targetHealth);
	}

	/** 自检 / 换世界：清空全部客户端血债状态。 */
	public static void clear() {
		shield = 0.0D;
		clearQuietTarget();
	}

	private static boolean matchesQuietHealth(float currentHealth) {
		return Math.abs(currentHealth - quietHealthTarget) <= 1.0E-4F;
	}

	private static void clearQuietTarget() {
		hasQuietTarget = false;
		quietHealthTarget = 0.0F;
		quietTargetTicks = 0;
	}
}
