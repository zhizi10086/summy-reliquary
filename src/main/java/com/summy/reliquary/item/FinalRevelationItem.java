package com.summy.reliquary.item;

import com.google.common.collect.Multimap;
import com.summy.reliquary.effect.AttributeManager;
import com.summy.reliquary.effect.SoulShield;
import com.summy.reliquary.slot.ReliquarySlots;
import com.summy.reliquary.text.SinTexts;
import net.minecraft.ChatFormatting;
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
 * 终末天启：启示之座饰品，由「伯列恒之星」在夜晚于揭示坐标露天保持 5 秒转化而来。
 *
 * <p>效果：魂心 +2（并入黄血体系）、创造飞行（速度减半）、继承伯列恒之星属性（攻击速度 +20%、
 * 造成伤害 +20%）；长按 V 蓄力 2.5 秒后射出启示之光（光柱）。
 * 属性统一由 {@code AttributeManager} 管理，黄血由 {@code SoulShield} 维护。
 */
public class FinalRevelationItem extends Item implements ICurioItem {
	/** 风味文字的金色底色 */
	private static final int TAGLINE_GOLD = 0xFFD700;
	/** 风味文字的扫光高光色 */
	private static final int TAGLINE_HIGHLIGHT = 0xFFF3B0;

	public FinalRevelationItem(Properties properties) {
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

	/** 属性修正由 AttributeManager 统一管理（顺带避免 Curios 在提示里加属性块） */
	@Override
	public Multimap<Attribute, AttributeModifier> getAttributeModifiers(SlotContext slotContext, UUID uuid,
			ItemStack stack) {
		return CurioItemSupport.noAttributes();
	}

	/** 装上：立刻重算属性并补满魂心黄血 */
	@Override
	public void onEquip(SlotContext slotContext, ItemStack prevStack, ItemStack stack) {
		if (slotContext.entity() != null && !slotContext.entity().level().isClientSide()) {
			AttributeManager.apply(slotContext.entity());
			SoulShield.onEquipped(slotContext.entity());
		}
	}

	/** 卸下：立刻移除属性并收回我方那份黄血 */
	@Override
	public void onUnequip(SlotContext slotContext, ItemStack newStack, ItemStack stack) {
		if (slotContext.entity() != null && !slotContext.entity().level().isClientSide()) {
			AttributeManager.apply(slotContext.entity());
			SoulShield.onUnequipped(slotContext.entity());
		}
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		// 风味：金色底 + 滚动金光
		tooltip.add(SinTexts.scrolling("item.summy-reliquary.final_revelation.tagline",
				TAGLINE_GOLD, TAGLINE_HIGHLIGHT, false));

		// 没有天使标记：只能看到风味行 + 一行提示，功能描述不可查阅
		if (ReliquaryTooltips.angelLocked()) {
			ReliquaryTooltips.appendAngelHint(tooltip);
			return;
		}
		if (ReliquaryTooltips.shiftDown()) {
			// 1.6.3：数值行按「属性名 + 数值」两段配色；说明行单独一行保持灰色
			ReliquaryTooltips.statLine(tooltip, com.summy.reliquary.text.ReliquaryFaction.ANGEL,
					"item.summy-reliquary.final_revelation.desc.1",
					(int) com.summy.reliquary.config.ReliquaryConfig.finalSoulHearts());
			ReliquaryTooltips.narrativeLine(tooltip, "item.summy-reliquary.final_revelation.desc.1.detail");
			ReliquaryTooltips.narrativeLine(tooltip, "item.summy-reliquary.final_revelation.desc.2");
			ReliquaryTooltips.statLine(tooltip, com.summy.reliquary.text.ReliquaryFaction.ANGEL,
					"item.summy-reliquary.final_revelation.desc.2.speed",
					com.summy.reliquary.config.ReliquaryConfig.starAttackSpeedPercent());
			ReliquaryTooltips.statLine(tooltip, com.summy.reliquary.text.ReliquaryFaction.ANGEL,
					"item.summy-reliquary.final_revelation.desc.2.damage",
					com.summy.reliquary.config.ReliquaryConfig.starDamagePercent());
			tooltip.add(Component.translatable("item.summy-reliquary.final_revelation.desc.3")
					.withStyle(ChatFormatting.GRAY));
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}

	/** 1.6.3：物品名按派系上色（天使线 = 白→金对称渐变） */
	@Override
	public Component getName(ItemStack stack) {
		return SinTexts.factionName("item.summy-reliquary.final_revelation",
				com.summy.reliquary.text.ReliquaryFaction.ANGEL);
	}
}
