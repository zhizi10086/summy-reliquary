package com.summy.reliquary.item;

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

/**
 * 白饭（Freeloader's Rice）：只能装进「胃袋」栏位。
 *
 * <p>佩戴期间的实际效果由 {@code com.summy.reliquary.effect.RiceHungerLock} 处理。
 */
public class FreeloadersRiceItem extends Item implements ICurioItem {
	public FreeloadersRiceItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.STOMACH);
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
		// 栏位行交给 Curios 自带文本，这里只输出风味文本与 Shift 提示
		ReliquaryTooltips.append(tooltip,
				"item.summy-reliquary.freeloaders_rice.tagline",
				"item.summy-reliquary.freeloaders_rice.desc");
	}
}
