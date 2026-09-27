package com.summy.reliquary.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * 本模组武器的自定材质（1.7.5 起：两把仪式匕首；1.7.7 起追加圣光短矛）。
 *
 * <p>剑本身不挖方块，所以 {@code getSpeed()} / {@code getLevel()} 只是走个过场（给剑类通用的默认值）；
 * 真正有意义的三个数是**耐久**、**附魔等级**与**修理材料**。
 */
public enum RitualTiers implements Tier {
	/** 献祭匕首：666 耐久 / 附魔 22 / 铁锭修理 */
	SACRIFICIAL(666, 22, () -> Items.IRON_INGOT),
	/** 暗仪刺刀：1666 耐久 / 附魔 25 / 下界合金碎片修理 */
	DARK_ARTS(1666, 25, () -> Items.NETHERITE_SCRAP),
	/** 圣光短矛（1.7.7）：1000 耐久 / 附魔 25 / 金锭修理 */
	HOLY_SPEAR(1000, 25, () -> Items.GOLD_INGOT),
	/** 炽天使之枪（1.7.8）：2222 耐久 / 附魔 30 / 心之碎片修理 */
	SERAPH_SPEAR(2222, 30, () -> com.summy.reliquary.SummyReliquary.HEART_SHARD.get());

	private final int uses;
	private final int enchantmentValue;
	/** 修理材料**惰性**取（模组物品要等它自己的 RegistryObject 就绪，不能在枚举初始化时就 get()） */
	private final java.util.function.Supplier<Item> repairItem;

	RitualTiers(int uses, int enchantmentValue, java.util.function.Supplier<Item> repairItem) {
		this.uses = uses;
		this.enchantmentValue = enchantmentValue;
		this.repairItem = repairItem;
	}

	@Override
	public int getUses() {
		return uses;
	}

	@Override
	public float getSpeed() {
		return 6.0F;
	}

	@Override
	public float getAttackDamageBonus() {
		// 伤害完全由物品属性修饰符决定（献祭 +3 / 暗仪 +5，与玩家基础的 1 相加）
		return 0.0F;
	}

	@Override
	public int getLevel() {
		return 0;
	}

	@Override
	public int getEnchantmentValue() {
		return enchantmentValue;
	}

	@Override
	public Ingredient getRepairIngredient() {
		return Ingredient.of(repairItem.get());
	}

	/** 该材质是否认这件物品作为修理材料（自检与铁砧口径一致） */
	public boolean repairs(ItemStack stack) {
		return !stack.isEmpty() && stack.is(repairItem.get());
	}
}
