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

/** 修女服（Habit）：天使线背饰，与仪式法袍共用 Curios 的「背饰」栏。 */
public class HabitItem extends Item implements ICurioItem {
	private static final int PALE_GOLD = 0xFFE4B5;

	public HabitItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.BACK, true);
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
	public void onEquip(SlotContext slotContext, ItemStack previousStack, ItemStack stack) {
		if (slotContext.entity() instanceof net.minecraft.server.level.ServerPlayer player) {
			com.summy.reliquary.effect.AttributeManager.apply(player);
			com.summy.reliquary.effect.SoulShield.onEquipped(player);
		}
	}

	@Override
	public void onUnequip(SlotContext slotContext, ItemStack newStack, ItemStack stack) {
		if (slotContext.entity() instanceof net.minecraft.server.level.ServerPlayer player) {
			com.summy.reliquary.effect.AttributeManager.apply(player);
			com.summy.reliquary.effect.SoulShield.clampToCapacity(player);
		}
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		ReliquaryTooltips.add(tooltip, Component.translatable("item.summy-reliquary.habit.desc.1")
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(PALE_GOLD)).withItalic(true)));
		ReliquaryTooltips.add(tooltip, Component.translatable("item.summy-reliquary.habit.desc.2")
				.withStyle(ChatFormatting.GRAY));

		if (ReliquaryTooltips.angelLocked()) {
			ReliquaryTooltips.appendAngelHint(tooltip);
			return;
		}
		if (!ReliquaryTooltips.shiftDown()) {
			tooltip.add(ReliquaryTooltips.shiftHint());
			return;
		}

		ReliquaryTooltips.statLine(tooltip, com.summy.reliquary.text.ReliquaryFaction.ANGEL,
				"item.summy-reliquary.habit.shift.1", ReliquaryConfig.habitSoulHearts());
		ReliquaryTooltips.statLine(tooltip, com.summy.reliquary.text.ReliquaryFaction.ANGEL,
				"item.summy-reliquary.habit.shift.2", ReliquaryTooltips.number(ReliquaryConfig.habitArmor()));
		ReliquaryTooltips.statLine(tooltip, com.summy.reliquary.text.ReliquaryFaction.ANGEL,
				"item.summy-reliquary.habit.shift.3",
				ReliquaryTooltips.number(ReliquaryConfig.habitChargePercent()),
				ReliquaryTooltips.number(ReliquaryConfig.habitChargeMinSeconds()));
	}

	@Override
	public Component getName(ItemStack stack) {
		return com.summy.reliquary.text.SinTexts.factionName("item.summy-reliquary.habit",
				com.summy.reliquary.text.ReliquaryFaction.ANGEL);
	}

	/** 2.15 的全局规则：本模组饰品死亡不掉落。 */
	@Override
	public top.theillusivec4.curios.api.type.capability.ICurio.DropRule getDropRule(
			SlotContext slotContext, net.minecraft.world.damagesource.DamageSource source,
			int lootingLevel, boolean recentlyHit, ItemStack stack) {
		return CurioItemSupport.keepOnDeath();
	}
}
