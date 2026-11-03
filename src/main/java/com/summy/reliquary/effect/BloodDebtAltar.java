package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 血债祭坛：承伤减半、另一半分 tick 静默偿还，主动用生命换取独立血债盾。
 */
public final class BloodDebtAltar {
	private static final TagKey<EntityType<?>> FORGE_BOSSES =
			TagKey.create(Registries.ENTITY_TYPE, new net.minecraft.resources.ResourceLocation("forge", "bosses"));
	private static final TagKey<EntityType<?>> COMMON_BOSSES =
			TagKey.create(Registries.ENTITY_TYPE, new net.minecraft.resources.ResourceLocation("c", "bosses"));
	private static final TagKey<EntityType<?>> SUMMY_BOSSES =
			TagKey.create(Registries.ENTITY_TYPE,
					new net.minecraft.resources.ResourceLocation(SummyReliquary.NAMESPACE, "bosses"));
	private static final Map<UUID, List<Debt>> DEBTS = new HashMap<>();
	private static final Map<UUID, DuplicateGuard> LAST_HURT = new HashMap<>();

	private BloodDebtAltar() {
	}

	public static boolean wears(LivingEntity entity) {
		return entity != null && ReliquaryConfig.enableBloodDebtAltar()
				&& CurioHelper.wears(entity, SummyReliquary.BLOOD_DEBT_ALTAR.get());
	}

	public static double shield(LivingEntity entity) {
		return Math.max(0.0D, PlayerFlags.bloodDebtShield(entity));
	}

	public static void setShield(ServerPlayer player, double value) {
		double cap = Math.max(0.0D, player.getMaxHealth()
				* ReliquaryConfig.bloodDebtCapMaxHealthPercent() / 100.0D);
		PlayerFlags.setBloodDebtShield(player, Math.min(cap, Math.max(0.0D, value)));
		RevelationTracker.sync(player);
	}

	/** 受伤时把减免部分拆成独立延迟段。 */
	public static void onIncomingHurt(LivingHurtEvent event) {
		if (event.isCanceled() || event.getAmount() <= 0.0F
				|| !(event.getEntity() instanceof ServerPlayer player) || !wears(player)) {
			return;
		}
		if (HolyLightEffect.isCovenantEffect(event.getSource())) {
			return;
		}
		long now = player.serverLevel().getGameTime();
		DuplicateGuard guard = LAST_HURT.get(player.getUUID());
		if (guard != null && guard.tick() == now && guard.source() == event.getSource()
				&& Math.abs(guard.originalAmount() - event.getAmount()) < 1.0E-4F) {
			return;
		}
		LAST_HURT.put(player.getUUID(), new DuplicateGuard(now, event.getSource(), event.getAmount()));
		double reduction = Math.max(0.0D,
				Math.min(100.0D, ReliquaryConfig.bloodDebtReductionPercent()));
		float deferred = (float) (event.getAmount() * reduction / 100.0D);
		if (deferred <= 0.0F) {
			return;
		}
		event.setAmount(Math.max(0.0F, event.getAmount() - deferred));
		double minimum = ReliquaryConfig.bloodDebtMinDeferredDamage();
		if (minimum > 0.0D && deferred <= minimum + 1.0E-6F) {
			return;
		}
		int ticks = ReliquaryConfig.bloodDebtDeferTicks();
		DEBTS.computeIfAbsent(player.getUUID(), ignored -> new ArrayList<>())
				.add(new Debt(deferred, ticks, ticks, event.getSource()));
	}

	/** 每 tick 静默扣血：血债盾 → 吸收 → 魂心 → 黑心 → 生命。 */
	public static void tickPlayer(ServerPlayer player) {
		List<Debt> debts = DEBTS.get(player.getUUID());
		if (debts == null) {
			return;
		}
		if (!wears(player) || player.isDeadOrDying()) {
			debts.clear();
			return;
		}
		double amount = 0.0D;
		DamageSource source = player.damageSources().magic();
		for (Debt debt : debts) {
			double payment = Math.min(debt.remainingAmount(), debt.perTick());
			debt.remainingAmount -= payment;
			debt.remainingTicks--;
			amount += payment;
			source = debt.source();
		}
		debts.removeIf(debt -> debt.remainingTicks() <= 0 || debt.remainingAmount() <= 1.0E-6D);
		if (debts.isEmpty()) {
			DEBTS.remove(player.getUUID());
		}
		if (amount > 0.0D) {
			silentDeduct(player, amount, source);
		}
	}

	/** R：消耗“当前生命 − 1”，按配置倍率换取独立血债盾。 */
	public static boolean activate(ServerPlayer player) {
		if (!wears(player)) {
			return false;
		}
		double cost = player.getHealth() - 1.0D;
		if (cost < 0.5D) {
			fail(player, "item.summy-reliquary.blood_debt_altar.fail.life");
			return false;
		}
		double cap = player.getMaxHealth() * ReliquaryConfig.bloodDebtCapMaxHealthPercent() / 100.0D;
		double room = cap - player.getAbsorptionAmount() - shield(player);
		if (room <= 1.0E-6D) {
			fail(player, "item.summy-reliquary.blood_debt_altar.fail.full");
			return false;
		}
		double gain = Math.min(room, cost * ReliquaryConfig.bloodDebtActiveAbsorbPercent() / 100.0D);
		player.setHealth((float) Math.max(0.5D, player.getHealth() - cost));
		setShield(player, shield(player) + gain);
		return true;
	}

	public static void onKill(ServerPlayer killer, LivingEntity killed) {
		if (!wears(killer) || killed == killer) {
			return;
		}
		if (isBoss(killed)) {
			DEBTS.remove(killer.getUUID());
			return;
		}
		if (!isHostile(killed)) {
			return;
		}
		List<Debt> debts = DEBTS.get(killer.getUUID());
		if (debts == null) {
			return;
		}
		double reduce = Math.max(0.0D, ReliquaryConfig.bloodDebtKillReduceSeconds() * 20.0D);
		for (Debt debt : debts) {
			debt.remainingTicks = Math.max(0, debt.remainingTicks - (int) Math.round(reduce));
		}
		debts.removeIf(debt -> debt.remainingTicks() <= 0);
		if (debts.isEmpty()) {
			DEBTS.remove(killer.getUUID());
		}
	}

	public static void onDeathOrUnequip(ServerPlayer player) {
		DEBTS.remove(player.getUUID());
		LAST_HURT.remove(player.getUUID());
	}

	public static void forget(ServerPlayer player) {
		if (player != null) {
			DEBTS.remove(player.getUUID());
			LAST_HURT.remove(player.getUUID());
		}
	}

	public static void clear() {
		DEBTS.clear();
		LAST_HURT.clear();
	}

	public static int segmentCount(ServerPlayer player) {
		List<Debt> debts = DEBTS.get(player.getUUID());
		return debts == null ? 0 : debts.size();
	}

	public static double pending(ServerPlayer player) {
		List<Debt> debts = DEBTS.get(player.getUUID());
		if (debts == null) {
			return 0.0D;
		}
		return debts.stream().mapToDouble(Debt::remainingAmount).sum();
	}

	private static void silentDeduct(ServerPlayer player, double amount, DamageSource source) {
		double remaining = amount;
		double blood = Math.min(shield(player), remaining);
		if (blood > 0.0D) {
			setShield(player, shield(player) - blood);
			remaining -= blood;
		}
		if (remaining > 0.0D && player.getAbsorptionAmount() > 0.0F) {
			float used = (float) Math.min(player.getAbsorptionAmount(), remaining);
			player.setAbsorptionAmount(Math.max(0.0F, player.getAbsorptionAmount() - used));
			remaining -= used;
		}
		if (remaining > 0.0D) {
			double soul = Math.min(remaining, SoulShield.points(player));
			if (soul > 0.0D) {
				SoulShield.deduct(player, soul);
				remaining -= soul;
			}
		}
		if (remaining > 0.0D) {
			double black = Math.min(remaining,
					Math.min(PlayerFlags.blackHeartPoints(player), DemonPact.blackHeartMaxPoints(player)));
			if (black > 0.0D) {
				DemonPact.deductBlackHearts(player, black);
				remaining -= black;
			}
		}
		if (remaining > 0.0D) {
			player.setHealth((float) Math.max(0.0D, player.getHealth() - remaining));
			if (player.getHealth() <= 0.0F) {
				player.die(source);
			}
		}
	}

	private static boolean isHostile(LivingEntity entity) {
		if (entity instanceof Enemy) {
			return true;
		}
		var id = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
		return id != null && ReliquaryConfig.bloodDebtExtraHostileEntities().contains(id.toString());
	}

	private static boolean isBoss(LivingEntity entity) {
		EntityType<?> type = entity.getType();
		if (type.is(FORGE_BOSSES) || type.is(COMMON_BOSSES) || type.is(SUMMY_BOSSES)) {
			return true;
		}
		var id = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(type);
		return id != null && ReliquaryConfig.bloodDebtExtraBossEntities().contains(id.toString());
	}

	private static void fail(ServerPlayer player, String key) {
		player.displayClientMessage(net.minecraft.network.chat.Component.translatable(key), true);
	}

	private record DuplicateGuard(long tick, DamageSource source, float originalAmount) {
	}

	private static final class Debt {
		private final double perTick;
		private int remainingTicks;
		private double remainingAmount;
		private final DamageSource source;

		private Debt(double total, int totalTicks, int remainingTicks, DamageSource source) {
			this.perTick = total / totalTicks;
			this.remainingTicks = remainingTicks;
			this.remainingAmount = total;
			this.source = source;
		}

		private int remainingTicks() {
			return remainingTicks;
		}

		private double perTick() {
			return perTick;
		}

		private double remainingAmount() {
			return remainingAmount;
		}

		private DamageSource source() {
			return source;
		}
	}
}
