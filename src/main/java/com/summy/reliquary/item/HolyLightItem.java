package com.summy.reliquary.item;

import com.summy.reliquary.config.ReliquaryConfig;
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
 * 圣光（Holy Light）：装入「加护」栏位（1.5.4）。
 *
 * <p>门槛与救恩一致：必须已经拿到**天使标记**才能佩戴与查阅。
 * 效果：佩戴者**命中时**（近战或箭矢/投掷物）有几率召唤圣光，造成以"各类倍率后、减伤前"的
 * 本次伤害为基准的圣光伤害，并在目标身上打下一道电火花光柱（结算逻辑在 {@code effect/HolyLightEffect}）。
 */
public class HolyLightItem extends Item implements ICurioItem {
	/** 淡金（经文用色） */
	private static final int PALE_GOLD = 0xFFE4B5;

	public HolyLightItem(Properties properties) {
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
		// 描述：淡金斜体经文 + 灰色出处（原版提示框无法居中，两行都左对齐）
		tooltip.add(Component.translatable("item.summy-reliquary.holy_light.desc.1")
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(PALE_GOLD)).withItalic(true)));
		tooltip.add(Component.translatable("item.summy-reliquary.holy_light.desc.2")
				.withStyle(ChatFormatting.GRAY));

		// 没有天使标记：不展开功能描述
		if (ReliquaryTooltips.angelLocked()) {
			ReliquaryTooltips.appendAngelHint(tooltip);
			return;
		}

		if (ReliquaryTooltips.shiftDown()) {
			// 1.6.3：属性名 + 数值两段配色（天使线）；1.8.2：数值直接写强化后的 25%
			ReliquaryTooltips.statLine(tooltip, com.summy.reliquary.text.ReliquaryFaction.ANGEL,
					"item.summy-reliquary.holy_light.desc");
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}

	/** 1.6.3：物品名按派系上色（天使线 = 白→金对称渐变） */
	@Override
	public Component getName(ItemStack stack) {
		return com.summy.reliquary.text.SinTexts.factionName("item.summy-reliquary.holy_light",
				com.summy.reliquary.text.ReliquaryFaction.ANGEL);
	}
}
