package com.summy.reliquary.item;

import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.effect.Betrayal;
import com.summy.reliquary.effect.PlayerFlags;
import com.summy.reliquary.slot.ReliquarySlots;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

import java.util.List;

/** 背叛（Betrayal）：恶魔线加护饰品，需当前持有恶魔标记，不接邪恶度门槛。 */
public class BetrayalItem extends Item implements ICurioItem {
	public BetrayalItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.BLESSING, false,
				entity -> PlayerFlags.isDemon(entity), "缺少恶魔标记");
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
	public void onUnequip(SlotContext slotContext, ItemStack newStack, ItemStack stack) {
		if (slotContext.entity() instanceof net.minecraft.server.level.ServerPlayer player) {
			Betrayal.endFor(player);
		}
	}

	@Override
	public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		ReliquaryTooltips.demonFlavorLine(tooltip, "item.summy-reliquary.betrayal.tagline.1");
		ReliquaryTooltips.demonFlavorLine(tooltip, "item.summy-reliquary.betrayal.tagline.2");
		Object local = ReliquaryTooltips.localPlayer();
		if (local instanceof net.minecraft.world.entity.LivingEntity entity && !PlayerFlags.isDemon(entity)) {
			ReliquaryTooltips.appendDemonHint(tooltip);
			return;
		}
		if (!ReliquaryTooltips.shiftDown()) {
			tooltip.add(ReliquaryTooltips.shiftHint());
			return;
		}
		ReliquaryTooltips.statLine(tooltip, com.summy.reliquary.text.ReliquaryFaction.DEMON,
				"item.summy-reliquary.betrayal.shift.1",
				ReliquaryTooltips.number(ReliquaryConfig.betrayalChancePerTick()),
				ReliquaryConfig.betrayalRadius());
		ReliquaryTooltips.statLine(tooltip, com.summy.reliquary.text.ReliquaryFaction.DEMON,
				"item.summy-reliquary.betrayal.shift.2",
				ReliquaryConfig.betrayalTauntSeconds(),
				ReliquaryConfig.betrayalCooldownSeconds());
	}

	@Override
	public Component getName(ItemStack stack) {
		return com.summy.reliquary.text.SinTexts.factionName("item.summy-reliquary.betrayal",
				com.summy.reliquary.text.ReliquaryFaction.DEMON);
	}

	@Override
	public top.theillusivec4.curios.api.type.capability.ICurio.DropRule getDropRule(
			SlotContext slotContext, net.minecraft.world.damagesource.DamageSource source,
			int lootingLevel, boolean recentlyHit, ItemStack stack) {
		return CurioItemSupport.keepOnDeath();
	}
}
