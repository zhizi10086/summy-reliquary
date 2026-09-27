package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;

/**
 * 饰品联动（1.6.10）：**必须同时佩戴两件**才生效的加成。
 *
 * <p>这里是全部联动的**唯一取值入口**：玩法（服务端）与物品提示（客户端）都调这几个方法，
 * 因此"提示里显示的数值"和"实际生效的数值"永远一致。判定统一走 {@link CurioHelper#wears}，
 * 客户端读的是本地玩家已同步的 Curios 栏，服务端读的是真实栏位。
 *
 * <p>神性联动：圣光几率、神圣斗篷无敌时长、圣心追踪半径（三件都在「加护」，可与神性同时成立）。
 * 亚巴顿联动：咒印碎裂伤害、玄秘魔眼恐惧归零、深渊领主狱火满级压制抗性。
 *
 * <p>1.7.10 追加「武器 × 饰品」联动：亚巴顿强化遁入暗影（时长 / 接触半径 / 强力半径）、
 * 圣心加快长矛投掷初速、神性强化炽天使之枪的落点圣光爆发。
 */
public final class Synergies {
	private Synergies() {
	}

	/** 是否同时戴着这两件（实体为空、任一未佩戴都返回 false） */
	public static boolean wearsBoth(LivingEntity entity, Item first, Item second) {
		return entity != null && CurioHelper.wears(entity, first) && CurioHelper.wears(entity, second);
	}

	// ==================== 神性联动 ====================

	/** 是否「神性 + 该件」同时佩戴 */
	public static boolean withGodhead(LivingEntity entity, Item item) {
		return wearsBoth(entity, SummyReliquary.GODHEAD.get(), item);
	}

	/** 圣光触发几率（百分比）：神性 + 圣光 → 配置的联动值，否则默认值 */
	public static int holyLightChancePercent(LivingEntity attacker) {
		return withGodhead(attacker, SummyReliquary.HOLY_LIGHT.get())
				? ReliquaryConfig.holyLightChancePercentGodhead()
				: ReliquaryConfig.holyLightChancePercent();
	}

	/** 神圣斗篷的无敌窗口（tick）：神性 + 斗篷 → 配置的联动值，否则默认值 */
	public static int holyMantleInvulnerableTicks(LivingEntity player) {
		return withGodhead(player, SummyReliquary.HOLY_MANTLE.get())
				? ReliquaryConfig.holyMantleInvulnerableTicksGodhead()
				: ReliquaryConfig.holyMantleInvulnerableTicks();
	}

	/** 圣心箭矢追踪半径（格）：神性 + 圣心 → 配置的联动值，否则默认值 */
	public static double sacredHeartArrowRadius(LivingEntity owner) {
		return withGodhead(owner, SummyReliquary.SACRED_HEART.get())
				? ReliquaryConfig.sacredHeartArrowRadiusGodhead()
				: ReliquaryConfig.sacredHeartArrowRadius();
	}

	// ==================== 亚巴顿联动 ====================

	/** 是否「亚巴顿 + 该件」同时佩戴 */
	public static boolean withAbaddon(LivingEntity entity, Item item) {
		return wearsBoth(entity, SummyReliquary.ABADDON.get(), item);
	}

	/** 黑心碎裂伤害：亚巴顿 + 咒印（60）> 咒印（40）> 默认（24） */
	public static double shatterDamage(LivingEntity entity) {
		return DemonPact.shatterDamage(entity);
	}

	/** 恐惧是否额外把目标移速归零（亚巴顿 + 玄秘魔眼） */
	public static boolean fearFreezes(LivingEntity wearer) {
		return ReliquaryConfig.fearFreezeWithAbaddon()
				&& withAbaddon(wearer, SummyReliquary.OCCULT_EYE.get());
	}

	/** 狱火满级是否压制目标抗性（亚巴顿 + 深渊领主） */
	public static boolean hellfireSuppressesResistance(LivingEntity attacker) {
		return ReliquaryConfig.hellfireHalvesResistanceWithAbaddon()
				&& withAbaddon(attacker, SummyReliquary.ABYSS_LORD.get());
	}

	// ==================== 武器 × 饰品联动（1.7.10） ====================

	/**
	 * 遁入暗影的判定时长（tick）：佩戴亚巴顿时 + 配置加成（默认 +20 tick = 技能 +1 秒）。
	 *
	 * <p>玩法的"加速时长＝判定时长"口径不变，所以这一项同时影响无敌与加速的持续时间。
	 */
	public static int shadowDashDurationTicks(LivingEntity wearer, boolean darkArts) {
		int base = ReliquaryConfig.shadowDashDurationTicks(darkArts);
		return Abaddon.wears(wearer) ? base + ReliquaryConfig.shadowDashAbaddonDurationTicks() : base;
	}

	/** 遁入暗影的接触判定半径（格）：佩戴亚巴顿时 + 配置加成 */
	public static double shadowDashContactRadius(LivingEntity wearer) {
		double base = ReliquaryConfig.shadowDashContactRadius();
		return Abaddon.wears(wearer) ? base + ReliquaryConfig.shadowDashAbaddonContactRadius() : base;
	}

	/** 遁入暗影的强力斩击半径（格）：佩戴亚巴顿时 + 配置加成 */
	public static double shadowDashHeavyRadius(LivingEntity wearer) {
		double base = ReliquaryConfig.shadowDashHeavyRadius();
		return Abaddon.wears(wearer) ? base + ReliquaryConfig.shadowDashAbaddonHeavyRadius() : base;
	}

	/**
	 * 长矛投掷的初速倍率：佩戴圣心时 1 + 配置百分比（默认 ×1.25），否则 ×1。
	 *
	 * <p>按需求**只作用于两把天使线长矛**；金刀片的投掷不走这里（也不吃圣心追踪）。
	 */
	public static float spearThrowVelocityMultiplier(LivingEntity thrower) {
		if (thrower == null || !CurioHelper.wears(thrower, SummyReliquary.SACRED_HEART.get())) {
			return 1.0F;
		}
		return 1.0F + ReliquaryConfig.sacredHeartThrowSpeedPercent() / 100.0F;
	}

	/** 落点圣光爆发的半径（格）：佩戴神性（且功能启用）时 + 配置加成 */
	public static double holyBurstRadius(LivingEntity thrower) {
		double base = ReliquaryConfig.holyLightBurstRadius();
		return Godhead.active(thrower) ? base + ReliquaryConfig.holyLightBurstRadiusGodhead() : base;
	}

	/** 落点圣光爆发的伤害（点）：佩戴神性（且功能启用）时 + 配置加成 */
	public static double holyBurstDamage(LivingEntity thrower) {
		double base = ReliquaryConfig.holyLightBurstDamage();
		return Godhead.active(thrower) ? base + ReliquaryConfig.holyLightBurstDamageGodhead() : base;
	}
}
