package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 「献祭」（1.8.2）：献祭匕首的右键技能（取代原来的「遁入暗影」）。
 *
 * <ul>
 *     <li>自损：对自身造成 {@code [sacrifice] self_damage} 点**真实伤害**（默认 4 = 2 颗心；
 *     专用伤害类型 {@code sacrifice_self}，**无视护甲 / 保护 / 抗性**，但**护盾照常抵挡** ——
 *     按「原版吸收 → 魂心 → 黑心 → 生命」结算）。**护盾不够就会真的致死**，
 *     且这一击**不触发任何死亡拦截**（详见 {@link #tryUse}）。</li>
 *     <li>增益：{@code damage_percent}（默认 +40%）**严格左键近战**伤害，持续
 *     {@code duration_seconds}（默认 8 秒），按剩余时间线性衰减到 0%。</li>
 *     <li>冷却：{@code cooldown_seconds}（默认 8 秒）计入物品冷却，物品栏图标因此显示原版冷却扇形。</li>
 * </ul>
 *
 * <p>「严格左键近战」＝伤害的直接来源就是攻击者本人，且不在投掷武器的命中结算期间
 * （投掷矛沿用 {@code playerAttack} 伤害源，必须靠 {@link #beginRangedResolve()} 区分）。
 */
public final class Sacrifice {
	/** 自伤专用的伤害类型（数据包里的 {@code summy-reliquary:sacrifice_self}） */
	private static final String SELF_DAMAGE_ID = "summy-reliquary.sacrifice_self";
	/** 增伤到期时刻（游戏 tick） */
	private static final Map<UUID, Long> UNTIL = new HashMap<>();
	/** 投掷武器命中结算中的层数（> 0 时不算左键近战） */
	private static int resolvingThrows;
	/** 自检用：成功释放次数 */
	private static int useCount;

	private Sacrifice() {
	}

	/** 当前剩余时间比例（1 → 0）；未生效返回 0 */
	public static double remainingRatio(LivingEntity entity) {
		if (entity == null) {
			return 0.0D;
		}
		Long until = UNTIL.get(entity.getUUID());
		if (until == null) {
			return 0.0D;
		}
		int total = ReliquaryConfig.sacrificeDurationTicks();
		long left = until - entity.level().getGameTime();
		if (total <= 0 || left <= 0L) {
			return 0.0D;
		}
		return Math.min(1.0D, left / (double) total);
	}

	/** 当前生效的近战增伤（百分比：满值 → 0 线性衰减） */
	public static double bonusPercent(LivingEntity entity) {
		double ratio = remainingRatio(entity);
		return ratio <= 0.0D ? 0.0D : ReliquaryConfig.sacrificeDamagePercent() * ratio;
	}

	/** 是否算「严格左键近战」：直接来源是攻击者本人，且不处于投掷结算中 */
	public static boolean isMeleeHit(LivingEntity attacker, DamageSource source) {
		return attacker != null && source != null
				&& source.getDirectEntity() == attacker && resolvingThrows == 0;
	}

	/** 是否是献祭的自伤（各受击逻辑据此豁免：不扣池、不触发斗篷与七罪副作用） */
	public static boolean isSelfDamage(DamageSource source) {
		return source != null && SELF_DAMAGE_ID.equals(source.getMsgId());
	}

	/** 献祭的自伤伤害源（带专门伤害类型；数据包缺失时退回原版 generic） */
	private static DamageSource selfDamageSource(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		var registry = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
		ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE,
				SummyReliquary.id("sacrifice_self"));
		var holder = registry.getHolder(key).orElse(null);
		if (holder == null) {
			return level.damageSources().generic();
		}
		return new DamageSource(holder, player, player);
	}

	/**
	 * 右键使用献祭：**固定真伤**自损（无视护甲 / 保护 / 抗性，但**护盾池照常参与**）
	 * + 近战增伤 + 进物品冷却。
	 *
	 * <p>判定顺序：开关 / 旁观者 / 死亡 → 静默失败；冷却中 → 提示剩余秒数；
	 * **创造模式放行**（不掉血，仍给增益与冷却）；其余情形以"生命 / 吸收 / 魂心 / 黑心是否真的减少"判定 ——
	 * 被无敌窗口（遁入暗影 / 亚巴顿 / 神性 / 免死 / 斗篷 / 魂心破碎）吃掉时提示"被打断"，
	 * 且**不消耗、不给增益、不进冷却**。**没有"生命过低拒绝"这一档**。
	 *
	 * <p><b>1.8.2：自伤不再钳制、也不再因生命过低拒绝</b> —— 护盾够就由护盾承担，
	 * 护盾不够就会真的致死（玩家自担风险），而且这一击**不触发任何死亡拦截**。
	 *
	 * <p>成功时**不显示任何动作栏文本**：反馈由物品冷却扇形与受伤表现提供。
	 *
	 * @return true 表示真的释放了
	 */
	public static boolean tryUse(ServerPlayer player) {
		if (player == null || !ReliquaryConfig.enableSacrifice()
				|| player.isSpectator() || player.isDeadOrDying()) {
			return false;
		}
		if (player.getCooldowns().isOnCooldown(SummyReliquary.SACRIFICIAL_DAGGER.get())) {
			actionBar(player, Component.translatable("message.summy-reliquary.sacrifice.cooldown",
					cooldownSecondsLeft(player)));
			return false;
		}
		// 完全放行：伤害就是配置值，不做"生命 − 1"钳制、也不因残血拒绝
		float damage = (float) ReliquaryConfig.sacrificeSelfDamage();
		if (damage <= 0.0F) {
			return false;
		}
		// 创造模式放行：不掉血，但仍给增益与冷却（调试便利）
		if (!player.isCreative()) {
			player.invulnerableTime = 0;
			float healthBefore = player.getHealth();
			float absorptionBefore = player.getAbsorptionAmount();
			double soulBefore = SoulShield.points(player);
			double blackBefore = PlayerFlags.blackHeartPoints(player);
			// 自伤不产生击退：结算前后保存 / 恢复速度（与神性光环同款做法）
			net.minecraft.world.phys.Vec3 velocity = player.getDeltaMovement();
			player.hurt(selfDamageSource(player), damage);
			player.setDeltaMovement(velocity);
			player.hurtMarked = true;
			// 是否真的承受了伤害：血量 / 吸收 / 魂心 / 黑心 任一减少即算生效。
			// 注意不能用 hurt 的返回值 —— 事件被取消（无敌窗口）时它仍然返回 true。
			boolean affected = player.getHealth() < healthBefore - 1.0E-4F
					|| player.getAbsorptionAmount() < absorptionBefore - 1.0E-4F
					|| SoulShield.points(player) < soulBefore - 1.0E-4D
					|| PlayerFlags.blackHeartPoints(player) < blackBefore - 1.0E-4D;
			if (!affected) {
				// 被无敌窗口吃掉：视为释放失败（不消耗、不给增益、不进冷却）
				actionBar(player, Component.translatable("message.summy-reliquary.sacrifice.interrupted"));
				return false;
			}
		}
		long now = player.level().getGameTime();
		useCount++;
		UNTIL.put(player.getUUID(), now + ReliquaryConfig.sacrificeDurationTicks());
		player.getCooldowns().addCooldown(SummyReliquary.SACRIFICIAL_DAGGER.get(),
				ReliquaryConfig.sacrificeCooldownTicks());
		return true;
	}

	/** 献祭剩余冷却秒数（向上取整；0 = 可用） */
	public static int cooldownSecondsLeft(ServerPlayer player) {
		if (player == null) {
			return 0;
		}
		float percent = player.getCooldowns()
				.getCooldownPercent(SummyReliquary.SACRIFICIAL_DAGGER.get(), 0.0F);
		int total = ReliquaryConfig.sacrificeCooldownTicks();
		return (int) Math.ceil(Math.max(0.0F, percent) * total / 20.0D);
	}

	/** 行动栏提示（失败侧用；成功侧刻意静默） */
	private static void actionBar(ServerPlayer player, Component message) {
		player.displayClientMessage(message, true);
	}

	/** 投掷武器命中结算开始（{@code ThrownSpear} 调用）；期间不算左键近战 */
	public static void beginRangedResolve() {
		resolvingThrows++;
	}

	/** 投掷武器命中结算结束（必须与 {@link #beginRangedResolve()} 成对） */
	public static void endRangedResolve() {
		if (resolvingThrows > 0) {
			resolvingThrows--;
		}
	}

	/** 服务端每 tick：清理已经过期的记录 */
	public static void tickPlayer(ServerPlayer player) {
		Long until = UNTIL.get(player.getUUID());
		if (until != null && player.level().getGameTime() >= until) {
			UNTIL.remove(player.getUUID());
		}
	}

	/** 玩家退出 / 死亡重生：清掉增益 */
	public static void forget(ServerPlayer player) {
		UNTIL.remove(player.getUUID());
	}

	public static void clear() {
		UNTIL.clear();
		resolvingThrows = 0;
	}

	// ==================== 自检接口 ====================

	public static int useCount() {
		return useCount;
	}

	/** 自检用：把增益剩余时间设成指定 tick */
	public static void setRemainingForTest(ServerPlayer player, int ticks) {
		UNTIL.put(player.getUUID(), player.level().getGameTime() + ticks);
	}

	public static void reset() {
		useCount = 0;
		UNTIL.clear();
		resolvingThrows = 0;
	}
}
