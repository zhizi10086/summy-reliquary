package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/**
 * 「深渊领主」（1.6.7）：加护栏的恶魔侧饰品。
 *
 * <p>佩戴时，**佩戴者造成的所有伤害**都会给受击者叠一层「狱火」（见 {@link HellfireEffect}）：
 * 首次是 1 级，之后每命中一次 +1 级、封顶 {@code hellfire_max_level}（默认 10），
 * 持续时间每次刷新（默认 6 秒）。
 *
 * <p>两类伤害不施加：本模组的**定值真伤**（启示之光 / 神性光环 / 献祭 / 恶魔之焰，它们的数值是配置直接给定的）
 * 与**狱火伤害自身**（否则每秒的狱火伤害会自己把自己叠到满级）。
 */
public final class AbyssLord {
	private AbyssLord() {
	}

	/** 是否佩戴着深渊领主（且功能已启用） */
	public static boolean wears(LivingEntity entity) {
		return entity != null && ReliquaryConfig.enableAbyssLord()
				&& CurioHelper.wears(entity, SummyReliquary.ABYSS_LORD.get());
	}

	/** 这一击是否应该给目标叠狱火（排除定值真伤与狱火自身） */
	public static boolean shouldApply(DamageSource source) {
		return source != null
				&& !HolyLightEffect.isExactDamage(source)
				&& !HolyLightEffect.isHellfire(source);
	}

	/** 给目标叠一层狱火（已有则 +1 级，封顶；同时刷新持续时间） */
	public static void applyStack(LivingEntity target) {
		applyStack(target, null);
	}

	/**
	 * 给目标叠一层狱火（已有则 +1 级，封顶；同时刷新持续时间）。
	 *
	 * <p>1.6.10 联动：若**攻击者同时佩戴亚巴顿**，给目标写一个"联动时间戳"（长度 = 狱火持续时间，
	 * 每次命中刷新）；{@link HellfireEffect} 每秒据此判断"狱火满级时把目标抗性等级减半"。
	 */
	public static void applyStack(LivingEntity target, LivingEntity attacker) {
		if (target == null || !target.isAlive()) {
			return;
		}
		int max = ReliquaryConfig.hellfireMaxLevel();
		MobEffectInstance current = target.getEffect(SummyReliquary.HELLFIRE.get());
		int level = current == null ? 1 : Math.min(max, current.getAmplifier() + 2);
		target.addEffect(new MobEffectInstance(SummyReliquary.HELLFIRE.get(), ReliquaryConfig.hellfireTicks(),
				level - 1, false, true, true));
		if (Synergies.hellfireSuppressesResistance(attacker)) {
			target.getPersistentData().putLong(HELLFIRE_LINK_UNTIL,
					target.level().getGameTime() + ReliquaryConfig.hellfireTicks());
		}
	}

	/** 联动时间戳的 NBT 键（写在**目标**的持久化数据里；长度 = 狱火持续时间） */
	public static final String HELLFIRE_LINK_UNTIL = "summy_reliquary.hellfire_abaddon_until";

	/**
	 * 1.7.10：**只减掉 1 级狱火**（遁入暗影的接触标记联动用）。
	 *
	 * <p>按需求"只移除自己的一层，不要全部清空"：等级 >1 → 等级 −1（并刷新剩余时长）；
	 * 等级 =1 → 直接移除效果。别人叠的层因此原样保留。
	 *
	 * @return true 表示真的改动过狱火状态
	 */
	public static boolean removeOneStack(LivingEntity target) {
		if (target == null) {
			return false;
		}
		MobEffectInstance current = target.getEffect(SummyReliquary.HELLFIRE.get());
		if (current == null) {
			return false;
		}
		int amplifier = current.getAmplifier();
		int duration = current.getDuration();
		// 必须先移除再重新加上"更低一级"的实例：原版 addEffect 只会**升**不会**降**
		// （MobEffectInstance.update 在等级更低时直接忽略），所以直接 addEffect 是无效的。
		target.removeEffect(SummyReliquary.HELLFIRE.get());
		if (amplifier > 0) {
			// 剩余时长沿用原来那一份，避免"退一层"顺手把狱火刷新成满时长
			target.addEffect(new MobEffectInstance(SummyReliquary.HELLFIRE.get(), duration,
					amplifier - 1, false, true, true));
		}
		return true;
	}

	/** 该目标的狱火是否处于"亚巴顿联动"状态（打标记的那一刻起、到狱火持续时间结束） */
	public static boolean linkedWithAbaddon(LivingEntity target) {
		if (target == null) {
			return false;
		}
		long until = target.getPersistentData().getLong(HELLFIRE_LINK_UNTIL);
		return until > 0L && target.level().getGameTime() <= until;
	}
}
