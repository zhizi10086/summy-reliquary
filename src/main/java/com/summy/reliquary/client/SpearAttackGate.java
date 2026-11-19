package com.summy.reliquary.client;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.item.SpearItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;

/**
 * 长矛的「没冷却完不能出手」门禁（1.8.4）。
 *
 * <p>只做一件事：主手拿着两把长矛之一、攻击强度又没恢复到位时**吞掉左键** ——
 * 不出手、不挥臂，也不重置攻击强度，因此连点不会推迟下一次真攻击。
 *
 * <p><b>瞄准方块一律放行</b>：Forge 在 {@code continueAttack}（挖方块）路径上也会 post 同一个事件，
 * 不分流的话"冷却未满时用手持矛挖方块"会被连带取消、直接挖不动。
 *
 * <p>本类**必须是顶层类**并带 {@code @Mod.EventBusSubscriber} —— 嵌套类的 FORGE 事件
 * 在本工程实测不会被注册（见 {@code ReliquaryClientTicks} 的注释）。
 *
 * <p><b>历史</b>：1.8.4 曾在这里实现过第一人称「左键戳刺」动画（接管
 * {@code applyForgeHandTransform}），因观感调不到位已**整体移除**，只保留本门禁；
 * 重启该动画的思路与踩过的坑记在本地维护的开发待办中（不随仓库分发）。
 */
@Mod.EventBusSubscriber(modid = SummyReliquary.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE,
		value = Dist.CLIENT)
public final class SpearAttackGate {
	/** 上一次真正放行的玩家实例；重生 / 换维度会替换实例。 */
	private static LocalPlayer lastPlayer;
	/** 上一次真正放行的客户端 tick */
	private static long lastAttackTick = Long.MIN_VALUE;
	/** Minecraft.missTime：>0 时 startAttack 直接返回 */
	private static Field missTimeField;
	private static boolean missTimeResolved;
	private static int appliedMissTime;

	private SpearAttackGate() {
	}

	/** 冷却未满时吞掉左键（瞄准方块时放行） */
	@SubscribeEvent
	public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
		if (!event.isAttack()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null || !isSpearInMainHand(player) || isChargingSpear(player)) {
			return;
		}
		// 挖掘：只在真正按住破坏方块时放行；点击方块的空挥仍走门禁
		if (aimsAtBlock(client) && client.gameMode != null && client.gameMode.isDestroying()) {
			return;
		}
		// 空挥不重置 attackStrengthTicker，attackAnim 也可能在按键事件时尚未更新；
		// 这里用自己记录的放行 tick，按 20 / 攻速严格拦截。
		int cooldown = SpearAnimation.swingDuration((float) player.getAttributeValue(Attributes.ATTACK_SPEED));
		long now = player.tickCount;
		boolean sameSession = ensureSession(client, player, now);
		if (isCoolingDown(sameSession, now, lastAttackTick, cooldown)) {
			event.setCanceled(true);
			event.setSwingHand(false);
			// 硬限：把 missTime 拉到剩余冷却，按住 / 连点也不会再进 startAttack
			if (!aimsAtBlock(client)) {
				applyMissTime(client, (int) (cooldown - (now - lastAttackTick)));
			}
		} else {
			lastPlayer = player;
			lastAttackTick = now;
		}
	}

	/**
	 * 每 tick 结尾重设 missTime。
	 *
	 * <p>{@code Minecraft.startAttack} 在 onClickInput 之后会把 missTime 重置成 10，
	 * 事件里设的剩余冷却会被覆盖；在 END 段再设一次，保证下一 tick 的 startAttack 继续被挡住。
	 */
	@SubscribeEvent
	public static void onClientTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null) {
			if (lastPlayer != null) {
				resetSession(client, null);
			}
			return;
		}
		if (!isSpearInMainHand(player) || isChargingSpear(player) || aimsAtBlock(client)
				|| !client.options.keyAttack.isDown()) {
			releaseMissTime(client);
			return;
		}
		int cooldown = SpearAnimation.swingDuration((float) player.getAttributeValue(Attributes.ATTACK_SPEED));
		long now = player.tickCount;
		boolean sameSession = ensureSession(client, player, now);
		if (isCoolingDown(sameSession, now, lastAttackTick, cooldown)) {
			applyMissTime(client, (int) (cooldown - (now - lastAttackTick)) + 1);
		} else {
			releaseMissTime(client);
		}
	}

	/**
	 * 冷却判定纯函数：只有同一 LocalPlayer、tick 未回退且确实是过去时才可能处于冷却。
	 */
	public static boolean isCoolingDown(boolean sameSession, long now, long lastAttackTick, int cooldown) {
		return sameSession && lastAttackTick != Long.MIN_VALUE
				&& now >= lastAttackTick && now - lastAttackTick < cooldown;
	}

	/** 新玩家实例、重登、换维度或重生导致 tickCount 回退时，建立全新会话。 */
	private static boolean ensureSession(Minecraft client, LocalPlayer player, long now) {
		if (lastPlayer == player && lastAttackTick != Long.MIN_VALUE && now >= lastAttackTick) {
			return true;
		}
		resetSession(client, player);
		return false;
	}

	private static void resetSession(Minecraft client, LocalPlayer player) {
		lastPlayer = player;
		lastAttackTick = player == null ? Long.MIN_VALUE : player.tickCount;
		releaseMissTime(client);
	}

	private static boolean aimsAtBlock(Minecraft client) {
		return client.hitResult != null && client.hitResult.getType() == HitResult.Type.BLOCK;
	}

	private static void applyMissTime(Minecraft client, int ticks) {
		int value = Math.max(1, ticks);
		appliedMissTime = setMissTime(client, value) ? value : 0;
	}

	/** 离开长矛拦截状态后只清理自己的计数，原版可能已递减一刻。 */
	private static void releaseMissTime(Minecraft client) {
		if (appliedMissTime == 0) {
			return;
		}
		int previous = appliedMissTime;
		appliedMissTime = 0;
		Field field = missTimeField;
		if (field == null) {
			return;
		}
		try {
			int current = field.getInt(client);
			if (current == previous || current == previous - 1) {
				setMissTime(client, 0);
			}
		} catch (IllegalAccessException | RuntimeException failure) {
			missTimeField = null;
			logMissTimeFallback(failure);
		}
	}

	/** 每个客户端只解析一次字段；失败后继续沿用输入事件取消。 */
	private static Field resolveMissTime() {
		if (!missTimeResolved) {
			missTimeResolved = true;
			try {
				Field field = ObfuscationReflectionHelper.findField(Minecraft.class, "f_91078_");
				field.setAccessible(true);
				missTimeField = field;
			} catch (RuntimeException failure) {
				logMissTimeFallback(failure);
			}
		}
		return missTimeField;
	}

	private static boolean setMissTime(Minecraft client, int ticks) {
		Field field = resolveMissTime();
		if (field == null) {
			return false;
		}
		try {
			field.setInt(client, ticks);
			return true;
		} catch (IllegalAccessException | RuntimeException failure) {
			missTimeField = null;
			logMissTimeFallback(failure);
			return false;
		}
	}

	private static void logMissTimeFallback(Exception failure) {
		SummyReliquary.LOGGER.warn("无法设置 Minecraft.missTime，长矛空挥硬限降级为事件取消", failure);
	}

	/** 主手是不是两把矛之一 */
	private static boolean isSpearInMainHand(LocalPlayer player) {
		return player.getMainHandItem().getItem() instanceof SpearItem;
	}

	/** 是否正在举矛蓄力（投掷前摇）—— 这种时候交还原版 */
	private static boolean isChargingSpear(LocalPlayer player) {
		return player.isUsingItem() && player.getUseItem().getItem() instanceof SpearItem;
	}
}
