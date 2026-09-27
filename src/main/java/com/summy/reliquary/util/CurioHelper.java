package com.summy.reliquary.util;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

/**
 * Curios 相关的公共小工具。
 */
public final class CurioHelper {
	private CurioHelper() {
	}

	/** 该实体是否佩戴着指定物品（任意 Curios 栏位） */
	public static boolean wears(LivingEntity entity, Item item) {
		if (entity == null) {
			return false;
		}
		return CuriosApi.getCuriosInventory(entity)
				.map(handler -> handler.isEquipped(item))
				.orElse(false);
	}

	/** 该实体是否同时佩戴了所有指定物品（套装判定） */
	public static boolean wearsAll(LivingEntity entity, Item... items) {
		for (Item item : items) {
			if (!wears(entity, item)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 「不可重复佩戴」：同一种物品已经在别的 Curios 格子里装备时拒绝再装一份。
	 *
	 * <p>灵台是一个 3 格的栏位，靠这条规则避免把同一件饰品堆两格。
	 */
	public static boolean isAlreadyEquipped(SlotContext context, ItemStack stack) {
		LivingEntity entity = context.entity();
		if (entity == null || stack.isEmpty()) {
			return false;
		}
		ICuriosItemHandler handler = CuriosApi.getCuriosInventory(entity).orElse(null);
		if (handler == null) {
			return false;
		}

		for (var entry : handler.getCurios().entrySet()) {
			String identifier = entry.getKey();
			int slots = entry.getValue().getSlots();
			for (int index = 0; index < slots; index++) {
				// 正在装备的这一格不算重复
				if (identifier.equals(context.identifier()) && index == context.index()) {
					continue;
				}
				if (entry.getValue().getStacks().getStackInSlot(index).is(stack.getItem())) {
					return true;
				}
			}
		}
		return false;
	}
}
