package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 「神圣斗篷」的受击无敌（1.5.4）。
 *
 * <p>规则：佩戴者**受击后**获得一小段无敌（默认 1 秒，见配置 {@code holy_mantle}）；
 * 无敌期内的一切伤害都被取消（不区分来源，虚空与指令同样拦），期间不再刷新、不叠层。
 *
 * <p>实现照搬「免死」的守卫写法：只存在内存里，服务端每 tick 清理过期项，玩家登出清掉。
 */
public final class HolyMantle {
	/** 玩家 UUID → 无敌守卫的到期游戏 tick */
	private static final Map<UUID, Long> GUARD_UNTIL = new HashMap<>();

	/** 自检用：开启次数 */
	private static int triggerCount;

	private HolyMantle() {
	}

	/**
	 * 受伤处理：无敌期内直接取消；否则佩戴者在这次伤害照常结算之后开启无敌窗口。
	 *
	 * <p>顺序很关键：本方法要放在 {@code DeathImmunity.onHurt} 之后、七罪/七德倍率之前。
	 */
	public static void onHurt(LivingHurtEvent event) {
		if (!(event.getEntity() instanceof ServerPlayer player) || !ReliquaryConfig.enableHolyMantle()) {
			return;
		}
		if (isGuarded(player)) {
			event.setAmount(0.0F);
			event.setCanceled(true);
			return;
		}
		// 1.8.2：献祭的自伤不算"受击" —— 不触发斗篷的无敌窗口
		if (com.summy.reliquary.effect.Sacrifice.isSelfDamage(event.getSource())) {
			return;
		}
		if (event.getAmount() <= 0.0F) {
			return;
		}
		if (!CurioHelper.wears(player, SummyReliquary.HOLY_MANTLE.get())) {
			return;
		}
		// 这一次伤害照常结算，随后进入无敌窗口
		GUARD_UNTIL.put(player.getUUID(), player.serverLevel().getGameTime()
				// 1.6.10：神性 + 斗篷同时佩戴时窗口延长（配置见 [holy_mantle]）
				+ Synergies.holyMantleInvulnerableTicks(player));
		triggerCount++;
	}

	/** 该玩家当前是否处在斗篷的无敌期 */
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

	/** 服务端每 tick 清理过期的守卫 */
	public static void tick(MinecraftServer server) {
		if (GUARD_UNTIL.isEmpty()) {
			return;
		}
		long now = server.overworld().getGameTime();
		GUARD_UNTIL.entrySet().removeIf(entry -> entry.getValue() <= now);
	}

	/** 玩家退出时清掉守卫，避免残留 */
	public static void forget(ServerPlayer player) {
		GUARD_UNTIL.remove(player.getUUID());
	}

	/** 自检用：守卫到期 tick（没在无敌期返回 0） */
	public static long guardUntil(ServerPlayer player) {
		return isGuarded(player) ? GUARD_UNTIL.getOrDefault(player.getUUID(), 0L) : 0L;
	}

	/** 自检用：开启次数 */
	public static int triggerCount() {
		return triggerCount;
	}

	/** 自检用：清空计数与守卫 */
	public static void reset() {
		triggerCount = 0;
		GUARD_UNTIL.clear();
	}
}
