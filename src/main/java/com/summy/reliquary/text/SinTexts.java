package com.summy.reliquary.text;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * 提示文本特效工具：静态渐变、滚动金光、呼吸渐变与短暂闪烁。
 *
 * <p>提示每帧都会重新构建，所以这里基于 {@link System#currentTimeMillis()} 的逐字上色会自然形成动画，
 * 不需要改动任何渲染代码。
 */
public final class SinTexts {
	/** 滚动金光的高光色 */
	public static final int GOLD = 0xFFD700;
	/** 七罪之源名字渐变：深红 → 深紫 */
	public static final int SIN_FROM = 0x8B0000;
	public static final int SIN_TO = 0x4A0080;
	/** 七罪祷文：褪色暗红为底色，短暂闪烁亮红 */
	public static final int PRAYER_BASE = 0x5A0000;
	public static final int PRAYER_FLASH = 0xC03030;

	/** 金光高光带的半宽（字符数） */
	private static final double SWEEP_HALF_WIDTH = 4.0D;
	/** 高光带每秒滚动的字符数 */
	private static final double SWEEP_CHARS_PER_SECOND = 14.0D;
	/** 闪烁周期与单次闪烁时长（毫秒） */
	private static final long FLASH_PERIOD_MS = 1800L;
	private static final long FLASH_DURATION_MS = 260L;

	private SinTexts() {
	}

	/** 按字符位置做静态渐变（用于物品名字） */
	public static MutableComponent gradient(String translationKey, int fromColor, int toColor) {
		String text = Component.translatable(translationKey).getString();
		MutableComponent result = Component.empty();
		int span = Math.max(1, text.length() - 1);

		for (int index = 0; index < text.length(); index++) {
			result.append(charText(text.charAt(index), lerp(fromColor, toColor, (float) index / span), false));
		}
		return result;
	}

	/** 在指定底色上让一道金色高光沿文字滚动 */
	public static MutableComponent scrollingGold(String translationKey, int baseColor, boolean italic) {
		return scrolling(translationKey, baseColor, GOLD, italic);
	}

	/** 在指定底色上让一道高光沿文字滚动（高光色可自定义） */
	public static MutableComponent scrolling(String translationKey, int baseColor, int highlightColor, boolean italic) {
		return scrolling(translationKey, baseColor, highlightColor, italic, SWEEP_CHARS_PER_SECOND);
	}

	/**
	 * 同上，但可以自定义流光速度（字符/秒）。
	 *
	 * <p>1.5.5 起新增：神性的描述要求「慢速流光」，用 {@code charsPerSecond = 6}；其余调用点仍用默认速度。
	 */
	public static MutableComponent scrolling(String translationKey, int baseColor, int highlightColor, boolean italic,
			double charsPerSecond) {
		String text = Component.translatable(translationKey).getString();
		double period = text.length() + SWEEP_HALF_WIDTH * 4.0D;
		double sweep = System.currentTimeMillis() / 1000.0D * charsPerSecond % period - SWEEP_HALF_WIDTH * 2.0D;

		MutableComponent result = Component.empty();
		for (int index = 0; index < text.length(); index++) {
			double distance = Math.abs(index - sweep);
			float strength = (float) Math.max(0.0D, 1.0D - distance / SWEEP_HALF_WIDTH);
			result.append(charText(text.charAt(index), lerp(baseColor, highlightColor, strength), italic));
		}
		return result;
	}

	/** 对称渐变：字符串中心用 centerColor，越往两端越接近 edgeColor */
	public static MutableComponent symmetricGradient(String translationKey, int centerColor, int edgeColor) {
		String text = Component.translatable(translationKey).getString();
		MutableComponent result = Component.empty();
		double middle = (text.length() - 1) / 2.0D;

		for (int index = 0; index < text.length(); index++) {
			float ratio = middle <= 0.0D ? 0.0F : (float) (Math.abs(index - middle) / middle);
			result.append(charText(text.charAt(index), lerp(centerColor, edgeColor, ratio), false));
		}
		return result;
	}

	/**
	 * 按「派系」给物品名上色（1.6.3）：
	 *
	 * <ul>
	 *     <li>天使线：白→金对称渐变（名字很短时退化成白底金扫光，与「美德」同款）；</li>
	 *     <li>恶魔线：暗红底 + 亮红扫光（与「七罪之源」同款）；</li>
	 *     <li>中立 / 材料：保持原版白（直接返回普通组件）。</li>
	 * </ul>
	 */
	public static net.minecraft.network.chat.Component factionName(String translationKey,
			com.summy.reliquary.text.ReliquaryFaction faction) {
		return switch (faction) {
			case ANGEL -> Component.translatable(translationKey).getString().length() >= 3
					? symmetricGradient(translationKey, 0xFFFFFF, GOLD)
					: scrolling(translationKey, 0xFFFFFF, GOLD, false);
			case DEMON -> scrolling(translationKey, SIN_FROM, PRAYER_FLASH, false);
			case NEUTRAL -> Component.translatable(translationKey);
		};
	}

	/** 呼吸式渐变：在 colorA 与 colorB 之间按正弦来回过渡 */
	public static int breathing(int colorA, int colorB, long periodMs) {
		double phase = (System.currentTimeMillis() % periodMs) / (double) periodMs;
		float strength = (float) ((1.0D - Math.cos(phase * Math.PI * 2.0D)) / 2.0D);
		return lerp(colorA, colorB, strength);
	}

	/** 单色文本（整行同色） */
	public static MutableComponent colored(String translationKey, int color) {
		return Component.translatable(translationKey).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)));
	}

	/** 单色文本，可附加斜体 / 删除线（用于「已赎罪」状态） */
	public static MutableComponent styled(String translationKey, int color, boolean italic, boolean strikethrough) {
		return Component.translatable(translationKey).setStyle(Style.EMPTY
				.withColor(TextColor.fromRgb(color))
				.withItalic(italic)
				.withStrikethrough(strikethrough));
	}

	/** 周期性短暂闪烁：大部分时间是 baseColor，每个周期的前一小段时间变成 flashColor */
	public static int flashing(int baseColor, int flashColor) {
		return System.currentTimeMillis() % FLASH_PERIOD_MS < FLASH_DURATION_MS ? flashColor : baseColor;
	}

	/** 两色线性插值 */
	public static int lerp(int fromColor, int toColor, float progress) {
		float t = Math.max(0.0F, Math.min(1.0F, progress));
		int fromR = (fromColor >> 16) & 0xFF;
		int fromG = (fromColor >> 8) & 0xFF;
		int fromB = fromColor & 0xFF;
		int toR = (toColor >> 16) & 0xFF;
		int toG = (toColor >> 8) & 0xFF;
		int toB = toColor & 0xFF;

		int r = Math.round(fromR + (toR - fromR) * t);
		int g = Math.round(fromG + (toG - fromG) * t);
		int b = Math.round(fromB + (toB - fromB) * t);
		return (r << 16) | (g << 8) | b;
	}

	private static MutableComponent charText(char character, int color, boolean italic) {
		return Component.literal(String.valueOf(character))
				.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)).withItalic(italic));
	}
}
