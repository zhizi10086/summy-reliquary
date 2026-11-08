package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * 「深渊领主」（1.6.7）：加护栏的恶魔侧饰品。
 *
 * <p>佩戴时，佩戴者造成的普通伤害会给目标叠加内部狱火状态。狱火的层数、
 * 护甲削减、周期伤害与亚巴顿联动均由 {@link HellfireState} 管理，
 * {@code hellfire} MobEffect 只负责视觉显示。
 */
public final class AbyssLord {
	private AbyssLord() {
	}

	public static boolean wears(LivingEntity entity) {
		return entity != null && ReliquaryConfig.enableAbyssLord()
				&& CurioHelper.wears(entity, SummyReliquary.ABYSS_LORD.get());
	}

	public static boolean shouldApply(DamageSource source) {
		return source != null
				&& !HolyLightEffect.isExactDamage(source)
				&& !HolyLightEffect.isCovenantEffect(source)
				&& !HolyLightEffect.isHellfire(source);
	}

	public static void applyStack(LivingEntity target) {
		applyStack(target, null);
	}

	public static void applyStack(LivingEntity target, LivingEntity attacker) {
		HellfireState.applyStack(target, attacker);
	}

	/** 保持旧 NBT 键名，供迁移期和现有调用方读取。 */
	public static final String HELLFIRE_LINK_UNTIL = HellfireState.LINK_UNTIL;

	/** 只减少一层内部狱火；不会刷新剩余持续时间。 */
	public static boolean removeOneStack(LivingEntity target) {
		return HellfireState.removeOneStack(target);
	}

	public static boolean linkedWithAbaddon(LivingEntity target) {
		return HellfireState.linkedWithAbaddon(target);
	}
}
