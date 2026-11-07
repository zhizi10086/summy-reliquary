package com.summy.reliquary.item;

import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.slot.ReliquarySlots;
import com.summy.reliquary.text.ReliquaryFaction;
import com.summy.reliquary.text.SinTexts;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurio;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import java.util.List;

/**
 * 慈善捆绑包：Curios 自带护符栏饰品。
 *
 * <p>实际结算由 {@code HumbleingBundleLootModifier} 与服务端事件共同完成；物品本身只负责
 * 栏位校验、提示和死亡保留。
 */
public class HumbleingBundleItem extends Item implements ICurioItem {
	public HumbleingBundleItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.CHARM);
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
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
			TooltipFlag flag) {
		tooltip.add(Component.translatable("item.summy-reliquary.humbleing_bundle.desc.1")
				.withStyle(ChatFormatting.GRAY));
		if (!ReliquaryTooltips.shiftDown()) {
			tooltip.add(ReliquaryTooltips.shiftHint());
			return;
		}

		ReliquaryTooltips.statLine(tooltip, ReliquaryFaction.NEUTRAL,
				"item.summy-reliquary.humbleing_bundle.shift.1",
				ReliquaryConfig.humbleingBundleExtraLootChancePercent());
		ReliquaryTooltips.statLine(tooltip, ReliquaryFaction.NEUTRAL,
				"item.summy-reliquary.humbleing_bundle.shift.2",
				ReliquaryConfig.humbleingBundleExtraLootChancePercent());
		ReliquaryTooltips.narrativeLine(tooltip,
				"item.summy-reliquary.humbleing_bundle.shift.3");
		ReliquaryTooltips.statLine(tooltip, ReliquaryFaction.NEUTRAL,
				"item.summy-reliquary.humbleing_bundle.shift.4",
				ReliquaryConfig.humbleingBundleNaturalLootChancePercent());
	}

	@Override
	public Component getName(ItemStack stack) {
		return SinTexts.factionName("item.summy-reliquary.humbleing_bundle",
				ReliquaryFaction.NEUTRAL);
	}

	@Override
	public ICurio.DropRule getDropRule(SlotContext slotContext,
			net.minecraft.world.damagesource.DamageSource source, int lootingLevel,
			boolean recentlyHit, ItemStack stack) {
		return CurioItemSupport.keepOnDeath();
	}
}
