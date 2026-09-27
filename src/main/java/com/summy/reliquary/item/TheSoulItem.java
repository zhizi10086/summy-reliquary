package com.summy.reliquary.item;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.summy.reliquary.effect.AttributeManager;
import com.summy.reliquary.effect.SoulShield;
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
 * 灵魂：灵台饰品。提供 +3 魂心（每点 2 点吸收，每 30 秒补满，见 {@code SoulShield}）；
 * 三件套齐时 15% 几率免疫投射物伤害。
 */
public class TheSoulItem extends Item implements ICurioItem {
	public TheSoulItem(Properties properties) {
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

	/** 魂心由 AttributeManager 统一管理，这里不返回属性修正（顺带避免 Curios 在提示里加属性块） */
	@Override
	public Multimap<Attribute, AttributeModifier> getAttributeModifiers(SlotContext slotContext, UUID uuid,
			ItemStack stack) {
		return CurioItemSupport.noAttributes();
	}

	/** 刚装上时立刻生效：重算属性并补满黄血，不用等配置的间隔 */
	@Override
	public void onEquip(SlotContext slotContext, ItemStack prevStack, ItemStack stack) {
		if (slotContext.entity() != null && !slotContext.entity().level().isClientSide()) {
			AttributeManager.apply(slotContext.entity());
			SoulShield.onEquipped(slotContext.entity());
		}
	}

	/** 卸下时立刻移除属性并收回我们那部分黄血（下一个 tick 也会兜底） */
	@Override
	public void onUnequip(SlotContext slotContext, ItemStack newStack, ItemStack stack) {
		if (slotContext.entity() != null && !slotContext.entity().level().isClientSide()) {
			AttributeManager.apply(slotContext.entity());
			SoulShield.onUnequipped(slotContext.entity());
		}
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.translatable("item.summy-reliquary.the_soul.tagline")
				.withStyle(net.minecraft.ChatFormatting.GRAY));
		// 没有天使标记：只能看到风味行 + 一行提示，功能描述不可查阅
		if (ReliquaryTooltips.angelLocked()) {
			ReliquaryTooltips.appendAngelHint(tooltip);
			return;
		}
		// 1.6.3：套装行按「属性名|数值」两段配色，触发后的说明另起一行
		SpiritAltarSetTooltips.appendWithDetail(tooltip, "item.summy-reliquary.the_soul.set_desc",
				"item.summy-reliquary.the_soul.set_desc.detail",
				com.summy.reliquary.config.ReliquaryConfig.deathImmunityPercent());
		// 1.6.3：属性名 + 数值两段配色（天使线）；1.6.5：删去"每 1 魂心提供……"那行说明
		if (ReliquaryTooltips.shiftDown()) {
			ReliquaryTooltips.statLine(tooltip, com.summy.reliquary.text.ReliquaryFaction.ANGEL,
					"item.summy-reliquary.the_soul.desc",
					(int) com.summy.reliquary.config.ReliquaryConfig.soulHearts());
		} else {
			tooltip.add(ReliquaryTooltips.shiftHint());
		}
	}

	/** 1.6.3：物品名按派系上色（天使线 = 白→金对称渐变） */
	@Override
	public Component getName(ItemStack stack) {
		return com.summy.reliquary.text.SinTexts.factionName("item.summy-reliquary.the_soul",
				com.summy.reliquary.text.ReliquaryFaction.ANGEL);
	}
}
