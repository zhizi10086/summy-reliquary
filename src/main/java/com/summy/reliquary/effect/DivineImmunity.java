package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

/**
 * 圣心 / 神性提供的**效果免疫**（1.6.7）。
 *
 * <p>佩戴圣心、神性，或**恶魔线已解锁玄秘魔眼（邪恶度 700，1.8.0 起）**的玩家：
 * <ul>
 *     <li>免疫「黑暗」（原版 {@code DARKNESS}）—— 不论来源是玄秘魔眼的恐惧、监守者还是别的模组；</li>
 *     <li>免疫「恐惧」（{@code summy-reliquary:fear}）。</li>
 * </ul>
 *
 * <p>只免疫这两个**减益**：注视发光与玄秘魔眼的 ×1.3 增伤照旧生效（与 1.6.5 定的口径一致）。
 */
public final class DivineImmunity {
	private DivineImmunity() {
	}

	/**
	 * 佩戴圣心或神性的玩家（神性 1.6.7 起同步圣心的免疫）；
	 * 1.8.0 起，恶魔线**解锁玄秘魔眼**（邪恶度 ≥ 700，或解锁位图已置位）的玩家同样免疫。
	 */
	public static boolean immune(LivingEntity entity) {
		if (entity == null) {
			return false;
		}
		return CurioHelper.wears(entity, SummyReliquary.SACRED_HEART.get())
				|| Godhead.active(entity)
				|| EvilUnlock.OCCULT_EYE.unlockedFor(entity);
	}

	/** 这个效果是否被上面的免疫挡住 */
	public static boolean blocks(MobEffect effect) {
		return effect == MobEffects.DARKNESS || effect == SummyReliquary.FEAR.get();
	}

	/** 每秒清理身上已有的黑暗 / 恐惧（例如先中招、之后才戴上圣心或神性） */
	public static void tickPlayer(ServerPlayer player) {
		if (!immune(player)) {
			return;
		}
		if (player.hasEffect(MobEffects.DARKNESS)) {
			player.removeEffect(MobEffects.DARKNESS);
		}
		if (player.hasEffect(SummyReliquary.FEAR.get())) {
			player.removeEffect(SummyReliquary.FEAR.get());
		}
	}
}
