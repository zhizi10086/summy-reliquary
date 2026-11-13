package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import net.minecraft.core.Holder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/**
 * 启示之光与恶魔印记的内部到期状态。
 *
 * <p>实际判定只读取实体持久数据，状态效果仅用于显示图标和倒计时。
 * 这样即使生物免疫状态效果，掉落标记仍能按时生效与过期。
 */
public final class TimedMarkerState {
	private static final String REVELATION_UNTIL = "summy_reliquary.revelation_light_until";
	private static final String DEMONIC_BRAND_UNTIL = "summy_reliquary.demonic_brand_until";

	private TimedMarkerState() {
	}

	/** 写入或刷新启示之光内部标记，并尝试同步视觉状态。 */
	public static void markRevelation(LivingEntity entity) {
		if (entity == null || entity.level().isClientSide()
				|| !ReliquaryConfig.enableRevelationLightBuff()) {
			return;
		}
		long until = entity.level().getGameTime() + ReliquaryConfig.revelationLightBuffTicks();
		entity.getPersistentData().putLong(REVELATION_UNTIL, until);
		applyVisual(entity, SummyReliquary.REVELATION_LIGHT,
				ReliquaryConfig.revelationLightBuffTicks());
	}

	/** 是否仍带有未过期的启示之光内部标记。 */
	public static boolean hasRevelation(LivingEntity entity) {
		return active(entity, REVELATION_UNTIL);
	}

	/** 写入或刷新恶魔印记内部标记，并尝试同步视觉状态。 */
	public static void markDemonicBrand(LivingEntity entity) {
		if (entity == null || entity.level().isClientSide()
				|| !ReliquaryConfig.enableDemonicBrandMark()) {
			return;
		}
		int ticks = ReliquaryConfig.demonicBrandTicks();
		long until = entity.level().getGameTime() + ticks;
		entity.getPersistentData().putLong(DEMONIC_BRAND_UNTIL, until);
		applyVisual(entity, SummyReliquary.DEMONIC_BRAND, ticks);
	}

	/** 是否仍带有未过期的恶魔印记内部标记。 */
	public static boolean hasDemonicBrand(LivingEntity entity) {
		return active(entity, DEMONIC_BRAND_UNTIL);
	}

	/** 三类恶魔伤害会触发恶魔印记。 */
	public static boolean isDemonicDamage(DamageSource source) {
		if (source == null) {
			return false;
		}
		return source.typeHolder().unwrapKey()
				.map(key -> {
					var location = key.location();
					if (!SummyReliquary.NAMESPACE.equals(location.getNamespace())) {
						return false;
					}
					String path = location.getPath();
					return "demon_flame".equals(path)
							|| "hellfire".equals(path)
							|| "abyss_aura".equals(path);
				})
				.orElse(false);
	}

	public static boolean isRevelationDamage(DamageSource source) {
		return source != null && source.typeHolder().unwrapKey()
				.map(key -> SummyReliquary.NAMESPACE.equals(key.location().getNamespace())
						&& "revelation_light".equals(key.location().getPath()))
				.orElse(false);
	}

	/** 清除内部标记与对应视觉状态。 */
	public static void clear(LivingEntity entity) {
		if (entity == null) {
			return;
		}
		entity.getPersistentData().remove(REVELATION_UNTIL);
		entity.getPersistentData().remove(DEMONIC_BRAND_UNTIL);
		entity.removeEffect(SummyReliquary.REVELATION_LIGHT);
		entity.removeEffect(SummyReliquary.DEMONIC_BRAND);
	}

	/** 自检：读取内部剩余 tick；-1 表示没有内部标记。 */
	public static long remainingTicksForTest(LivingEntity entity, boolean demonic) {
		if (entity == null) {
			return -1L;
		}
		String key = demonic ? DEMONIC_BRAND_UNTIL : REVELATION_UNTIL;
		if (!entity.getPersistentData().contains(key)) {
			return -1L;
		}
		return entity.getPersistentData().getLong(key) - entity.level().getGameTime();
	}

	public static void setRemainingTicksForTest(LivingEntity entity, boolean demonic, long ticks) {
		if (entity == null || !Boolean.getBoolean("summy.devcheck")) {
			return;
		}
		String key = demonic ? DEMONIC_BRAND_UNTIL : REVELATION_UNTIL;
		entity.getPersistentData().putLong(key, entity.level().getGameTime() + ticks);
	}

	private static boolean active(LivingEntity entity, String key) {
		if (entity == null || entity.level().isClientSide()
				|| !entity.getPersistentData().contains(key)) {
			return false;
		}
		long until = entity.getPersistentData().getLong(key);
		if (entity.level().getGameTime() <= until) {
			return true;
		}
		entity.getPersistentData().remove(key);
		return false;
	}

	private static void applyVisual(LivingEntity entity, Holder<MobEffect> effect, int ticks) {
		entity.addEffect(new MobEffectInstance(effect, Math.max(1, ticks),
				0, false, true, true));
	}
}
