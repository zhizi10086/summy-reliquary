package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.loot.HumbleingBundleLoot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** 「碎心」的死亡掉落与慈善捆绑包额外判定。 */
public final class BrokenHeartDrop {
	private static Boolean forcedRoll;
	private static Boolean forcedBonusRoll;
	private static int dropCount;
	private static String lastOutcome = "none";
	private static String lastBonusOutcome = "none";

	private BrokenHeartDrop() {
	}

	public static void onDeath(LivingEntity victim, Entity killer) {
		if (!ReliquaryConfig.enableBrokenHeartDrop() || victim instanceof net.minecraft.world.entity.player.Player) {
			return;
		}
		if (!TimedMarkerState.hasDemonicBrand(victim)) {
			lastOutcome = "no-brand";
			return;
		}
		if (!RareMaterialDropSupport.qualifies(victim)) {
			lastOutcome = DummySupport.isTargetDummyEntity(victim) ? "dummy" : "not-qualified";
			logCheck(victim, killer, null, lastOutcome.equals("dummy")
					? "训练人偶（不参与掉落）" : "非敌对且无仇恨目标");
			return;
		}

		boolean base = roll(victim, false);
		lastOutcome = base ? "drop" : "miss";
		ServerPlayer bonusPlayer = HumbleingBundleLoot.specialDropBonusPlayer(victim);
		boolean bonus = bonusPlayer != null && roll(victim, true);
		lastBonusOutcome = bonusPlayer == null ? "unavailable" : (bonus ? "drop" : "miss");
		int count = (base ? 1 : 0) + (bonus ? 1 : 0);
		if (count <= 0) {
			logCheck(victim, killer, bonusPlayer, String.format(Locale.ROOT,
					"基础=%s / 捆绑包=%s、阈值=%.2f%%、最终数量=0",
					lastOutcome, lastBonusOutcome, chanceFor(victim)));
			return;
		}

		RareMaterialDropSupport.spawnProtected(victim, SummyReliquary.BROKEN_HEART.get(), count);
		dropCount += count;
		SummyReliquary.LOGGER.info(
				"[Summy Reliquary] 碎心掉落：生物={}、击杀者={}、触发玩家={}、基础={}、捆绑包额外={}、概率={}%、最终数量={}、坐标=[{},{},{}]",
				BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()),
				killer == null ? "（无）" : killer.getName().getString(),
				bonusPlayer == null ? "（无）" : bonusPlayer.getName().getString(),
				lastOutcome, lastBonusOutcome, formatChance(victim), count,
				(int) victim.getX(), (int) victim.getY(), (int) victim.getZ());
	}

	public static double chanceFor(LivingEntity victim) {
		return RareMaterialDropSupport.chanceFor(victim,
				ReliquaryConfig.brokenHeartDropChance(),
				ReliquaryConfig.brokenHeartBossChance(),
				ReliquaryConfig.brokenHeartDragonChance());
	}

	private static boolean roll(LivingEntity victim, boolean bonus) {
		Boolean forced = bonus ? forcedBonusRoll : forcedRoll;
		if (forced != null) {
			return forced;
		}
		return victim.getRandom().nextDouble() * 100.0D < chanceFor(victim);
	}

	private static void logCheck(LivingEntity victim, Entity killer, ServerPlayer bonusPlayer, String result) {
		if (!ReliquaryConfig.logBrokenHeartDropChecks()) {
			return;
		}
		SummyReliquary.LOGGER.info(
				"[Summy Reliquary] 碎心判定：生物={}、击杀者={}、触发玩家={}、结果={}、坐标=[{},{},{}]",
				BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()),
				killer == null ? "（无）" : killer.getName().getString(),
				bonusPlayer == null ? "（无）" : bonusPlayer.getName().getString(), result,
				(int) victim.getX(), (int) victim.getY(), (int) victim.getZ());
	}

	private static String formatChance(LivingEntity victim) {
		return String.format(Locale.ROOT, "%.2f", chanceFor(victim));
	}

	public static void setForcedRoll(@Nullable Boolean value) {
		if (Boolean.getBoolean("summy.devcheck")) {
			forcedRoll = value;
		}
	}

	public static void setForcedBonusRoll(@Nullable Boolean value) {
		if (Boolean.getBoolean("summy.devcheck")) {
			forcedBonusRoll = value;
		}
	}

	public static int dropCount() {
		return dropCount;
	}

	public static String lastOutcome() {
		return lastOutcome;
	}

	public static String lastBonusOutcome() {
		return lastBonusOutcome;
	}

	public static void reset() {
		forcedRoll = null;
		forcedBonusRoll = null;
		dropCount = 0;
		lastOutcome = "none";
		lastBonusOutcome = "none";
	}
}
