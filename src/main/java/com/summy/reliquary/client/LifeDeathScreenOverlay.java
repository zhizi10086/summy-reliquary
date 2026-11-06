package com.summy.reliquary.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * 死形态的屏幕边缘暗红泛光。
 *
 * <p>只使用 GUI 半透明色块，不改世界亮度、不使用 Shader。倒计时进入最后 5 秒且
 * 尚未达到复活击杀目标时，以 18 tick 为一个周期轻微呼吸；目标达成后恢复为静态红光。
 */
public final class LifeDeathScreenOverlay implements IGuiOverlay {
	/** 静态外侧不透明度：40 / 255 ≈ 15.7%。 */
	private static final int BASE_ALPHA = 40;
	/** 警告脉冲最多额外增加的 12 / 255 ≈ 4.7 个百分点。 */
	private static final int PULSE_ALPHA = 12;
	/** 最后 5 秒开始警告。 */
	private static final int WARNING_TICKS = 100;
	/** 18 tick ≈ 0.9 秒一次完整呼吸。 */
	private static final float PULSE_PERIOD_TICKS = 18.0F;
	private static final int EDGE_RGB = 0x7A1010;
	private static final int SIDE_BANDS = 6;

	@Override
	public void render(net.minecraftforge.client.gui.overlay.ForgeGui gui, GuiGraphics graphics,
			float partialTick, int width, int height) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.options.hideGui || client.screen != null
				|| client.player.isSpectator() || !ReliquaryClientState.isDeathFormVisualActive()) {
			return;
		}
		int alpha = edgeAlpha(true,
				ReliquaryClientState.lifeDeathRemainingTicks(),
				ReliquaryClientState.lifeDeathKillWeight(),
				ReliquaryClientState.lifeDeathKillRequired(),
				client.player.tickCount + partialTick);
		if (alpha <= 0 || width <= 0 || height <= 0) {
			return;
		}

		int edge = Math.max(18, Math.min(64, Math.min(width, height) / 10));
		// 顶部横向完全不描；左右侧边从屏幕顶端开始，保证侧边连续到四角。
		int sideTop = 0;
		graphics.fillGradient(0, height - edge, width, height, argb(0), argb(alpha));
		for (int band = 0; band < SIDE_BANDS; band++) {
			int x0 = band * edge / SIDE_BANDS;
			int x1 = (band + 1) * edge / SIDE_BANDS;
			int bandAlpha = Math.round(alpha * (SIDE_BANDS - band) / (float) SIDE_BANDS);
			if (x1 > x0 && bandAlpha > 0) {
				graphics.fill(0, sideTop, x1, height, argb(bandAlpha));
				graphics.fill(width - x1, sideTop, width - x0, height, argb(bandAlpha));
			}
		}
	}

	/** 自检用：左右侧边从屏幕最顶端开始，顶部横向仍由 render 保持不绘制。 */
	public static int sideBandStart() {
		return 0;
	}

	/**
	 * 计算外侧边缘不透明度。抽成纯函数供自检覆盖静态、达标和警告三个分支。
	 *
	 * @param animationTick 连续动画时间（玩家 tick + partialTick）
	 */
	public static int edgeAlpha(boolean deathForm, int remainingTicks, int killWeight,
			int killRequired, float animationTick) {
		if (!deathForm || remainingTicks <= 0) {
			return 0;
		}
		float alpha = BASE_ALPHA;
		boolean warning = remainingTicks <= WARNING_TICKS
				&& killRequired > 0 && killWeight < killRequired;
		if (warning) {
			float phase = animationTick * (Mth.PI * 2.0F / PULSE_PERIOD_TICKS);
			float pulse = (Mth.sin(phase) + 1.0F) * 0.5F;
			alpha += PULSE_ALPHA * pulse;
		}
		return Math.round(alpha);
	}

	private static int argb(int alpha) {
		return (Math.max(0, Math.min(255, alpha)) << 24) | EDGE_RGB;
	}
}
