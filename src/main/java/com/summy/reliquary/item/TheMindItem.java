package com.summy.reliquary.item;

import com.google.common.collect.Multimap;
import com.summy.reliquary.slot.ReliquarySlots;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import java.util.List;
import java.util.UUID;

/**
 * 思想：灵台饰品。让半径 15 格内的敌对生物与玩家发光；三件套齐时对发光目标额外 +10% 伤害。
 * 发光与加成的逻辑见 {@code SpiritAltarSet}。
 */
public class TheMindItem extends Item implements ICurioItem {
	public TheMindItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.SPIRIT_ALTAR, true);
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
	public Multimap<Attribute, AttributeModifier> getAttributeModifiers(SlotContext slotContext, UUID uuid,
			ItemStack stack) {
		return CurioItemSupport.noAttributes();
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.translatable("item.summy-reliquary.the_mind.tagline")
				.withStyle(net.minecraft.ChatFormatting.GRAY));
		// 没有天使标记：只能看到风味行 + 一行提示，功能描述不可查阅
		if (ReliquaryTooltips.angelLocked()) {
			ReliquaryTooltips.appendAngelHint(tooltip);
			return;
		}		SpiritAltarSetTooltips.append(tooltip, "item.summy-reliquary.the_mind.set_desc",
				com.summy.reliquary.config.ReliquaryConfig.mindBonusPercent());
		// 1.6.3：按「属性名|数值」两段配色（天使线 = 淡金 + 亮金）
		if (ReliquaryTooltips.shiftDown()) {
			ReliquaryTooltips.statLine(tooltip, com.summy.reliquary.text.ReliquaryFaction.ANGEL,
					"item.summy-reliquary.the_mind.desc",
					com.summy.reliquary.config.ReliquaryConfig.glowRadius());
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}

	/** 1.6.3：物品名按派系上色（天使线 = 白→金对称渐变） */
	@Override
	public Component getName(ItemStack stack) {
		return com.summy.reliquary.text.SinTexts.factionName("item.summy-reliquary.the_mind",
				com.summy.reliquary.text.ReliquaryFaction.ANGEL);
	}
}
