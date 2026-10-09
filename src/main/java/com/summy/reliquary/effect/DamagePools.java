package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
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
 *
 * <p><b>1.8.5 补修</b>：致命一击的「整击拦下」（神性死亡拦截 / 亚巴顿 / 免死）**不再并入吸收值**，
 * 而是在 {@code prepare} 里直接 {@code event.setCanceled(true)} —— 伤害根本不落地、吸收值一个点都不动，
 * 并把这一击占用的原版无敌帧清 0。旧做法会让"读当前值做增量记账"的护盾模组（Enchantment Reforged
 * 的生命护盾）误判自己被吃掉，配合"拦截后回满血 → 治疗转护盾"就会让吸收值每次 +一个生命上限。
 */
public final class DamagePools {
	/** 一次命中的挂起记录（1.6.6：额外记住来源与金额，用来识别 Kilt 的"同一击重复事件"） */
	private record Pending(long tick, float absorptionBefore, float topUp, double chargedSoul,
			double chargedBlack, DamageSource source, float amount) {
	}

	private static final Map<UUID, Pending> PENDING = new HashMap<>();
	/**
	 * 新路径的"本击登记"（1.8.5 第二轮）：`prepare` 登记、`LivingDamageEvent` 处理器消费，
	 * 值 = 登记时的游戏 tick（用来识别"这环境根本收不到 LivingDamageEvent"）。
	 */
	private static final Map<UUID, Long> NEW_PATH_HITS = new HashMap<>();
	/**
	 * 能力探测：当前环境（Forge / Connector）是否真的会派发 {@code LivingDamageEvent}。
	 * Kilt 上那处注入被跳过 → 永远探测不到 → 自动全程走旧路径（见 {@link Mode}）。
	 */
	private static boolean healthDamageEventWorks = false;
	/** 是否运行在 Fabric 侧（Kilt / Connector）；1.6.6 起**只用于日志标签**，不再参与是否介入的判断 */
	private static final boolean FABRIC_SIDE = classPresent("net.fabricmc.loader.api.FabricLoader");
	/** 自检用：在 Forge 上强制按 Fabric 侧处理（复现 Kilt 的行为） */
	private static boolean fabricForTest = false;
	/** 自检用：强制走哪条路径（默认 AUTO = 按能力探测自动选） */
	private static Mode modeForTest = Mode.AUTO;

	/** 池子扣减走哪条路（1.8.5 第二轮） */
	public enum Mode {
		/** 按能力探测自动选 */
		AUTO,
		/** 新路径：`LivingDamageEvent` 里按真实血伤扣池（吸附值一个点都不动） */
		HEALTH_DAMAGE,
		/** 旧路径：并入原版吸收值 + 事后对账（Kilt 等收不到 LivingDamageEvent 的环境） */
		LEGACY
	}

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

	/** 自检用：强制走哪条路径（传 {@code null} 回到 AUTO） */
	public static void setModeForTest(Mode mode) {
		modeForTest = mode == null ? Mode.AUTO : mode;
	}

	/** 自检 / 日志用：当前是否已探测到 `LivingDamageEvent` 可用 */
	public static boolean healthDamageEventWorks() {
		return healthDamageEventWorks;
	}

	/** 服务器停止：复位能力探测（换了服务器 / 加载器之后必须重新探测） */
	public static void resetCapabilityProbe() {
		healthDamageEventWorks = false;
		NEW_PATH_HITS.clear();
	}

	/** 本环境该走哪条路径 */
	public static boolean useHealthDamagePath() {
		return modeForTest == Mode.HEALTH_DAMAGE
				|| (modeForTest == Mode.AUTO && healthDamageEventWorks);
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
		// 1.8.5 第二轮：能收到 LivingDamageEvent 的环境（Forge / Connector）改走"后置扣池" —— 本击
		// 完全不在吸收值上做手脚（详见类注释"新路径"）；Kilt 收不到那个事件，自动落回下面的旧路径。
		if (useHealthDamagePath()) {
			return prepareHealthDamagePath(player, event);
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
		// ① 完全拦下（守卫内 / 致命一击）：两条路径共用
		if (guardOrNullify(player, event, healthPart, absorptionBefore)) {
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
				new Pending(now(player), absorptionBefore, topUp, usedSoul, usedBlack, event.getSource(),
						event.getAmount()));
		player.setAbsorptionAmount(absorptionBefore + topUp);
		logPrepare("命中前扣池", player, absorptionBefore, topUp, healthPart, charged);
		return true;
	}

	/**
	 * 新路径的 {@code LivingHurtEvent} 阶段（1.8.5 第二轮）：**不并入吸收值**，只做"完全拦下"判定并登记本击，
	 * 真正的扣池搬到 {@link #applyToHealthDamage(LivingDamageEvent)}（那里能拿到"真实会打到血"的金额）。
	 */
	private static boolean prepareHealthDamagePath(ServerPlayer player, LivingHurtEvent event) {
		// 兜底：刚切换模式时可能还残留旧路径的挂起记录
		reconcile(player);
		float absorptionBefore = player.getAbsorptionAmount();
		float healthPart = DamageEstimate.healthPart(player, event.getSource(), event.getAmount(),
				absorptionBefore);
		if (guardOrNullify(player, event, healthPart, absorptionBefore)) {
			return true;
		}
		if (effectiveSoul(player) + effectiveBlack(player) <= 0.0D) {
			return false;
		}
		NEW_PATH_HITS.put(player.getUUID(), now(player));
		logPrepare("命中登记（LivingDamage 扣池）", player, absorptionBefore, 0.0F, healthPart, 0.0D);
		return true;
	}

	/**
	 * 完全拦下判定（两条路径共用）：
	 *
	 * <p>① 已经在守卫窗口里（Kilt / Connector 的"同一击双事件"会走到这里）：只取消、不重复拦截
	 * （重复拦截会再传送一次、重置守卫计时）。
	 *
	 * <p>② 致命一击 → 神性死亡拦截 → 亚巴顿（有冷却）→ 灵魂免死：拦下后**直接取消这一击** ——
	 * {@code ForgeHooks#onLivingHurt} 在事件被取消时返回 0，原版 {@code actuallyHurt} 随即 return，
	 * 伤害根本不落地、吸收值与血量都不被碰（1.8.5 之前的"并入整击金额再写回绝对值"会污染
	 * Enchantment Reforged 那类增量记账的护盾模组，实测每次拦截吸收值 +一个生命上限）。
	 *
	 * @param healthPart 估算"会打到生命值"的那部分（见 {@link DamageEstimate}）
	 */
	private static boolean guardOrNullify(ServerPlayer player, LivingHurtEvent event, float healthPart,
			float absorptionBefore) {
		if (healthPart <= 0.0F) {
			return false;
		}
		// ① 已经在守卫窗口里（神性 2 秒 / 亚巴顿 8 秒 / 免死 2 秒）：只取消、不重复拦截
		//    （Kilt / Connector 的"同一击双事件"会走到这里；重复拦截会再传送一次并重置守卫计时）。
		//    注意这一支**不看池子** —— 那几个窗口的语义是"完全免疫"，不能因为魂心还能扛就改成扣魂心。
		if (Godhead.isGuarded(player) || Abaddon.isGuarded(player) || DeathImmunity.isGuarded(player)) {
			if (healthPart < player.getHealth()) {
				return false;
			}
			cancelHit(event);
			logPrepare("守卫内拦下·不重复拦截", player, absorptionBefore, 0.0F, healthPart, 0.0D);
			return true;
		}
		// ② 致命一击：魂心 / 黑心是"吸收值之后、生命值之前"的一层 —— 先把池子能扛的份额算掉，
		//    只有**扣完池子仍然致死**才轮到死亡拦截。
		//
		//    1.8.5 第二轮补修：老写法直接用"忽略池子"的血伤估算判定致命，于是池子还满着也会触发拦截。
		//    实机（Ponder Time）证据：吸收被打空后一次 1.63 点伤害直接吃掉神性拦截，而魂心池还有 9.4 点
		//    一动不动 —— 玩家看到的就是"魂心没生效"。
		double poolTotal = effectiveSoul(player) + effectiveBlack(player);
		float uncovered = (float) Math.max(0.0D, healthPart - poolTotal);
		if (uncovered < player.getHealth()) {
			return false;
		}
		// 1.8.2：献祭自伤**不做任何死亡拦截** —— 该致死就致死
		if (!Sacrifice.isSelfDamage(event.getSource())
				&& (Godhead.tryNullify(player, healthPart)
						|| Abaddon.tryNullify(player, healthPart, event.getSource())
						|| DeathImmunity.tryNullify(player, healthPart))) {
			cancelHit(event);
			logPrepare("整击拦下·取消这一击", player, absorptionBefore, 0.0F, healthPart, 0.0D);
			return true;
		}
		return false;
	}

	/**
	 * 新路径的核心（1.8.5 第二轮）：在 {@code LivingDamageEvent}（LOWEST）里按**真实**"将打到血"的金额扣池。
	 *
	 * <p>为什么可行（1.20.1 原版 {@code LivingEntity#actuallyHurt} 的顺序，已核字节码）：
	 * {@code onLivingHurt}（护甲 / 抗性**之前**）→ 护甲 → 抗性 + 保护 → **扣吸收值** → {@code onLivingDamage}
	 * （金额 = 扣完吸收后剩下的、将打到血的那份；**返回值直接用于扣血**）。所以在这里：
	 * {@code share = min(池子总量, 事件金额)} → 扣魂心 / 黑心 → {@code event.setAmount(amount - share)}，
	 * 玩家少掉的血正好等于池子承担的量，而**吸收值一个点都不动** —— 读吸收值做记账的模组
	 * （Enchantment Reforged 的生命护盾）不会再被"先并入、再退还"牵连。
	 *
	 * <p>顺带收益：不依赖 {@link DamageEstimate} 的估算，也就没有"估算偏大 → 退还"这一说。
	 *
	 * <p>注意：这里把金额改小之后，原版在扣血之后还会执行一次 {@code setAbsorptionAmount(getAbsorptionAmount() - amount)}
	 * —— 但 {@code share > 0} 意味着"血伤 > 0"，也就意味着吸收值早已被扣到 0（否则血伤为 0），
	 * 那次减法会被原版钳到 0，**不会额外扣任何东西**（已核字节码）。
	 */
	public static void applyToHealthDamage(LivingDamageEvent event) {
		// 能力探测：能收到这个事件，就说明当前环境没有跳过 LivingDamageEvent（Connector 走的是 Forge 管线）
		if (!healthDamageEventWorks) {
			healthDamageEventWorks = true;
			SummyReliquary.LOGGER.info(
					"[Summy Reliquary] 伤害池：本环境会派发 LivingDamageEvent → 池子份额改走「后置扣血」口径"
							+ "（不再并入吸收值，与 Enchantment Reforged 的生命护盾互不干扰）");
		}
		if (!(event.getEntity() instanceof ServerPlayer player)) {
			return;
		}
		Long stamp = NEW_PATH_HITS.remove(player.getUUID());
		if (stamp == null) {
			// 不是新口径的命中（旧路径 / 被完全拦下 / 没登记）
			return;
		}
		if (event.isCanceled() || event.getAmount() <= 0.0F) {
			return;
		}
		double soul = effectiveSoul(player);
		double black = effectiveBlack(player);
		double poolBefore = soul + black;
		float amountBefore = event.getAmount();
		double share = Math.min(poolBefore, amountBefore);
		if (share <= 0.0D) {
			return;
		}
		double usedSoul = Math.min(soul, share);
		double usedBlack = Math.min(black, share - usedSoul);
		if (usedSoul > 0.0D) {
			SoulShield.deduct(player, usedSoul);
		}
		if (usedBlack > 0.0D) {
			DemonPact.deductBlackHearts(player, usedBlack);
		}
		event.setAmount((float) (amountBefore - share));
		// 池子被打空 → 破碎（与旧路径同口径：只有真的吃到池子才判定）
		SoulShield.shatterIfEmpty(player);
		DemonPact.shatterBlackHeartsIfEmpty(player);
		logHealthDamage(player, player.getAbsorptionAmount(), poolBefore, share, amountBefore, event.getAmount());
	}

	/**
	 * 把这一击整击取消：伤害不落地、吸收值不动，并把原版无敌帧还回去（1.8.5 补修）。
	 *
	 * <p>时机说明：取消发生在 {@code LivingHurtEvent}，此时原版 {@code hurt()} 早已写过
	 * {@code invulnerableTime = 20} 并调用 {@code markHurt()} —— 后者（客户端"抖一下"）已经来不及补救，
	 * 前者可以在这里清 0：这一击既然不算数，就不该占掉一次原版无敌帧。
	 */
	private static void cancelHit(LivingHurtEvent event) {
		event.setAmount(0.0F);
		event.setCanceled(true);
		if (event.getEntity() instanceof ServerPlayer player) {
			player.invulnerableTime = 0;
		}
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
		// 1.8.5：把"还原吸收值"从**写回绝对值**改成**增量退还** —— 只减掉自己那一份没被吃掉的并入量
		// （FIFO 口径：先扣原版护盾，再扣我们并入的那一份，所以剩下的并入量 = min(T, 结算后吸收)）。
		//
		// 为什么必须改：Enchantment Reforged 那类护盾模组是「读当前值 ± delta」的增量写法，
		// 写回绝对值会把它们在本击窗口内的护盾增长一并抹掉（ER 的 tick 记账还会据此误判"自己被打掉"，
		// 顺手缩小自己的护盾）。改增量之后，无外部写入时结果与旧公式**逐值相同**，有外部写入时则是
		// "在对方结果之上再减掉我们那一份"，两边的账都不丢。
		float leftoverTopUp = Math.min(pending.topUp(), after);
		player.setAbsorptionAmount(Math.max(0.0F, after - leftoverTopUp));
		if (ourActual > 0.0D) {
			// 这一击确实吃到池子 → 检查是否被打空（被吞掉的命中不会走到这里）
			SoulShield.shatterIfEmpty(player);
			DemonPact.shatterBlackHeartsIfEmpty(player);
		}
		// 1.8.5 第二轮：旧路径（Kilt 等收不到 LivingDamageEvent 的环境）里，把 Enchantment Reforged 那套
		// 「读当前值做增量记账」的护盾值也校正到与我们一致 —— 否则它会把我们退还的那一份记成自己的消耗。
		EnchantmentReforgedShieldCompat.reconcileAfterPoolWrite(player, player.getAbsorptionAmount(),
				shieldConsumed);
		logReconcile("对账", player, pending.absorptionBefore(), pending.topUp(), after, shieldConsumed,
				ourActual, refund);
	}

	/** 服务端每 tick（玩家 tick 结束）：上一 tick 的挂起项已经走完，可以安全对账 */
	public static void tickPlayer(ServerPlayer player) {
		// 新路径的登记只对"本 tick"有效：若到了 tick 末尾登记还在，说明这个环境其实**收不到**
		// LivingDamageEvent（例如 Kilt 跳过注入的那个版本）→ 立刻回退旧路径，避免池子干脆不生效。
		Long stamp = NEW_PATH_HITS.get(player.getUUID());
		if (stamp != null && stamp < now(player)) {
			NEW_PATH_HITS.remove(player.getUUID());
			if (modeForTest == Mode.AUTO && healthDamageEventWorks) {
				healthDamageEventWorks = false;
				SummyReliquary.LOGGER.warn(
						"[Summy Reliquary] 伤害池：本环境没有收到 LivingDamageEvent（注入被跳过），"
								+ "已回退到旧的「并入吸收值」口径");
			}
		}
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
		NEW_PATH_HITS.clear();
	}

	/** 自检用：该玩家是否有挂起记录 */
	public static boolean hasPending(LivingEntity entity) {
		return entity != null && PENDING.containsKey(entity.getUUID());
	}

	/** 自检用：直接挂一条"待对账"记录（1.7.3 用来验证创世纪会把它清掉） */
	public static void markPendingForTest(ServerPlayer player) {
		PENDING.put(player.getUUID(), new Pending(now(player), player.getAbsorptionAmount(), 0.0F,
				0.0D, 0.0D, player.damageSources().generic(), 0.0F));
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

	/**
	 * 新路径的日志文本（1.8.5 第二轮）：`LivingDamageEvent` 里按真实血伤扣池的那一行。
	 *
	 * <p>做成 public 方法是为了让自检能直接断言字段（与另外两个日志辅助一致）。
	 */
	public static String healthDamageLogLine(String side, String playerName, float absorptionAfter,
			double poolBefore, double share, float amountBefore, float amountAfter) {
		return String.format(
				"伤害池[%s|LivingDamage 扣池] %s：结算后护盾=%s（本击不动吸收值）、池子 %s → %s、"
						+ "本次扣池=%s、血伤 %s → %s",
				side, playerName, absorptionAfter, poolBefore, Math.max(0.0D, poolBefore - share), share,
				amountBefore, amountAfter);
	}

	/** 新路径的日志（受 {@code [combat] log_damage_pools} 控制） */
	private static void logHealthDamage(ServerPlayer player, float absorptionAfter, double poolBefore,
			double share, float amountBefore, float amountAfter) {
		if (!ReliquaryConfig.logDamagePools()) {
			return;
		}
		SummyReliquary.LOGGER.info("[Summy Reliquary] {}", healthDamageLogLine(
				fabricSide() ? "Fabric" : "Forge", player.getName().getString(), absorptionAfter, poolBefore,
				share, amountBefore, amountAfter));
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
