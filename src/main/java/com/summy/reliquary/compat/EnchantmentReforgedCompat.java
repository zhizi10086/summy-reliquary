package com.summy.reliquary.compat;

import com.summy.reliquary.SummyReliquary;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Summy Enchantment 兼容层（反射实现）。
 *
 * <p>对方模组是 Fabric 专用模组，Forge 工程没法在编译期依赖它，因此在运行时用反射找
 * {@code com.summy.enchantment.combat.EnchantmentEffects#foodLevelCap(LivingEntity)}：
 * <ul>
 *     <li>在 Kilt/Fabric 环境下装了该模组 —— 直接调用，白饭会跟随「大胃袋」抬高的上限；</li>
 *     <li>纯 Forge 环境或调用失败 —— 返回空，白饭退回原版上限 20。</li>
 * </ul>
 */
public final class EnchantmentReforgedCompat {
	/** 目标类名 */
	private static final String TARGET_CLASS = "com.summy.enchantment.combat.EnchantmentEffects";
	/** 目标方法名 */
	private static final String TARGET_METHOD = "foodLevelCap";
	/** SE 死神祝福的固定伤害排除标签 */
	private static final TagKey<DamageType> NO_DEATHS_BLESSING_OUTGOING = TagKey.create(
			Registries.DAMAGE_TYPE,
			new ResourceLocation("summy_enchantment", "no_deaths_blessing_outgoing"));

	/** 已经解析过的目标方法；null 表示不可用 */
	private static Method foodLevelCapMethod;
	private static boolean resolved = false;
	/** SE 死神祝福造成伤害倍率入口 */
	private static Method deathsBlessingOutgoingMethod;
	private static boolean deathsBlessingResolved;
	/** 反射失败只警告一次，避免战斗热路径刷日志 */
	private static final AtomicBoolean DEATHS_BLESSING_WARNED = new AtomicBoolean();
	/** 自检用：模拟 SE 死神祝福倍率；null 表示恢复反射读取 */
	private static @Nullable Float forcedDeathsBlessingForTest;
	/** 自检用：模拟 SE 接口缺失 */
	private static boolean forcedMissingDeathsBlessingForTest;
	/** 自检用：模拟 SE 接口调用异常 */
	private static boolean forcedFailureDeathsBlessingForTest;

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
			SummyReliquary.LOGGER.warn("[Summy Reliquary] 调用 Summy Enchantment 的饥饿上限失败，退回原版 20：{}",
					exception.toString());
			// 失败后不再重复尝试，避免每 tick 刷日志
			foodLevelCapMethod = null;
			resolved = true;
		}
		return OptionalInt.empty();
	}

	/** 该伤害是否被 SE 的标签排除在原版死神祝福之外。 */
	public static boolean isDeathsBlessingExcluded(DamageSource source) {
		return source != null && source.is(NO_DEATHS_BLESSING_OUTGOING);
	}

	/**
	 * 为被 SE 排除的 SR 定值真伤补一次死神祝福倍率。
	 *
	 * <p>SE 的受伤侧会主动跳过排除标签内的伤害，因此这里只在标签命中时读取同一个
	 * {@code deathsBlessingOutgoing} 公式。SE 缺失、接口异常或倍率无效时固定返回 1.0。
	 */
	public static float deathsBlessingOutgoingForFixedDamage(
			@Nullable LivingEntity attacker, DamageSource source) {
		if (attacker == null || !isDeathsBlessingExcluded(source)) {
			return 1.0F;
		}
		return deathsBlessingOutgoing(attacker);
	}

	/** 定值真伤进入 SE 死神祝福的唯一闸门。 */
	public static float applyDeathsBlessingToFixedDamage(float amount,
			@Nullable LivingEntity attacker, DamageSource source, boolean exactDamageBonuses) {
		if (!exactDamageBonuses || amount <= 0.0F) {
			return amount;
		}
		return amount * deathsBlessingOutgoingForFixedDamage(attacker, source);
	}

	/** 自检入口：模拟倍率、接口缺失或调用异常；全部传默认值即恢复反射读取。 */
	public static void setDeathsBlessingProbeForTest(@Nullable Float value,
			boolean missing, boolean failure) {
		if (Boolean.getBoolean("summy.devcheck")) {
			forcedDeathsBlessingForTest = value;
			forcedMissingDeathsBlessingForTest = missing;
			forcedFailureDeathsBlessingForTest = failure;
		}
	}

	/** 过滤 NaN、无穷与非正倍率，保证异常反射结果不会污染伤害。 */
	public static float safeDeathsBlessingMultiplier(@Nullable Float value) {
		return value != null && Float.isFinite(value) && value > 0.0F ? value : 1.0F;
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
			SummyReliquary.LOGGER.info("[Summy Reliquary] 已启用 Summy Enchantment 兼容（饥饿上限跟随「大胃袋」）");
		} catch (ClassNotFoundException exception) {
			SummyReliquary.LOGGER.info("[Summy Reliquary] 未找到 Summy Enchantment，白饭使用原版饥饿上限 20");
			foodLevelCapMethod = null;
		} catch (NoSuchMethodException exception) {
			SummyReliquary.LOGGER.warn("[Summy Reliquary] Summy Enchantment 版本不匹配（缺少 {}），使用原版饥饿上限 20",
					TARGET_METHOD);
			foodLevelCapMethod = null;
		}
		return foodLevelCapMethod;
	}

	@Nullable
	private static Method resolveDeathsBlessing() {
		if (deathsBlessingResolved) {
			return deathsBlessingOutgoingMethod;
		}
		deathsBlessingResolved = true;
		try {
			Class<?> target = Class.forName(TARGET_CLASS);
			deathsBlessingOutgoingMethod = target.getMethod(
					"deathsBlessingOutgoing", LivingEntity.class);
		} catch (ClassNotFoundException | NoSuchMethodException exception) {
			warnDeathsBlessingOnce("未找到 SE 死神祝福接口，固定伤害按 1.0 倍处理", exception);
			deathsBlessingOutgoingMethod = null;
		}
		return deathsBlessingOutgoingMethod;
	}

	private static float deathsBlessingOutgoing(LivingEntity attacker) {
		if (forcedDeathsBlessingForTest != null) {
			return safeDeathsBlessingMultiplier(forcedDeathsBlessingForTest);
		}
		if (forcedMissingDeathsBlessingForTest) {
			return 1.0F;
		}
		if (forcedFailureDeathsBlessingForTest) {
			warnDeathsBlessingOnce("调用 SE 死神祝福失败，固定伤害按 1.0 倍处理", null);
			return 1.0F;
		}
		Method method = resolveDeathsBlessing();
		if (method == null) {
			return 1.0F;
		}
		try {
			Object value = method.invoke(null, attacker);
			return value instanceof Number number
					? safeDeathsBlessingMultiplier(number.floatValue()) : 1.0F;
		} catch (ReflectiveOperationException | RuntimeException exception) {
			warnDeathsBlessingOnce("调用 SE 死神祝福失败，固定伤害按 1.0 倍处理", exception);
			deathsBlessingOutgoingMethod = null;
			deathsBlessingResolved = true;
			return 1.0F;
		}
	}

	private static void warnDeathsBlessingOnce(String message, @Nullable Throwable throwable) {
		if (DEATHS_BLESSING_WARNED.compareAndSet(false, true)) {
			SummyReliquary.LOGGER.warn("[Summy Reliquary] {}", message, throwable);
		}
	}
}
