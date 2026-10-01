package com.summy.reliquary.effect;

import com.summy.reliquary.SummyReliquary;
import com.summy.reliquary.sin.Sin;
import com.summy.reliquary.sin.SinManager;
import com.summy.reliquary.util.CurioHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * 七宗罪碎片的「赎罪」交互：手持对应碎片右击，把已经激活的那一罪变为「已赎罪」。
 *
 * <p>要求：佩戴着七罪之源、该罪当前处于「已激活」；碎片不消耗。
 */
public final class SinFragments {
	private SinFragments() {
	}

	/** 该物品是不是七宗罪碎片；是就返回它对应的罪 */
	public static Sin sinOf(ItemStack stack) {
		Item item = stack.getItem();
		if (item == SummyReliquary.SIN_FRAGMENT_PRIDE.get()) {
			return Sin.PRIDE;
		}
		if (item == SummyReliquary.SIN_FRAGMENT_GREED.get()) {
			return Sin.GREED;
		}
		if (item == SummyReliquary.SIN_FRAGMENT_LUST.get()) {
			return Sin.LUST;
		}
		if (item == SummyReliquary.SIN_FRAGMENT_ENVY.get()) {
			return Sin.ENVY;
		}
		if (item == SummyReliquary.SIN_FRAGMENT_GLUTTONY.get()) {
			return Sin.GLUTTONY;
		}
		if (item == SummyReliquary.SIN_FRAGMENT_WRATH.get()) {
			return Sin.WRATH;
		}
		if (item == SummyReliquary.SIN_FRAGMENT_SLOTH.get()) {
			return Sin.SLOTH;
		}
		return null;
	}

	/**
	 * 尝试赎罪；返回 true 表示状态真的发生了变化（调用方据此取消这次交互）。
	 */
	public static boolean tryRedeem(Player player, PlayerInteractEvent event) {
		Sin sin = sinOf(event.getItemStack());
		if (sin == null) {
			return false;
		}

		// 只在服务端处理状态，客户端由同步包刷新显示
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return false;
		}

		// 必须佩戴七罪之源
		if (!CurioHelper.wears(player, SummyReliquary.SOURCE_OF_SINS.get())) {
			message(serverPlayer, "message.summy-reliquary.sin.redeem.need_source");
			return false;
		}

		switch (SinManager.state(player, sin)) {
			case REDEEMED -> {
				message(serverPlayer, "message.summy-reliquary.sin.redeem.already");
				return false;
			}
			case UNACTIVATED -> {
				message(serverPlayer, "message.summy-reliquary.sin.redeem.not_activated");
				return false;
			}
			default -> {
			}
		}

		// 1.8.0：走统一的赎罪入口（置为已赎罪 + 清零该罪计数 + 同步客户端与属性）
		SinManager.redeem(serverPlayer, sin);

		serverPlayer.level().playSound(null, serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ(),
				SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8F, 1.4F);
		// 赎罪成功的提示：整行金色，罪名亮金
		serverPlayer.displayClientMessage(Component.translatable("message.summy-reliquary.sin.redeem.done",
				Component.translatable(sin.nameKey()).withStyle(net.minecraft.ChatFormatting.GOLD))
				.withStyle(net.minecraft.ChatFormatting.GOLD), true);

		if (event instanceof PlayerInteractEvent.RightClickBlock blockEvent) {
			blockEvent.setCancellationResult(InteractionResult.SUCCESS);
		} else if (event instanceof PlayerInteractEvent.RightClickItem itemEvent) {
			itemEvent.setCancellationResult(InteractionResult.SUCCESS);
		}
		return true;
	}

	private static void message(ServerPlayer player, String key) {
		player.displayClientMessage(Component.translatable(key), true);
	}
}
