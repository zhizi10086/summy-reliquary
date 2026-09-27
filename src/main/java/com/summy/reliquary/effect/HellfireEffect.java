package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.UUID;

/**
 * 「狱火」（1.6.7）：深渊领主给被自己打中的目标叠的减益载体。
 *
 * <p>每一级做两件事：
 * <ul>
 *     <li><b>护甲 -10% / 级</b>：走原版属性修饰符机制（{@link #addAttributeModifiers}），
 *     {@code MULTIPLY_TOTAL} 的 {@code -0.1 × 等级}，所以 10 级时护甲归零、不会变成负数。
 *     等级变化时原版会先移除再加，因此固定一个 UUID 就够；</li>
 *     <li><b>每秒 等级 × 1 点狱火伤害</b>：复用 {@code summy-reliquary:hellfire} 伤害类型
 *     （写进 {@code bypasses_armor} = 法伤口径：无视护甲，保护附魔与抗性照常生效），
 *     结算前清无敌帧，所以每秒那一下必定落地。</li>
 * </ul>
 *
 * <p>它只能由**深渊领主**施加（见 {@link AbyssLord}）；复仇之魂的"狱火伤害"只是伤害，不挂这个效果。
 */
public class HellfireEffect extends MobEffect {
	/** 护甲减益的固定 UUID（等级变化时按它移除/重加） */
	private static final UUID ARMOR_UUID = UUID.fromString("8f4c1d2e-9a37-4b6f-8c5d-1e2f3a4b5c6d");
	/** 图标色调（硫磺火橙红） */
	private static final int COLOR = 0xFF6B00;

	public HellfireEffect() {
		super(MobEffectCategory.HARMFUL, COLOR);
	}

	@Override
	public void addAttributeModifiers(LivingEntity entity, AttributeMap attributes, int amplifier) {
		AttributeInstance armor = attributes.getInstance(Attributes.ARMOR);
		if (armor == null) {
			return;
		}
		// 先清掉可能残留的同 UUID 修饰符，再按当前等级写一份
		armor.removeModifier(ARMOR_UUID);
		double value = -(ReliquaryConfig.hellfireArmorPercentPerLevel() / 100.0D) * (amplifier + 1);
		if (Math.abs(value) > 1.0E-6D) {
			armor.addTransientModifier(new AttributeModifier(ARMOR_UUID, "summy_reliquary_hellfire_armor",
					value, AttributeModifier.Operation.MULTIPLY_TOTAL));
		}
	}

	@Override
	public void removeAttributeModifiers(LivingEntity entity, AttributeMap attributes, int amplifier) {
		AttributeInstance armor = attributes.getInstance(Attributes.ARMOR);
		if (armor != null) {
			armor.removeModifier(ARMOR_UUID);
		}
	}

	@Override
	public void applyEffectTick(LivingEntity entity, int amplifier) {
		if (!ReliquaryConfig.enableAbyssLord() || entity.level().isClientSide()) {
			return;
		}
		// 1.6.10 联动：狱火满级 + 施加者同时佩戴亚巴顿 → 压制目标「抗性提升」的等级（每秒覆盖一次）
		suppressResistance(entity, amplifier);
		double damage = ReliquaryConfig.hellfireDamagePerLevelPerSecond() * (amplifier + 1);
		if (damage <= 0.0D || !(entity.level() instanceof ServerLevel level)) {
			return;
		}
		// 与复仇之魂同款：先清无敌帧，保证每秒这一下必定落地
		entity.invulnerableTime = 0;
		entity.hurt(hellfireSource(level), (float) damage);
	}

	/** 每秒结算一次 */
	@Override
	public boolean isDurationEffectTick(int duration, int amplifier) {
		return duration % 20 == 0;
	}

	/** 狱火伤害源（无击杀归属，死亡文本走 {@code death.attack.summy-reliquary.hellfire}） */
	private static DamageSource hellfireSource(ServerLevel level) {
		ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE, SummyReliquary.id("hellfire"));
		var holder = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolder(key).orElse(null);
		if (holder == null) {
			return level.damageSources().magic();
		}
		return new DamageSource(holder);
	}

	/**
	 * 亚巴顿联动（1.6.10）：狱火达到上限等级时，把目标身上「抗性提升」的**等级减半（向上取整）**，
	 * 并且**每秒覆盖一次** —— 目标中途喝抗性药水也会在 ≤1 秒内被重新压下去。
	 *
	 * <p>等级＝amplifier + 1：抗性 IV（amp 3）→ 等级 2（amp 1）；抗性 V（amp 4）→ 等级 3（amp 2）；
	 * 抗性 I（amp 0）→ 减半后仍是 1，不变。剩余时长与显示参数（粒子 / 图标）原样保留。
	 *
	 * <p>联动前提（"施加者同时佩戴亚巴顿"）由 {@link AbyssLord#linkedWithAbaddon} 的时间戳判定，
	 * 时间戳在每次命中叠狱火时刷新，长度＝狱火持续时间，所以它天然跟着这次狱火一起过期。
	 */
	private static void suppressResistance(LivingEntity entity, int amplifier) {
		if (!ReliquaryConfig.hellfireHalvesResistanceWithAbaddon()) {
			return;
		}
		if (amplifier + 1 < ReliquaryConfig.hellfireMaxLevel()) {
			return;
		}
		if (!AbyssLord.linkedWithAbaddon(entity)) {
			return;
		}
		var resistance = entity.getEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE);
		if (resistance == null) {
			return;
		}
		int level = resistance.getAmplifier() + 1;
		int halved = (level + 1) / 2; // 向上取整
		if (halved >= level) {
			return; // 已经减半到位（1 级时不变），不必重复写
		}
		entity.removeEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE);
		entity.addEffect(new net.minecraft.world.effect.MobEffectInstance(
				net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE,
				Math.max(1, resistance.getDuration()), halved - 1,
				resistance.isAmbient(), resistance.isVisible(), resistance.showIcon()));
	}
}
