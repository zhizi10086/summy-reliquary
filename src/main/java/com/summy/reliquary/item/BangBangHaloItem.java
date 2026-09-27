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
 * 邦邦咔邦光环：只能装进「光环」栏位（Curios）。
 *
 * <p>它能不能放进某个栏位由两道校验共同保证：
 * {@link ReliquarySlots} 注册的 {@code summy-reliquary:halo_only} 谓词（数据驱动栏位用）
 * 与这里覆写的 {@link #canEquip(SlotContext, ItemStack)}。
 */
public class BangBangHaloItem extends Item implements ICurioItem {
	public BangBangHaloItem(Properties properties) {
		super(properties);
	}

	@Override
	public boolean canEquip(SlotContext slotContext, ItemStack stack) {
		return CurioItemSupport.canEquipInto(slotContext, stack, ReliquarySlots.HALO);
	}

	/** 关掉 Curios 自动生成的栏位行，改由本模组自己的「栏位：光环」带色文本显示 */
	@Override
	public List<Component> getSlotsTooltip(List<Component> tooltips, ItemStack stack) {
		return CurioItemSupport.keepLines(tooltips);
	}

	/** 关掉 Curios 自动生成的属性数值行（属性效果由提示文本概括） */
	@Override
	public List<Component> getAttributesTooltip(List<Component> tooltips, ItemStack stack) {
		return CurioItemSupport.keepLines(tooltips);
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		// 栏位行交给 Curios 自带文本，这里只输出风味文本与 Shift 提示
		ReliquaryTooltips.append(tooltip,
				"item.summy-reliquary.bangbang_halo.tagline",
				"item.summy-reliquary.bangbang_halo.desc");
	}
}
