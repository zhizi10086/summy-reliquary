package com.summy.reliquary.effect;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 神性 / 亚巴顿的「负面免疫」（1.8.2）。
 *
 * <p>佩戴**神性**或**亚巴顿**时：
 * <ul>
 *     <li>拦住新施加的「原版负面效果」—— 判定＝效果注册名在 {@code minecraft} 命名空间下，且效果分类是
 *     {@link MobEffectCategory#HARMFUL}；</li>
 *     <li>每秒清掉身上已有的同类效果（先中招、后戴上的情况）。</li>
 * </ul>
 *
 * <p>本模组自己的 {@code fear}（恐惧）与 {@code hellfire}（狱火）都注册在 {@code summy-reliquary} 命名空间下，
 * 因此**不受影响** —— 恐惧的减益仍由 {@link DivineImmunity} 那套单独的口径处理。
 */
public final class DebuffImmunity {
	/** 本免疫只针对原版效果 */
	private static final String VANILLA_NAMESPACE = "minecraft";

	private DebuffImmunity() {
	}

	/** 佩戴神性或亚巴顿（两者占同一个「启示之座」栏位，不会同时成立） */
	public static boolean covers(LivingEntity entity) {
		return Godhead.active(entity) || Abaddon.wears(entity);
	}

	/** 是否是本免疫要挡住的「原版负面效果」 */
	public static boolean blocks(MobEffect effect) {
		if (effect == null || effect.getCategory() != MobEffectCategory.HARMFUL) {
			return false;
		}
		ResourceLocation key = BuiltInRegistries.MOB_EFFECT.getKey(effect);
		return key != null && VANILLA_NAMESPACE.equals(key.getNamespace());
	}

	/** 服务端每秒：清掉身上已有的原版负面效果 */
	public static void tickPlayer(ServerPlayer player) {
		if (player == null || !covers(player)) {
			return;
		}
		List<MobEffect> harmful = new ArrayList<>();
		for (MobEffectInstance instance : player.getActiveEffects()) {
			if (blocks(instance.getEffect())) {
				harmful.add(instance.getEffect());
			}
		}
		for (MobEffect effect : harmful) {
			player.removeEffect(effect);
		}
	}
}
