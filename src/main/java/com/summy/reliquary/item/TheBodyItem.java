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
 * 肉体：灵台饰品。佩戴时最大生命 +10，三件套齐时改为 +20（数值由 AttributeManager 每秒重算）。
 */
public class TheBodyItem extends Item implements ICurioItem {
	public TheBodyItem(Properties properties) {
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
		tooltip.add(Component.translatable("item.summy-reliquary.the_body.tagline")
				.withStyle(net.minecraft.ChatFormatting.GRAY));
		// 没有天使标记：只能看到风味行 + 一行提示，功能描述不可查阅
		if (ReliquaryTooltips.angelLocked()) {
			ReliquaryTooltips.appendAngelHint(tooltip);
			return;
		}
		SpiritAltarSetTooltips.append(tooltip, "item.summy-reliquary.the_body.set_desc",
				com.summy.reliquary.config.ReliquaryConfig.bodySetDamageReductionPercent());
		// 1.6.3：Shift 行改成「属性名|数值」两段配色（天使线 = 淡金 + 亮金）
		if (ReliquaryTooltips.shiftDown()) {
			ReliquaryTooltips.statLine(tooltip, com.summy.reliquary.text.ReliquaryFaction.ANGEL,
					"item.summy-reliquary.the_body.desc",
					com.summy.reliquary.config.ReliquaryConfig.bodyHealth());
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}

	/** 1.6.3：物品名按派系上色（天使线 = 白→金对称渐变） */
	@Override
	public Component getName(ItemStack stack) {
		return com.summy.reliquary.text.SinTexts.factionName("item.summy-reliquary.the_body",
				com.summy.reliquary.text.ReliquaryFaction.ANGEL);
	}
}
