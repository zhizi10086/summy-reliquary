package com.summy.reliquary.effect;

import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.sin.SinEffects;
import com.summy.reliquary.sin.SinManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 七德加成（1.4.4）。
 *
 * <p><b>生效条件</b>：佩戴美德**且七罪全部已赎清**（未赎清时既不能查阅属性，也不产生任何效果）。
 * 这些都是"赎罪后世界给你的回报"，所以在 {@link SinManager#allRedeemed} 之前一律封存。
 *
 * <p>数值全部来自 {@code [virtues]} 配置段。实现全部走事件 / 属性层，不使用任何 Mixin。
 */
public final class VirtuesEffects {
	/** 慷慨：移动速度修饰符 */
	private static final UUID CHARITY_MOVE = UUID.fromString("6f5c1a2e-0000-4a0b-9d1e-000000000031");
	/** 勤勉：移动速度修饰符 */
	private static final UUID DILIGENCE_MOVE = UUID.fromString("6f5c1a2e-0000-4a0b-9d1e-000000000032");
	/** 勤勉：攻击速度修饰符 */
	private static final UUID DILIGENCE_ATTACK = UUID.fromString("6f5c1a2e-0000-4a0b-9d1e-000000000033");

	/** 慷慨的层数与到期时间 */
	private static final Map<UUID, int[]> CHARITY = new HashMap<>();
	/** 慷慨的到期游戏 tick（单独存，便于每秒判定） */
	private static final Map<UUID, Long> CHARITY_UNTIL = new HashMap<>();
	/** 耐心：目标 UUID + 层数 + 最后一次命中的游戏 tick */
	private static final Map<UUID, Patience> PATIENCE = new HashMap<>();
	/** 节制：上一 tick 的饥饿值与饱和度（用于把被扣的值恢复回来） */
	private static final Map<UUID, float[]> TEMPERANCE = new HashMap<>();

	private VirtuesEffects() {
	}

	private record Patience(UUID target, int stacks, long lastTick) {
	}

	/** 是否满足七德加成的前提：佩戴美德 + 七罪全部已赎清 */
	public static boolean active(LivingEntity entity) {
		return ReliquaryConfig.enableVirtueEffects()
				&& SinEffects.wearingVirtues(entity)
				&& SinManager.allRedeemed(entity);
	}

	// ==================== 属性修正（勤勉 / 慷慨） ====================

	/** 由 {@code AttributeManager.apply} 每次重算时调用 */
	public static void applyAttributes(LivingEntity entity) {
		boolean active = active(entity);
		double diligence = active ? ReliquaryConfig.diligenceSpeedPercent() / 100.0D : 0.0D;
		AttributeManager.applyModifier(entity, Attributes.MOVEMENT_SPEED, DILIGENCE_MOVE,
				"virtue_diligence_movement", diligence, AttributeModifier.Operation.MULTIPLY_TOTAL);
		AttributeManager.applyModifier(entity, Attributes.ATTACK_SPEED, DILIGENCE_ATTACK,
				"virtue_diligence_attack_speed", diligence, AttributeModifier.Operation.MULTIPLY_TOTAL);

		double charity = active ? charityBonusPercent(entity) / 100.0D : 0.0D;
		AttributeManager.applyModifier(entity, Attributes.MOVEMENT_SPEED, CHARITY_MOVE,
				"virtue_charity_movement", charity, AttributeModifier.Operation.MULTIPLY_TOTAL);
	}

	/** 勤勉的挖掘速度倍率（供 BreakSpeed 使用） */
	public static float breakSpeedFactor(Player player) {
		return active(player) ? 1.0F + ReliquaryConfig.diligenceSpeedPercent() / 100.0F : 1.0F;
	}

	private static int charityBonusPercent(LivingEntity entity) {
		int[] stacks = CHARITY.get(entity.getUUID());
		if (stacks == null) {
			return 0;
		}
		return Math.min(ReliquaryConfig.charitySpeedCapPercent(),
				stacks[0] * ReliquaryConfig.charitySpeedPercentPerDrop());
	}

	// ==================== 伤害管线（谦逊 / 耐心） ====================

	/** 攻击者是本模组玩家时的七德伤害修正 */
	public static float modifyOutgoingDamage(ServerPlayer attacker, LivingEntity victim, DamageSource source,
			float amount) {
		if (!active(attacker)) {
			return amount;
		}
		float result = amount;

		// 谦逊：目标生命百分比或最大值高于你 → 额外 +15%（作用于全部伤害）
		float victimRatio = victim.getMaxHealth() <= 0.0F ? 0.0F : victim.getHealth() / victim.getMaxHealth();
		float selfRatio = attacker.getMaxHealth() <= 0.0F ? 0.0F : attacker.getHealth() / attacker.getMaxHealth();
		if (victim.getMaxHealth() > attacker.getMaxHealth() || victimRatio > selfRatio) {
			result *= 1.0F + ReliquaryConfig.humilityDamageBonusPercent() / 100.0F;
		}

		// 耐心：仅近战，对同一目标连续命中叠加 +6%/层、上限 30%
		boolean melee = source != null && source.getDirectEntity() == attacker;
		if (melee) {
			long now = attacker.level().getGameTime();
			long resetTicks = ReliquaryConfig.patienceResetSeconds() * 20L;
			Patience previous = PATIENCE.get(attacker.getUUID());
			int stacks = (previous != null && previous.target().equals(victim.getUUID())
					&& now - previous.lastTick() <= resetTicks) ? previous.stacks() + 1 : 1;
			PATIENCE.put(attacker.getUUID(), new Patience(victim.getUUID(), stacks, now));
			int perHit = Math.max(1, ReliquaryConfig.patienceBonusPercentPerHit());
			int capped = Math.min(ReliquaryConfig.patienceCapPercent() / perHit, stacks);
			result *= 1.0F + (capped * perHit) / 100.0F;
		}
		return result;
	}

	/** 自检用：当前耐心层数带来的加成百分比 */
	public static int patienceBonusPercent(LivingEntity entity) {
		Patience patience = PATIENCE.get(entity.getUUID());
		if (patience == null) {
			return 0;
		}
		int perHit = Math.max(1, ReliquaryConfig.patienceBonusPercentPerHit());
		return Math.min(ReliquaryConfig.patienceCapPercent(), patience.stacks() * perHit);
	}

	// ==================== 丢物（慷慨） ====================

	/** 玩家丢弃物品：叠一层慷慨（可刷新） */
	public static void onItemToss(ServerPlayer player) {
		int[] stacks = CHARITY.getOrDefault(player.getUUID(), new int[]{0});
		int maxStacks = Math.max(1, ReliquaryConfig.charitySpeedCapPercent()
				/ Math.max(1, ReliquaryConfig.charitySpeedPercentPerDrop()));
		stacks[0] = Math.min(maxStacks, stacks[0] + 1);
		CHARITY.put(player.getUUID(), stacks);
		CHARITY_UNTIL.put(player.getUUID(), player.level().getGameTime()
				+ ReliquaryConfig.charityDurationSeconds() * 20L);
		AttributeManager.apply(player);
	}

	/** 自检用：当前慷慨加成百分比 */
	public static int charityBonusPercentOf(LivingEntity entity) {
		return charityBonusPercent(entity);
	}

	// ==================== 每秒维护 ====================

	/** 服务端每秒调用：慷慨过期、贞洁修装备、善意回血 */
	public static void tickPlayer(ServerPlayer player) {
		if (!active(player)) {
			clear(player);
			return;
		}
		// 慷慨：到点清零
		Long until = CHARITY_UNTIL.get(player.getUUID());
		if (until != null && player.level().getGameTime() >= until) {
			CHARITY.remove(player.getUUID());
			CHARITY_UNTIL.remove(player.getUUID());
			AttributeManager.apply(player);
		}
		// 贞洁：为身上的装备回复耐久
		int repair = ReliquaryConfig.chastityDurabilityPerSecond();
		if (repair > 0) {
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				ItemStack stack = player.getItemBySlot(slot);
				if (stack.isDamageableItem() && stack.getDamageValue() > 0) {
					stack.setDamageValue(Math.max(0, stack.getDamageValue() - repair));
				}
			}
		}
		// 善意：自己与范围内非敌对生物 / 玩家回血
		double heal = ReliquaryConfig.kindnessHealPerSecond();
		if (heal > 0.0D) {
			if (player.getHealth() < player.getMaxHealth()) {
				player.heal((float) heal);
			}
			double radius = ReliquaryConfig.kindnessRadius();
			for (LivingEntity other : player.level().getEntitiesOfClass(LivingEntity.class,
					player.getBoundingBox().inflate(radius),
					candidate -> candidate != player && candidate.isAlive() && !(candidate instanceof Enemy))) {
				if (other.getHealth() < other.getMaxHealth()) {
					other.heal((float) heal);
				}
			}
		}
	}

	/** 服务端每 tick 调用：节制（把被扣掉的饥饿值与饱和度恢复回来，正在回血时放行） */
	public static void tickPlayerEveryTick(ServerPlayer player) {
		if (!active(player)) {
			TEMPERANCE.remove(player.getUUID());
			return;
		}
		float[] last = TEMPERANCE.get(player.getUUID());
		int food = player.getFoodData().getFoodLevel();
		float saturation = player.getFoodData().getSaturationLevel();
		// 生命不满 = 正在被自然回血消耗，此时允许扣饥饿值
		if (last != null && player.getHealth() >= player.getMaxHealth()) {
			if (food < last[0]) {
				player.getFoodData().setFoodLevel((int) last[0]);
			}
			if (saturation < last[1]) {
				player.getFoodData().setSaturation(last[1]);
			}
		}
		TEMPERANCE.put(player.getUUID(), new float[]{
				player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel()});
	}

	/** 条件失效时清掉所有缓存与加成 */
	private static void clear(ServerPlayer player) {
		boolean changed = CHARITY.remove(player.getUUID()) != null;
		CHARITY_UNTIL.remove(player.getUUID());
		PATIENCE.remove(player.getUUID());
		TEMPERANCE.remove(player.getUUID());
		if (changed) {
			AttributeManager.apply(player);
		}
	}

	/** 玩家退出时清理 */
	public static void forget(ServerPlayer player) {
		clear(player);
	}

	/** 提示里七德效果行的参数（全部读 [virtues] 配置） */
	public static Object[] descriptionArgs(String virtueKey) {
		return switch (virtueKey) {
			case "item.summy-reliquary.virtue.humility" ->
					new Object[]{ReliquaryConfig.humilityDamageBonusPercent()};
			case "item.summy-reliquary.virtue.charity" ->
					new Object[]{ReliquaryConfig.charitySpeedPercentPerDrop(),
							ReliquaryConfig.charitySpeedCapPercent(),
							ReliquaryConfig.charityDurationSeconds()};
			case "item.summy-reliquary.virtue.chastity" ->
					new Object[]{ReliquaryConfig.chastityDurabilityPerSecond()};
			case "item.summy-reliquary.virtue.kindness" ->
					new Object[]{ReliquaryConfig.kindnessRadius(), ReliquaryConfig.kindnessHealPerSecond()};
			case "item.summy-reliquary.virtue.patience" ->
					new Object[]{ReliquaryConfig.patienceBonusPercentPerHit(),
							ReliquaryConfig.patienceCapPercent(),
							ReliquaryConfig.patienceResetSeconds()};
			case "item.summy-reliquary.virtue.diligence" ->
					new Object[]{ReliquaryConfig.diligenceSpeedPercent()};
			default -> new Object[0];
		};
	}
}
