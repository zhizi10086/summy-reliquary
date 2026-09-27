package com.summy.reliquary.effect;

import com.summy.reliquary.config.ReliquaryConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 受伤无敌帧（1.6.3 返工：**沿用原版逻辑，只改窗口长度**）。
 *
 * <p>原版 {@code LivingEntity#hurt} 的判定是 `invulnerableTime > 10.0F`，而且 {@code baseTick} 每 tick 减 1，
 * 所以**有效窗口 = 字段值 − 10**、原版字段写的是 20（有效 10 tick）。我们据此写成
 * `字段值 = 有效窗口 + 10`：**帧伤/持续环境伤害 10 tick（= 原版）**、**一般受伤 5 tick**。
 *
 * <p>差值结算、`#bypasses_cooldown` 的"原始数值 + 刷新窗口 + 更新 lastHurt"、窗口内挡下，全部由原版负责，
 * 我们只在**新的一击**（原版刚把字段写成 20）时把它改写成我们想要的窗口长度，避免和原版双重判定打架。
 */
public final class CombatTuning {
	/** 原版判定阈值：字段值必须大于它，无敌才生效 */
	public static final int VANILLA_GATE = 10;
	/** 原版"新的一击"会把字段写成这个值 */
	private static final int VANILLA_FRESH = 20;

	/** 汇总日志：实体 →（伤害来源 id → [尝试次数, 落地次数]） */
	private static final Map<UUID, Map<String, int[]>> STATS = new HashMap<>();
	/** Fabric 侧（Kilt）：本 tick 该用的有效窗口（tick 末尾钳位用） */
	private static final Map<UUID, Integer> FABRIC_WINDOW = new HashMap<>();
	/** 自检用：在 Forge 上强制按 Fabric 侧处理 */
	private static boolean fabricForTest = false;

	/**
	 * 「帧伤」的显式伤害 id。
	 *
	 * <p>注意 {@code DamageSource#getMsgId()} 返回的是伤害类型的 **{@code message_id}**（死亡文本键），
	 * 原版是 camelCase 且**不带命名空间**（`cactus` / `sweetBerryBush`），所以两种写法都收。
	 */
	private static final java.util.Set<String> EXPLICIT_FRAME_IDS = java.util.Set.of(
			"cactus", "minecraft:cactus",
			"sweetBerryBush", "minecraft:sweet_berry_bush");

	private CombatTuning() {
	}

	/**
	 * 某个伤害 id 是否被配置 {@code frame_damage_ids} 点名（归入 10 tick 档）。
	 *
	 * <p>为了写得顺手，三种写法都能匹配：`msgId` 原样（`summy-reliquary.hellfire` / `cactus`）、
	 * 注册表 id（`mod:laser`，把 `:` 归一成 `.` 再比）、以及省略 `minecraft:` 前缀的短名。
	 */
	public static boolean isConfiguredFrameDamage(String msgId) {
		if (msgId == null) {
			return false;
		}
		String normalizedMsg = msgId.replace(':', '.');
		for (String extra : ReliquaryConfig.frameDamageIds()) {
			if (extra == null) {
				continue;
			}
			String trimmed = extra.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			if (trimmed.equals(msgId)) {
				return true;
			}
			String normalized = trimmed.replace(':', '.');
			if (normalized.equals(normalizedMsg)) {
				return true;
			}
			// 允许只写短名：minecraft:cactus 能匹配 msgId "cactus"
			if (normalized.startsWith("minecraft.") && normalizedMsg.equals(normalized.substring(10))) {
				return true;
			}
		}
		return false;
	}

	/** 这条伤害是不是"帧伤 / 持续环境伤害"（决定用哪一档窗口） */
	public static boolean isFrameDamage(DamageSource source) {
		if (source == null) {
			return false;
		}
		if (source.is(DamageTypeTags.IS_FIRE) || source.is(DamageTypeTags.IS_FREEZING)
				|| source.is(DamageTypeTags.IS_DROWNING) || source.is(DamageTypeTags.IS_FALL)) {
			return true;
		}
		String id = source.getMsgId();
		return EXPLICIT_FRAME_IDS.contains(id) || isConfiguredFrameDamage(id);
	}

	/** 该来源对应的有效窗口（tick）；0 表示"这一档不改，交回原版" */
	public static int windowFor(DamageSource source) {
		return isFrameDamage(source)
				? ReliquaryConfig.invulnerabilityTicksFrameDamage()
				: ReliquaryConfig.invulnerabilityTicks();
	}

	/**
	 * 在 {@code LivingHurtEvent} 里改写窗口长度。
	 *
	 * <p>跳过 `#bypasses_cooldown` 与本模组特效伤害：它们按原版"原始数值落地 + 刷新窗口"处理。
	 */
	public static void applyWindow(LivingHurtEvent event) {
		LivingEntity entity = event.getEntity();
		DamageSource source = event.getSource();
		record(entity, source, false);
		if (!ReliquaryConfig.enableInvulnerabilityChange()
				|| source.is(DamageTypeTags.BYPASSES_COOLDOWN)
				|| HolyLightEffect.isDivine(source)) {
			return;
		}
		int window = windowFor(source);
		if (window <= 0) {
			return;
		}
		// 1.6.5：Fabric 侧（Kilt）的事件发生在 hurt 开头，字段还没写成 20，写不进去 ——
		// 所以这里只**登记**本击该用的窗口，等玩家 tick 末尾再钳位。
		if (fabricSide() && isNewHit(entity, source)) {
			FABRIC_WINDOW.put(entity.getUUID(), window);
		}
		// 只有"新的一击"（原版刚写成 20）才改写窗口；差值命中保持原版的递减，不额外刷新
		if (entity.invulnerableTime >= VANILLA_FRESH) {
			entity.invulnerableTime = window + VANILLA_GATE;
		}
	}

	/**
	 * 玩家 tick 末尾：把 Fabric 侧登记的窗口钳到 `窗口 + 10`。
	 *
	 * <p>时机说明：Kilt 上原版会在 `hurt` 里把字段写成 20，到玩家 tick 结束已经递减 1 → 19；
	 * 钳到 15（一般 5 tick 档）或 20（帧伤 10 tick 档），即达到"一般 0.25 秒 / 帧伤 0.5 秒"。
	 * 误差最多 1 tick。Forge 侧因为事件里已经写过，钳位是空操作。
	 */
	public static void clampFabricWindow(ServerPlayer player) {
		if (player == null) {
			return;
		}
		Integer window = FABRIC_WINDOW.remove(player.getUUID());
		if (window == null || window <= 0) {
			return;
		}
		int target = window + VANILLA_GATE;
		if (player.invulnerableTime > target) {
			player.invulnerableTime = target;
		}
	}

	/** 是否按 Fabric 侧处理（Kilt / Connector，或自检强制） */
	public static boolean fabricSide() {
		return fabricForTest || com.summy.reliquary.effect.DamagePools.fabricSide();
	}

	/** 自检用：强制按 Fabric 侧处理 */
	public static void setFabricForTest(boolean value) {
		fabricForTest = value;
	}

	/** 自检用：直接登记本击该用的窗口 */
	public static void recordWindowForTest(ServerPlayer player, int window) {
		FABRIC_WINDOW.put(player.getUUID(), window);
	}

	/** 该来源是不是"新的一击"（Fabric 侧判据：窗口内的命中会被原版吞掉） */
	private static boolean isNewHit(LivingEntity entity, DamageSource source) {
		return entity.invulnerableTime <= VANILLA_GATE || source.is(DamageTypeTags.BYPASSES_COOLDOWN);
	}

	/** 在 {@code LivingAttackEvent} 里统计"尝试"（这一步在原版无敌判定之前） */
	public static void recordAttempt(LivingAttackEvent event) {
		record(event.getEntity(), event.getSource(), true);
	}

	private static void record(LivingEntity entity, DamageSource source, boolean attempt) {
		if (entity == null || source == null) {
			return;
		}
		Map<String, int[]> perSource = STATS.computeIfAbsent(entity.getUUID(), key -> new HashMap<>());
		int[] counts = perSource.computeIfAbsent(source.getMsgId(), key -> new int[2]);
		counts[attempt ? 0 : 1]++;
	}

	/**
	 * 每 5 秒汇总一条 INFO（`[combat] log_iframe_throttle`）：
	 * 打印"尝试 / 落地 / 被无敌帧挡下"的次数与来源，方便确认"帧伤"到底是谁在打。
	 */
	public static void flushLog(ServerPlayer player) {
		Map<String, int[]> perSource = STATS.remove(player.getUUID());
		if (perSource == null || perSource.isEmpty() || !ReliquaryConfig.logIframeThrottle()) {
			return;
		}
		int attempts = 0;
		int landed = 0;
		StringBuilder detail = new StringBuilder();
		for (Map.Entry<String, int[]> entry : perSource.entrySet()) {
			int tried = entry.getValue()[0];
			int hit = entry.getValue()[1];
			attempts += tried;
			landed += hit;
			if (tried > hit) {
				if (detail.length() > 0) {
					detail.append("、");
				}
				detail.append(entry.getKey()).append('×').append(tried - hit);
			}
		}
		if (attempts <= 0) {
			return;
		}
		com.summy.reliquary.SummyReliquary.LOGGER.info(
				"[Summy Reliquary] 受伤节流（最近 5 秒）：尝试 {} 次、落地 {} 次、被无敌帧挡下 {} 次{}",
				attempts, landed, Math.max(0, attempts - landed),
				detail.length() == 0 ? "" : "（挡住来源：" + detail + "）");
	}

	/** 玩家退出 / 服务器停止：清掉统计 */
	public static void forget(LivingEntity entity) {
		if (entity != null) {
			STATS.remove(entity.getUUID());
			FABRIC_WINDOW.remove(entity.getUUID());
		}
	}

	public static void clear() {
		STATS.clear();
		FABRIC_WINDOW.clear();
	}
}
