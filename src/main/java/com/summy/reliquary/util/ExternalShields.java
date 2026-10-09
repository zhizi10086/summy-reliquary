package com.summy.reliquary.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 外部护盾的软读取（1.8.5 补修）。
 *
 * <p>目前只用到 Enchantment Reforged 的「生命护盾」属性 {@code enchantment_reforged:life_shield}：
 * ER 把护盾值存在玩家属性的**基础值**里（只由服务端读写、随 NBT 持久化），所以读基础值就是
 * "ER 自己认为的护盾"。没装 ER（属性不存在）时一律返回 0。
 *
 * <p>**软依赖**：只按 id 查注册表，不引用 ER 的任何类 —— 没装 ER 时也不会出错。
 */
public final class ExternalShields {
	/** Enchantment Reforged 的生命护盾属性 id */
	private static final ResourceLocation ER_LIFE_SHIELD =
			new ResourceLocation("enchantment_reforged", "life_shield");

	/** 找到的属性实例（注册表在游戏启动后冻结，找到一次就能一直用） */
	private static Attribute cachedAttribute;

	private ExternalShields() {
	}

	/**
	 * Enchantment Reforged 生命护盾的当前值。
	 *
	 * @return ER 护盾值（基础值）；没装 ER、属性缺失或实体没有该属性时返回 0
	 */
	public static double enchantmentReforgedLifeShield(LivingEntity entity) {
		Attribute attribute = findAttribute();
		if (entity == null || attribute == null) {
			return 0.0D;
		}
		AttributeInstance instance = entity.getAttribute(attribute);
		return instance == null ? 0.0D : Math.max(0.0D, instance.getBaseValue());
	}

	/** 按 id 找属性；两套注册表都试一遍（找不到就返回 null，下次再找） */
	private static Attribute findAttribute() {
		if (cachedAttribute != null) {
			return cachedAttribute;
		}
		try {
			Attribute fromForge = ForgeRegistries.ATTRIBUTES.getValue(ER_LIFE_SHIELD);
			if (fromForge != null) {
				cachedAttribute = fromForge;
				return cachedAttribute;
			}
		} catch (Throwable ignored) {
			// 注册表还没准备好等情况一律按"没有"处理
		}
		try {
			cachedAttribute = BuiltInRegistries.ATTRIBUTE.getOptional(ER_LIFE_SHIELD).orElse(null);
		} catch (Throwable ignored) {
			cachedAttribute = null;
		}
		return cachedAttribute;
	}
}
