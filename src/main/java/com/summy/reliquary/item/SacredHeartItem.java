package com.summy.reliquary.item;

import com.summy.reliquary.slot.ReliquarySlots;
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
 * 圣心（Sacred Heart）：装入「加护」栏位（1.5.5）。
 *
 * <p>门槛与其它加护饰品一致：需要**天使标记**才能佩戴与查阅。
 * 效果：大批属性提升（生命/护甲/韧性/攻速/移速/挖掘，见配置 {@code sacred_heart}）+
 * 与神性共用同一个「全伤害最终倍率」乘区（默认 +30%，相加）+ 箭矢追踪
 * （追踪逻辑在 {@code effect/SacredHeart}）。
 */
public class SacredHeartItem extends Item implements ICurioItem {
	/** 淡金（经文用色） */
	private static final int PALE_GOLD = 0xFFE4B5;

	public SacredHeartItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.BLESSING, true);
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
		tooltip.add(Component.translatable("item.summy-reliquary.sacred_heart.desc.1")
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(PALE_GOLD)).withItalic(true)));
		tooltip.add(Component.translatable("item.summy-reliquary.sacred_heart.desc.2")
				.withStyle(ChatFormatting.GRAY));

		if (ReliquaryTooltips.angelLocked()) {
			ReliquaryTooltips.appendAngelHint(tooltip);
			return;
		}

		if (ReliquaryTooltips.shiftDown()) {
			tooltip.addAll(shiftLines());
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}

	/** Shift 四行（按需求原文，不列具体数值）：末两行是「·追踪能力」与「你不再恐惧深渊」（1.6.5 新增） */
	public static java.util.List<Component> shiftLines() {
		return java.util.List.of(
				Component.translatable("item.summy-reliquary.sacred_heart.shift.1")
						.withStyle(ChatFormatting.GRAY),
				Component.translatable("item.summy-reliquary.sacred_heart.shift.2")
						.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(PALE_GOLD))),
				Component.translatable("item.summy-reliquary.sacred_heart.shift.3")
						.withStyle(ChatFormatting.GRAY),
				Component.translatable("item.summy-reliquary.sacred_heart.shift.4")
						.withStyle(ChatFormatting.GRAY));
	}

	/** 1.6.3：物品名按派系上色（天使线 = 白→金对称渐变） */
	@Override
	public Component getName(ItemStack stack) {
		return com.summy.reliquary.text.SinTexts.factionName("item.summy-reliquary.sacred_heart",
				com.summy.reliquary.text.ReliquaryFaction.ANGEL);
	}
}
