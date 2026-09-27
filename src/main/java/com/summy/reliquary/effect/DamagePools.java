package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 魂心 / 黑心的统一结算（1.6.5：**命中前一次算清 + 事后对账**）。
 *
 * <p>为什么不改事件金额、也不靠 {@code LivingDamageEvent}：
 * <ul>
 *     <li>Kilt-20.1.20 里 {@code LivingEntityInject} 的那处 {@code LivingDamageEvent} 注入被跳过
 *     （日志：{@code Shift.BY=2 ... exceeds the maximum allowed value: 0}）；</li>
 *     <li>Kilt 对玩家会**触发两次 {@code LivingHurtEvent}**（{@code hurt} 开头一次、{@code actuallyHurt} 里一次），
 *     "命中前并入 + 事件后拆分"会被第二次触发撤销并入、或由过早点位的事件判定成"没被吸收"，
 *     于是池子永不扣、伤害全落原版护盾与红血。</li>
 * </ul>
 *
 * <p><b>1.6.6 修正</b>：Forge 的 {@code LivingHurtEvent} 是在 {@code LivingEntity#actuallyHurt} **内部**触发的，
 * 而原版 {@code hurt} 在调用 {@code actuallyHurt} **之前**就已经把 {@code invulnerableTime} 写成 20 —— 所以 1.6.5 那个
 * "新的一击"门槛（{@code invulnerableTime <= 10}）对**任何真实命中**都是 false，池子一次都没介入；
 * 而且该门槛只在 Fabric 侧生效，于是 Forge 自检全绿、线上（Kilt）全废。现在改成**每一击都介入**，
 * 只用 {@link #isDuplicateEvent} 去重来对付 Kilt 的双触发。
 *
 * <p>现在的流程：
 * <ol>
 *     <li>{@link #prepare}（{@code LivingHurtEvent}，LOWEST）：确认这是"会落地的新的一击"后，
 *     先用 {@link DamageEstimate} 估算"会打到血的部分"，**当场扣池**，并把同额**临时并入原版吸收值** ——
 *     于是原版自己的结算就会把这一份吃掉，玩家少掉的血正好等于池子承担的量；</li>
 *     <li>{@link #reconcile}（下一次 {@code prepare} 或玩家 tick 末尾）：此时上一击的吸收消耗早就做完了，
 *     用结算后的吸收值反推"原版护盾扣了多少、池子实际扣了多少"，把**多并入的部分退回去**并还原吸收值；
 *     **归零破碎也在这里判定**，所以被吞掉的命中不会假触发；</li>
 *     <li>并入与对账都在命中前后的极短时间内完成，玩家与 HUD 看不到中间态。</li>
 * </ol>
 */
public final class DamagePools {
	/** 一次命中的挂起记录（1.6.6：额外记住来源与金额，用来识别 Kilt 的"同一击重复事件"） */
	private record Pending(long tick, float absorptionBefore, float topUp, double chargedSoul,
			double chargedBlack, boolean nullify, DamageSource source, float amount) {
	}

	private static final Map<UUID, Pending> PENDING = new HashMap<>();
	/** 是否运行在 Fabric 侧（Kilt / Connector）；1.6.6 起**只用于日志标签**，不再参与是否介入的判断 */
	private static final boolean FABRIC_SIDE = classPresent("net.fabricmc.loader.api.FabricLoader");
	/** 自检用：在 Forge 上强制按 Fabric 侧处理（复现 Kilt 的行为） */
	private static boolean fabricForTest = false;

	private DamagePools() {
	}

	/** 是否运行在 Fabric 侧（Kilt / Connector）；自检与日志用 */
	public static boolean fabricSide() {
		return FABRIC_SIDE || fabricForTest;
	}

	/** 自检用：强制按 Fabric 侧处理 */
	public static void setFabricForTest(boolean value) {
		fabricForTest = value;
	}

	/**
	 * 命中前：对账上一击 → 估算本击 → 当场扣池并临时并入原版吸收；致命一击则整击拦下（神性优先于免死）。
	 *
	 * @return true 表示这次介入了
	 */
	public static boolean prepare(LivingHurtEvent event) {
		if (!(event.getEntity() instanceof ServerPlayer player)) {
			return false;
		}
		if (event.isCanceled() || event.getAmount() <= 0.0F || player.isDeadOrDying()) {
			return false;
		}
		if (player.isInvulnerable() || player.isInvulnerableTo(event.getSource())) {
			return false;
		}
		// Kilt：同一个 actuallyHurt 里会对玩家连发两次 LivingHurtEvent（两处注入锚在同一点）。
		// 第二次必须**原样放过** —— 既不能再扣一次池，也绝不能把第一次并进去的吸收值当场对账掉。
		if (isDuplicateEvent(player, event)) {
			return false;
		}
		// 上一击的吸收消耗早已在它自己的 hurt 调用里做完 → 这里做精确对账
		reconcile(player);

		float absorptionBefore = player.getAbsorptionAmount();
		float healthPart = DamageEstimate.healthPart(player, event.getSource(), event.getAmount(),
				absorptionBefore);
		// ① 整击拦下：神性死亡拦截 → 亚巴顿（有冷却）→ 灵魂免死
		boolean nullify = false;
		if (healthPart > 0.0F && healthPart >= player.getHealth()) {
			nullify = Godhead.tryNullify(player, healthPart)
					|| Abaddon.tryNullify(player, healthPart, event.getSource())
					|| DeathImmunity.tryNullify(player, healthPart);
		}
		if (nullify) {
			float topUp = event.getAmount();
			PENDING.put(player.getUUID(),
					new Pending(now(player), absorptionBefore, topUp, 0.0D, 0.0D, true, event.getSource(),
							event.getAmount()));
			player.setAbsorptionAmount(absorptionBefore + topUp);
			logPrepare("整击拦下", player, absorptionBefore, topUp, 0.0F, 0.0D);
			return true;
		}
		// ② 池子：魂心 → 黑心（当场扣，破碎推迟到对账）
		double soul = effectiveSoul(player);
		double black = effectiveBlack(player);
		double usedSoul = Math.min(soul, healthPart);
		double usedBlack = Math.min(black, Math.max(0.0F, healthPart - usedSoul));
		double charged = usedSoul + usedBlack;
		if (charged <= 0.0D) {
			return false;
		}
		if (usedSoul > 0.0D) {
			SoulShield.deduct(player, usedSoul);
		}
		if (usedBlack > 0.0D) {
			DemonPact.deductBlackHearts(player, usedBlack);
		}
		float topUp = (float) charged;
		PENDING.put(player.getUUID(),
				new Pending(now(player), absorptionBefore, topUp, usedSoul, usedBlack, false, event.getSource(),
						event.getAmount()));
		player.setAbsorptionAmount(absorptionBefore + topUp);
		logPrepare("命中前扣池", player, absorptionBefore, topUp, healthPart, charged);
		return true;
	}

	/**
	 * 对账：把上一击"多并入/没被吞掉"的部分退回去、还原吸收值，并在真的把池子打到 0 时触发破碎。
	 *
	 * <p>触发点：下一次 {@link #prepare}、玩家 tick 末尾的看门狗。此时上一击的吸收消耗已经完成，
	 * 所以读到的吸收值可以放心用。
	 */
	public static void reconcile(ServerPlayer player) {
		Pending pending = PENDING.remove(player.getUUID());
		if (pending == null) {
			return;
		}
		if (pending.nullify()) {
			// 整击被吸收：护盾与池子都不损失
			player.setAbsorptionAmount(pending.absorptionBefore());
			logReconcile("对账·整击拦下", player, pending.absorptionBefore(), pending.topUp(), 0.0F, 0.0F,
					0.0D, 0.0D);
			return;
		}
		float after = player.getAbsorptionAmount();
		float consumed = Math.max(0.0F, pending.absorptionBefore() + pending.topUp() - after);
		float shieldConsumed = Math.min(pending.absorptionBefore(), consumed);
		double ourActual = Math.max(0.0D, consumed - shieldConsumed);
		double charged = pending.chargedSoul() + pending.chargedBlack();
		double refund = Math.max(0.0D, charged - ourActual);
		if (refund > 0.0D) {
			// 扣减顺序是 魂心 → 黑心，退回反过来：先退黑心、再退魂心
			double refundBlack = Math.min(pending.chargedBlack(), refund);
			double refundSoul = refund - refundBlack;
			if (refundBlack > 0.0D) {
				DemonPact.refundBlackHearts(player, refundBlack);
			}
			if (refundSoul > 0.0D) {
				SoulShield.refund(player, refundSoul);
			}
		}
		player.setAbsorptionAmount(Math.max(0.0F, pending.absorptionBefore() - shieldConsumed));
		if (ourActual > 0.0D) {
			// 这一击确实吃到池子 → 检查是否被打空（被吞掉的命中不会走到这里）
			SoulShield.shatterIfEmpty(player);
			DemonPact.shatterBlackHeartsIfEmpty(player);
		}
		logReconcile("对账", player, pending.absorptionBefore(), pending.topUp(), after, shieldConsumed,
				ourActual, refund);
	}

	/** 服务端每 tick（玩家 tick 结束）：上一 tick 的挂起项已经走完，可以安全对账 */
	public static void tickPlayer(ServerPlayer player) {
		Pending pending = PENDING.get(player.getUUID());
		if (pending == null || pending.tick() == now(player)) {
			return;
		}
		reconcile(player);
	}

	/** 玩家退出：先对账再清记录 */
	public static void forget(ServerPlayer player) {
		reconcile(player);
		PENDING.remove(player.getUUID());
	}

	public static void clear() {
		PENDING.clear();
	}

	/** 自检用：该玩家是否有挂起记录 */
	public static boolean hasPending(LivingEntity entity) {
		return entity != null && PENDING.containsKey(entity.getUUID());
	}

	/** 自检用：直接挂一条"待对账"记录（1.7.3 用来验证创世纪会把它清掉） */
	public static void markPendingForTest(ServerPlayer player) {
		PENDING.put(player.getUUID(), new Pending(now(player), player.getAbsorptionAmount(), 0.0F,
				0.0D, 0.0D, false, player.damageSources().generic(), 0.0F));
	}

	// ==================== 内部 ====================

	/** 生效中的魂心点数（受容量夹取） */
	private static double effectiveSoul(ServerPlayer player) {
		return Math.max(0.0D, Math.min(SoulShield.points(player), SoulShield.capacityFor(player)));
	}

	/** 生效中的黑心点数（未佩戴契约时为 0，受当前上限夹取） */
	private static double effectiveBlack(ServerPlayer player) {
		if (!DemonPact.active(player)) {
			return 0.0D;
		}
		return Math.max(0.0D,
				Math.min(PlayerFlags.blackHeartPoints(player), DemonPact.blackHeartMaxPoints(player)));
	}

	/**
	 * 这次事件是不是"同一击的重复触发"（Kilt 会对玩家连发两次 {@code LivingHurtEvent}）。
	 *
	 * <p>判据全部满足才算重复：① 挂起记录是**本 tick**生成的（原版每 tick 最多一次真正落地的一击）；
	 * ② 同一个 {@code DamageSource} 实例、同一个金额（Kilt 的两处注入读的是同一个方法参数）；
	 * ③ 吸收值**还等于**"命中前 + 本次并入量" —— 说明原版还没扣吸收，第二次事件确实发生在同一击的
	 * 减伤之前。若是同 tick 的另一次真实命中，吸收值早已被上一击消耗过，不会被误判。
	 */
	private static boolean isDuplicateEvent(ServerPlayer player, LivingHurtEvent event) {
		Pending pending = PENDING.get(player.getUUID());
		if (pending == null) {
			return false;
		}
		if (pending.tick() != now(player)) {
			return false;
		}
		if (pending.source() != event.getSource()) {
			return false;
		}
		if (Math.abs(pending.amount() - event.getAmount()) > 1.0E-4F) {
			return false;
		}
		float expected = pending.absorptionBefore() + pending.topUp();
		return Math.abs(player.getAbsorptionAmount() - expected) <= 1.0E-4F;
	}

	private static long now(ServerPlayer player) {
		return player.serverLevel().getGameTime();
	}

	/**
	 * prepare 行的日志文本（1.6.9 起字段语义修正）。
	 *
	 * <p>以前 prepare 与对账共用一套格式，于是"命中前估算的血伤"被打在了"结算后吸收"的位置上，
	 * 看上去像是吸收值异常。现在拆成两条独立格式：prepare 行只报 {@code 估算血伤}，对账行才报
	 * {@code 结算后吸收}。做成 public 方法是为了让自检能直接断言字段名。
	 */
	public static String prepareLogLine(String side, String tag, String playerName, float absorptionBefore,
			float topUp, float healthPart, double charged) {
		return String.format("伤害池[%s|%s] %s：护盾 A0=%s、并入 T=%s、估算血伤=%s、本次扣池=%s",
				side, tag, playerName, absorptionBefore, topUp, healthPart, charged);
	}

	/** 对账行的日志文本（{@code 结算后吸收} 才在这里出现） */
	public static String reconcileLogLine(String side, String tag, String playerName, float absorptionBefore,
			float topUp, float after, float shieldConsumed, double ourActual, double refund) {
		return String.format(
				"伤害池[%s|%s] %s：护盾 A0=%s、并入 T=%s、结算后吸收=%s（护盾扣 %s / 池扣 %s）、退还=%s",
				side, tag, playerName, absorptionBefore, topUp, after, shieldConsumed, ourActual, refund);
	}

	/** prepare 阶段的日志（字段：估算血伤） */
	private static void logPrepare(String tag, ServerPlayer player, float absorptionBefore, float topUp,
			float healthPart, double charged) {
		if (!ReliquaryConfig.logDamagePools()) {
			return;
		}
		SummyReliquary.LOGGER.info("[Summy Reliquary] {}", prepareLogLine(fabricSide() ? "Fabric" : "Forge",
				tag, player.getName().getString(), absorptionBefore, topUp, healthPart, charged));
	}

	/** 对账阶段的日志（字段：结算后吸收） */
	private static void logReconcile(String tag, ServerPlayer player, float absorptionBefore, float topUp,
			float after, float shieldConsumed, double ourActual, double refund) {
		if (!ReliquaryConfig.logDamagePools()) {
			return;
		}
		SummyReliquary.LOGGER.info("[Summy Reliquary] {}", reconcileLogLine(
				fabricSide() ? "Fabric" : "Forge", tag, player.getName().getString(), absorptionBefore, topUp,
				after, shieldConsumed, ourActual, refund));
	}

	/** 类是否存在（用来判断 Fabric 侧：Kilt / Connector 都会带 FabricLoader） */
	private static boolean classPresent(String name) {
		try {
			Class.forName(name);
			return true;
		} catch (Throwable throwable) {
			return false;
		}
	}
}
