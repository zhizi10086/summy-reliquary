package com.summy.reliquary.effect;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 「神性回溯 / 恶魔形态」的 X 键入口（1.8.2）。
 *
 * <p>两件终极饰品占同一个「启示之座」栏位，所以一次按键只会命中其中一条分支：
 * 佩戴神性 → 传送回上一次死亡地点；佩戴亚巴顿 → 主动激活恶魔形态。
 */
public final class DivineActions {
	private DivineActions() {
	}

	/** 服务端处理一次 X 键 */
	public static void perform(ServerPlayer player) {
		if (player == null) {
			return;
		}
		if (Godhead.active(player)) {
			recall(player);
			return;
		}
		if (Abaddon.wears(player)) {
			activateDemonForm(player);
		}
	}

	/** 神性：回溯到上一次死亡地点（可反复使用、无冷却） */
	public static boolean recall(ServerPlayer player) {
		if (!PlayerFlags.hasLastDeath(player)) {
			actionBar(player, Component.translatable("message.summy-reliquary.godhead.no_death_point"));
			return false;
		}
		ResourceLocation id = ResourceLocation.tryParse(PlayerFlags.lastDeathDimension(player));
		MinecraftServer server = player.getServer();
		ServerLevel target = id == null || server == null
				? null
				: server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
		if (target == null) {
			actionBar(player, Component.translatable("message.summy-reliquary.godhead.no_death_point"));
			return false;
		}
		double x = PlayerFlags.lastDeathX(player);
		double y = PlayerFlags.lastDeathY(player);
		double z = PlayerFlags.lastDeathZ(player);
		if (player.serverLevel() == target) {
			player.teleportTo(x, y, z);
		} else {
			player.teleportTo(target, x, y, z, player.getYRot(), player.getXRot());
		}
		target.sendParticles(ParticleTypes.WAX_ON, x, y + 0.9D, z, 20, 0.5D, 0.5D, 0.5D, 0.0D);
		actionBar(player, Component.translatable("message.summy-reliquary.godhead.recall"));
		return true;
	}

	/** 亚巴顿：主动激活恶魔形态（冷却未好时给出剩余秒数） */
	public static boolean activateDemonForm(ServerPlayer player) {
		if (Abaddon.activateManually(player)) {
			actionBar(player, Component.translatable("message.summy-reliquary.abaddon.form_active"));
			return true;
		}
		actionBar(player, Component.translatable("message.summy-reliquary.abaddon.form_cooldown",
				Abaddon.cooldownSecondsLeft(player)));
		return false;
	}

	/** 行动栏提示 */
	private static void actionBar(ServerPlayer player, Component message) {
		player.displayClientMessage(message, true);
	}
}
