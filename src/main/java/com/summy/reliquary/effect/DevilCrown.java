package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.world.entity.LivingEntity;

/**
 * 「恶魔王冠」（1.7.1）：光环栏饰品，**需同时佩戴撒旦圣经**才生效。
 *
 * <p>两条效果：
 * <ul>
 *     <li><b>六项属性</b>（移速 +20% / 攻速 +0.4 / 近战 +6 / 生命 −4 / 护甲 −4 / 韧性 −4）：
 *     由 {@code AttributeManager} 每秒重算，条件＝{@link #active}；</li>
 *     <li><b>受伤加成</b>（邪恶 666 = `hell_locked` 后）：按"已损失生命"每 10% 一档 +3%，
 *     上限 +15%（{@link #damageBonusPercent}），挂在 {@code SpiritAltarSet.onLivingHurt} 的同一层。</li>
 * </ul>
 */
public final class DevilCrown {
	private DevilCrown() {
	}

	/** 属性生效条件：总开关 + 佩戴王冠 + 佩戴撒旦圣经 */
	public static boolean active(LivingEntity entity) {
		return entity != null && ReliquaryConfig.enableDevilCrown()
				&& CurioHelper.wears(entity, SummyReliquary.DEVIL_CROWN.get())
				&& CurioHelper.wears(entity, SummyReliquary.SATANIC_BIBLE.get());
	}

	/**
	 * 受伤加成（百分比）：`hell_locked` 后按"已损失生命比例"分档，每 10% 一档、每档配置值（默认 3%），
	 * 上限配置值（默认 15%）。不满足条件（开关关 / 未戴王冠 / 未戴圣经 / 未到 666）返回 0。
	 */
	public static double damageBonusPercent(LivingEntity entity) {
		if (!active(entity) || !PlayerFlags.isHellLocked(entity)) {
			return 0.0D;
		}
		float maxHealth = Math.max(1.0F, entity.getMaxHealth());
		double lost = Math.max(0.0D, 1.0D - entity.getHealth() / maxHealth);
		int steps = (int) Math.floor(lost * 10.0D);
		double perDecile = ReliquaryConfig.devilCrownDamagePerDecilePercent();
		double cap = ReliquaryConfig.devilCrownDamageCapPercent();
		return Math.max(0.0D, Math.min(cap, perDecile * steps));
	}
}
