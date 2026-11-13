package com.summy.reliquary.client;

import net.minecraft.util.Mth;

/**
 * 长矛动画的纯函数曲线（1.8.8）。
 *
 * <p>数值参照 {@code Backported-Spears} 1.21.1 的 {@code holdUpAnimation} 与戳刺曲线：
 * 蓄力分「举起 / 摇晃」两段，戳刺用三段缓动。全部写成静态纯函数，便于自检断言，
 * 不依赖客户端实体或渲染状态。
 */
public final class SpearAnimation {
	/** 挥击时长下限（tick）：攻速再快也不低于 4 tick */
	public static final int SWING_TICKS_MIN = 4;
	/** 挥击时长上限（tick）：2 秒 */
	public static final int SWING_TICKS_MAX = 40;
	/** 蓄力举矛的举起时长（tick），与 {@code SpearItem.MIN_CHARGE_TICKS} 一致 */
	public static final int RAISE_TICKS = 10;

	private SpearAnimation() {
	}

	/**
	 * 挥击时长：与攻击冷却严格同步（原版攻击间隔 = 20 / 攻速）。
	 *
	 * @param attackSpeed 当前攻击速度属性值
	 */
	public static int swingDuration(float attackSpeed) {
		float speed = Math.max(0.1F, attackSpeed);
		return Mth.clamp(Math.round(20.0F / speed), SWING_TICKS_MIN, SWING_TICKS_MAX);
	}

	/** 蓄力动画的 10 个分量；无冲刺段时 sway/lower/back 恒为 0 */
	public record HoldUp(float raiseProgress, float raiseProgressStart, float raiseProgressMiddle,
			float raiseProgressEnd, float swayProgress, float lowerProgress, float raiseBackProgress,
			float swayIntensity, float swayScaleSlow, float swayScaleFast) {
	}

	/**
	 * 蓄力曲线：{@code useTicks} 为已蓄力 tick，{@code maxHoldTicks} 为可举着的上限。
	 */
	public static HoldUp holdUp(float useTicks, int maxHoldTicks) {
		int raise = Math.max(1, RAISE_TICKS);
		int hold = Math.max(raise, maxHoldTicks);
		float g = Mth.clamp(useTicks / (float) raise, 0.0F, 1.0F);
		float h = Mth.clamp(lerp(g, 0.0F, 0.5F), 0.0F, 1.0F);
		float m = Mth.clamp(lerp(g, 0.5F, 0.8F), 0.0F, 1.0F);
		float n = Mth.clamp(lerp(g, 0.8F, 1.0F), 0.0F, 1.0F);
		float sway = Mth.sin(useTicks * 19.0F * (float) (Math.PI / 180.0));
		float swayFast = Mth.sin(useTicks * 30.0F * (float) (Math.PI / 180.0));
		float intensity = Mth.clamp((1.0F - useTicks / (float) hold) * 20.0F, 0.0F, 1.0F) * 2.9F;
		return new HoldUp(g, h, m, n, 0.0F, 0.0F, 0.0F, intensity, sway * intensity, swayFast * intensity);
	}

	/** 戳刺曲线的三个分量 */
	public record Stab(float g, float h, float j) {
	}

	/** 第三人称戳刺围绕手腕枢轴的最大旋转角度 */
	public static final float THIRD_PERSON_ROTATION_DEGREES = 70.0F;
	/** 第三人称戳刺沿旋转后局部轴的最大推进距离 */
	public static final float THIRD_PERSON_ADVANCE = 0.125F;
	/** 第三人称常态持矛在实体空间中上移 2 像素；实体渲染 Y 已翻转，因此使用负值 */
	public static final float THIRD_PERSON_HOLD_Y_ADJUSTMENT = -0.125F;

	/**
	 * 戳刺曲线：{@code progress} 是原版 {@code getAttackAnim} 的 0..1。
	 */
	public static Stab stab(float progress) {
		float p = Mth.clamp(progress, 0.0F, 1.0F);
		float g = -(Mth.cos((float) Math.PI * Mth.clamp(lerp(p, 0.0F, 0.05F), 0.0F, 1.0F)) - 1.0F) / 2.0F;
		// 起点 cos(0)=1 会算出 -0.0，归一成 +0.0
		if (g == 0.0F) {
			g = 0.0F;
		}
		float h = Mth.clamp(lerp(p, 0.05F, 0.2F), 0.0F, 1.0F);
		h *= h;
		float j = Mth.clamp(lerp(p, 0.4F, 1.0F), 0.0F, 1.0F);
		if (j < 0.5F) {
			j = j == 0.0F ? 0.0F : (float) (Math.pow(2.0, 20.0 * j - 10.0) / 2.0);
		} else {
			j = j == 1.0F ? 1.0F : (float) ((2.0 - Math.pow(2.0, -20.0 * j + 10.0)) / 2.0);
		}
		return new Stab(g, h, j);
	}

	/** 第三人称戳刺的挥动权重；正值向前，负值回收。 */
	public static float thirdPersonSwing(float progress) {
		Stab stab = stab(progress);
		return stab.h() - stab.j();
	}

	/** 第三人称戳刺围绕手腕枢轴的旋转角度。 */
	public static float thirdPersonRotationDegrees(float progress) {
		return THIRD_PERSON_ROTATION_DEGREES * thirdPersonSwing(progress);
	}

	/** 第三人称戳刺沿旋转后局部 Y 轴的推进距离。 */
	public static float thirdPersonAdvance(float progress) {
		return THIRD_PERSON_ADVANCE * thirdPersonSwing(progress);
	}

	private static float lerp(float value, float start, float end) {
		return (value - start) / (end - start);
	}

}
