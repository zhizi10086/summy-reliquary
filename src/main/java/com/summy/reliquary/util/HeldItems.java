package com.summy.reliquary.util;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 「身上是否持有某件物品」的统一判定（1.7.10 修订）。
 *
 * <p><b>为什么要专门写这个</b>：以前各处用的是 {@code player.getInventory().contains(new ItemStack(item))}，
 * 而原版 {@code Container#contains(ItemStack)} 走的是 {@code ItemStack.isSameItemSameTags} —— **连 NBT 一起比**。
 * 于是只要那件物品**掉过耐久**（匕首是剑，近战就会掉）、**附过魔**或者**改过名**，就会被判成"没有"：
 * 防丢失会误报"你摸了摸腰间，没有找到你的匕首"、成就兜底也会漏判。
 *
 * <p>本工具只比**物品类型**（{@code ItemStack.is(item)}），并把扫描范围补全到——
 * <b>背包 36 格 + 副手 + 盔甲 + 光标（鼠标拿着的那一份）+ 全部 Curios 栏位</b>。
 * 客户端（{@code LocalPlayer}）同样可用：{@code containerMenu} 与 Curios 副本都在本地。
 */
public final class HeldItems {
	private HeldItems() {
	}

	/** 身上是否持有该物品（只比类型、忽略 NBT；含副手 / 盔甲 / 光标 / 饰品栏） */
	public static boolean holds(LivingEntity entity, Item item) {
		if (entity == null || item == null) {
			return false;
		}
		if (CurioHelper.wears(entity, item)) {
			return true;
		}
		if (!(entity instanceof Player player)) {
			return false;
		}
		// 背包 36 格（含快捷栏）
		var inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (inventory.getItem(slot).is(item)) {
				return true;
			}
		}
		// 副手
		if (inventory.offhand.stream().anyMatch(stack -> stack.is(item))) {
			return true;
		}
		// 盔甲四格
		if (inventory.armor.stream().anyMatch(stack -> stack.is(item))) {
			return true;
		}
		// 光标（鼠标上拿着的那一份；客户端也成立）
		if (player.containerMenu != null && player.containerMenu.getCarried().is(item)) {
			return true;
		}
		return false;
	}

}
