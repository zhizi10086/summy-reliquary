package com.summy.reliquary.effect;

import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.item.CovenantItem;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * 誓约五件饰品的 R 键分派、统一冷却与提示参数。
 */
public final class CovenantActions {
	private CovenantActions() {
	}

	public static void perform(ServerPlayer player) {
		KindState state = current(player);
		if (state == null) {
			// 未佩戴誓约饰品时按 R：完全静默。
			return;
		}
		long now = player.serverLevel().getGameTime();
		long until = PlayerFlags.oathCooldownUntil(player);
		if (until > now && state.kind() != CovenantItem.Kind.LIFE_DEATH) {
			player.displayClientMessage(Component.translatable(
					"item.summy-reliquary.covenant.fail.cooldown",
					Math.max(1, (until - now + 19L) / 20L)), true);
			return;
		}
		boolean success = switch (state.kind()) {
			case LIFE_DEATH -> LifeDeath.activate(player);
			case DIVINE_INTERVENTION -> DivineIntervention.activate(player);
			case SACRED_VERDICT -> SacredVerdict.activate(player);
			case BLOOD_DEBT_ALTAR -> BloodDebtAltar.activate(player);
			case HEX_SEAL -> HexSeal.activate(player);
		};
		if (success) {
			int cooldown = cooldownTicks(state.kind());
			if (cooldown > 0) {
				PlayerFlags.setOathCooldownUntil(player, now + cooldown);
				player.getCooldowns().addCooldown(state.stack().getItem(), cooldown);
			}
		}
	}

	public static void tickPlayer(ServerPlayer player) {
		LifeDeath.tickPlayer(player);
		BloodDebtAltar.tickPlayer(player);
	}

	public static void onUnequip(ServerPlayer player, CovenantItem.Kind kind) {
		switch (kind) {
			case LIFE_DEATH -> LifeDeath.onUnequip(player);
			case DIVINE_INTERVENTION -> DivineIntervention.forget(player);
			case SACRED_VERDICT -> {
				// 标记本身无玩家属性；再次佩戴后仍按完整攻击前置结算。
			}
			case BLOOD_DEBT_ALTAR -> {
				BloodDebtAltar.onDeathOrUnequip(player);
			}
			case HEX_SEAL -> HexSeal.removeOwner(player);
		}
		PlayerFlags.setOathCooldownUntil(player, 0L);
	}

	public static boolean hasActiveCooldown(ServerPlayer player) {
		return player != null && PlayerFlags.oathCooldownUntil(player)
				> player.serverLevel().getGameTime();
	}

	/** 修女服推进誓约 R 冷却：按该技能总冷却比例推进，并保留最小推进量。 */
	public static void advanceCooldown(ServerPlayer player, double fraction, int minTicks) {
		if (!hasActiveCooldown(player)) {
			return;
		}
		KindState state = current(player);
		if (state == null || state.kind() == CovenantItem.Kind.LIFE_DEATH) {
			return;
		}
		long now = player.serverLevel().getGameTime();
		long remaining = PlayerFlags.oathCooldownUntil(player) - now;
		long advanced = Math.max(minTicks, (long) Math.ceil(
				cooldownTicks(state.kind()) * Math.max(0.0D, fraction)));
		long next = Math.max(0L, remaining - advanced);
		PlayerFlags.setOathCooldownUntil(player, now + next);
		player.getCooldowns().addCooldown(state.stack().getItem(), (int) next);
	}

	/**
	 * 每行提示只返回该行需要的参数，避免同一件饰品的后续行误用第一行的值。
	 */
	public static Object[] tooltipArguments(CovenantItem.Kind kind, int lineIndex) {
		return switch (kind) {
			case LIFE_DEATH -> switch (lineIndex) {
				case 2 -> new Object[]{
						ReliquaryConfig.lifeDeathDeathFormSeconds(),
						ReliquaryTooltipsNumber.of(ReliquaryConfig.lifeDeathDamageBase()),
						ReliquaryTooltipsNumber.of(
								ReliquaryConfig.lifeDeathDamageStepPercent() / 100.0D),
						ReliquaryTooltipsNumber.of(ReliquaryConfig.lifeDeathDamageCap())
				};
				case 3 -> new Object[]{
						ReliquaryConfig.lifeDeathMoveSpeedPercent(),
						ReliquaryConfig.lifeDeathAttackSpeedPercent()
				};
				case 4 -> new Object[]{ReliquaryConfig.lifeDeathReviveKillRequired()};
				case 5 -> new Object[]{ReliquaryConfig.lifeDeathCooldownSeconds()};
				default -> new Object[0];
			};
			case DIVINE_INTERVENTION -> switch (lineIndex) {
				case 1 -> new Object[]{
						ReliquaryTooltipsNumber.of(ReliquaryConfig.divineInterventionReflectPercent())
				};
				case 2 -> new Object[]{
						ReliquaryTooltipsNumber.of(ReliquaryConfig.divineInterventionBarrierDistance()),
						ReliquaryConfig.divineInterventionBarrierTicks() / 20,
						ReliquaryTooltipsNumber.of(ReliquaryConfig.divineInterventionBarrierWidth()),
						ReliquaryTooltipsNumber.of(ReliquaryConfig.divineInterventionBarrierHeight())
				};
				case 4 -> new Object[]{
						ReliquaryTooltipsNumber.of(ReliquaryConfig.divineInterventionProjectileDamagePercent())
				};
				case 5 -> new Object[]{ReliquaryConfig.divineInterventionCooldownSeconds()};
				default -> new Object[0];
			};
			case SACRED_VERDICT -> switch (lineIndex) {
				case 1 -> new Object[]{
						ReliquaryTooltipsNumber.of(ReliquaryConfig.sacredVerdictProcChancePercent()),
						ReliquaryConfig.sacredVerdictProcTicks() / 20
				};
				case 2 -> new Object[]{
						ReliquaryTooltipsNumber.of(ReliquaryConfig.sacredVerdictBonusDamagePercent())
				};
				case 4 -> new Object[]{
						ReliquaryTooltipsNumber.of(ReliquaryConfig.sacredVerdictCastRadius()),
						ReliquaryTooltipsNumber.of(ReliquaryConfig.sacredVerdictCastDamage()),
						ReliquaryConfig.sacredVerdictCastMarkTicks() / 20
				};
				case 5 -> new Object[]{ReliquaryConfig.sacredVerdictCooldownSeconds()};
				default -> new Object[0];
			};
			case BLOOD_DEBT_ALTAR -> switch (lineIndex) {
				case 1 -> new Object[]{
						ReliquaryTooltipsNumber.of(ReliquaryConfig.bloodDebtReductionPercent()),
						ReliquaryTooltipsNumber.of(ReliquaryConfig.bloodDebtDeferTicks() / 20.0D),
						ReliquaryTooltipsNumber.of(ReliquaryConfig.bloodDebtMinDeferredDamage())
				};
				case 3 -> new Object[]{
						ReliquaryTooltipsNumber.of(ReliquaryConfig.bloodDebtActiveAbsorbPercent()),
						ReliquaryTooltipsNumber.of(ReliquaryConfig.bloodDebtCapMaxHealthPercent())
				};
				case 4 -> new Object[]{
						ReliquaryTooltipsNumber.of(ReliquaryConfig.bloodDebtKillReduceSeconds())
				};
				case 5 -> new Object[]{ReliquaryConfig.bloodDebtCooldownSeconds()};
				default -> new Object[0];
			};
			case HEX_SEAL -> switch (lineIndex) {
				case 1 -> new Object[]{
						ReliquaryTooltipsNumber.of(ReliquaryConfig.hexSealProcChancePercent()),
						ReliquaryConfig.hexSealProcTicks() / 20
				};
				case 2 -> new Object[]{
						ReliquaryTooltipsNumber.of(ReliquaryConfig.hexSealCastAngleDegrees()),
						ReliquaryTooltipsNumber.of(ReliquaryConfig.hexSealCastRange()),
						ReliquaryConfig.hexSealCastTicks() / 20
				};
				case 3 -> new Object[]{
						ReliquaryTooltipsNumber.of(ReliquaryConfig.hexSealDamageTakenPercent())
				};
				case 4 -> new Object[]{
						ReliquaryTooltipsNumber.of(ReliquaryConfig.hexSealTransferPercent())
				};
				case 5 -> new Object[]{
						ReliquaryTooltipsNumber.of(ReliquaryConfig.hexSealSpreadRadius())
				};
				case 6 -> new Object[]{ReliquaryConfig.hexSealCooldownSeconds()};
				default -> new Object[0];
			};
		};
	}

	private static int cooldownTicks(CovenantItem.Kind kind) {
		return switch (kind) {
			case LIFE_DEATH -> 0;
			case DIVINE_INTERVENTION -> ReliquaryConfig.divineInterventionCooldownTicks();
			case SACRED_VERDICT -> ReliquaryConfig.sacredVerdictCooldownTicks();
			case BLOOD_DEBT_ALTAR -> ReliquaryConfig.bloodDebtCooldownTicks();
			case HEX_SEAL -> ReliquaryConfig.hexSealCooldownTicks();
		};
	}

	private static KindState current(ServerPlayer player) {
		var handler = CuriosApi.getCuriosInventory(player).orElse(null);
		if (handler == null) {
			return null;
		}
		for (CovenantItem.Kind kind : CovenantItem.Kind.values()) {
			ItemStack stack = findEquipped(handler, kind.item());
			if (!stack.isEmpty()) {
				return new KindState(kind, stack);
			}
		}
		return null;
	}

	private static ItemStack findEquipped(
			top.theillusivec4.curios.api.type.capability.ICuriosItemHandler handler, Item item) {
		for (var entry : handler.getCurios().entrySet()) {
			for (int index = 0; index < entry.getValue().getSlots(); index++) {
				ItemStack stack = entry.getValue().getStacks().getStackInSlot(index);
				if (stack.is(item)) {
					return stack;
				}
			}
		}
		return ItemStack.EMPTY;
	}

	private record KindState(CovenantItem.Kind kind, ItemStack stack) {
	}

	/** 避免效果层反向依赖物品提示工具类。 */
	private static final class ReliquaryTooltipsNumber {
		private static Object of(double value) {
			return com.summy.reliquary.item.ReliquaryTooltips.number(value);
		}
	}
}
