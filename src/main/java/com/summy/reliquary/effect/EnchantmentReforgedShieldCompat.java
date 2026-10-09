package com.summy.reliquary.effect;

import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * 与 Enchantment Reforged「生命护盾」的**软互操作**（1.8.5 第二轮）。
 *
 * <p>背景：ER 的护盾靠「读当前值做增量记账」判断自己被打掉多少 —— 它把
 * {@code SHIELD_TRACK}（上次看到的池子值）与当前吸收值相减，得到"我方护盾被消耗了多少"。
 * SR 的旧路径（把魂心 / 黑心份额**临时并入**吸收值、事后退还）会在 ER 的两次记账之间造成
 * "先涨后跌"，于是 ER 会**把 SR 退还的那一份记成自己护盾的消耗**（表现为 ER 护盾值被慢慢扣小、
 * 提前归零，需要重新靠治疗补；吸收池本身不会被多扣）。
 *
 * <p>Forge / Connector 侧已经改走 {@code LivingDamageEvent} 后置扣池（完全不碰吸收值），本类只服务
 * **旧路径**（Kilt 等收不到该事件的环境）：在每次对账写回池值之后，把 ER 的记账校正成与池子一致 ——
 * {@code SHIELD_TRACK[X] = 校正后的池值}，并按"真实护盾消耗 = min(原护盾, 原版实际吃掉的吸收值)"
 * 调小 ER 的护盾值（量化到半颗心，照抄 ER 的口径）。
 *
 * <p><b>软依赖</b>：全部走反射，ER 不在、类 / 字段 / 方法改名、或任何异常都静默跳过（行为回到"不校正"，
 * 即现状），绝不影响正常伤害结算。ER 是普通 Forge mod（自动模块 → open），所以 {@code setAccessible} 可用。
 */
public final class EnchantmentReforgedShieldCompat {
	/** ER 的结算类（护盾读写与记账都在这里） */
	private static final String EFFECTS_CLASS = "com.enchantmentreforged.combat.EnchantmentEffects";

	/** 解析结果缓存（ER 缺席时保持 null，不会反复反射） */
	private static boolean resolved;
	private static Class<?> effectsClass;
	private static Method lifeShieldMethod;
	private static Method setLifeShieldMethod;
	private static Field shieldTrackField;
	private static Map<?, ?> shieldTrack;

	private EnchantmentReforgedShieldCompat() {
	}

	/**
	 * 旧路径对账结束后调用：把 ER 的记账校正到与当前池值一致。
	 *
	 * @param player         目标玩家
	 * @param poolAfter      我们写回的池值（吸收值）
	 * @param shieldConsumed 这一击**真实**吃掉的护盾份额（= min(原吸收值, 原版实际消耗)）
	 */
	public static void reconcileAfterPoolWrite(LivingEntity player, float poolAfter, float shieldConsumed) {
		if (player == null || !resolve()) {
			return;
		}
		try {
			// ① 记账对齐：ER 下次 tick 看到的"上次池值"就是我们刚写回的池值 → 不会凭空扣自己
			if (shieldTrack != null) {
				@SuppressWarnings("unchecked")
				Map<Object, Object> track = (Map<Object, Object>) shieldTrack;
				track.put(player, poolAfter);
			}
			// ② 真实消耗：ER 自己该掉多少就掉多少（量化到半颗心，与它内部口径一致）
			if (shieldConsumed > 0.0F && lifeShieldMethod != null && setLifeShieldMethod != null) {
				Object current = lifeShieldMethod.invoke(null, player);
				if (current instanceof Number number) {
					float after = quantizeShield(Math.max(0.0F, number.floatValue() - shieldConsumed));
					if (after < number.floatValue() - 0.001F) {
						setLifeShieldMethod.invoke(null, player, after);
					}
				}
			}
		} catch (Throwable ignored) {
			// 任何异常都当成"这个版本对不上"处理：下不为例，不影响结算
			effectsClass = null;
			lifeShieldMethod = null;
			setLifeShieldMethod = null;
			shieldTrackField = null;
			shieldTrack = null;
			resolved = true;
		}
	}

	/** 自检用：ER 的记账是否已被成功解析（ER 缺席时为 false） */
	public static boolean available() {
		return resolve();
	}

	/** 自检用：ER 当前的护盾值（取不到时返回 -1） */
	public static float lifeShieldForTest(LivingEntity entity) {
		if (entity == null || !resolve() || lifeShieldMethod == null) {
			return -1.0F;
		}
		try {
			Object value = lifeShieldMethod.invoke(null, entity);
			return value instanceof Number number ? number.floatValue() : -1.0F;
		} catch (Throwable ignored) {
			return -1.0F;
		}
	}

	/** 自检用：ER 记的"上次池值"（取不到时返回 -1） */
	public static float shieldTrackForTest(LivingEntity entity) {
		if (entity == null || !resolve() || shieldTrack == null) {
			return -1.0F;
		}
		Object value = shieldTrack.get(entity);
		return value instanceof Number number ? number.floatValue() : -1.0F;
	}

	/** 量化到半颗心（与 ER 的 quantizeShield 同口径） */
	private static float quantizeShield(float value) {
		return Math.max(0.0F, Math.round(value * 2.0F) / 2.0F);
	}

	/** 解析 ER 的类 / 方法 / 字段（只做一次；任何一步失败都按"没有 ER"处理） */
	private static boolean resolve() {
		if (resolved) {
			return effectsClass != null;
		}
		resolved = true;
		try {
			Class<?> clazz = Class.forName(EFFECTS_CLASS);
			Method read = findMethod(clazz, "lifeShield", 1);
			Method write = findMethod(clazz, "setLifeShield", 2);
			Field track = findShieldTrack(clazz);
			if (read == null || write == null) {
				return false;
			}
			effectsClass = clazz;
			lifeShieldMethod = read;
			setLifeShieldMethod = write;
			shieldTrackField = track;
			if (track != null) {
				track.setAccessible(true);
				Object value = track.get(null);
				if (value instanceof Map<?, ?> map) {
					shieldTrack = map;
				}
			}
			return true;
		} catch (Throwable ignored) {
			effectsClass = null;
			return false;
		}
	}

	/**
	 * 按名字 + 参数个数找静态方法，并校验第一个参数能接受 {@code LivingEntity}。
	 *
	 * <p>这样写是为了兼容 Fabric 侧（Kilt 实例里的 ER 是 Fabric 构建，泛型 / 形参类型可能被映射成
	 * intermediary 名），不依赖精确的类型签名。
	 */
	private static Method findMethod(Class<?> clazz, String name, int parameterCount) {
		for (Method method : clazz.getDeclaredMethods()) {
			if (!method.getName().equals(name) || method.getParameterCount() != parameterCount) {
				continue;
			}
			if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
				continue;
			}
			Class<?>[] types = method.getParameterTypes();
			if (types.length == 0 || !types[0].isAssignableFrom(LivingEntity.class)) {
				continue;
			}
			method.setAccessible(true);
			return method;
		}
		return null;
	}

	/** 找 ER 的记账表：名字优先，其次退化为"静态 Map 字段" */
	private static Field findShieldTrack(Class<?> clazz) {
		Field named = null;
		for (Field field : clazz.getDeclaredFields()) {
			if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
					|| !Map.class.isAssignableFrom(field.getType())) {
				continue;
			}
			if (field.getName().contains("SHIELD_TRACK") || field.getName().contains("shieldTrack")) {
				return field;
			}
			if (named == null) {
				named = field;
			}
		}
		return named;
	}
}
