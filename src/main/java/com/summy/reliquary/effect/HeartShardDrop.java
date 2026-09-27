package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 「心之碎片」的掉落规则（1.5.6）。
 *
 * <p>生物死亡时：非玩家、**不是训练人偶**、死亡瞬间仍带「启示之光」、且是敌对生物或对某人有仇恨的
 * 生物 → 按概率掉一个心之碎片（掉在死亡位置）。普通 0.5%、凋灵 / 监守者 5%、末影龙 20%；
 * **若击杀者是玩家且戴着圣心则完全不掉**。
 *
 * <p>掉落物会被标记为**不可摧毁**（防火 / 防爆 / 防雷 / 防刺，连岩浆也烧不掉），这是刻意设计。
 */
public final class HeartShardDrop {
	/** 自检用：强制掷点结果（null = 正常随机） */
	private static Boolean forcedRoll = null;
	/** 自检用：累计掉落次数 */
	private static int dropCount;
	/** 自检用：最近一次判定的结果（no-buff / dummy / not-qualified / suppressed / miss / drop） */
	private static String lastOutcome = "none";

	private HeartShardDrop() {
	}

	/** 生物死亡时调用（服务端） */
	public static void onDeath(LivingEntity victim, Entity killer) {
		// 静默前置：这些是"绝大多数死亡"的情况，打日志会刷屏
		if (!ReliquaryConfig.enableHeartShardDrop() || victim instanceof Player) {
			return;
		}
		// 必须"死时还带着启示之光"
		if (!victim.hasEffect(SummyReliquary.REVELATION_LIGHT.get())) {
			lastOutcome = "no-buff";
			return;
		}
		// 从这里开始就是"带着启示之光死亡"这一稀有分支：逐条判定并（可选地）打 INFO
		// 训练人偶永远不掉（开关只管领域 / 光环的判定）
		if (DummySupport.isTargetDummyEntity(victim)) {
			lastOutcome = "dummy";
			logCheck(victim, killer, "训练人偶（不参与掉落）");
			return;
		}
		// 判定口径与神性光环一致：敌对生物，或有攻击目标的生物
		boolean qualifies = victim instanceof Enemy
				|| (victim instanceof Mob mob && mob.getTarget() != null);
		if (!qualifies) {
			lastOutcome = "not-qualified";
			logCheck(victim, killer, "非敌对且无仇恨目标");
			return;
		}
		// 击杀者是玩家且戴着圣心 → 不掉
		if (killer instanceof ServerPlayer player && CurioHelper.wears(player, SummyReliquary.SACRED_HEART.get())) {
			lastOutcome = "suppressed";
			logCheck(victim, killer, "被抑制（击杀者 " + player.getName().getString() + " 戴着圣心）");
			return;
		}
		if (!roll(victim)) {
			lastOutcome = "miss";
			logCheck(victim, killer, String.format("未中（阈值 %.2f%%）", chanceFor(victim)));
			return;
		}
		lastOutcome = "drop";
		drop(victim, killer);
	}

	/** 「带着启示之光死亡」时的判定日志（[revelation_light] log_drop_checks 控制开关） */
	private static void logCheck(LivingEntity victim, Entity killer, String result) {
		if (!ReliquaryConfig.logDropChecks()) {
			return;
		}
		SummyReliquary.LOGGER.info(
				"[Summy Reliquary] 心之碎片判定：生物={}、击杀者={}、结果={}、坐标=[{},{},{}]",
				net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()),
				killer == null ? "（无）" : killer.getName().getString(),
				result,
				(int) victim.getX(), (int) victim.getY(), (int) victim.getZ());
	}

	/** 该生物对应的掉落几率（百分比）：末影龙 20 / 凋灵与监守者 5 / 其余 0.5 */
	public static double chanceFor(LivingEntity victim) {
		if (victim instanceof EnderDragon) {
			return ReliquaryConfig.heartShardDragonChance();
		}
		if (victim instanceof WitherBoss || victim instanceof Warden) {
			return ReliquaryConfig.heartShardBossChance();
		}
		return ReliquaryConfig.heartShardDropChance();
	}

	private static boolean roll(LivingEntity victim) {
		if (forcedRoll != null) {
			return forcedRoll;
		}
		return victim.getRandom().nextDouble() * 100.0D < chanceFor(victim);
	}

	/** 掉一个心之碎片在死亡位置，并把它标记为不可摧毁 */
	public static ItemEntity drop(LivingEntity victim, Entity killer) {
		if (!(victim.level() instanceof ServerLevel level)) {
			return null;
		}
		ItemEntity item = new ItemEntity(level, victim.getX(), victim.getY() + 0.5D, victim.getZ(),
				new ItemStack(SummyReliquary.HEART_SHARD.get()));
		// 防火 / 防爆 / 防雷 / 防刺（含岩浆等一切伤害）
		item.setInvulnerable(true);
		level.addFreshEntity(item);
		dropCount++;
		// 1.5.8 诊断：真正掉落时打一条 INFO，方便确认"到底掉没掉"（被圣心抑制的不会走到这里）
		SummyReliquary.LOGGER.info(
				"[Summy Reliquary] 心之碎片掉落：生物={}、击杀者={}、几率={}%%、坐标=[{},{},{}]",
				net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()),
				killer == null ? "（无）" : killer.getName().getString(),
				String.format("%.2f", chanceFor(victim)),
				(int) victim.getX(), (int) victim.getY(), (int) victim.getZ());
		return item;
	}

	// ==================== 自检接口 ====================

	/** 自检用：强制掷点结果（null = 正常随机） */
	public static void setForcedRoll(Boolean value) {
		forcedRoll = value;
	}

	/** 自检用：累计掉落次数 */
	public static int dropCount() {
		return dropCount;
	}

	/** 自检用：最近一次判定的结果 */
	public static String lastOutcome() {
		return lastOutcome;
	}

	/** 自检用：清空计数与强制掷点 */
	public static void reset() {
		forcedRoll = null;
		dropCount = 0;
		lastOutcome = "none";
	}
}
