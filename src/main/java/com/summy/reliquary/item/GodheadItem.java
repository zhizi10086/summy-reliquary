package com.summy.reliquary.item;

import com.summy.reliquary.slot.ReliquarySlots;
import com.summy.reliquary.text.SinTexts;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import java.util.List;

/**
 * 神性（Godhead）：装入「启示之座」栏位（1.5.5，与伯列恒之星/终末天启互斥）。
 *
 * <p>门槛与其它天使饰品一致：需要**天使标记**才能佩戴与查阅。
 * 效果：继承终末天启的属性与光柱（并强化到蓄力 1.5 秒 / 冷却 5 秒 / 半径 3 / 射程 35）、救恩半径 5、
 * 8 格光环真实伤害、创造飞行不减速、环境伤害免疫、死亡拦截回重生点（逻辑在 {@code effect/Godhead}）。
 */
public class GodheadItem extends Item implements ICurioItem {
	/** 淡金（经文用色） */
	private static final int PALE_GOLD = 0xFFE4B5;
	/** 流光高光 */
	private static final int HIGHLIGHT = 0xFFD700;
	/** 经文流光速度（字符/秒）：慢速 */
	private static final double SWEEP_CHARS_PER_SECOND = 6.0D;

	public GodheadItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.REVELATION, true);
	}

	@Override
	public List<Component> getSlotsTooltip(List<Component> tooltips, ItemStack stack) {
		return CurioItemSupport.keepLines(tooltips);
	}

	@Override
	public List<Component> getAttributesTooltip(List<Component> tooltips, ItemStack stack) {
		return CurioItemSupport.keepLines(tooltips);
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		// 经文：淡金斜体 + 慢速流光（其余物品仍用默认速度）
		tooltip.add(SinTexts.scrolling("item.summy-reliquary.godhead.desc.1", PALE_GOLD, HIGHLIGHT, true,
				SWEEP_CHARS_PER_SECOND));
		tooltip.add(Component.translatable("item.summy-reliquary.godhead.desc.2")
				.withStyle(ChatFormatting.GRAY));

		if (ReliquaryTooltips.angelLocked()) {
			ReliquaryTooltips.appendAngelHint(tooltip);
			return;
		}

		if (ReliquaryTooltips.shiftDown()) {
			for (Component line : functionLines()) {
				ReliquaryTooltips.add(tooltip, line);
			}
		} else if (ReliquaryTooltips.altDown()) {
			for (Component line : loreLines()) {
				ReliquaryTooltips.add(tooltip, line);
			}
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
			tooltip.add(ReliquaryTooltips.altHint());
		}
	}

	/** Shift 八行功能：按「标题|说明」两段配色（天使线） */
	public static java.util.List<Component> functionLines() {
		java.util.List<Component> lines = new java.util.ArrayList<>();
		for (int index = 1; index <= 8; index++) {
			lines.add(ReliquaryTooltips.statComponent(
					com.summy.reliquary.text.ReliquaryFaction.ANGEL,
					"item.summy-reliquary.godhead.shift." + index));
		}
		return lines;
	}

	/** Alt 五行诗句：前缀「如祂一样，」淡金斜体 + 白色正体尾巴 */
	public static java.util.List<Component> loreLines() {
		java.util.List<Component> lines = new java.util.ArrayList<>();
		for (int index = 1; index <= 5; index++) {
			lines.add(Component.empty()
					.append(Component.translatable("item.summy-reliquary.godhead.shift.prefix")
							.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(PALE_GOLD)).withItalic(true)))
					.append(Component.translatable("item.summy-reliquary.godhead.alt." + index)
							.withStyle(ChatFormatting.WHITE)));
		}
		return lines;
	}

	/** 1.6.3：物品名按派系上色（天使线 = 白→金对称渐变） */
	@Override
	public Component getName(ItemStack stack) {
		return SinTexts.factionName("item.summy-reliquary.godhead",
				com.summy.reliquary.text.ReliquaryFaction.ANGEL);
	}
}
