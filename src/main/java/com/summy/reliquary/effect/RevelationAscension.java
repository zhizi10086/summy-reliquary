package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.config.ReliquaryConfig;
import com.summy.reliquary.slot.ReliquarySlots;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 「伯列恒之星 → 终末天启」的夜间转化。
 *
 * <p>条件：佩戴伯列恒之星、坐标已揭示、当前维度是夜晚、与揭示坐标的水平距离在容差内（忽略 Y）、
 * 头顶露天（{@code canSeeSky}），并**连续**保持 {@code transform_seconds} 秒。
 * 满足后把启示之座栏位里的伯列恒之星**原地替换**为终末天启。
 */
public final class RevelationAscension {
	/** 每个玩家的连续满足计数（tick） */
	private static final Map<UUID, Integer> PROGRESS = new HashMap<>();
	/** 静止判定用的锚点：开始计时时的位置 */
	private static final Map<UUID, Vec3> ANCHORS = new HashMap<>();

	private RevelationAscension() {
	}

	/** 服务端每 5 tick 检查一次 */
	public static void tickServer(MinecraftServer server) {
		if (server.getTickCount() % 5 != 0) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			tick(player);
		}
	}

	/** 玩家退出时清掉进度 */
	public static void forget(ServerPlayer player) {
		PROGRESS.remove(player.getUUID());
		ANCHORS.remove(player.getUUID());
	}

	private static void tick(ServerPlayer player) {
		boolean ready = CurioHelper.wears(player, SummyReliquary.STAR_OF_BETHLEHEM.get())
				&& RevelationTracker.isRevealed(player)
				&& conditionsMet(player);
		if (!ready) {
			interrupt(player);
			return;
		}

		// 静止判定：位置相对锚点偏移超过容差就算移动，计时从头开始
		UUID id = player.getUUID();
		Vec3 anchor = ANCHORS.get(id);
		if (anchor == null) {
			ANCHORS.put(id, player.position());
		} else if (player.position().distanceTo(anchor) > ReliquaryConfig.transformMaxMoveBlocks()) {
			interrupt(player);
			ANCHORS.put(id, player.position());
			return;
		}

		int ticks = PROGRESS.merge(id, 5, Integer::sum);
		int needed = Math.max(1, ReliquaryConfig.transformSeconds()) * 20;
		if (ticks >= needed) {
			PROGRESS.remove(id);
			ANCHORS.remove(id);
			convert(player);
			return;
		}
		if (ticks % 20 == 0) {
			player.displayClientMessage(Component.translatable("message.summy-reliquary.ascension.progress",
					ticks / 20, needed / 20), true);
		}
	}

	/** 条件中断：清掉进度与锚点；计时超过 1 秒才提示一次，避免移动时刷屏 */
	private static void interrupt(ServerPlayer player) {
		Integer progress = PROGRESS.remove(player.getUUID());
		ANCHORS.remove(player.getUUID());
		if (progress != null && progress >= 20) {
				player.displayClientMessage(
						Component.translatable("message.summy-reliquary.ascension.interrupted"), true);
		}
	}

	/** 夜晚 + 露天 + 在揭示坐标附近（忽略 Y） */
	private static boolean conditionsMet(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		// 注意：服务端的 Level#isNight() 依赖 skyDarken（只由客户端渲染刷新），专用服务器上不可靠，
		// 这里直接按世界时间判断夜晚（13000 ~ 23000 为夜晚）
		long time = level.getDayTime() % 24000L;
		if (time < 13000L || time >= 23000L) {
			return false;
		}
		double dx = player.getX() - RevelationTracker.revealX(player);
		double dz = player.getZ() - RevelationTracker.revealZ(player);
		if (Math.sqrt(dx * dx + dz * dz) > ReliquaryConfig.transformRadius()) {
			return false;
		}
		return level.canSeeSky(player.blockPosition());
	}

	/** 把启示之座栏位里的伯列恒之星原地换成终末天启 */
	private static void convert(ServerPlayer player) {
		ICuriosItemHandler handler = CuriosApi.getCuriosInventory(player).orElse(null);
		if (handler == null) {
			return;
		}
		var stacks = handler.getStacksHandler(ReliquarySlots.REVELATION).orElse(null);
		if (stacks == null) {
			return;
		}
		for (int index = 0; index < stacks.getSlots(); index++) {
			if (!stacks.getStacks().getStackInSlot(index).is(SummyReliquary.STAR_OF_BETHLEHEM.get())) {
				continue;
			}
			handler.setEquippedCurio(ReliquarySlots.REVELATION, index,
					new ItemStack(SummyReliquary.FINAL_REVELATION.get()));
			// 属性与黄血立刻跟随新饰品
			AttributeManager.apply(player);
			SoulShield.onEquipped(player);
			player.displayClientMessage(Component.translatable("message.summy-reliquary.ascension.done"), true);
			player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.0F, 0.8F);
			SummyReliquary.LOGGER.info("[Summy Reliquary] {} 的伯列恒之星转化为终末天启",
					player.getName().getString());
			// 进度：启示
			com.summy.reliquary.advancement.ReliquaryAdvancements.fire(player,
					com.summy.reliquary.advancement.ReliquaryAdvancements.REVELATION_ASCENDED);
			// 1.6.10：记下"该玩家获取过启示"（恶魔交易从此彻底关闭；创世纪会清掉它）
			PlayerFlags.setRevelationObtained(player, true);
			RevelationTracker.sync(player);
			return;
		}
	}
}
