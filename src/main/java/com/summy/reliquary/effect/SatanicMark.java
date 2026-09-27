package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.world.entity.LivingEntity;

/**
 * 「咒印」（1.6.2）：灵台栏的恶魔侧饰品。
 *
 * <p>它**继承天使线灵台套装的全部属性**（生命 +10、套装减伤、发光与对发光加伤、20% 免死），
 * 但把灵魂的 +3 魂心**转化成黑心**（所以戴它时没有魂心池，黑心上限 +3 心）；
 * 佩戴期间邪恶度不再自然衰减，且黑心碎裂伤害从 24 提到 40。
 */
public final class SatanicMark {
	private SatanicMark() {
	}

	/** 是否佩戴着咒印 */
	public static boolean wears(LivingEntity entity) {
		return entity != null && CurioHelper.wears(entity, SummyReliquary.THE_MARK.get());
	}

	/** 咒印带来的黑心池上限加成（心数） */
	public static double blackHeartHearts(LivingEntity entity) {
		return wears(entity) ? ReliquaryConfig.markBlackHearts() : 0.0D;
	}
}
