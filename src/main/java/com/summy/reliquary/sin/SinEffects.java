package com.summy.reliquary.sin;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.effect.AttributeManager;
import com.summy.reliquary.effect.RiceHungerLock;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 七罪的具体效果（1.4.0）。
 *
 * <p>三条总规则：
 * <ol>
 *     <li>只有**佩戴着七罪之源**时才计数、才生效（卸下时状态保留但不生效）；</li>
 *     <li>「已激活」= 全部效果；「已赎罪」= 保留该罪的增益、去掉负面（逐罪见各方法注释）；</li>
 *     <li>数值全部来自 {@code config/summy_reliquary-common.toml} 的 {@code [sins]} 段。</li>
 * </ol>
 */
public final class SinEffects {
	/** 七罪属性修饰符的固定 UUID */
	private static final UUID SLOTH_MOVEMENT = id(21);
	private static final UUID SLOTH_ATTACK_SPEED = id(22);
	private static final UUID LUST_ARMOR = id(23);
	private static final UUID LUST_TOUGHNESS = id(24);

	/** 原版四件护甲位（脱下目标 / 自己护甲时用） */
	private static final EquipmentSlot[] ARMOR_SLOTS = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
	};

	/** 嫉妒强制敌对的中立生物（UUID → 实体），用于离开范围或赎罪后清除目标 */
	private static final Map<UUID, Mob> ENVY_HOSTILES = new HashMap<>();

	/** 防递归：暴怒自伤再走一遍伤害管线时不重复套用七罪加成 */
	private static boolean applyingSelfHit;

	// ===== 自检计数 =====
	private static int wrathSelfHitCount;
	private static int lustStripArmorCount;
	private static int lustSelfStripCount;

	private SinEffects() {
	}

	private static UUID id(int index) {
		return UUID.fromString(String.format("6f5c1a2e-0000-4a0b-9d1e-%012d", index));
	}

	// ==================== 状态判定 ====================

	/** 是否佩戴着七罪之源 */
	public static boolean wearingSource(LivingEntity entity) {
		return CurioHelper.wears(entity, SummyReliquary.SOURCE_OF_SINS.get());
	}

	/** 是否佩戴着美德（1.4.3 起：美德继承七罪「赎罪后」保留的增益） */
	public static boolean wearingVirtues(LivingEntity entity) {
		return CurioHelper.wears(entity, SummyReliquary.VIRTUES.get());
	}

	/**
	 * 是否佩戴着撒旦圣经（1.6.1）。
	 *
	 * <p>撒旦圣经**继承七罪的全部加成、屏蔽全部负面**：它只在"增益侧"（{@link #awakened}）
	 * 参与判定，所有减益仍然只走只认七罪之源的 {@link #active} / {@link #effectsEnabled}，
	 * 所以戴上它时傲慢的受伤增加、贪婪的一成惩罚、暴食的四条负面等会自动全部失效。
	 */
	public static boolean wearingSatanicBible(LivingEntity entity) {
		return CurioHelper.wears(entity, SummyReliquary.SATANIC_BIBLE.get());
	}

	/** 效果总开关 + 佩戴七罪之源 */
	public static boolean effectsEnabled(LivingEntity entity) {
		return ReliquaryConfig.enableSinEffects() && wearingSource(entity);
	}

	/** 佩戴七罪之源或美德（用于伤害管线的前置判断；触发计数仍只认 effectsEnabled） */
	private static boolean anyWearer(LivingEntity entity) {
		return ReliquaryConfig.enableSinEffects()
				&& (wearingSource(entity) || wearingVirtues(entity) || wearingSatanicBible(entity));
	}

	/** 已激活且未赎罪（全部效果） */
	public static boolean active(LivingEntity entity, Sin sin) {
		return effectsEnabled(entity) && SinManager.state(entity, sin) == SinManager.SinState.ACTIVATED;
	}

	/**
	 * 已激活或已赎罪（赎罪后仍保留的那部分效果）。
	 *
	 * <p>1.4.3 起：佩戴**美德**时同样成立（继承七罪赎罪后的增益）；但触发计数与所有减益
	 * 仍然只认佩戴七罪之源（见 {@link #effectsEnabled} 与 {@link #active}）。
	 */
	public static boolean awakened(LivingEntity entity, Sin sin) {
		if (!ReliquaryConfig.enableSinEffects()) {
			return false;
		}
		if (wearingSource(entity)) {
			return SinManager.state(entity, sin) != SinManager.SinState.UNACTIVATED;
		}
		// 美德：必须"七罪全部已赎清"才继承赎罪后的增益（1.4.4 的双重门之一）
		if (wearingVirtues(entity)) {
			return SinManager.allRedeemed(entity)
					&& SinManager.state(entity, sin) != SinManager.SinState.UNACTIVATED;
		}
		// 撒旦圣经（1.6.1）：继承七罪的"全部"加成（激活即生效；转化时已经全部激活）
		if (wearingSatanicBible(entity)) {
			return SinManager.state(entity, sin) != SinManager.SinState.UNACTIVATED;
		}
		return false;
	}

	/** 已经赎罪 */
	public static boolean redeemed(LivingEntity entity, Sin sin) {
		return SinManager.state(entity, sin) == SinManager.SinState.REDEEMED;
	}

	/**
	 * 该罪是否按「赎罪后」那一档结算（1.6.2）。
	 *
	 * <p><b>1.7.2 改</b>：只有**真正已赎罪**的罪才算这一档。以前"戴撒旦圣经且该罪已激活"也算，
	 * 于是圣经能白拿赎罪后的额外提升（怠惰抗性 II、暴怒随机上限 2.0）；现在圣经只继承**激活档**的增益
	 * （暴怒 0.5~1.5、怠惰抗性 I），同时自伤与减速本来就只认七罪之源、已经正确移除。
	 * 佩戴美德必然"七罪全赎"，所以美德仍走赎罪档，不受这次改动影响。
	 */
	public static boolean tierRedeemed(LivingEntity entity, Sin sin) {
		return redeemed(entity, sin);
	}

	// ==================== 属性修正 ====================

	/** 由 {@code AttributeManager.apply} 每次重算时调用 */
	public static void applyAttributes(LivingEntity entity) {
		double slothSlowdown = 0.0D;
		double lustArmor = 0.0D;
		if (effectsEnabled(entity)) {
			// 怠惰（未赎罪）：移动 / 攻击速度 -20%（挖掘速度另走 BreakSpeed）
			if (active(entity, Sin.SLOTH)) {
				slothSlowdown = ReliquaryConfig.slothSlowdownPercent() / 100.0D;
			}
			// 色欲（未赎罪）：护甲值与盔甲韧性 -30%
			if (active(entity, Sin.LUST)) {
				lustArmor = ReliquaryConfig.lustArmorReductionPercent() / 100.0D;
			}
		}
		AttributeManager.applyModifier(entity, Attributes.MOVEMENT_SPEED, SLOTH_MOVEMENT,
				"sin_sloth_movement", -slothSlowdown, AttributeModifier.Operation.MULTIPLY_TOTAL);
		AttributeManager.applyModifier(entity, Attributes.ATTACK_SPEED, SLOTH_ATTACK_SPEED,
				"sin_sloth_attack_speed", -slothSlowdown, AttributeModifier.Operation.MULTIPLY_TOTAL);
		AttributeManager.applyModifier(entity, Attributes.ARMOR, LUST_ARMOR,
				"sin_lust_armor", -lustArmor, AttributeModifier.Operation.MULTIPLY_TOTAL);
		AttributeManager.applyModifier(entity, Attributes.ARMOR_TOUGHNESS, LUST_TOUGHNESS,
				"sin_lust_toughness", -lustArmor, AttributeModifier.Operation.MULTIPLY_TOTAL);
	}

	/** 怠惰（未赎罪）时挖掘速度乘数（供 BreakSpeed 使用） */
	public static float breakSpeedFactor(Player player) {
		if (!active(player, Sin.SLOTH)) {
			return 1.0F;
		}
		return 1.0F - ReliquaryConfig.slothSlowdownPercent() / 100.0F;
	}

	/** 暴食（未赎罪）会让「白饭」失效：不再锁饥饿、也不再禁止进食 */
	public static boolean gluttonyDisablesRice(Player player) {
		return active(player, Sin.GLUTTONY);
	}

	// ==================== 伤害管线 ====================

	/**
	 * 攻击者是本模组玩家时的伤害修正（傲慢 / 嫉妒 / 暴怒 / 贪婪的增伤、色欲的脱甲）。
	 *
	 * @param victim 受击目标（护甲脱落等需要它）
	 */
	/** 旧签名（自检等内部调用）：默认按近战处理 */
	public static float modifyOutgoingDamage(ServerPlayer attacker, LivingEntity victim, float amount) {
		return modifyOutgoingDamage(attacker, victim, attacker.damageSources().playerAttack(attacker), amount);
	}

	/**
	 * 攻击者是本模组玩家时的伤害修正（1.4.4 起按乘区口径结算）。
	 *
	 * <p>近战（伤害源直接实体就是攻击者）：{@code × (1 + 傲慢加成 + 贪婪增伤) × (1 + 嫉妒) × 暴怒随机 × 贪婪惩罚}；
	 * 远程：{@code × (1 + 嫉妒) × 暴怒随机 × 贪婪惩罚}（傲慢与贪婪增伤只作用于近战，贪婪惩罚对全伤害生效）。
	 */
	public static float modifyOutgoingDamage(ServerPlayer attacker, LivingEntity victim,
			net.minecraft.world.damagesource.DamageSource source, float amount) {
		if (applyingSelfHit || !anyWearer(attacker)) {
			return amount;
		}
		float result = amount;
		boolean melee = source != null && source.getDirectEntity() == attacker;

		// ① 近战乘区：傲慢 + 贪婪增伤，两者相加后同乘
		double meleeBonus = 0.0D;
		if (melee && awakened(attacker, Sin.PRIDE) && victim.getMaxHealth() > 0.0F) {
			float missing = 1.0F - Math.max(0.0F, victim.getHealth()) / victim.getMaxHealth();
			meleeBonus += missing * 100.0F * ReliquaryConfig.prideDamagePerPercent() / 100.0D;
		}
		int diamonds = countDiamonds(attacker);
		// 1.7.2：阈值改成「达到即给」 —— 36 颗（= 阈值）就给增伤、35 及以下才吃惩罚
		if (melee && awakened(attacker, Sin.GREED) && diamonds >= ReliquaryConfig.greedDiamondThreshold()) {
			int bonus = Math.min(ReliquaryConfig.greedDamageCapPercent(),
					diamonds * ReliquaryConfig.greedDamagePerDiamondPercent());
			meleeBonus += bonus / 100.0D;
		}
		if (meleeBonus > 0.0D) {
			result *= (float) (1.0D + meleeBonus);
		}

		// ② 独立乘区：嫉妒（对全部伤害生效）
		if (awakened(attacker, Sin.ENVY) && envyTarget(attacker, victim)) {
			result *= 1.0F + ReliquaryConfig.envyBonusPercent() / 100.0F;
		}

		// ③ 独立乘区：暴怒随机（对全部伤害生效；未赎罪时还有几率自伤）
		if (awakened(attacker, Sin.WRATH)) {
			double min = ReliquaryConfig.wrathRandomMin();
			// 1.7.2：只有**真正已赎罪**（含美德，它本身就是全赎罪）才取赎罪后的上限 2.0；
			// 撒旦圣经只继承"激活档" → 仍是 0.5~1.5（1.6.2 那版"圣经也算赎罪档"已废弃）
			double max = tierRedeemed(attacker, Sin.WRATH)
					? ReliquaryConfig.wrathRandomMaxRedeemed()
					: ReliquaryConfig.wrathRandomMax();
			double roll = min + attacker.getRandom().nextDouble() * Math.max(0.0D, max - min);
			result *= (float) roll;
			if (active(attacker, Sin.WRATH)
					&& attacker.getRandom().nextDouble() * 100.0D < ReliquaryConfig.wrathSelfHitPercent()) {
				selfHit(attacker, (float) (result * ReliquaryConfig.wrathSelfHitMultiplier()));
			}
		}

		// ④ 贪婪惩罚：钻石**少于**阈值（≤35）时伤害降到一成（对全部伤害生效；赎罪后移除）
		if (awakened(attacker, Sin.GREED) && diamonds < ReliquaryConfig.greedDiamondThreshold()
				&& active(attacker, Sin.GREED)) {
			result *= (float) ReliquaryConfig.greedLowDiamondDamageFactor();
		}

		// ⑤ 色欲：攻击有几率脱下目标随机一件护甲（与伤害乘区无关）
		if (awakened(attacker, Sin.LUST)
				&& attacker.getRandom().nextDouble() * 100.0D < ReliquaryConfig.lustStripArmorPercent()
				&& stripArmor(victim)) {
			lustStripArmorCount++;
		}
		return result;
	}

	/** 受害者是本模组玩家时的伤害修正（傲慢的受伤增加、色欲的受击脱甲） */
	public static float modifyIncomingDamage(ServerPlayer victim, float amount) {
		if (applyingSelfHit || !anyWearer(victim)) {
			return amount;
		}
		float result = amount;

		// 傲慢（未赎罪）：受到的所有伤害 +50%
		if (active(victim, Sin.PRIDE)) {
			result *= 1.0F + ReliquaryConfig.prideIncomingDamagePercent() / 100.0F;
		}

		// 色欲（未赎罪）：受击有几率把自身一件护甲脱到背包
		if (active(victim, Sin.LUST)
				&& victim.getRandom().nextDouble() * 100.0D < ReliquaryConfig.lustSelfStripPercent()
				&& stripSelfArmorToInventory(victim)) {
			lustSelfStripCount++;
		}
		return result;
	}

	/**
	 * 暴怒自伤：按配置倍率打在自己身上（带防递归标记，避免再次套用七罪加成）。
	 *
	 * <p>1.8.0：自伤**永不致死** —— 结算前把伤害钳到「当前生命 − 1」，血量 ≤ 1 时直接跳过。
	 * 仍走原版伤害管线，所以护甲 / 保护 / 抗性照常减免，只是不会把人打死。
	 */
	private static void selfHit(ServerPlayer player, float amount) {
		if (applyingSelfHit || amount <= 0.0F || !player.isAlive()) {
			return;
		}
		float health = player.getHealth();
		if (health <= 1.0F) {
			return;
		}
		float safeAmount = Math.min(amount, health - 1.0F);
		if (safeAmount <= 0.0F) {
			return;
		}
		applyingSelfHit = true;
		try {
			wrathSelfHitCount++;
			player.hurt(player.damageSources().generic(), safeAmount);
		} finally {
			applyingSelfHit = false;
		}
	}

	// ==================== 触发事件 ====================

	/** 击杀生物（傲慢的中立击杀、暴怒的累计击杀、暴食的击杀回复） */
	public static void onKill(ServerPlayer killer, LivingEntity victim) {
		if (victim instanceof Player) {
			return;
		}
		// 触发计数仍然只认「佩戴七罪之源」
		if (effectsEnabled(killer)) {
			// 暴怒：任意非玩家生物
			int wrathKills = SinProgress.add(killer, SinProgress.WRATH_KILLS, 1);
			if (wrathKills >= ReliquaryConfig.wrathKillRequired()) {
				SinManager.activate(killer, Sin.WRATH, false);
			}
			// 傲慢：中立 / 友善（非 Enemy）生物
			// 末影人按需求列入黑名单：击杀它不计入傲慢（它本身也被原版标记为敌对，这里是显式保险）
			if (!(victim instanceof Enemy) && !(victim instanceof net.minecraft.world.entity.monster.EnderMan)) {
				int neutralKills = SinProgress.add(killer, SinProgress.PRIDE_KILLS, 1);
				if (neutralKills >= ReliquaryConfig.prideKillRequired()) {
					SinManager.activate(killer, Sin.PRIDE, false);
				}
			}
		}
		// 暴食：击杀回复（赎罪后保留的增益；戴美德 / 撒旦圣经时同样生效，1.6.2 修）
		if (awakened(killer, Sin.GLUTTONY)) {
			Feed.feed(killer, ReliquaryConfig.gluttonyKillHeal(), ReliquaryConfig.gluttonyKillFood());
		}
	}

	/** 玩家开始睡觉（怠惰的早睡计数） */
	public static void onSleep(ServerPlayer player) {
		if (!effectsEnabled(player) || SinManager.state(player, Sin.SLOTH) != SinManager.SinState.UNACTIVATED) {
			return;
		}
		if (!isEarlySleep(player)) {
			return;
		}
		int sleeps = SinProgress.add(player, SinProgress.SLOTH_SLEEPS, 1);
		if (sleeps >= ReliquaryConfig.slothSleepRequired()) {
			SinManager.activate(player, Sin.SLOTH, false);
		} else {
			player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
					"message.summy-reliquary.sin.sloth.progress", sleeps,
					ReliquaryConfig.slothSleepRequired()), true);
		}
	}

	/** 入睡时刻是否算「早睡」：游戏时钟早于配置小时（默认 20 = 晚上 8 点） */
	public static boolean isEarlySleep(ServerPlayer player) {
		long time = player.level().getDayTime() % 24000L;
		long hour = (time / 1000L + 6L) % 24L;
		return hour < ReliquaryConfig.slothSleepBeforeHour();
	}

	/** 动物繁殖成功（色欲的繁殖计数） */
	public static void onBred(ServerPlayer player) {
		if (!effectsEnabled(player) || SinManager.state(player, Sin.LUST) != SinManager.SinState.UNACTIVATED) {
			return;
		}
		int breeds = SinProgress.add(player, SinProgress.LUST_BREEDS, 1);
		if (breeds >= ReliquaryConfig.lustBreedRequired()) {
			SinManager.activate(player, Sin.LUST, false);
		}
	}

	/** 玩家吃 / 喝下某件物品（暴食的高饱和进食计数） */
	public static void onItemConsumed(ServerPlayer player, ItemStack stack) {
		if (!effectsEnabled(player) || SinManager.state(player, Sin.GLUTTONY) != SinManager.SinState.UNACTIVATED) {
			return;
		}
		if (!stack.isEdible()) {
			return;
		}
		net.minecraft.world.food.FoodProperties food = stack.getFoodProperties(player);
		if (food == null) {
			return;
		}
		double saturation = food.getNutrition() * food.getSaturationModifier() * 2.0D;
		if (saturation <= ReliquaryConfig.gluttonySaturationThreshold()) {
			return;
		}
		int meals = SinProgress.add(player, SinProgress.GLUTTONY_MEALS, 1);
		if (meals >= ReliquaryConfig.gluttonyMealRequired()) {
			SinManager.activate(player, Sin.GLUTTONY, false);
		}
	}

	/** 玩家死亡（贪婪的死亡扣钻石） */
	public static void onPlayerDeath(ServerPlayer player) {
		// 赎罪后移除死亡惩罚
		if (!active(player, Sin.GREED)) {
			return;
		}
		int min = ReliquaryConfig.greedDeathDiamondMin();
		int max = Math.max(min, ReliquaryConfig.greedDeathDiamondMax());
		int want = min + player.getRandom().nextInt(max - min + 1);
		int removed = removeDiamonds(player, want);
		SummyReliquary.LOGGER.info("[Summy Reliquary] 贪婪吞噬了 {} 的 {} 颗钻石",
				player.getName().getString(), removed);
	}

	// ==================== 每秒维护 ====================

	/** 服务端每秒调用（佩戴七罪之源时）：检测触发条件并维护持续性效果 */
	public static void tickPlayer(ServerPlayer player) {
		// 卸下七罪之源时清掉嫉妒造成的仇恨
		if (!effectsEnabled(player)) {
			clearEnvyTargetsOf(player);
			// 戴美德：只维护"赎罪后保留的增益"（惰性续期怠惰的抗性提升）
			maintainRedeemedBlessings(player);
			return;
		}

		// 贪婪：记录钻石峰值 + 超过阈值即触发
		int diamonds = countDiamonds(player);
		if (diamonds > SinProgress.get(player, SinProgress.GREED_PEAK_DIAMONDS)) {
			SinProgress.set(player, SinProgress.GREED_PEAK_DIAMONDS, diamonds);
		}
		// 1.7.2：达到阈值即觉醒（与增伤判定同一口径）
		if (diamonds >= ReliquaryConfig.greedDiamondThreshold()) {
			SinManager.activate(player, Sin.GREED, false);
		}

		// 色欲：隐藏触发条件——背包里带着末地烛
		if (SinManager.state(player, Sin.LUST) == SinManager.SinState.UNACTIVATED && countItem(player, Items.END_ROD) > 0) {
			SinManager.activate(player, Sin.LUST, true);
		}

		// 嫉妒：观察到满足增伤条件的生物即触发
		if (SinManager.state(player, Sin.ENVY) == SinManager.SinState.UNACTIVATED && observeEnvyTarget(player)) {
			SinManager.activate(player, Sin.ENVY, false);
		}

		// 暴食：装备白饭视为直接满足触发条件
		if (SinManager.state(player, Sin.GLUTTONY) == SinManager.SinState.UNACTIVATED && RiceHungerLock.wearsRice(player)) {
			SinManager.activate(player, Sin.GLUTTONY, false);
		}

		// 赎罪后保留的增益（怠惰抗性提升）——佩戴七罪之源或美德都会走这里
		maintainRedeemedBlessings(player);

		// 暴食：饥饿上限、周期扣减、低饥饿的缓慢 + 虚弱
		if (active(player, Sin.GLUTTONY)) {
			Gluttony.upkeep(player);
		}

		// 嫉妒：维护中立生物对玩家的敌意
		maintainEnvyTargets(player);
	}

	/** 「赎罪后保留的增益」的持续维护（目前只有怠惰的抗性提升）；减益由 active(...) 单独控制 */
	private static void maintainRedeemedBlessings(ServerPlayer player) {
		if (!awakened(player, Sin.SLOTH)) {
			return;
		}
		// 1.7.2：只有**真正已赎罪**（含美德）才给抗性 II；撒旦圣经只继承"激活档" → 抗性 I
		int amplifier = tierRedeemed(player, Sin.SLOTH)
				? ReliquaryConfig.slothResistanceAmplifierRedeemed()
				: ReliquaryConfig.slothResistanceAmplifier();
		player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 60, amplifier, false, false));
	}

	/**
	 * 服务端**每 tick**调用：只做"不能超过上限"的即时压制。
	 *
	 * <p>暴食的饥饿上限必须每 tick 压，否则吃完东西后会先涨到 19/20、下一次每秒维护才被压回，
	 * 玩家就会看到"短暂恢复到 18 以上"。这里只做两行判断，代价可以忽略。
	 */
	public static void tickPlayerEveryTick(ServerPlayer player) {
		if (!active(player, Sin.GLUTTONY)) {
			return;
		}
		net.minecraft.world.food.FoodData food = player.getFoodData();
		int cap = Gluttony.foodCap(player);
		if (food.getFoodLevel() > cap) {
			food.setFoodLevel(cap);
		}
		if (food.getSaturationLevel() > cap) {
			food.setSaturation(cap);
		}
	}

	/** 嫉妒的增伤条件：穿有任意护甲 / 最大生命高于自身 / 身上有正面效果 */
	public static boolean envyTarget(LivingEntity self, LivingEntity target) {
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			if (!target.getItemBySlot(slot).isEmpty()) {
				return true;
			}
		}
		for (MobEffectInstance effect : target.getActiveEffects()) {
			if (effect.getEffect().isBeneficial()) {
				return true;
			}
		}
		return false;
	}

	/** 「观察到」= 半径内 + 视线 30° 锥内 */
	private static boolean observeEnvyTarget(ServerPlayer player) {
		double radius = ReliquaryConfig.envyObserveRadius();
		Vec3 look = player.getLookAngle();
		if (look.lengthSqr() < 1.0E-6D) {
			return false;
		}
		look = look.normalize();
		double cosLimit = Math.cos(Math.toRadians(30.0D));
		for (LivingEntity target : player.level().getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate(radius), candidate -> candidate != player && candidate.isAlive())) {
			Vec3 direction = target.getEyePosition().subtract(player.getEyePosition());
			if (direction.lengthSqr() > radius * radius || direction.lengthSqr() < 1.0E-6D) {
				continue;
			}
			if (direction.normalize().dot(look) < cosLimit) {
				continue;
			}
			if (envyTarget(player, target)) {
				return true;
			}
		}
		return false;
	}

	/** 嫉妒（未赎罪）：把范围内有战斗 AI 的中立生物的目标设为该玩家 */
	private static void maintainEnvyTargets(ServerPlayer player) {
		if (!active(player, Sin.ENVY)) {
			clearEnvyTargetsOf(player);
			return;
		}
		if (player.tickCount % ReliquaryConfig.envyHostileRefreshTicks() != 0) {
			return;
		}
		maintainEnvyTargetsNow(player);
	}

	/** 立刻执行一次仇恨维护（自检用，不受刷新间隔限制） */
	public static void forceEnvyMaintenance(ServerPlayer player) {
		if (!active(player, Sin.ENVY)) {
			clearEnvyTargetsOf(player);
			return;
		}
		maintainEnvyTargetsNow(player);
	}

	/** 当前被嫉妒强制敌对的中立生物数量（自检用） */
	public static int envyHostileCount() {
		return ENVY_HOSTILES.size();
	}

	private static void maintainEnvyTargetsNow(ServerPlayer player) {
		double radius = ReliquaryConfig.envyHostileRadius();
		for (Mob mob : player.level().getEntitiesOfClass(Mob.class,
				player.getBoundingBox().inflate(radius), SinEffects::canBeEnvyHostile)) {
			if (!mob.canAttack(player)) {
				continue;
			}
			mob.setTarget(player);
			ENVY_HOSTILES.put(mob.getUUID(), mob);
		}
	}

	/** 清除由嫉妒造成的仇恨（玩家卸下七罪之源、赎罪、或离开范围时） */
	private static void clearEnvyTargetsOf(ServerPlayer player) {
		Iterator<Map.Entry<UUID, Mob>> iterator = ENVY_HOSTILES.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Mob> entry = iterator.next();
			Mob mob = entry.getValue();
			if (mob == null || !mob.isAlive()) {
				iterator.remove();
				continue;
			}
			if (mob.getTarget() == player) {
				mob.setTarget(null);
			}
			iterator.remove();
		}
	}

	/** 自检 / 关服时清空嫉妒仇恨表 */
	public static void clearAllEnvyTargets() {
		for (Mob mob : ENVY_HOSTILES.values()) {
			if (mob != null && mob.isAlive()) {
				mob.setTarget(null);
			}
		}
		ENVY_HOSTILES.clear();
	}

	/** 玩家退出时清掉他造成的仇恨 */
	public static void forget(ServerPlayer player) {
		clearEnvyTargetsOf(player);
	}

	/** 「有战斗 AI」的中立生物：非 Enemy，且目标选择器里有近战 / 远程 / 选择攻击目标的行为 */
	public static boolean canBeEnvyHostile(Mob mob) {
		if (mob instanceof Enemy || !mob.isAlive()) {
			return false;
		}
		for (WrappedGoal wrapped : mob.targetSelector.getAvailableGoals()) {
			if (wrapped.getGoal() instanceof MeleeAttackGoal
					|| wrapped.getGoal() instanceof RangedAttackGoal
					|| wrapped.getGoal() instanceof NearestAttackableTargetGoal<?>) {
				return true;
			}
		}
		return false;
	}

	// ==================== 工具 ====================

	/** 统计背包（36 格 + 副手 + 盔甲）里的某件物品总数 */
	public static int countItem(Player player, net.minecraft.world.item.Item item) {
		int total = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.is(item)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/** 背包里的钻石数量 */
	public static int countDiamonds(Player player) {
		return countItem(player, Items.DIAMOND);
	}

	/** 随机扣掉 n 颗钻石（跨堆叠，从后往前），返回实际扣掉的数量 */
	public static int removeDiamonds(Player player, int amount) {
		int removed = 0;
		for (int slot = player.getInventory().getContainerSize() - 1; slot >= 0 && removed < amount; slot--) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.is(Items.DIAMOND)) {
				continue;
			}
			int take = Math.min(stack.getCount(), amount - removed);
			stack.shrink(take);
			removed += take;
		}
		return removed;
	}

	/** 脱下目标随机一件护甲（掉在原地），成功返回 true */
	private static boolean stripArmor(LivingEntity target) {
		List<EquipmentSlot> filled = filledArmorSlots(target);
		if (filled.isEmpty()) {
			return false;
		}
		EquipmentSlot slot = filled.get(target.getRandom().nextInt(filled.size()));
		ItemStack stack = target.getItemBySlot(slot).copy();
		target.setItemSlot(slot, ItemStack.EMPTY);
		target.spawnAtLocation(stack, 0.5F);
		return true;
	}

	/** 把自身随机一件护甲脱到背包里（背包满则掉在地上），成功返回 true */
	private static boolean stripSelfArmorToInventory(ServerPlayer player) {
		List<EquipmentSlot> filled = filledArmorSlots(player);
		if (filled.isEmpty()) {
			return false;
		}
		EquipmentSlot slot = filled.get(player.getRandom().nextInt(filled.size()));
		ItemStack stack = player.getItemBySlot(slot).copy();
		player.setItemSlot(slot, ItemStack.EMPTY);
		if (!player.getInventory().add(stack)) {
			player.drop(stack, false);
		}
		return true;
	}

	private static List<EquipmentSlot> filledArmorSlots(LivingEntity entity) {
		List<EquipmentSlot> filled = new ArrayList<>(ARMOR_SLOTS.length);
		for (EquipmentSlot slot : ARMOR_SLOTS) {
			if (!entity.getItemBySlot(slot).isEmpty()) {
				filled.add(slot);
			}
		}
		return filled;
	}

	// ==================== 暴食 / 进食辅助 ====================

	/** 进食与饥饿值相关的小工具 */
	private static final class Feed {
		private Feed() {
		}

		/** 回复生命与饥饿值 / 饱和度（不越过暴食的饥饿上限） */
		static void feed(ServerPlayer player, double heal, int foodAmount) {
			if (heal > 0.0D) {
				player.heal((float) heal);
			}
			if (foodAmount <= 0) {
				return;
			}
			FoodData food = player.getFoodData();
			int cap = Math.min(Gluttony.foodCap(player), RiceHungerLock.foodCap(player));
			food.setFoodLevel(Math.min(cap, food.getFoodLevel() + foodAmount));
			food.setSaturation(Math.min(cap, food.getSaturationLevel() + foodAmount));
		}
	}

	/** 暴食的持续维护（饥饿上限、周期扣减、低饥饿的缓慢 / 虚弱） */
	private static final class Gluttony {
		private Gluttony() {
		}

		/** 暴食未赎罪时的饥饿值上限 = min(配置上限, 当前环境下限) */
		static int foodCap(ServerPlayer player) {
			return Math.min(ReliquaryConfig.gluttonyFoodCap(), RiceHungerLock.foodCap(player));
		}

		static void upkeep(ServerPlayer player) {
			FoodData food = player.getFoodData();
			int cap = foodCap(player);
			if (food.getFoodLevel() > cap) {
				food.setFoodLevel(cap);
			}
			// 每 N 秒强制扣 1 点饥饿值与饱和度
			long period = ReliquaryConfig.gluttonyDrainSeconds() * 20L;
			if (period > 0L && player.level().getGameTime() % period == 0L) {
				food.setFoodLevel(Math.max(0, food.getFoodLevel() - 1));
				food.setSaturation(Math.max(0.0F, food.getSaturationLevel() - 1.0F));
			}
			// 饥饿值低于阈值：缓慢 I + 虚弱 I
			if (food.getFoodLevel() < ReliquaryConfig.gluttonyWeakFoodThreshold()) {
				player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 0, false, false));
				player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 0, false, false));
			}
		}
	}

	// ==================== 自检辅助 ====================

	public static int wrathSelfHitCount() {
		return wrathSelfHitCount;
	}

	public static int lustStripArmorCount() {
		return lustStripArmorCount;
	}

	public static int lustSelfStripCount() {
		return lustSelfStripCount;
	}

	/** 清空自检计数 */
	public static void resetCounters() {
		wrathSelfHitCount = 0;
		lustStripArmorCount = 0;
		lustSelfStripCount = 0;
	}
}
