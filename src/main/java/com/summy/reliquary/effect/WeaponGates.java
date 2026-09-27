package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 四把仪式武器的**统一门槛**（1.7.9）。
 *
 * <p>需求原话：「恶魔线两把匕首，天使线两把矛，均需要玩家达到其对应要求才可合成与使用，
 * 对于未达标则**完全禁用所有能力**」。对应要求就是各自的当前获取门槛：
 *
 * <table border="1">
 *     <tr><th>武器</th><th>判据</th></tr>
 *     <tr><td>献祭匕首</td><td>恶魔标记</td></tr>
 *     <tr><td>暗仪刺刀</td><td>恶魔标记 + 邪恶 700（{@link EvilUnlock#OCCULT_EYE}）已解锁</td></tr>
 *     <tr><td>圣光短矛 / 炽天使之枪</td><td>天使标记</td></tr>
 * </table>
 *
 * <p>「完全禁用」= 右键技能 / 投掷、攻击距离加成、10% 圣光召唤、以及**左键近战伤害**全部失效
 * （左键归零由 {@code ReliquaryEvents#onLivingHurt} 兜底，见 {@link #mainHandAttackAllowed}）。
 * 物品本身、配方与获取途径不受影响：签约与忏悔都不没收、不摘除这四把武器，只有创世纪会清掉它们。
 */
public final class WeaponGates {
	/** 圣光短矛的近战攻击距离加成（格） */
	public static final double HOLY_SPEAR_REACH = 0.5D;
	/** 炽天使之枪的近战攻击距离加成（格） */
	public static final double SERAPH_SPEAR_REACH = 1.0D;

	private WeaponGates() {
	}

	/** 是否是受门槛管辖的四把仪式武器之一 */
	public static boolean isRitualWeapon(Item item) {
		return item == SummyReliquary.SACRIFICIAL_DAGGER.get()
				|| item == SummyReliquary.DARK_ARTS.get()
				|| item == SummyReliquary.HOLY_SPEAR.get()
				|| item == SummyReliquary.SERAPH_SPEAR.get();
	}

	/** 是否是两把天使线长矛之一 */
	public static boolean isSpear(Item item) {
		return item == SummyReliquary.HOLY_SPEAR.get() || item == SummyReliquary.SERAPH_SPEAR.get();
	}

	/**
	 * 该实体对这把武器是否达标。
	 *
	 * <p>非本模组的四把武器一律放行（返回 true），其它模组 / 原版武器完全不受影响。
	 */
	public static boolean qualified(LivingEntity entity, Item item) {
		if (entity == null || item == null) {
			return true;
		}
		if (item == SummyReliquary.SACRIFICIAL_DAGGER.get()) {
			return PlayerFlags.isDemon(entity);
		}
		if (item == SummyReliquary.DARK_ARTS.get()) {
			return EvilUnlock.usable(entity, EvilUnlock.OCCULT_EYE);
		}
		if (isSpear(item)) {
			return PlayerFlags.hasAngel(entity);
		}
		return true;
	}

	/**
	 * 左键近战是否允许结算：手持的四把武器里任意一把未达标 → false。
	 *
	 * <p>判据只看**主手**（其余武器、空手一律 true），因为"完全禁用"针对的是这件武器本身。
	 */
	public static boolean mainHandAttackAllowed(LivingEntity entity) {
		if (!(entity instanceof net.minecraft.world.entity.player.Player player)) {
			return true;
		}
		ItemStack main = player.getMainHandItem();
		return main.isEmpty() || qualified(entity, main.getItem());
	}

	/** 未达标时的行动栏提示键（匕首分"没签过约"与"契约还不够深"两态） */
	public static String hintKey(LivingEntity entity, Item item) {
		if (item == SummyReliquary.DARK_ARTS.get() && PlayerFlags.isDemon(entity)) {
			return "item.summy-reliquary.demon.not_ready";
		}
		if (item == SummyReliquary.SACRIFICIAL_DAGGER.get() || item == SummyReliquary.DARK_ARTS.get()) {
			return "item.summy-reliquary.demon.required";
		}
		return "item.summy-reliquary.angel.required";
	}

	/**
	 * 手持长矛时的近战攻击距离加成（格）：达标才有，切走立刻失效。
	 *
	 * <p>由 {@code AttributeManager} 每 10 tick 重算一次（与光环 / 王冠同一套机制），
	 * 挂在 {@code ForgeMod.ENTITY_REACH} 的 ADDITION 修饰符上。
	 */
	public static double reachBonus(LivingEntity entity) {
		if (!(entity instanceof net.minecraft.world.entity.player.Player player)) {
			return 0.0D;
		}
		ItemStack main = player.getMainHandItem();
		if (main.isEmpty() || !qualified(entity, main.getItem())) {
			return 0.0D;
		}
		if (main.is(SummyReliquary.HOLY_SPEAR.get())) {
			return HOLY_SPEAR_REACH;
		}
		if (main.is(SummyReliquary.SERAPH_SPEAR.get())) {
			return SERAPH_SPEAR_REACH;
		}
		return 0.0D;
	}

	/**
	 * 手持圣光短矛且达标时，这一次命中会额外走一条**独立的 10% 圣光召唤**通道
	 * （与饰品位 15% / 25% 各自掷骰，同一次命中可能出两条光柱）。
	 */
	public static boolean holySpearProc(LivingEntity entity) {
		if (!(entity instanceof net.minecraft.world.entity.player.Player player)) {
			return false;
		}
		return ReliquaryConfig.enableHolyLight()
				&& player.getMainHandItem().is(SummyReliquary.HOLY_SPEAR.get())
				&& qualified(entity, SummyReliquary.HOLY_SPEAR.get());
	}
}
