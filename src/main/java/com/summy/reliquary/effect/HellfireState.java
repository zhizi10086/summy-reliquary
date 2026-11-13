package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.UUID;

/**
 * 狱火的内部等级、期限与周期结算。
 *
 * <p>{@code hellfire} MobEffect 只显示等级和剩余时间；护甲削减、周期伤害与
 * 亚巴顿抗性压制均从这里读取，不受状态效果免疫或清除影响。
 */
public final class HellfireState {
	private static final UUID ARMOR_UUID =
			UUID.fromString("8f4c1d2e-9a37-4b6f-8c5d-1e2f3a4b5c6d");
	private static final String LEVEL = "summy_reliquary.hellfire_level";
	private static final String UNTIL = "summy_reliquary.hellfire_until";
	private static final String NEXT_DAMAGE = "summy_reliquary.hellfire_next_damage_at";
	/** 保持旧 NBT 键名，兼容已经存在的目标数据。 */
	public static final String LINK_UNTIL = "summy_reliquary.hellfire_abaddon_until";

	private HellfireState() {
	}

	public static void applyStack(LivingEntity target, LivingEntity attacker) {
		if (target == null || !target.isAlive() || target.level().isClientSide()) {
			return;
		}
		int level = Math.min(ReliquaryConfig.hellfireMaxLevel(), level(target) + 1);
		long now = target.level().getGameTime();
		int ticks = ReliquaryConfig.hellfireTicks();
		var data = target.getPersistentData();
		data.putInt(LEVEL, level);
		data.putLong(UNTIL, now + ticks);
		data.putLong(NEXT_DAMAGE, now + 20L);
		if (Synergies.hellfireSuppressesResistance(attacker)) {
			data.putLong(LINK_UNTIL, now + ticks);
		}
		updateArmor(target, level);
		refreshVisual(target, level, ticks);
	}

	public static int level(LivingEntity target) {
		if (target == null || target.level().isClientSide()) {
			return 0;
		}
		int level = target.getPersistentData().getInt(LEVEL);
		if (level > 0) {
			return Math.min(level, ReliquaryConfig.hellfireMaxLevel());
		}
		// 旧存档迁移：此前由带机制的 MobEffect 保存层数。
		MobEffectInstance legacy = target.getEffect(SummyReliquary.HELLFIRE);
		if (legacy != null && legacy.getDuration() > 0) {
			long now = target.level().getGameTime();
			int migrated = Math.min(ReliquaryConfig.hellfireMaxLevel(), legacy.getAmplifier() + 1);
			target.getPersistentData().putInt(LEVEL, migrated);
			target.getPersistentData().putLong(UNTIL, now + legacy.getDuration());
			target.getPersistentData().putLong(NEXT_DAMAGE, now + 20L);
			return migrated;
		}
		return 0;
	}

	public static boolean active(LivingEntity target) {
		return level(target) > 0 && target.level().getGameTime() <= target.getPersistentData().getLong(UNTIL);
	}

	public static void tick(LivingEntity target) {
		if (target == null || target.level().isClientSide() || !target.isAlive()) {
			return;
		}
		int level = level(target);
		if (level <= 0) {
			return;
		}
		long now = target.level().getGameTime();
		long until = target.getPersistentData().getLong(UNTIL);
		if (now > until) {
			clear(target);
			return;
		}
		updateArmor(target, level);
		if (!ReliquaryConfig.enableAbyssLord()) {
			return;
		}
		long next = target.getPersistentData().getLong(NEXT_DAMAGE);
		if (next <= 0L) {
			target.getPersistentData().putLong(NEXT_DAMAGE, now + 20L);
			return;
		}
		if (now < next) {
			return;
		}
		target.getPersistentData().putLong(NEXT_DAMAGE, now + 20L);
		suppressResistance(target, level);
		double damage = ReliquaryConfig.hellfireDamagePerLevelPerSecond() * level;
		if (damage <= 0.0D || !(target.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		TimedMarkerState.markDemonicBrand(target);
		target.invulnerableTime = 0;
		TrueDamageLimits.hurtWithoutKnockback(target, hellfireSource(serverLevel), (float) damage);
	}

	public static boolean removeOneStack(LivingEntity target) {
		int level = level(target);
		if (level <= 0) {
			return false;
		}
		int remaining = level - 1;
		if (remaining <= 0) {
			clear(target);
			return true;
		}
		target.getPersistentData().putInt(LEVEL, remaining);
		updateArmor(target, remaining);
		long ticks = Math.max(1L,
				target.getPersistentData().getLong(UNTIL) - target.level().getGameTime());
		refreshVisual(target, remaining, (int) Math.min(Integer.MAX_VALUE, ticks));
		return true;
	}

	public static boolean linkedWithAbaddon(LivingEntity target) {
		if (target == null) {
			return false;
		}
		long until = target.getPersistentData().getLong(LINK_UNTIL);
		return until > 0L && target.level().getGameTime() <= until;
	}

	public static void clear(LivingEntity target) {
		if (target == null) {
			return;
		}
		removeArmor(target);
		var data = target.getPersistentData();
		data.remove(LEVEL);
		data.remove(UNTIL);
		data.remove(NEXT_DAMAGE);
		data.remove(LINK_UNTIL);
		target.removeEffect(SummyReliquary.HELLFIRE);
	}

	public static void setNextDamageForTest(LivingEntity target, long gameTime) {
		if (Boolean.getBoolean("summy.devcheck") && target != null) {
			target.getPersistentData().putLong(NEXT_DAMAGE, gameTime);
		}
	}

	public static boolean hasArmorModifier(LivingEntity target) {
		AttributeInstance armor = target == null ? null : target.getAttribute(Attributes.ARMOR);
		return armor != null && armor.getModifier(SummyReliquary.id("hellfire_armor")) != null;
	}

	public static long remainingTicksForTest(LivingEntity target) {
		if (target == null || target.getPersistentData().getInt(LEVEL) <= 0) {
			return -1L;
		}
		return target.getPersistentData().getLong(UNTIL) - target.level().getGameTime();
	}

	public static void setRemainingTicksForTest(LivingEntity target, long ticks) {
		if (target != null && Boolean.getBoolean("summy.devcheck")) {
			target.getPersistentData().putLong(UNTIL, target.level().getGameTime() + ticks);
		}
	}

	private static void refreshVisual(LivingEntity target, int level, int ticks) {
		target.addEffect(new MobEffectInstance(SummyReliquary.HELLFIRE, Math.max(1, ticks),
				Math.max(0, level - 1), false, true, true));
	}

	private static void updateArmor(LivingEntity target, int level) {
		AttributeInstance armor = target.getAttribute(Attributes.ARMOR);
		if (armor == null) {
			return;
		}
		armor.removeModifier(SummyReliquary.id("hellfire_armor"));
		double value = -(ReliquaryConfig.hellfireArmorPercentPerLevel() / 100.0D) * level;
		if (Math.abs(value) > 1.0E-6D) {
			armor.addTransientModifier(new AttributeModifier(
					SummyReliquary.id("hellfire_armor"), value,
					AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
	}

	private static void removeArmor(LivingEntity target) {
		AttributeInstance armor = target.getAttribute(Attributes.ARMOR);
		if (armor != null) {
			armor.removeModifier(SummyReliquary.id("hellfire_armor"));
		}
	}

	private static DamageSource hellfireSource(ServerLevel level) {
		ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE,
				SummyReliquary.id("hellfire"));
		var holder = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
				.getHolder(key).orElse(null);
		return holder == null ? level.damageSources().magic() : new DamageSource(holder);
	}

	private static void suppressResistance(LivingEntity target, int level) {
		if (!ReliquaryConfig.hellfireHalvesResistanceWithAbaddon()
				|| level < ReliquaryConfig.hellfireMaxLevel()
				|| !linkedWithAbaddon(target)) {
			return;
		}
		var resistance = target.getEffect(MobEffects.DAMAGE_RESISTANCE);
		if (resistance == null) {
			return;
		}
		int currentLevel = resistance.getAmplifier() + 1;
		int halved = (currentLevel + 1) / 2;
		if (halved >= currentLevel) {
			return;
		}
		target.removeEffect(MobEffects.DAMAGE_RESISTANCE);
		target.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE,
				Math.max(1, resistance.getDuration()), halved - 1,
				resistance.isAmbient(), resistance.isVisible(), resistance.showIcon()));
	}
}
