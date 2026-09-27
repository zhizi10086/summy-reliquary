package com.summy.reliquary.item;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

import java.util.List;

/**
 * 灵台三件套的提示块：
 * <ol>
 *     <li>绿色需求行「当[肉体·思想·灵魂]同时佩戴时，激活套装效果」，三件名字按是否佩戴着色（未佩戴灰、已佩戴绿）；</li>
 *     <li>浅灰「按住 Alt 查看套装效果」，按住 Alt 时换成该物品的套装效果描述。</li>
 * </ol>
 */
public final class SpiritAltarSetTooltips {
	/** 套装文字与已佩戴名字的颜色（绿） */
	private static final int SET_GREEN = 0x55FF55;
	/** 未佩戴名字的颜色（灰） */
	private static final int MISSING_GRAY = 0x7F7F7F;
	/** Alt 行与套装描述的颜色（浅灰） */
	private static final int HINT_GRAY = 0xAAAAAA;

	private static final List<Item> SET_ITEMS = List.of(
			SummyReliquary.THE_BODY.get(),
			SummyReliquary.THE_MIND.get(),
			SummyReliquary.THE_SOUL.get());

	private static final List<String> SET_NAME_KEYS = List.of(
			"item.summy-reliquary.the_body",
			"item.summy-reliquary.the_mind",
			"item.summy-reliquary.the_soul");

	private SpiritAltarSetTooltips() {
	}

	/** 在提示里追加套装块（风味行之后、Shift 行之前调用） */
	public static void append(List<Component> tooltip, String setDescriptionKey) {
		append(tooltip, setDescriptionKey, new Object[0]);
	}

	/** 同上，但套装描述里可以带参数（例如把配置里的百分比填进去） */
	public static void append(List<Component> tooltip, String setDescriptionKey, Object... descriptionArgs) {
		append(tooltip, setDescriptionKey, (String) null, descriptionArgs);
	}

	/**
	 * 同上，但套装描述之后还可以带一行**说明行**（1.6.3：把"属性 + 数值 + 叙述"的混合行拆开，
	 * 说明行不含 {@code |}、统一浅灰）。{@code detailKey} 传 null 表示没有说明行。
	 */
	public static void appendWithDetail(List<Component> tooltip, String setDescriptionKey, String detailKey,
			Object... descriptionArgs) {
		append(tooltip, setDescriptionKey, detailKey, descriptionArgs);
	}

	private static void append(List<Component> tooltip, String setDescriptionKey, String detailKey,
			Object... descriptionArgs) {
		// 本地玩家只在客户端存在；服务端返回 null（客户端类由 DistExecutor 延迟加载，服务端不会加载）
		Player player = ReliquaryTooltips.localPlayer() instanceof Player candidate ? candidate : null;

		MutableComponent names = Component.empty();
		for (int index = 0; index < SET_ITEMS.size(); index++) {
			if (index > 0) {
				// 分隔符不留两侧空格（与中文文本清理同一口径）
			names.append(Component.literal("·").withStyle(Style.EMPTY.withColor(SET_GREEN)));
			}
			boolean worn = player != null && CurioHelper.wears(player, SET_ITEMS.get(index));
			names.append(Component.translatable(SET_NAME_KEYS.get(index))
					.withStyle(Style.EMPTY.withColor(worn ? SET_GREEN : MISSING_GRAY)));
		}

		tooltip.add(Component.translatable("item.summy-reliquary.set.requirement", names)
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(SET_GREEN))));

		// 套装效果描述：套装已激活时用绿色，未激活保持浅灰；未按 Alt 时显示浅灰提示行
		boolean altDown = ReliquaryTooltips.altDown();
		boolean active = player != null
				&& CurioHelper.wearsAll(player, SET_ITEMS.get(0), SET_ITEMS.get(1), SET_ITEMS.get(2));
		if (!altDown) {
			tooltip.add(Component.translatable("item.summy-reliquary.set.alt_hint")
					.withStyle(Style.EMPTY.withColor(HINT_GRAY)));
			return;
		}
		// 1.6.3：套装描述也按「属性名|数值」两段配色；激活时用"绿 + 亮绿"、未激活用"灰 + 白"
		String description = Component.translatable(setDescriptionKey, descriptionArgs).getString();
		int separator = description.indexOf('|');
		int nameColor = active ? SET_GREEN : HINT_GRAY;
		int valueColor = active ? 0x7CFF7C : 0xFFFFFF;
		if (separator < 0) {
			tooltip.add(Component.literal(description).withStyle(Style.EMPTY.withColor(nameColor)));
		} else {
			tooltip.add(Component.literal(description.substring(0, separator))
					.withStyle(Style.EMPTY.withColor(nameColor))
					.append(Component.literal(" " + description.substring(separator + 1))
							.withStyle(Style.EMPTY.withColor(valueColor))));
		}
		if (detailKey != null) {
			// 说明行：不含数值，统一浅灰
			tooltip.add(Component.translatable(detailKey).withStyle(Style.EMPTY.withColor(HINT_GRAY)));
		}
	}
}
