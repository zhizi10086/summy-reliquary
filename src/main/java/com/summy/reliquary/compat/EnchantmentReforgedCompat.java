package com.summy.reliquary.compat;

import com.summy.reliquary.SummyReliquary;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Method;
import java.util.OptionalInt;

/**
 * Enchantment Reforged 兼容层（反射实现）。
 *
 * <p>对方模组是 Fabric 专用模组，Forge 工程没法在编译期依赖它，因此在运行时用反射找
 * {@code com.enchantmentreforged.combat.EnchantmentEffects#foodLevelCap(LivingEntity)}：
 * <ul>
 *     <li>在 Kilt/Fabric 环境下装了该模组 —— 直接调用，白饭会跟随「大胃袋」抬高的上限；</li>
 *     <li>纯 Forge 环境或调用失败 —— 返回空，白饭退回原版上限 20。</li>
 * </ul>
 */
public final class EnchantmentReforgedCompat {
	/** 目标类名 */
	private static final String TARGET_CLASS = "com.enchantmentreforged.combat.EnchantmentEffects";
	/** 目标方法名 */
	private static final String TARGET_METHOD = "foodLevelCap";

	/** 已经解析过的目标方法；null 表示不可用 */
	private static Method foodLevelCapMethod;
	private static boolean resolved = false;

	private EnchantmentReforgedCompat() {
	}

	/** 饥饿上限；目标模组不存在或调用失败时返回空 */
	public static OptionalInt foodLevelCap(Player player) {
		Method method = resolve();
		if (method == null) {
			return OptionalInt.empty();
		}
		try {
			Object result = method.invoke(null, player);
			if (result instanceof Integer cap && cap > 0) {
				return OptionalInt.of(cap);
			}
		} catch (ReflectiveOperationException | RuntimeException exception) {
			SummyReliquary.LOGGER.warn("[Summy Reliquary] 调用 Enchantment Reforged 的饥饿上限失败，退回原版 20：{}",
					exception.toString());
			// 失败后不再重复尝试，避免每 tick 刷日志
			foodLevelCapMethod = null;
			resolved = true;
		}
		return OptionalInt.empty();
	}

	/** 解析目标方法（只做一次），并在结果变化时打一条日志便于排查 */
	private static Method resolve() {
		if (resolved) {
			return foodLevelCapMethod;
		}
		resolved = true;
		try {
			Class<?> target = Class.forName(TARGET_CLASS);
			Method method = target.getMethod(TARGET_METHOD, LivingEntity.class);
			foodLevelCapMethod = method;
			SummyReliquary.LOGGER.info("[Summy Reliquary] 已启用 Enchantment Reforged 兼容（饥饿上限跟随「大胃袋」）");
		} catch (ClassNotFoundException exception) {
			SummyReliquary.LOGGER.info("[Summy Reliquary] 未找到 Enchantment Reforged，白饭使用原版饥饿上限 20");
			foodLevelCapMethod = null;
		} catch (NoSuchMethodException exception) {
			SummyReliquary.LOGGER.warn("[Summy Reliquary] Enchantment Reforged 版本不匹配（缺少 {}），使用原版饥饿上限 20",
					TARGET_METHOD);
			foodLevelCapMethod = null;
		}
		return foodLevelCapMethod;
	}
}
