package com.summy.reliquary.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 客户端专用的提示辅助。
 *
 * <p>为什么要单独放一个类：专用服务端一旦在任何地方加载到引用 {@code Minecraft#player}
 * （类型是 {@code LocalPlayer}）或 {@code Screen} 的类，Forge 的 dist 校验会直接判为非法，
 * 导致整个模组加载失败。因此通用代码一律通过
 * {@code DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ……)} 调用这里，服务器端根本不会加载本类。
 */
public final class TooltipClientHooks {
	private TooltipClientHooks() {
	}

	/** 本地玩家（没进世界时为 null） */
	public static LivingEntity localPlayer() {
		return Minecraft.getInstance().player;
	}

	/** 是否按住 Shift */
	public static boolean isShiftDown() {
		return Screen.hasShiftDown();
	}

	/** 是否按住 Alt */
	public static boolean isAltDown() {
		return Screen.hasAltDown();
	}

	/**
	 * 把一行提示按提示框宽度切成多行。
	 *
	 * <p>原版物品提示**不会自动折行**（{@code ClientTextTooltip} 是单行渲染），长句会直接顶出屏幕，
	 * 所以这里按与原版悬停文本一致的口径 `max(窗口 GUI 宽 / 2, 200)` 手动折行。
	 * 折行时先把组件拍平成「(样式, 文本)」片段，再逐字符量宽，保证切完每一段的配色不丢。
	 */
	public static List<Component> wrap(Component line) {
		Minecraft client = Minecraft.getInstance();
		Font font = client.font;
		int maxWidth = Math.max(200, client.getWindow().getGuiScaledWidth() / 2);

		List<Style> styles = new ArrayList<>();
		List<String> texts = new ArrayList<>();
		line.visit((style, text) -> {
			styles.add(style);
			texts.add(text);
			return Optional.empty();
		}, Style.EMPTY);

		List<Component> lines = new ArrayList<>();
		MutableComponent current = Component.empty();
		int currentWidth = 0;
		for (int index = 0; index < texts.size(); index++) {
			Style style = styles.get(index);
			String text = texts.get(index);
			for (int offset = 0; offset < text.length(); ) {
				int codePoint = text.codePointAt(offset);
				offset += Character.charCount(codePoint);
				String single = new String(Character.toChars(codePoint));
				int width = font.width(single);
				if (currentWidth > 0 && currentWidth + width > maxWidth) {
					lines.add(current);
					current = Component.empty();
					currentWidth = 0;
				}
				current.append(Component.literal(single).withStyle(style));
				currentWidth += width;
			}
		}
		if (currentWidth > 0) {
			lines.add(current);
		}
		return lines.isEmpty() ? List.of(line) : lines;
	}
}
