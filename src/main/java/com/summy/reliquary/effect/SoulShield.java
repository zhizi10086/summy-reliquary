package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 「魂心」池（1.6.2 起改为**独立池**，与黑心并列）。
 *
 * <p>口径：**吸收 / 护盾 → 魂心 → 黑心 → 真实血量**。
 * <ul>
 *     <li>容量 =（灵魂 +3 / 终末天启·神性 +2 心）× {@code absorption_per_soul_heart}；
 *     佩戴**咒印**时不提供魂心（它的灵魂魂心已经"转化"成黑心）；</li>
 *     <li>不再动原版吸收值 —— 旧版把份额加进吸收池会和其它模组的护盾互相挤占，已废弃；</li>
 *     <li>装备来源时补满，每 {@code soul_refresh_seconds} 秒补缺额，卸下清空；</li>
 *     <li><b>1.6.9 修正</b>：{@link #tickPlayer} 以前是**每 tick**调用却按"秒"递减计时器，
 *     于是"每 30 秒"实际只有 30 tick（≈1.5 秒）；现在挪到 {@code ReliquaryEvents} 的每秒分支。
 *     另外完全破碎会把计时器写回完整周期（打空后要再等满一轮才补）。</li>
 *     <li>**完全破碎**（从 &gt;0 归零）时：把 {@code soul_shatter_radius} 格内与神性光环同口径的敌人
 *     大力击退，并给玩家 {@code soul_shatter_invulnerable_seconds} 秒无敌（期间取消一切来源的伤害）。</li>
 * </ul>
 */
public final class SoulShield {
	/** 距离下一次「补满」还剩多少秒 */
	private static final Map<UUID, Integer> REFRESH_TIMERS = new HashMap<>();
	/** 破碎无敌的截止 gameTime */
	private static final Map<UUID, Long> GUARD_UNTIL = new HashMap<>();
	/** 上一次检测到的「是否佩戴魂心来源」 */
	private static final Map<UUID, Boolean> WEARING = new HashMap<>();
	/** 自检用：累计破碎次数 */
	private static int shatterCount = 0;

	private SoulShield() {
	}

	/** 是否佩戴了任意一个提供魂心的饰品（灵魂 / 终末天启 / 神性） */
	public static boolean wearsSource(LivingEntity entity) {
		return entity != null
				&& (CurioHelper.wears(entity, SummyReliquary.THE_SOUL.get())
						|| CurioHelper.wears(entity, SummyReliquary.FINAL_REVELATION.get())
						|| CurioHelper.wears(entity, SummyReliquary.GODHEAD.get()));
	}

	/** 魂心池容量（吸收点）；没戴来源、或戴着咒印时为 0 */
	public static double capacityFor(LivingEntity entity) {
		if (entity == null) {
			return 0.0D;
		}
		double hearts = 0.0D;
		// 1.6.2：戴咒印时"灵魂的魂心"已经转化成黑心，所以不算这一份（天启 / 神性的仍然算）
		if (CurioHelper.wears(entity, SummyReliquary.THE_SOUL.get())
				&& !com.summy.reliquary.effect.SatanicMark.wears(entity)) {
			hearts += ReliquaryConfig.soulHearts();
		}
		if (CurioHelper.wears(entity, SummyReliquary.FINAL_REVELATION.get())
				|| CurioHelper.wears(entity, SummyReliquary.GODHEAD.get())) {
			hearts += ReliquaryConfig.finalSoulHearts();
		}
		return hearts * ReliquaryConfig.absorptionPerSoulHeart();
	}

	/** 兼容旧自检 / 日志的入口：返回池子点数（不再是"吸收池里的份额"） */
	public static double share(LivingEntity entity) {
		return points(entity);
	}

	/** 当前池子点数 */
	public static double points(LivingEntity entity) {
		return PlayerFlags.soulHeartPoints(entity);
	}

	public static void setPoints(ServerPlayer player, double value) {
		PlayerFlags.setSoulHeartPoints(player, value);
	}

	/** 登录 / 换维度 / 复活：按容量补满（池子被外部清过时重新给一份） */
	public static void onJoin(LivingEntity entity) {
		if (!(entity instanceof ServerPlayer player)) {
			return;
		}
		double capacity = capacityFor(player);
		REFRESH_TIMERS.put(player.getUUID(), refreshIntervalSeconds());
		WEARING.put(player.getUUID(), capacity > 0.0D);
		if (capacity <= 0.0D) {
			setPoints(player, 0.0D);
		} else if (points(player) <= 0.0D || points(player) > capacity) {
			setPoints(player, capacity);
		}
		DemonPact.sync(player);
	}

	/** 刚装上魂心来源：直接补满 */
	public static void onEquipped(LivingEntity entity) {
		if (!(entity instanceof ServerPlayer player)) {
			return;
		}
		double capacity = capacityFor(player);
		if (capacity <= 0.0D) {
			return;
		}
		setPoints(player, capacity);
		REFRESH_TIMERS.put(player.getUUID(), refreshIntervalSeconds());
		WEARING.put(player.getUUID(), true);
		DemonPact.sync(player);
	}

	/** 卸下魂心来源：清空池子 */
	public static void onUnequipped(LivingEntity entity) {
		if (!(entity instanceof ServerPlayer player)) {
			return;
		}
		setPoints(player, 0.0D);
		REFRESH_TIMERS.remove(player.getUUID());
		WEARING.put(player.getUUID(), false);
		DemonPact.sync(player);
	}

	/** 服务端每秒：装备状态变化即补满 / 清空；每 N 秒只补缺额 */
	public static void tickPlayer(ServerPlayer player) {
		double capacity = capacityFor(player);
		boolean wearing = capacity > 0.0D;
		boolean wasWearing = WEARING.getOrDefault(player.getUUID(), false);
		if (wearing != wasWearing) {
			if (wearing) {
				onEquipped(player);
			} else {
				onUnequipped(player);
			}
			return;
		}
		if (!wearing) {
			return;
		}
		// 容量下降（例如换上咒印、摘下天启）时把超出部分夹回
		if (points(player) > capacity) {
			setPoints(player, capacity);
			DemonPact.sync(player);
		}
		int remaining = REFRESH_TIMERS.getOrDefault(player.getUUID(), refreshIntervalSeconds()) - 1;
		if (remaining > 0) {
			REFRESH_TIMERS.put(player.getUUID(), remaining);
			return;
		}
		REFRESH_TIMERS.put(player.getUUID(), refreshIntervalSeconds());
		if (points(player) < capacity) {
			setPoints(player, capacity);
			DemonPact.sync(player);
		}
	}

	/**
	 * 扣池（1.6.4：由 {@link DamagePools} 按"这一次实际被吸收的量"调用）。
	 *
	 * <p>池子从 &gt;0 归零的那一次触发"完全破碎"。之所以改成这个入口：Kilt 上事件金额不可靠，
	 * 所以池子改成"临时并入原版吸收、结算后再拆分"的路径（见 {@link DamagePools}）。
	 */
	public static void consume(ServerPlayer player, double amount) {
		if (player == null || amount <= 0.0D) {
			return;
		}
		double pool = Math.min(points(player), capacityFor(player));
		if (pool <= 0.0D) {
			return;
		}
		double absorbed = Math.min(pool, amount);
		double remaining = pool - absorbed;
		setPoints(player, remaining);
		if (remaining <= 0.0D) {
			shatter(player);
		}
		DemonPact.sync(player);
	}

	/**
	 * 1.6.5：只扣池、**不**触发破碎（由 {@link DamagePools} 在命中前调用，破碎推迟到对账时判定，
	 * 这样"被无敌帧/格挡吞掉的命中"不会假触发）。
	 */
	public static void deduct(ServerPlayer player, double amount) {
		if (player == null || amount <= 0.0D) {
			return;
		}
		double pool = Math.min(points(player), capacityFor(player));
		if (pool <= 0.0D) {
			return;
		}
		setPoints(player, Math.max(0.0D, pool - Math.min(pool, amount)));
		DemonPact.sync(player);
	}

	/** 1.6.5：对账时退还"多扣"的部分（夹在容量内） */
	public static void refund(ServerPlayer player, double amount) {
		if (player == null || amount <= 0.0D) {
			return;
		}
		double capacity = capacityFor(player);
		if (capacity <= 0.0D) {
			return;
		}
		setPoints(player, Math.min(capacity, points(player) + amount));
		DemonPact.sync(player);
	}

	/** 1.6.5：对账时判定"这一击把池子打空了" → 触发完全破碎（由调用方保证这一击确实吃到池子） */
	public static void shatterIfEmpty(ServerPlayer player) {
		if (player == null || capacityFor(player) <= 0.0D || points(player) > 0.0D) {
			return;
		}
		shatter(player);
		DemonPact.sync(player);
	}

	/** 魂心完全破碎：半径内的敌人被大力击退 + 玩家获得数秒无敌 */
	private static void shatter(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		// 1.6.9：破碎后重新计时，避免"打空后马上又被补满"（周期与刚装上时一致）
		REFRESH_TIMERS.put(player.getUUID(), refreshIntervalSeconds());
		double radius = ReliquaryConfig.soulShatterRadius();
		double strength = ReliquaryConfig.soulShatterKnockback();
		int hits = 0;
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate(radius),
				entity -> Godhead.isAuraTarget(player, entity))) {
			if (target.distanceTo(player) > radius) {
				continue;
			}
			Vec3 away = target.position().subtract(player.position());
			double dx = away.x;
			double dz = away.z;
			double length = Math.sqrt(dx * dx + dz * dz);
			if (length < 1.0E-4D) {
				dx = 1.0D;
				dz = 0.0D;
			} else {
				dx /= length;
				dz /= length;
			}
			if (strength > 0.0D) {
				// 原版 knockback 的方向参数是按 -x / -z 用的，这里取反得到"向外飞"
				target.knockback((float) strength, -dx, -dz);
				target.push(0.0D, 0.35D, 0.0D);
				target.hurtMarked = true;
			}
			hits++;
		}
		double seconds = ReliquaryConfig.soulShatterInvulnerableSeconds();
		if (seconds > 0.0D) {
			GUARD_UNTIL.put(player.getUUID(), level.getGameTime() + (long) (seconds * 20.0D));
		}
		shatterCount++;
		SummyReliquary.LOGGER.info("[Summy Reliquary] {} 的魂心完全破碎：击退 {} 个目标、获得 {} 秒无敌",
				player.getName().getString(), hits, seconds);
	}

	/** 受伤前：破碎后的无敌期内取消一切伤害（与神圣斗篷同一套写法） */
	public static void onHurt(LivingHurtEvent event) {
		if (event.getEntity() instanceof ServerPlayer player && isGuarded(player)) {
			event.setAmount(0.0F);
			event.setCanceled(true);
		}
	}

	public static boolean isGuarded(ServerPlayer player) {
		Long until = GUARD_UNTIL.get(player.getUUID());
		if (until == null) {
			return false;
		}
		if (player.serverLevel().getGameTime() >= until) {
			GUARD_UNTIL.remove(player.getUUID());
			return false;
		}
		return true;
	}

	/** 玩家退出：清掉内存记录（池子已落盘） */
	public static void forget(LivingEntity entity) {
		UUID id = entity.getUUID();
		REFRESH_TIMERS.remove(id);
		GUARD_UNTIL.remove(id);
		WEARING.remove(id);
	}

	/** 服务器停止：清掉内存计时 */
	public static void clear() {
		REFRESH_TIMERS.clear();
		GUARD_UNTIL.clear();
		WEARING.clear();
		shatterCount = 0;
	}

	/** 自检用：把下一次「补满」压到指定秒数之后 */
	public static void setRefreshTimer(LivingEntity entity, int seconds) {
		REFRESH_TIMERS.put(entity.getUUID(), Math.max(1, seconds));
	}

	/** 自检用：累计破碎次数与无敌截止时间 */
	public static int shatterCount() {
		return shatterCount;
	}

	/** 自检用：清掉破碎无敌（不影响池子与装备状态） */
	public static void clearGuardForTest(ServerPlayer player) {
		GUARD_UNTIL.remove(player.getUUID());
	}

	public static long guardUntil(ServerPlayer player) {
		return GUARD_UNTIL.getOrDefault(player.getUUID(), 0L);
	}

	/** 自检用：人为设置一次破碎无敌窗口（1.7.3 用来验证创世纪会把它清掉） */
	public static void setGuardForTest(ServerPlayer player, int ticks) {
		GUARD_UNTIL.put(player.getUUID(), player.level().getGameTime() + ticks);
	}

	/** 补满间隔（秒） */
	private static int refreshIntervalSeconds() {
		return Math.max(1, ReliquaryConfig.soulRefreshSeconds());
	}
}
