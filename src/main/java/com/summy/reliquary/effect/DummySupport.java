package com.summy.reliquary.effect;

import com.summy.reliquary.config.ReliquaryConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * 「训练人偶」兼容（1.5.6，软依赖）。
 *
 * <p>试验假人模组（DuMmmMmmy）的实体 id 是 {@code dummmmmmy:target_dummy}，它不是 {@code Enemy}，
 * 所以默认不会被救恩领域 / 神性光环判定。这里用**实体类型 id 字符串**做软检测：
 * 没装那个模组时判定永远为 false，也不会产生编译期依赖。
 *
 * <p>注意：只有「领域 / 光环」会用到它；**心之碎片的掉落明确排除假人**。
 */
public final class DummySupport {
	/** 试验假人的实体 id */
	public static final ResourceLocation TARGET_DUMMY_ID = ResourceLocation.tryParse("dummmmmmy:target_dummy");

	private DummySupport() {
	}

	/** 该实体类型 id 是否是训练人偶 */
	public static boolean isDummyId(ResourceLocation id) {
		return id != null && id.equals(TARGET_DUMMY_ID);
	}

	/** 该实体是否是训练人偶（并已开启开关） */
	public static boolean isTargetDummy(LivingEntity entity) {
		if (entity == null || !ReliquaryConfig.affectTargetDummies()) {
			return false;
		}
		return isDummyId(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
	}

	/** 不带开关的原始判定（心之碎片掉落要无条件排除假人） */
	public static boolean isTargetDummyEntity(LivingEntity entity) {
		return entity != null && isDummyId(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
	}

	/**
	 * 保护训练人偶（1.5.7）：被我们的真实伤害打到之后把血量回满，保证**只掉伤害数字、不会被打坏**。
	 *
	 * <p>训练人偶一律保护，与 {@code affect_target_dummies} 开关无关；普通生物零影响。
	 */
	public static void protectDummy(LivingEntity entity) {
		if (!isTargetDummyEntity(entity)) {
			return;
		}
		entity.setHealth(entity.getMaxHealth());
	}
}
